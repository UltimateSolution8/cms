-- =====================================================================================
-- When did each scheduled sweep last run, durably, so that "it stopped" is answerable.
--
-- -------------------------------------------------------------------------------------
-- What was wrong.
--
-- Eight scheduled jobs maintain the evidence plane: expiry, retention, integrity, rights
-- SLA, breach SLA, partition maintenance, re-confirmation, and the outbox relay. Nothing
-- recorded that any of them ran. A silently dead ExpirySweeper writes no EXPIRED events
-- and the evidence plane goes quietly incomplete while every decision stays correct,
-- which is exactly what makes it hard to spot: there is no error, no failed request and
-- no alert — only a growing absence.
--
-- Thirteen rules in deploy/observability/alerts.yaml and only PartitionRunwayShort would
-- notice a sweep having stopped, and only for that one job.
--
-- -------------------------------------------------------------------------------------
-- Why this is a table and not a gauge held in memory.
--
-- The obvious implementation — each sweeper keeps its own lastRunAt and publishes it — is
-- wrong here, and wrong in a way that would have shipped and then been muted.
--
-- SweepLock runs a sweep on ONE instance at a time by taking a PostgreSQL session
-- advisory lock, and deploy/k8s/deployment.yaml ships replicas: 3. So on any given tick
-- exactly one instance runs the job and the other two skip. An in-memory timestamp would
-- therefore read "never ran" on two instances out of three FOREVER, an alert over the
-- maximum age would fire permanently, and it would be silenced inside a week — which is
-- the same failure the propagation register was redesigned to avoid before it shipped
-- (Phase 17, D1: a control whose alert can never clear is not a control).
--
-- The record has to live in the thing all three instances share. That is this table.
--
-- -------------------------------------------------------------------------------------
-- Why it is mutable, and why that is not a hole in the evidence plane.
--
-- UPDATE is deliberately NOT revoked. This is current state — "when did this job last
-- finish" — and it is operational telemetry, not evidence about a data principal. One row
-- per sweep, upserted.
--
-- The append-only alternative is one row per sweep per tick, which for a relay running
-- every two seconds is ~43,000 rows a day to answer a question one row answers, in a
-- table nothing would ever prune. LedgerAppendOnlyIT's hand-written list therefore does
-- NOT include this table, and carries a comment saying so — the same treatment as
-- rights_request_verification, whose attempts and consumption are likewise mutable state.
--
-- Not entity-scoped, so no RLS policy and no EntityAccessGuard prefix: a sweep is a
-- platform job, not a fiduciary's record. RowLevelSecurityIT derives its protected set
-- from the tables carrying entity_id, so it stays green unmodified. If it fails on this
-- table, the table has an entity_id it should not have.
-- =====================================================================================

create table sweep_run (
    -- The sweep's own name, as passed to SweepLock.runExclusively. One row per sweep.
    sweep_name        varchar(64)  primary key,

    -- Set when the sweep begins and again when it ends. Both are kept: a sweep that
    -- started and never finished is a different fault from one that never started, and a
    -- single "last run" column cannot tell them apart.
    last_started_at   timestamptz  not null,
    last_finished_at  timestamptz,

    -- Which instance ran it. Free text, and it is the first thing an operator wants when
    -- one replica is wedged and the other two are healthy.
    last_ran_on       text,

    -- Whether the sweep body threw. The lock is released either way; a sweep that throws
    -- every tick would otherwise look identical to one that succeeds every tick.
    last_outcome      varchar(16)  not null default 'OK',

    constraint ck_sweep_run_outcome check (last_outcome in ('OK', 'FAILED'))
);

comment on table sweep_run is
    'When each scheduled sweep last ran, and where. Current state, not evidence: one row per sweep, '
    'upserted. Exists because SweepLock runs a sweep on one instance at a time, so an in-memory '
    'timestamp reads "never" on every instance that did not win the lock. See the migration header.';

comment on column sweep_run.last_finished_at is
    'Null while a sweep is in flight, and null after one that died mid-run. A sweep whose '
    'last_started_at is old and whose last_finished_at is null did not merely fail to run.';

do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'uds_consent_app') then
        raise notice 'role uds_consent_app not present; grants for sweep_run NOT applied.';
        return;
    end if;

    -- UPDATE granted deliberately. See the header: this is current state, upserted, and it
    -- is the one table in the platform whose whole purpose is to be overwritten.
    execute 'grant select, insert, update on sweep_run to uds_consent_app';
end
$$;
