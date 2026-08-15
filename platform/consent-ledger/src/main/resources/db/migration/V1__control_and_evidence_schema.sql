-- =====================================================================================
-- UDS Consent & Privacy Control Plane — core schema
--
-- Three planes, one database in this deployment shape:
--   * control plane   — slow-changing configuration an administrator edits
--   * evidence plane  — append-only consent events, the thing the burden of proof rests on
--   * projections     — derived state the enforcement plane reads on the hot path
--
-- Nothing in the evidence plane is ever updated or deleted. That is enforced in V2 by
-- triggers and revoked grants rather than by application discipline, because "we agreed
-- not to" is not a control an auditor can test.
-- =====================================================================================


-- -------------------------------------------------------------------------------------
-- CONTROL PLANE
-- -------------------------------------------------------------------------------------

-- The group's legal entities. Modelled as data, never as hard-coded structure: UDS acquires,
-- merges and divests, and each such change must be a row rather than a release. Tangy Supplies
-- and Stanworth Management were absorbed in May 2025; the next such change should cost minutes.
create table fiduciary_entity (
    entity_id            varchar(64)  primary key,
    legal_name           text         not null,
    short_name           text         not null,
    parent_entity_id     varchar(64)  references fiduciary_entity (entity_id),
    uds_stake_percent    numeric(5, 2),
    primary_jurisdiction varchar(8)   not null,
    -- Rule 13 of the DPDP Rules lets the government bar offshore transfer of specified
    -- categories for Significant Data Fiduciaries. Residency is therefore configuration.
    data_residency_region text        not null default 'ap-south-1',
    dpo_contact          text,
    grievance_uri        text,
    significant_fiduciary boolean     not null default false,
    active               boolean      not null default true,
    created_at           timestamptz  not null default now()
);

comment on column fiduciary_entity.parent_entity_id is
    'Policy inheritance walks this chain: an entity without its own purpose configuration '
    'inherits its parent''s. This is what makes onboarding an acquisition a configuration task.';

-- Every surface that can capture consent or ask for a decision, per environment and platform.
-- Environment is part of the identity because a staging build must never write to the
-- production ledger, and the registry is where that is enforced.
create table application_registry (
    application_id varchar(64) primary key,
    entity_id      varchar(64) not null references fiduciary_entity (entity_id),
    name           text        not null,
    platform       varchar(32) not null,
    environment    varchar(16) not null,
    description    text,
    active         boolean     not null default true,
    created_at     timestamptz not null default now(),
    constraint uq_application unique (entity_id, name, platform, environment)
);

-- Data categories are kept strictly separate from purposes. The whole point is to be able to
-- say "GPS location for field attendance" and "GPS location for marketing" are different
-- questions, and a subject may answer them differently.
create table data_category (
    code        varchar(64) primary key,
    name        text        not null,
    description text,
    sensitive   boolean     not null default false,
    -- Malaysia's PDPA (Amendment) 2024 classifies biometric data as sensitive personal data.
    -- Flagged separately because several group entities run biometric attendance.
    biometric   boolean     not null default false
);

create table purpose (
    code       varchar(64) primary key,
    name       text        not null,
    owner      text,
    created_at timestamptz not null default now()
);

-- Purposes are versioned and versions are immutable. Consent given against version 5 is not
-- consent to version 9, and the platform must be able to say which one a person agreed to.
create table purpose_version (
    id                        bigserial   primary key,
    purpose_code              varchar(64) not null references purpose (code),
    version                   int         not null,
    name                      text        not null,
    description               text        not null,
    expiry_policy             varchar(32) not null,
    expiry_days               int,
    failure_behavior          varchar(16) not null,
    notice_id                 varchar(64),
    requires_separate_consent boolean     not null default false,
    permitted_for_children    boolean     not null default false,
    material_change           boolean     not null default false,
    retired                   boolean     not null default false,
    published_at              timestamptz not null default now(),
    published_by              text,
    constraint uq_purpose_version unique (purpose_code, version),
    constraint ck_expiry_days check (expiry_policy <> 'FIXED_DAYS' or expiry_days is not null)
);

comment on column purpose_version.material_change is
    'Set when a change alters what the subject agreed to. Drives the blast-radius calculation: '
    'a material change requires re-consent, a cosmetic one only a notice update.';

comment on column purpose_version.failure_behavior is
    'FAIL_OPEN or FAIL_CLOSED, signed off by legal per purpose. Deliberately not a runtime '
    'choice for calling services: left to callers, every team picks fail-open under pressure.';

