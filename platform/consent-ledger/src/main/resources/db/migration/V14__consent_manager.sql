-- =====================================================================================
-- Consent Manager interoperability — DPDP Rules 2025, Rule 4 and the First Schedule.
--
-- Operational from 13 November 2026. This is the first hard dated obligation in the DPDP
-- rollout, and the platform had no concept of a Consent Manager at all.
--
-- -------------------------------------------------------------------------------------
-- What this is NOT.
--
-- It is not registration. UDS cannot register as a Consent Manager while it is a Data
-- Fiduciary for the same principals: the First Schedule requires a Consent Manager to be
-- independent of the fiduciaries it intermediates for, and to act solely in the interest of
-- the principal. That is a structural bar, not a paperwork one, and no amount of internal
-- separation fixes it.
--
-- What UDS must be able to do is *transact* with one. A principal who manages their consent
-- through a registered Consent Manager and withdraws there produces a withdrawal UDS is
-- obliged to honour — and until now there was no way for it to arrive. The obligation runs
-- one way: the platform must accept what a registered CM relays, and must be able to hand
-- back the record of what it holds.
--
-- -------------------------------------------------------------------------------------
-- Why two tables.
--
-- consent_manager is the register: who the Board has registered, and whether they still are.
-- It is group-wide configuration, like the purpose taxonomy — a Consent Manager is registered
-- with the Board, not with Denave — so it carries no entity_id and no isolation policy.
--
-- consent_manager_link is the relationship: this principal, at this entity, is managed
-- through this CM, and the CM knows them by its own reference. That is personal data and is
-- entity-scoped, so it comes under V13's isolation policy with everything else.
--
-- The CM's own reference matters more than it looks. A principal is identified to the CM by
-- whatever the CM chose, and to UDS by a peppered hash of an identifier. Without a stored
-- mapping, an inbound withdrawal naming the CM's reference cannot be resolved to a subject at
-- all — and a withdrawal that cannot be resolved is a withdrawal that does not happen.
--
-- -------------------------------------------------------------------------------------
-- The public key column, and why it is nullable.
--
-- The Board has not published a standard for how a Consent Manager signs a relayed request.
-- The column exists because the requirement is certain and the format is not; verification is
-- wired the day a standard lands. Until then a CM authenticates as an API client over TLS,
-- which is what the rest of the platform does and is honest about being an interim position.
-- Making the column NOT NULL now would mean inventing a format, and a signature verified
-- against a scheme nobody else implements is worse than no signature — it looks like proof.
-- =====================================================================================

create table consent_manager (
    -- The Board's registration number is the identity. Not a surrogate key: this is the value
    -- that appears on a relayed request, in the Board's register, and in any dispute about
    -- whether the relay should have been honoured.
    registration_id     varchar(128) primary key,
    name                varchar(256) not null,
    -- REGISTERED | SUSPENDED | DEREGISTERED. Deliberately not a boolean. The Board can suspend
    -- a Consent Manager without deregistering it, and the two have different consequences: a
    -- suspended CM's existing links survive and its relays do not, which a boolean cannot say.
    status              varchar(32)  not null,
    -- The credential a relayed request authenticates as, so an inbound call can be tied to a
    -- registration without the caller asserting its own identity in the body.
    api_client_id       varchar(128) unique,
    public_key          text,
    registered_at       timestamptz  not null,
    status_changed_at   timestamptz,
    -- Free text, because "why is this one suspended" is a question somebody will ask under
    -- time pressure and the answer is not enumerable.
    status_reason       text,
    contact_email       varchar(256),
    recorded_at         timestamptz  not null default now(),

    constraint consent_manager_status_known
        check (status in ('REGISTERED', 'SUSPENDED', 'DEREGISTERED'))
);

comment on table consent_manager is
    'Consent Managers registered with the Data Protection Board under DPDP Rule 4. Group-wide '
    'configuration: a CM is registered with the Board, not with a fiduciary entity, so this '
    'table carries no entity_id and no isolation policy.';

comment on column consent_manager.public_key is
    'For verifying signatures on relayed requests. Nullable because the Board has not published '
    'a signing standard; the column exists so that wiring it later is not a migration against '
    'live data.';

