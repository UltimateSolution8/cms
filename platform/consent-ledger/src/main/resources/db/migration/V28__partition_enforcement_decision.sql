-- =====================================================================================
-- The one table whose growth is unbounded by anything the group controls.
--
-- enforcement_decision takes a row per refusal — which means a row per dialer pre-flight that
-- comes back DENIED. At Denave's volumes that is the fastest-growing table in the schema by an
-- order of magnitude, it is append-only, and nothing ever removes a row from it. Rule 6's
-- one-year floor is modelled; no ceiling is.
--
-- Converted now, while it is empty. Retro-fitting range partitioning onto a live append-only
-- table carrying triggers, RLS policies and revoked grants is a maintenance window and a
-- rehearsal; doing it today is a rename, a create and a drop of an empty table.
--
-- -------------------------------------------------------------------------------------
-- Why consent_event is NOT partitioned, having set out to partition both.
--
-- This is the more interesting half of the change and it is a refusal.
--
-- PostgreSQL requires every unique constraint on a partitioned table to include the partition
-- key. consent_event carries two, and both are load-bearing:
--
--   unique (entity_id, subject_id, sequence_number) — the hash chain has no forks. This is
--       what makes the ledger a chain rather than a heap, and it is the constraint the whole
--       integrity story rests on.
--   unique (entity_id, idempotency_key)             — a field device retrying a queued capture
--       after a flaky reconnect does not record a second consent.
--
-- Partitioning by recorded_at would force both to become (recorded_at, …). At that point two
-- events could share a subject and a sequence number provided they were recorded in different
-- months, and a retried offline capture crossing midnight on the first of the month would be
-- accepted twice. The database would stop enforcing the two guarantees the evidence plane is
-- built on, in exchange for a scan-pruning property nobody has measured a need for.
--
-- Partitioning by entity_id instead would preserve both constraints and buys much less: the
-- growth being managed is over time, not across the fifteen entities, and it would not enable
-- archival by age at all.
--
-- So consent_event stays whole, and the honest reasons it can afford to are worth stating.
-- Its growth is bounded by subjects times events per subject rather than by traffic — a
-- decision is not an event, and only captures, withdrawals, expiries and invalidations are
-- written. Every query against it is by (entity, subject) and is indexed. And it is evidence
-- that the group is required to keep, so partitioning it for the ability to drop old months
-- would be building a mechanism nobody may use.
--
-- If it ever does need partitioning, the shape is by entity_id, the constraints survive, and
-- this comment is the argument for why that is the only shape available.
-- =====================================================================================

alter table enforcement_decision rename to enforcement_decision_unpartitioned;

-- The sequence too, and this is not cosmetic. Renaming a table leaves its owned sequence under
-- the old name, so the bigserial below would find enforcement_decision_id_seq taken and be given
-- enforcement_decision_id_seq1 instead — at which point the grant further down names a sequence
-- that no longer belongs to anything, the migration fails, and the reason is four statements away
-- from the symptom.
alter sequence enforcement_decision_id_seq rename to enforcement_decision_unpartitioned_id_seq;

create table enforcement_decision (
    id              bigserial,
    entity_id       varchar(64) not null references fiduciary_entity (entity_id),
    subject_id      varchar(64),
    purpose_code    varchar(64),
    purpose_version int,
    channel         varchar(32),
    jurisdiction    varchar(8),
    outcome         varchar(16) not null,
    reason          varchar(64) not null,
    explanation     text,
    application_id  varchar(64),
    vendor_id       varchar(64),
    client_id       varchar(64),
    campaign_id     varchar(64),
    policy_version  varchar(64) not null,
    decided_at      timestamptz not null,
    recorded_at     timestamptz not null default now(),
    -- The partition key has to be in the primary key. Unlike consent_event this costs nothing:
    -- id is a surrogate with no meaning, nothing joins on it, and no guarantee anywhere depends
    -- on it being unique independently of when the row was written.
    primary key (recorded_at, id),
    -- Carried forward verbatim from V15. Every decision about processing names its purpose and
    -- jurisdiction; the exceptions are the Consent Manager relays refused before either has been
    -- asked about.
    constraint enforcement_decision_shape check (
        reason in ('CONSENT_MANAGER_NOT_REGISTERED', 'CONSENT_MANAGER_NOT_BOUND')
        or (purpose_code is not null and jurisdiction is not null)
    )
) partition by range (recorded_at);