-- A purpose does not have one legal basis. It has one per jurisdiction — the same outreach
-- may rest on legitimate interest in the UK and require consent in India. A jurisdiction with
-- no row here is one where the purpose is simply not permitted.
create table purpose_legal_basis (
    purpose_version_id bigint      not null references purpose_version (id) on delete cascade,
    jurisdiction       varchar(8)  not null,
    legal_basis        varchar(48) not null,
    -- GDPR Art.6(1)(f) is only available with a documented Legitimate Interests Assessment.
    -- Storing the reference here means the console can refuse to publish without one.
    assessment_ref     text,
    notes              text,
    primary key (purpose_version_id, jurisdiction),
    constraint ck_lia_present check (
        legal_basis <> 'LEGITIMATE_INTEREST' or assessment_ref is not null)
);

create table purpose_channel (
    purpose_version_id bigint      not null references purpose_version (id) on delete cascade,
    channel            varchar(32) not null,
    primary key (purpose_version_id, channel)
);

create table purpose_data_category (
    purpose_version_id bigint      not null references purpose_version (id) on delete cascade,
    data_category_code varchar(64) not null references data_category (code),
    primary key (purpose_version_id, data_category_code)
);

create table notice (
    notice_id  varchar(64) primary key,
    entity_id  varchar(64) not null references fiduciary_entity (entity_id),
    name       text        not null,
    created_at timestamptz not null default now()
);

-- Notice versions are immutable so that the exact text a person saw in 2026 can be reproduced
-- in 2031. Rule 3 of the DPDP Rules makes each element of a notice a required field rather
-- than prose, which is why they are modelled as columns.
create table notice_version (
    id              bigserial   primary key,
    notice_id       varchar(64) not null references notice (notice_id),
    version         int         not null,
    jurisdiction    varchar(8)  not null,
    material_change boolean     not null default false,
    withdrawal_uri  text        not null,
    rights_uri      text        not null,
    grievance_uri   text        not null,
    published_at    timestamptz not null default now(),
    published_by    text,
    constraint uq_notice_version unique (notice_id, version)
);

-- English plus the twenty-two languages of the Eighth Schedule. A notice with no translation
-- in the subject's language is not an informed notice.
create table notice_translation (
    notice_version_id bigint      not null references notice_version (id) on delete cascade,
    language_tag      varchar(16) not null,
    title             text        not null,
    body              text        not null,
    primary key (notice_version_id, language_tag)
);

create table vendor (
    vendor_id     varchar(64) primary key,
    entity_id     varchar(64) not null references fiduciary_entity (entity_id),
    name          text        not null,
    role          varchar(32) not null,
    countries     jsonb       not null default '[]'::jsonb,
    dpa_reference text,
    dpa_signed_at date,
    active        boolean     not null default true,
    created_at    timestamptz not null default now()
);

create table vendor_purpose (
    vendor_id    varchar(64) not null references vendor (vendor_id) on delete cascade,
    purpose_code varchar(64) not null references purpose (code),
    primary key (vendor_id, purpose_code)
);

-- The Record of Processing Activities. Populated during Phase 0 discovery and thereafter kept
-- current by the console; this is the table a regulator asks to see first.
create table processing_activity (
    id                    bigserial   primary key,
    entity_id             varchar(64) not null references fiduciary_entity (entity_id),
    name                  text        not null,
    description           text,
    purpose_code          varchar(64) not null references purpose (code),
    system_name           text        not null,
    data_categories       jsonb       not null default '[]'::jsonb,
    recipients            jsonb       not null default '[]'::jsonb,
    cross_border_countries jsonb      not null default '[]'::jsonb,
    retention_period_days int,
    retention_basis       text,
    owner                 text,
    updated_at            timestamptz not null default now()
);


-- -------------------------------------------------------------------------------------
-- SUBJECTS
--
-- The ledger answers "did subject X consent to purpose Y" and nothing else. It holds no
-- names, no phone numbers and no email addresses — only peppered hashes — so that it cannot
-- quietly become a second master customer database sitting beside the CRM.
-- -------------------------------------------------------------------------------------

create table subject (
    subject_id varchar(64) primary key,
    entity_id  varchar(64) not null references fiduciary_entity (entity_id),
    -- DPDP s.9: subjects under eighteen need verifiable parental consent and may not be
    -- tracked or advertised at. Held as a flag so the decision engine can enforce it.
    is_child   boolean     not null default false,
    created_at timestamptz not null default now()
);

