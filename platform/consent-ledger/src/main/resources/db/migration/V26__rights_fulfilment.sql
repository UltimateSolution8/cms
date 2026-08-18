-- =====================================================================================
-- FULFILLED has to mean something.
--
-- RightsService.transition() lets an operator move a request to FULFILLED with a resolution
-- string. That is the whole of it. Nothing in this platform erases, exports or corrects
-- anything in DenCRM, the HRMS or the BGV workflow — so "FULFILLED" has been an assertion by
-- whoever was on shift, recorded immutably, and indistinguishable on the record from an
-- assertion by somebody who did nothing at all.
--
-- This is the single largest compliance exposure in the system, and it is worth being exact
-- about why. Intake and a clock are the easy half of DPDP ss.11-13. The platform can prove a
-- request arrived and was closed inside the statutory period. It cannot prove anything was
-- done, and a Board asking "what did you actually erase" gets a sentence somebody typed.
--
-- -------------------------------------------------------------------------------------
-- What this migration does, and what it deliberately does not.
--
-- It does NOT build connectors. We have no access to DenCRM, the HRMS or the BGV workflow, and
-- writing plausible-looking stubs against systems nobody here can call would be worse than
-- nothing: it would look like fulfilment.
--
-- It builds the two things that are ours to build. A REGISTER of which systems must act for a
-- given request type — so the set is a configured fact rather than whatever an operator
-- remembered — and an append-only RECORD of what each of them did, by whom, with a reference.
-- Then FULFILLED is gated on the register being satisfied.
--
-- The effect is narrow and real: it converts "an operator asserted" into "an operator asserted,
-- against a named system, with a reference, and the platform can list the systems that were
-- left out". A manual SOP recorded this way is defensible. A manual SOP recorded nowhere is the
-- exposure.
-- =====================================================================================


-- Which systems have to act, per entity and per request type.
--
-- Per request type because they genuinely differ: an ERASURE reaches every system holding the
-- person's data, and an ACCESS request reaches whichever ones can produce an export. Modelling
-- one list for both would either over-state what an access request requires or under-state what
-- an erasure does, and the second failure is the one that matters.
create table fulfilment_target (
    entity_id    varchar(64) not null references fiduciary_entity (entity_id),
    request_type varchar(32) not null,
    -- A short stable name for the downstream system: DENCRM, HRMS, BGV, ATHENA_DIALER. Free
    -- text rather than a foreign key to application_registry, because the systems that hold a
    -- person's data are not the same set as the surfaces that capture consent, and forcing them
    -- into one table would make each one wrong about the other.
    system_code  varchar(64) not null,
    -- Whether FULFILLED is blocked until this system has acted. A non-mandatory target is a
    -- system worth recording and not worth blocking on — a reporting warehouse that refreshes
    -- nightly, say. Defaulting to true is the safe direction: a target added and forgotten
    -- blocks a closure and gets noticed, rather than silently permitting one.
    mandatory    boolean     not null default true,
    active       boolean     not null default true,
    description  text,
    created_at   timestamptz not null default now(),
    primary key (entity_id, request_type, system_code)
);

create index idx_fulfilment_target_active on fulfilment_target (entity_id, request_type)
    where active;

comment on table fulfilment_target is
    'The systems that must act to fulfil a rights request, per entity and request type. Empty '
    'means the platform blocks nothing — which is the state before UDS configures it, and is '
    'why the scope statement in REGULATORY_HANDOFF matters: an unconfigured register is not the '
    'same as no obligation.';


-- What each system actually did. Append-only, like the ledger.
create table rights_fulfilment_action (
    action_id    bigserial   primary key,
    request_id   varchar(64) not null references rights_request (request_id),
    entity_id    varchar(64) not null references fiduciary_entity (entity_id),
    system_code  varchar(64) not null,
    -- ERASED, EXPORTED, CORRECTED, NOTHING_HELD, REFUSED. Kept as text rather than an enum
    -- constraint: the set will grow as UDS discovers what its systems actually do, and a check
    -- constraint would turn that discovery into a migration during an open statutory clock.
    action_type  varchar(32) not null,
    -- COMPLETED or FAILED. Only COMPLETED satisfies a mandatory target — a failed attempt is
    -- worth recording precisely because it must not count.
    status       varchar(16) not null,
    performed_by text        not null,
    performed_at timestamptz not null default now(),
    -- The thing that makes this evidence rather than a second assertion: a ticket id, an export
    -- file hash, a deletion job reference. Required. "We erased it" with no reference is the
    -- claim this table exists to stop being sufficient.
    evidence_ref text        not null,
    detail       text,
    constraint ck_fulfilment_action_status check (status in ('COMPLETED', 'FAILED'))
);

create index idx_fulfilment_action_request on rights_fulfilment_action (request_id);

comment on column rights_fulfilment_action.evidence_ref is
    'A ticket id, an export hash, a deletion job reference — something a reviewer can follow to '
    'a system other than this one. Required, because an attestation with nothing behind it is '
    'the same unevidenced assertion the resolution field already was.';

do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'uds_consent_app') then
        raise notice 'role uds_consent_app not present; fulfilment guards NOT applied.';
        return;
    end if;

    -- Append-only. An attestation that could be withdrawn after the request closed would let a
    -- record be tidied into looking clean, which is exactly the shape of the problem this whole
    -- table exists to prevent.
    execute 'revoke update, delete on rights_fulfilment_action from uds_consent_app';

    alter table rights_fulfilment_action enable row level security;
    create policy uds_entity_isolation on rights_fulfilment_action
        using (uds_entity_claim() is null or entity_id = uds_entity_claim())
        with check (uds_entity_claim() is null or entity_id = uds_entity_claim());

    alter table fulfilment_target enable row level security;
    create policy uds_entity_isolation on fulfilment_target
        using (uds_entity_claim() is null or entity_id = uds_entity_claim())
        with check (uds_entity_claim() is null or entity_id = uds_entity_claim());
end
$$;