insert into enforcement_decision
select * from enforcement_decision_unpartitioned;

drop table enforcement_decision_unpartitioned;

comment on table enforcement_decision is
    'Denied enforcement decisions, recorded in full. Allowances are counted on scrub_run rather '
    'than enumerated here. DPDP Rules 2025, Rule 6. Range-partitioned by month from V28 — it '
    'takes a row per dialer pre-flight refusal and is the only table in the schema whose growth '
    'is bounded by traffic rather than by population.';


-- -------------------------------------------------------------------------------------
-- The default partition, and why it is not a smell.
--
-- Without one, an insert whose recorded_at falls outside every declared range fails outright —
-- and the thing that fails is the evidence write for a refusal that has already been served to
-- a dialer. The platform would deny the call correctly and lose the proof, which is the exact
-- failure EnforcementRecorder's best-effort design exists to avoid.
--
-- So a default catches anything the maintenance sweeper has not provisioned for. It is a safety
-- net rather than a plan: rows landing there are reported, because a default partition quietly
-- accumulating a year of traffic is a partitioned table with extra steps.
-- -------------------------------------------------------------------------------------

create table enforcement_decision_default partition of enforcement_decision default;

-- Enough months to cover a deployment that never runs the sweeper for a quarter. Generated
-- rather than written out, because twelve hand-written CREATE TABLE statements is twelve
-- chances to fat-finger a boundary and leave a one-day hole that reads as a default-partition
-- row six months later.
do $$
declare
    start_month date := date_trunc('month', now())::date - interval '1 month';
    partition_month date;
begin
    for i in 0..12 loop
        partition_month := (start_month + (i || ' months')::interval)::date;
        execute format(
            'create table %I partition of enforcement_decision for values from (%L) to (%L)',
            'enforcement_decision_' || to_char(partition_month, 'YYYY_MM'),
            partition_month,
            (partition_month + interval '1 month')::date);
    end loop;
end
$$;


-- -------------------------------------------------------------------------------------
-- Everything V8 established, restated. A partitioned table is a new table: it inherits none of
-- the old one's indexes, triggers, policies or grants, and a migration that recreated four of
-- those five would leave a silent hole in whichever one it forgot.
-- -------------------------------------------------------------------------------------

-- Declared on the parent, so PostgreSQL creates and maintains the matching index on every
-- partition — including the ones the sweeper adds next year, which is the property that makes
-- this survivable without anybody remembering.
create index idx_enforcement_decision_subject
    on enforcement_decision (entity_id, subject_id, decided_at desc);
create index idx_enforcement_decision_campaign
    on enforcement_decision (entity_id, campaign_id, decided_at desc)
 where campaign_id is not null;
create index idx_enforcement_decision_recorded
    on enforcement_decision (recorded_at desc);

-- Row triggers on a partitioned parent propagate to every partition in PostgreSQL 13 and later,
-- existing and future. Before that they had to be created per partition, which is the version
-- of this migration that would have rotted.
create trigger trg_enforcement_decision_no_update
    before update on enforcement_decision
    for each row execute function evidence_row_is_immutable();

create trigger trg_enforcement_decision_no_delete
    before delete on enforcement_decision
    for each row execute function evidence_row_is_immutable();

do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'uds_consent_app') then
        raise notice
            'role uds_consent_app not present; enforcement_decision guards NOT reapplied after '
            'partitioning. This is the migration that would silently undo V8 and V13.';
        return;
    end if;

    grant insert, select on enforcement_decision to uds_consent_app;
    grant usage, select on sequence enforcement_decision_id_seq to uds_consent_app;
    revoke update, delete, truncate on enforcement_decision from uds_consent_app;

    -- V13's isolation policy, which the rename dropped with the old table. A policy on the
    -- parent applies to every partition, so this is one statement rather than one per month.
    alter table enforcement_decision enable row level security;
    create policy uds_entity_isolation on enforcement_decision
        using (uds_entity_claim() is null or entity_id = uds_entity_claim())
        with check (uds_entity_claim() is null or entity_id = uds_entity_claim());
end
$$;
