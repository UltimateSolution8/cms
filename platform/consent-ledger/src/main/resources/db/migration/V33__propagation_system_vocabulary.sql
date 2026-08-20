-- =====================================================================================
-- A system_code the database can resolve, so a typo cannot write permanent false evidence.
--
-- -------------------------------------------------------------------------------------
-- What was wrong.
--
-- V31 joined propagation_target.system_code to webhook_subscription.system_code as free
-- text, upper-cased on both sides. That closes the case-mismatch failure and nothing else.
-- A target registered for DENCRM against a subscription an operator named DENCRM_PROD
-- never joins — so the reconciler reports a mandatory obligation as uncovered, every day,
-- for a system that is in fact perfectly reachable and receiving every event.
--
-- propagation_gap is APPEND-ONLY. Those rows are therefore permanent evidence of a
-- failure that never happened, in the table the platform would hand a regulator to show
-- what it did and did not propagate. A register that records a lie is worse than one that
-- records nothing, because the lie is believed.
--
-- fulfilment_target gets the same class of error right by failing LOUD and CLOSED: a
-- system code nobody has registered produces a 409 naming the system, at the moment an
-- operator tries to close a request. Propagation fails QUIET, and writes.
--
-- -------------------------------------------------------------------------------------
-- Why a vocabulary table and not validation at PUT time.
--
-- "Refuse a target whose system_code matches no subscription" is circular. A target must
-- be registrable BEFORE its subscription exists — that is the entire point of the
-- register: it is how the platform says "DENCRM must be told and nobody has wired it
-- yet". Validating against subscriptions would make that state unexpressible.
--
-- So the vocabulary is its own thing: the set of system codes this entity recognises,
-- declared once, and both sides reference it. A target may name a system with no
-- subscription (correctly reported as uncovered); neither side may name a system that
-- does not exist at all.
--
-- V26's argument against a foreign key on fulfilment_target.system_code is re-argued here
-- rather than inherited, because the asymmetry is decisive: there a mismatch fails loud
-- and closed, here it fails quiet and writes to an append-only table.
--
-- -------------------------------------------------------------------------------------
-- The backfill fabricates nothing.
--
-- Every distinct system_code already present in propagation_target and
-- webhook_subscription becomes a vocabulary row. Those are labels operators already chose
-- — including any typo, which is the honest outcome: this migration must not silently
-- decide that DENCRM_PROD "meant" DENCRM. It makes the mismatch visible and fixable; it
-- does not guess. Existing joins are unchanged, so every PropagationIT fixture that
-- resolved before this migration resolves after it.
--
-- Entity-scoped, so it carries an RLS policy in V13's shape. RowLevelSecurityIT derives
-- its protected set from information_schema and will pick this table up unmodified.
-- =====================================================================================

create table propagation_system (
    entity_id    varchar(64)  not null references fiduciary_entity (entity_id),

    -- The label. Upper case on this side too, so the check that keeps the join exact is
    -- stated in one more place rather than assumed from the two that reference it.
    system_code  varchar(64)  not null,

    description  text,

    -- A retired system stays in the vocabulary. Removing it would orphan the propagation_gap
    -- rows that name it, and those are evidence: "this system was not told, on these days"
    -- has to remain readable after the system is decommissioned.
    active       boolean      not null default true,

    created_at   timestamptz  not null default now(),

    primary key (entity_id, system_code),
    constraint ck_propagation_system_code_upper check (system_code = upper(system_code))
);

comment on table propagation_system is
    'The system codes an entity recognises for propagation. propagation_target and '
    'webhook_subscription both reference it, so a typo is refused by the database rather than '
    'producing a phantom gap that is indistinguishable from a real one. See the migration header.';

comment on column propagation_system.active is
    'A decommissioned system stays here. Deleting it would orphan the propagation_gap rows naming '
    'it, and those rows are append-only evidence that must stay readable.';

-- Backfill from what is already in use, on both sides. Nothing is invented and nothing is
-- corrected: a code that is a typo becomes a vocabulary entry, which is what makes it
-- visible and removable rather than silently reinterpreted.
insert into propagation_system (entity_id, system_code, description)
select distinct entity_id, system_code,
       'backfilled by V33 from an existing propagation target'
  from propagation_target
on conflict (entity_id, system_code) do nothing;

insert into propagation_system (entity_id, system_code, description)
select distinct entity_id, system_code,
       'backfilled by V33 from an existing webhook subscription'
  from webhook_subscription
 where system_code is not null
on conflict (entity_id, system_code) do nothing;

alter table propagation_target
    add constraint fk_propagation_target_system
    foreign key (entity_id, system_code)
    references propagation_system (entity_id, system_code);

-- Nullable on this side, because V31 left it nullable and a subscription predating that
-- migration may still carry a null. NOT VALID is deliberately not used: the backfill above
-- guarantees every existing non-null value has a row, so the constraint is checkable now
-- and an unvalidated constraint is one nobody ever gets round to validating.
alter table webhook_subscription
    add constraint fk_webhook_subscription_system
    foreign key (entity_id, system_code)
    references propagation_system (entity_id, system_code);

do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'uds_consent_app') then
        raise notice 'role uds_consent_app not present; propagation_system isolation NOT applied.';
        return;
    end if;

    -- Configuration rather than evidence: an operator must be able to retire a code and correct
    -- a description. UPDATE is granted; DELETE deliberately is not, because a code named by a
    -- propagation_gap row cannot be removed without orphaning evidence.
    execute 'grant select, insert, update on propagation_system to uds_consent_app';

    execute 'alter table propagation_system enable row level security';
    execute $f$
        create policy uds_entity_isolation on propagation_system
            using (uds_entity_claim() is null or entity_id = uds_entity_claim())
            with check (uds_entity_claim() is null or entity_id = uds_entity_claim())
    $f$;
end
$$;
