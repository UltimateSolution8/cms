-- =====================================================================================
-- Prove a withdrawal arrived — or say plainly that nobody was listening.
--
-- /phase-gate step 5 asks seven adversarial questions. The platform can answer six. The
-- second one it cannot:
--
--     A principal withdrew. Prove it reached every consuming system. Name the link that
--     is assumed rather than evidenced.
--
-- The link is in WebhookEventPublisher. When no subscription matches a topic it logs at
-- debug and returns normally, and com.uds.consent is INFO in every profile but local — so
-- the line does not exist in production. OutboxRelay then calls markPublished, because
-- publish did not throw. event_outbox.published_at therefore means "the publisher did not
-- throw", not "anything received it", and a webhook_delivery row is structurally
-- impossible for a system nobody registered, because that row requires a subscription_id.
--
-- Three separate audits made this finding from three directions — Phase 15's adversarial
-- pass, TRACEABILITY.md's Art. 7(3) row, ROADMAP.md — and none closed it. Registering
-- DenCRM is UDS's job. Being able to say that DenCRM was never registered is ours, and
-- nothing in the platform could say it.
--
-- -------------------------------------------------------------------------------------
-- Two tables, because these are two different questions.
--
-- The first draft of this migration had one, and it could answer neither. It asked "is
-- this gap still open?" by anti-joining webhook_delivery for a given outbox_id — which can
-- never become true, because fetchUnpublished selects where published_at is null and the
-- relay sets it on the same pass. Register DenCRM at 09:00, register its subscription at
-- 09:05, and the 300 events in between are open FOREVER: the gauge never returns to zero,
-- the critical alert fires for the life of the database, and it is muted inside a week.
--
-- So: CURRENT STATE needs no table at all. A mandatory active propagation_target with no
-- active webhook_subscription carrying that system_code is the finding, it is bounded by
-- the size of the register, and it returns to zero the moment an operator fixes the
-- configuration. That is what the gauge and the alert read.
--
-- HISTORICAL EVIDENCE is propagation_gap, append-only, and answers only "which obligations
-- went unmet on which day". Nothing alerts on it — alerting on an append-only count is how
-- the unreachable-zero problem comes back through the window.
--
-- This is the same split the platform already uses for rights_request_verification against
-- rights_request: mutable state that an operator can fix, beside evidence nobody can edit.
--
-- -------------------------------------------------------------------------------------
-- Why one row per target per DAY, and not per message.
--
-- propagation_gap is genuinely partitionable where consent_event is not. But it needs a
-- unique key to be idempotent under three concurrent relays, and a partitioned table's
-- unique constraints must include the partition key — at which point a retry crossing a
-- month boundary is accepted twice. That is V28's trap exactly, and the answer is to bound
-- the rows rather than partition them.
--
-- One row per uncovered target per day. Growth is targets × days rather than targets ×
-- events, dedup is free, partitioning is unnecessary, and the evidential property survives
-- intact: "this obligation was unmet on that day" is the fact a regulator asks about.
--
-- -------------------------------------------------------------------------------------
-- NO INHERITANCE. Stated out loud because rules §3 is the most misread section in that
-- file, and Phase 16's C6 established what that costs: a false claim that purposes inherit
-- by recursive CTE propagated into seven documents, including the reviewer agent's own
-- checklist, over three phases. A propagation target belongs to the entity that declares
-- it. A subsidiary does not inherit its parent's targets, there is no walk here, and
-- nothing in this migration should be read as inviting one.
-- =====================================================================================