create table subject_identifier (
    entity_id       varchar(64) not null references fiduciary_entity (entity_id),
    identifier_type varchar(32) not null,
    identifier_hash varchar(64) not null,
    subject_id      varchar(64) not null references subject (subject_id),
    created_at      timestamptz not null default now(),
    primary key (entity_id, identifier_type, identifier_hash)
);

create index idx_subject_identifier_subject on subject_identifier (subject_id);


-- -------------------------------------------------------------------------------------
-- EVIDENCE PLANE — append only
-- -------------------------------------------------------------------------------------

-- One immutable fact per row, hash-chained per subject within an entity.
--
-- canonical_payload holds the exact bytes that were hashed. Verification re-reads that string
-- rather than re-serialising from the structured columns, so the chain stays verifiable even
-- after the schema gains fields years from now — and any divergence between the payload and
-- the columns is itself detectable evidence of tampering.
create table consent_event (
    event_id          uuid        primary key,
    entity_id         varchar(64) not null references fiduciary_entity (entity_id),
    subject_id        varchar(64) not null,
    purpose_code      varchar(64) not null,
    purpose_version   int         not null,
    event_type        varchar(32) not null,
    legal_basis       varchar(48),
    notice_id         varchar(64),
    notice_version    int,
    language_tag      varchar(16),
    capture_method    varchar(48),
    channel           varchar(32),
    application_id    varchar(64),
    jurisdiction      varchar(8),
    -- occurred_at is when the subject acted; recorded_at is when the ledger durably wrote it.
    -- On a field device with no connectivity these can be days apart, and conflating them
    -- would make an offline capture look like a late fabrication.
    occurred_at       timestamptz not null,
    recorded_at       timestamptz not null,
    expires_at        timestamptz,
    actor_type        varchar(32),
    actor_id          text,
    reason            text,
    evidence_ref      text,
    idempotency_key   text,
    attributes        jsonb       not null default '{}'::jsonb,
    sequence_number   bigint      not null,
    previous_hash     char(64)    not null,
    event_hash        char(64)    not null,
    canonical_payload text        not null,
    constraint uq_consent_event_chain unique (entity_id, subject_id, sequence_number),
    -- Makes offline replay safe: a field device that retries a queued capture after a flaky
    -- reconnect must not create a second event. NULLs are distinct in Postgres, so events
    -- without a key are unaffected.
    constraint uq_consent_event_idempotency unique (entity_id, idempotency_key)
);

create index idx_consent_event_subject
    on consent_event (entity_id, subject_id, purpose_code, sequence_number desc);
create index idx_consent_event_recorded on consent_event (recorded_at);
create index idx_consent_event_expiry on consent_event (expires_at)
    where expires_at is not null;

-- Serialises sequence allocation per subject. This row is a pointer, not evidence, so unlike
-- consent_event it may be updated. Appends take a row lock here, which is what guarantees the
-- sequence is strictly monotonic and the chain has no forks under concurrent writes.
create table consent_chain_head (
    entity_id     varchar(64) not null,
    subject_id    varchar(64) not null,
    last_sequence bigint      not null,
    last_hash     char(64)    not null,
    updated_at    timestamptz not null default now(),
    primary key (entity_id, subject_id)
);

-- Reliable outbound publication. Writing the event and its outbox row in one transaction is
-- what stops a withdrawal being recorded but never fanned out — the failure that would leave
-- a dialer calling someone who has opted out.
create table event_outbox (
    id           bigserial   primary key,
    topic        varchar(64) not null,
    event_key    text        not null,
    payload      jsonb       not null,
    created_at   timestamptz not null default now(),
    published_at timestamptz,
    attempts     int         not null default 0,
    last_error   text
);

create index idx_outbox_unpublished on event_outbox (created_at)
    where published_at is null;


-- -------------------------------------------------------------------------------------
-- PROJECTIONS — derived, rebuildable, never authoritative
-- -------------------------------------------------------------------------------------

create table consent_artefact (
    entity_id       varchar(64) not null,
    subject_id      varchar(64) not null,
    purpose_code    varchar(64) not null,
    purpose_version int         not null,
    status          varchar(32) not null,
    legal_basis     varchar(48),
    notice_id       varchar(64),
    notice_version  int,
    language_tag    varchar(16),
    capture_method  varchar(48),
    channel         varchar(32),
    application_id  varchar(64),
    jurisdiction    varchar(8),
    granted_at      timestamptz,
    expires_at      timestamptz,
    withdrawn_at    timestamptz,
    last_event_at   timestamptz not null,
    sequence_number bigint      not null,
    last_event_hash char(64)    not null,
    -- Incremented when a late-arriving event could not be ordered confidently against what is
    -- already here. Non-zero means a human should look; the decision engine denies meanwhile.
    conflict_count  int         not null default 0,
    updated_at      timestamptz not null default now(),
    primary key (entity_id, subject_id, purpose_code)
);

