-- =====================================================================================
-- Evidence that enforcement happened.
--
-- The platform could already prove what a subject consented to. It could not prove that
-- anybody asked before dialling them. PolicyEngine.evaluate returned and forgot;
-- SuppressionService.scrub returned counts and wrote nothing — while DecisionController's own
-- javadoc called itself "one place a regulator can be shown how the answer was reached".
--
-- Two regimes want this and they want different things.
--
--   TRAI acts today. Its question is "show us you scrubbed this number against the national
--   preference register before you called it", asked about a campaign rather than a person.
--   scrub_run answers it.
--
--   DPDP Rule 6 binds from 13 May 2027 and is broader: logs of access and processing,
--   monitored and reviewed, retained for at least a year. enforcement_decision answers it.
--
-- -------------------------------------------------------------------------------------
-- Denials in full, allowances in aggregate.
--
-- A dialer at a hundred thousand calls a day would otherwise write a hundred thousand rows
-- to prove that nothing happened. The evidentiary questions are always one of two shapes:
-- "why was this person contacted despite X", which needs the denial; and "was this population
-- screened at all", which needs the run. Both are bounded. Enumerating every allowance would
-- buy a third answer nobody asks, at a cost that would eventually force someone to turn the
-- logging off — and evidence that gets switched off under load is worse than none, because
-- the gap lands exactly on the busy days.
--
-- Both tables are under the same append-only trigger family and the same grant revocations as
-- the rest of the evidence plane. Evidence the application can quietly edit is not evidence.
-- =====================================================================================

create table enforcement_decision (
    id              bigserial   primary key,
    entity_id       varchar(64) not null references fiduciary_entity (entity_id),
    -- The opaque subject reference, never an identifier. This table will be large and is read
    -- during investigations; it must not become a second contact database.
    subject_id      varchar(64),
    purpose_code    varchar(64) not null,
    purpose_version int,
    channel         varchar(32),
    jurisdiction    varchar(8)  not null,
    outcome         varchar(16) not null,
    reason          varchar(64) not null,
    explanation     text,
    -- Who asked, and on whose behalf. A denial nobody can attribute to a caller cannot be
    -- turned into a fix.
    application_id  varchar(64),
    vendor_id       varchar(64),
    client_id       varchar(64),
    campaign_id     varchar(64),
    -- The policy bundle in force. Without it a decision from 2026 cannot be reproduced in
    -- 2031, only described — and a description is not evidence.
    policy_version  varchar(64) not null,
    decided_at      timestamptz not null,
    recorded_at     timestamptz not null default now()
);

comment on table enforcement_decision is
    'Denied enforcement decisions, recorded in full. Allowances are counted on scrub_run '
        'rather than enumerated here. DPDP Rules 2025, Rule 6.';

-- The investigation always starts from a person or from a campaign, and always inside a
-- window. Indexed for both entry points; nothing indexes the reason alone, because a query
-- for every CONSENT_WITHDRAWN across all time is a report rather than an investigation.
create index idx_enforcement_decision_subject
    on enforcement_decision (entity_id, subject_id, decided_at desc);
create index idx_enforcement_decision_campaign
    on enforcement_decision (entity_id, campaign_id, decided_at desc)
 where campaign_id is not null;
create index idx_enforcement_decision_recorded
    on enforcement_decision (recorded_at desc);

-- One row per scrub call. This is the artefact a TRAI investigation asks for: not "was this
-- number on the list" but "did you run the check, over what, and what came out".
create table scrub_run (
    id              bigserial   primary key,
    entity_id       varchar(64) not null references fiduciary_entity (entity_id),
    channel         varchar(32) not null,
    client_id       varchar(64),
    campaign_id     varchar(64),
    actor_id        text        not null,
    submitted_count int         not null,
    permitted_count int         not null,
    excluded_count  int         not null,
    -- Counts by exclusion reason. Held as jsonb rather than a child table because it is read
    -- as a whole and never joined — and because the set of reasons changes when a new
    -- suppression source is added, which a column set would turn into a migration.
    reason_counts   jsonb       not null default '{}'::jsonb,
    run_at          timestamptz not null default now()
);

comment on table scrub_run is
    'One row per campaign scrub. Evidence that a list was screened before it was used — the '
        'question TRAI asks, which no per-subject record answers.';

create index idx_scrub_run_entity on scrub_run (entity_id, run_at desc);
create index idx_scrub_run_campaign on scrub_run (campaign_id, run_at desc)
 where campaign_id is not null;

-- -------------------------------------------------------------------------------------
-- The same immutability as the rest of the evidence plane.
-- -------------------------------------------------------------------------------------

create trigger trg_enforcement_decision_no_update
    before update on enforcement_decision
    for each row execute function evidence_row_is_immutable();

create trigger trg_enforcement_decision_no_delete
    before delete on enforcement_decision
    for each row execute function evidence_row_is_immutable();

create trigger trg_scrub_run_no_update
    before update on scrub_run
    for each row execute function evidence_row_is_immutable();

create trigger trg_scrub_run_no_delete
    before delete on scrub_run
    for each row execute function evidence_row_is_immutable();

do $$
begin
    if exists (select 1 from pg_roles where rolname = 'uds_consent_app') then
        grant insert, select on enforcement_decision to uds_consent_app;
        grant insert, select on scrub_run            to uds_consent_app;
        grant usage, select on sequence enforcement_decision_id_seq to uds_consent_app;
        grant usage, select on sequence scrub_run_id_seq            to uds_consent_app;
        revoke update, delete, truncate on enforcement_decision from uds_consent_app;
        revoke update, delete, truncate on scrub_run            from uds_consent_app;
        raise notice 'append-only grants applied to enforcement evidence tables';
    else
        raise notice
            'role uds_consent_app not present; enforcement evidence grants NOT applied.';
    end if;
end
$$;