-- Which systems must be told about a consent change, per entity and per topic.
--
-- Keyed on (entity_id, topic, system_code) and deliberately NOT hung off subscription_id.
-- A target is WHO MUST HEAR; a subscription is HOW THEY ARE REACHED. A target that hangs
-- off a subscription cannot express "DenCRM must be told and nobody registered it", which
-- is the entire finding this table exists to make expressible. Putting `mandatory` on the
-- subscription instead has exactly the same defect.
create table propagation_target (
    entity_id   varchar(64) not null references fiduciary_entity (entity_id),
    -- The stream, and the join axis. topic is what subscriptions actually route on. It
    -- cannot distinguish a withdrawal from a grant — that is what event_type on the gap
    -- row is for — and there is no per-entity purpose configuration to key on instead
    -- (REGULATORY_HANDOFF §8.2, and rules §3 as corrected in Phase 16's C6).
    topic       varchar(64) not null,
    -- DENCRM, ATHENA_DIALER, HRMS. Free text and not a foreign key, for the reason V26
    -- already gives for fulfilment_target: the systems that hold a person's data are not
    -- the same set as the surfaces that capture consent, and forcing them into one table
    -- makes each one wrong about the other.
    --
    -- Upper-cased by constraint on both sides of the join. `DenCRM` against `DENCRM` would
    -- be a permanent false gap, and the asymmetry with fulfilment_target is the point:
    -- there a mismatch fails LOUD and CLOSED, as a 409 naming the system. Here it would
    -- fail quiet, and write a false row into an append-only table every day forever.
    system_code varchar(64) not null,
    -- Whether a missing subscription is a finding. Non-mandatory is a system worth
    -- recording and not worth alerting on. Defaults true, the safe direction: a target
    -- added and forgotten produces a visible gap rather than a silent one.
    mandatory   boolean     not null default true,
    active      boolean     not null default true,
    description text,
    created_at  timestamptz not null default now(),
    primary key (entity_id, topic, system_code),
    constraint ck_propagation_target_code_upper check (system_code = upper(system_code))
);

create index idx_propagation_target_active on propagation_target (entity_id, topic)
    where active;

comment on table propagation_target is
    'The systems that must be told about a consent change, per entity and per topic. Empty '
    'means the platform reports nothing — which is the state before UDS configures it, and '
    'is why REGULATORY_HANDOFF §8.7 needs a signature. An unconfigured register is not the '
    'same as no obligation. Targets do NOT inherit down the entity hierarchy.';


-- What went untold, and on what day. Append-only.
--
-- Every column here records something OBSERVED. None of them records a conclusion, and the
-- distinction is the whole reason `reason` exists — see its comment below.
create table propagation_gap (
    gap_id      bigserial   primary key,
    entity_id   varchar(64) not null references fiduciary_entity (entity_id),
    -- Carried on the row rather than recovered by joining event_outbox. event_outbox has
    -- no entity_id, no subject_id, no index on event_key and NO RLS POLICY — it is absent
    -- from V13 — so reading it under an entity-scoped path would sequentially scan the
    -- largest table in the database through a layer that does not cover it. That is
    -- precisely the hole rules §2 exists to refuse. Nullable: the reconciler resolves the
    -- subject from the outbox key, and rights.verification.requested carries none.
    --
    -- AN EXEMPLAR, NOT A SET, AND THIS IS THE ROW'S SHARPEST EDGE. The unique key below is
    -- (entity_id, topic, system_code, detected_on) — it does not include subject_id — so the
    -- first message of the day for an uncovered target writes the row and every later one is
    -- discarded by `on conflict do nothing`. This column therefore names ONE principal whose
    -- message went untold, not all of them.
    --
    -- That is deliberate and it is a real limit. Admitting subject_id to the key would make
    -- growth targets × subjects × days — unbounded by population, which is exactly what the
    -- daily grain exists to avoid. So the fact this table records is a REGISTER-LEVEL one:
    -- "this obligation was unmet on that day". It is not per-principal evidence, and nothing
    -- built on it may claim to be. The evidence bundle's propagation section says so.
    subject_id  varchar(64),
    topic       varchar(64) not null,
    system_code varchar(64) not null,
    -- So a missed withdrawal and a missed grant are distinguishable where the row exists.
    -- Subject to the same exemplar caveat as subject_id above: the type recorded is that of
    -- the FIRST message of the day for this target, so a grant drained at 09:00 takes the
    -- row and a withdrawal untold at 14:00 leaves no trace of having been a withdrawal.
    -- Read it as "an example of what went untold", never as a census.
    event_type  varchar(32),
    -- WHAT WAS OBSERVED, NEVER A CONCLUSION.
    --
    --   NO_SUBSCRIPTION      a mandatory target has no active subscription. The platform
    --                        knows nobody was reachable.
    --   NOT_DELIVERED        a subscription exists and produced no DELIVERED row for this
    --                        message.
    --   NO_DELIVERY_CHANNEL  the configured publisher writes no delivery evidence at all.
    --
    -- The third is not a technicality: webhook_delivery is written ONLY by
    -- WebhookEventPublisher. LoggingEventPublisher and KafkaEventPublisher never write one
    -- — EventPublisher discards outboxId deliberately — and the default publisher is `log`,
    -- which is what the Denave pilot runs. Writing NOT_DELIVERED there would assert that a
    -- system was not told when the truth is that the platform has no way to know, and
    -- under `kafka` with DenCRM consuming normally it would simply be false.
    --
    -- That is the same false statement as answering "no recipients" on a receipt where the
    -- truth is "nobody wrote down who the recipients are" — the defect rules §1 spends a
    -- paragraph refusing. Record the absence of a channel; never infer non-delivery.
    reason      varchar(32) not null,
    -- The grain. A date rather than a timestamp because the unique key below is what makes
    -- this table idempotent under concurrent relays, and the fact worth preserving is that
    -- the obligation was unmet on that day.
    detected_on date        not null default current_date,
    detected_at timestamptz not null default now(),
    constraint ck_propagation_gap_reason check (
        reason in ('NO_SUBSCRIPTION', 'NOT_DELIVERED', 'NO_DELIVERY_CHANNEL')),
    -- Idempotency, and it has to be here rather than in a lock. OutboxStore.fetchUnpublished
    -- has no `for update skip locked` and OutboxRelay — unlike all seven sweepers — takes no
    -- SweepLock, while deploy/k8s ships replicas: 3. So three relays drain the same batch.
    -- The insert below is `on conflict do nothing`, which needs only INSERT; `do update`
    -- would need UPDATE and would be refused by the revoke at the foot of this file. That
    -- is a reason to write it that way, not a preference.
    constraint uq_propagation_gap_daily unique (entity_id, topic, system_code, detected_on)
);

create index idx_propagation_gap_entity_day on propagation_gap (entity_id, detected_on);

comment on table propagation_gap is
    'One row per uncovered propagation target per day: an obligation to tell a downstream '
    'system about a consent change that the platform cannot show was met. Append-only. '
    'History, not current state — nothing alerts on this table; the gauge reads the '
    'register instead, because a count that can never return to zero is a muted alert.';


-- The join key, on the other side.
--
-- Backfilled to subscription_id rather than left null. Null never joins, so after this
-- migration every existing subscription would fail to match every mandatory target and the
-- register would report a group-wide gap that is an artefact of the migration rather than a
-- fact about the group. subscription_id is a label the operator already chose, so nothing
-- is fabricated by adopting it — and where it does not match the target an operator meant,
-- the admin GET returns the resolved subscription id or null per target, which puts the
-- mismatch on the page they are already looking at.
alter table webhook_subscription
    add column if not exists system_code varchar(64);

update webhook_subscription set system_code = upper(subscription_id) where system_code is null;

alter table webhook_subscription
    add constraint ck_webhook_subscription_code_upper check (
        system_code is null or system_code = upper(system_code));

create unique index if not exists uq_webhook_subscription_system
    on webhook_subscription (entity_id, topic, system_code)
    where system_code is not null;

comment on column webhook_subscription.system_code is
    'Which downstream system this endpoint reaches, joining propagation_target. Upper-cased '
    'on both sides by constraint: DenCRM against DENCRM would be a permanent false gap, '
    'written into an append-only table once a day, forever.';


-- Whose consent change a delivery carried.
--
-- Nullable, and pre-V31 rows stay null. The publisher already holds the subject at the
-- point it writes the row, so every row from here on carries it. Backfilling the existing
-- ones from event_outbox.event_key would put a derived guess into an append-only evidence
-- table, which is the defect class Phase 16's C8 closed. Null means "written before the
-- platform recorded this", which is the only true value.
alter table webhook_delivery
    add column if not exists subject_id varchar(64);

comment on column webhook_delivery.subject_id is
    'The data principal whose consent change this delivery carried. Null for rows written '
    'before V31 — not backfilled, because a derived value in append-only evidence is a '
    'fabricated fact.';


do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'uds_consent_app') then
        raise notice
            'role uds_consent_app not present; propagation guards NOT applied. '
            'Create the role and re-run V31 before going to production.';
        return;
    end if;

    -- Append-only, following V27's shape rather than V2's triggers. Every evidence table
    -- added since V24 — subject_alias, rights_fulfilment_action, webhook_delivery — uses
    -- the bare revoke; V2's trigger functions guard the V1-era tables. Copy the sibling.
    --
    -- A gap record that could be edited after a complaint is not evidence of anything, and
    -- this is the table that says the group failed to tell one of its own systems.
    execute 'revoke update, delete on propagation_gap from uds_consent_app';

    alter table propagation_gap enable row level security;
    create policy uds_entity_isolation on propagation_gap
        using (uds_entity_claim() is null or entity_id = uds_entity_claim())
        with check (uds_entity_claim() is null or entity_id = uds_entity_claim());

    -- The register is configuration rather than evidence, so it stays mutable — an
    -- operator fixing a wrong system_code must be able to fix it. It is still
    -- entity-scoped: which of another group company's systems are behind on hearing about
    -- withdrawals is not something one entity should read about another.
    alter table propagation_target enable row level security;
    create policy uds_entity_isolation on propagation_target
        using (uds_entity_claim() is null or entity_id = uds_entity_claim())
        with check (uds_entity_claim() is null or entity_id = uds_entity_claim());
end
$$;