create index idx_artefact_expiry on consent_artefact (expires_at)
    where expires_at is not null and status = 'GRANTED';
create index idx_artefact_purpose on consent_artefact (purpose_code, status);


-- -------------------------------------------------------------------------------------
-- PROVENANCE
--
-- The field no commercial consent platform has, and the one that matters most commercially:
-- where did this contact record come from, and can we substantiate it.
-- -------------------------------------------------------------------------------------

create table provenance_record (
    id                        bigserial   primary key,
    entity_id                 varchar(64) not null references fiduciary_entity (entity_id),
    subject_id                varchar(64) not null,
    source_type               varchar(48) not null,
    source_name               text        not null,
    acquired_at               timestamptz not null,
    original_legal_basis      varchar(48),
    original_consent_evidence_ref text,
    contract_ref              text,
    substantiated             boolean     not null default false,
    substantiation_note       text,
    -- Defaults to true on purpose. A record arrives quarantined and must be affirmatively
    -- substantiated to leave that state. Unsubstantiable records are quarantined, never
    -- grandfathered, and the schema is where that decision is made irreversible.
    quarantined               boolean     not null default true,
    reviewed_by               text,
    reviewed_at               timestamptz,
    created_at                timestamptz not null default now()
);

create index idx_provenance_subject on provenance_record (entity_id, subject_id);
create index idx_provenance_quarantined on provenance_record (entity_id, quarantined);


-- -------------------------------------------------------------------------------------
-- SUPPRESSION
--
-- Statutory registries are enforced today, unlike DPDP's substantive obligations. TRAI in
-- particular acts against telemarketers with financial penalties and disconnection, so this
-- table protects against the nearer-term risk.
-- -------------------------------------------------------------------------------------

create table suppression_entry (
    id              bigserial   primary key,
    entity_id       varchar(64) references fiduciary_entity (entity_id),
    scope           varchar(16) not null,
    source          varchar(32) not null,
    channel         varchar(32) not null,
    identifier_type varchar(32) not null,
    identifier_hash varchar(64) not null,
    subject_id      varchar(64),
    client_id       varchar(64),
    campaign_id     varchar(64),
    effective_from  timestamptz not null default now(),
    effective_to    timestamptz,
    reason          text,
    created_by      text,
    created_at      timestamptz not null default now(),
    constraint ck_scope_entity check (scope = 'GLOBAL' or entity_id is not null)
);

create index idx_suppression_lookup
    on suppression_entry (identifier_hash, channel, effective_from);
create index idx_suppression_subject on suppression_entry (subject_id, channel)
    where subject_id is not null;


-- -------------------------------------------------------------------------------------
-- ADMINISTRATIVE AUDIT
--
-- Administrators never silently edit consent. Where an administrator acts on a subject's
-- behalf the ledger gets an event with actor_type = ADMIN, and the action is recorded here
-- as well, so that "who changed this and why" is answerable without reading application logs.
-- -------------------------------------------------------------------------------------

create table admin_audit_event (
    id          bigserial   primary key,
    actor_id    text        not null,
    action      varchar(64) not null,
    entity_id   varchar(64),
    target_type varchar(64),
    target_id   text,
    detail      jsonb       not null default '{}'::jsonb,
    occurred_at timestamptz not null default now()
);

create index idx_admin_audit_time on admin_audit_event (occurred_at desc);


-- -------------------------------------------------------------------------------------
-- GRIEVANCES AND RIGHTS REQUESTS
-- -------------------------------------------------------------------------------------

create table rights_request (
    request_id    varchar(64) primary key,
    entity_id     varchar(64) not null references fiduciary_entity (entity_id),
    subject_id    varchar(64) not null,
    request_type  varchar(32) not null,
    status        varchar(32) not null default 'RECEIVED',
    received_at   timestamptz not null default now(),
    -- Statutory clock. Tracked as a column rather than computed on read so that a breach of
    -- the deadline is queryable, alertable, and visible in the console without a join.
    due_at        timestamptz not null,
    closed_at     timestamptz,
    assigned_to   text,
    resolution    text
);

create index idx_rights_due on rights_request (status, due_at);