create table consent_manager_link (
    id                  bigserial primary key,
    entity_id           varchar(64)  not null references fiduciary_entity(entity_id),
    subject_id          varchar(128) not null,
    registration_id     varchar(128) not null references consent_manager(registration_id),
    -- How the Consent Manager identifies this principal. The join that makes an inbound relay
    -- resolvable to a subject.
    cm_subject_ref      varchar(256) not null,
    linked_at           timestamptz  not null,
    unlinked_at         timestamptz,
    recorded_at         timestamptz  not null default now(),

    -- One live link per principal per CM per entity. A principal may use more than one Consent
    -- Manager — nothing forbids it — so the constraint is scoped to the CM rather than to the
    -- principal. Partial, so that an unlinked row does not block re-linking later: people
    -- change their minds, and a unique index that made that impossible would be discovered by
    -- someone who could not re-link and had no idea why.
    constraint consent_manager_link_ref_shape check (length(trim(cm_subject_ref)) > 0)
);

create unique index consent_manager_link_live
    on consent_manager_link (entity_id, subject_id, registration_id)
    where unlinked_at is null;

-- The lookup an inbound relay performs on every request: "who is CM reference X at entity Y".
create unique index consent_manager_link_by_ref
    on consent_manager_link (entity_id, registration_id, cm_subject_ref)
    where unlinked_at is null;

comment on table consent_manager_link is
    'Which principals are managed through which Consent Manager, and under what reference the '
    'CM knows them. Personal data, entity-scoped, and covered by the V13 isolation policy.';

-- -------------------------------------------------------------------------------------
-- Isolation, on the same terms as everything else that holds personal data.
--
-- Repeated here rather than folded into V13 because V13 has run in every environment that
-- exists; editing it would either be ignored by Flyway or fail its checksum. A migration is
-- append-only for the same reason the ledger is.
-- -------------------------------------------------------------------------------------
do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'uds_consent_app') then
        raise notice
            'role uds_consent_app not present; consent_manager_link policy NOT applied.';
        return;
    end if;

    alter table consent_manager_link enable row level security;

    execute $f$
        create policy uds_entity_isolation on consent_manager_link
            using (uds_entity_claim() is null or entity_id = uds_entity_claim())
            with check (uds_entity_claim() is null or entity_id = uds_entity_claim())
    $f$;

    -- The register itself is readable by every entity and writable by nobody through the
    -- application: it mirrors the Board's register, and a fiduciary that could edit the list of
    -- who is registered could authorise its own relays.
    execute 'grant select on consent_manager to uds_consent_app';
    execute 'grant select, insert, update on consent_manager_link to uds_consent_app';
    execute 'grant usage, select on sequence consent_manager_link_id_seq to uds_consent_app';
end
$$;

-- -------------------------------------------------------------------------------------
-- A refusal is not a decision about a purpose.
--
-- enforcement_decision was designed around one shape of row: "we were asked whether X could be
-- processed for purpose P in jurisdiction J, and we said no". A refused Consent Manager relay
-- is a different shape — the caller is refused before any question about a principal, a purpose
-- or a jurisdiction has been asked, and inventing values for those columns would put facts in
-- the evidence plane that nobody established.
--
-- So the two columns become nullable, and a check constraint says exactly when they may be:
-- only for this one reason. The guarantee that a genuine decision always names its purpose and
-- its jurisdiction is unchanged, and it is now written down rather than implied by a NOT NULL
-- that also happened to cover a case nobody had thought about.
-- -------------------------------------------------------------------------------------
alter table enforcement_decision alter column purpose_code drop not null;
alter table enforcement_decision alter column jurisdiction drop not null;

alter table enforcement_decision add constraint enforcement_decision_shape
    check (
        reason = 'CONSENT_MANAGER_NOT_REGISTERED'
        or (purpose_code is not null and jurisdiction is not null)
    );

comment on constraint enforcement_decision_shape on enforcement_decision is
    'Every decision about processing names its purpose and jurisdiction. The one exception is a '
    'refused Consent Manager relay, which is refused before either has been asked about.';

-- -------------------------------------------------------------------------------------
-- Seed: the pilot's own test registration.
--
-- Not a real Board registration and named so that nobody mistakes it for one. It exists so
-- that the integration suite and a manual walk-through have something to relay through before
-- the first real CM registers — and so that the "deregistered CM is refused" path has a
-- subject that is unambiguously fictional.
-- -------------------------------------------------------------------------------------
insert into consent_manager (registration_id, name, status, api_client_id, registered_at,
                             contact_email)
values ('CM-TEST-0001', 'UDS pilot test Consent Manager (not a Board registration)',
        'REGISTERED', 'cm-test-client', timestamptz '2026-11-13T00:00:00Z',
        'privacy@uds.example'),
       ('CM-TEST-0002', 'UDS pilot deregistered Consent Manager (not a Board registration)',
        'DEREGISTERED', 'cm-deregistered-client', timestamptz '2026-11-13T00:00:00Z',
        'privacy@uds.example')
on conflict (registration_id) do nothing;
