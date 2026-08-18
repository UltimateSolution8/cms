-- =====================================================================================
-- Something has to receive a withdrawal.
--
-- The outbox works. ConsentLedger enqueues on every event, OutboxRelay drains every two
-- seconds, retries on failure and pages after ten attempts. It publishes to a broker that
-- nobody consumes, and the default publisher is `log`.
--
-- So the platform's answer to "has this person withdrawn" is correct, immediate and entirely
-- passive. DenCRM, the HRMS, Athena and the campaign tools each have to *ask*. Any one that
-- forgets to ask keeps calling somebody who opted out, and the platform records nothing about
-- it — because from here, nothing happened.
--
-- That is the defining function of a consent management platform, and until now it was a
-- promise. This migration is the receiving half.
--
-- -------------------------------------------------------------------------------------
-- Why webhooks and not a broker consumer.
--
-- A broker consumer is a second deployable, with its own lifecycle, its own credentials and its
-- own on-call. Every downstream system in the group would need one written, in whatever
-- language that team uses.
--
-- An outbound HTTP POST needs the downstream system to expose one endpoint. DenCRM can be
-- receiving withdrawals in an afternoon. Kafka remains available and is the better answer at
-- high fan-out; this is the answer that gets a withdrawal into DenCRM before the pilot.
--
-- -------------------------------------------------------------------------------------
-- Why the delivery table exists at all.
--
-- Because "did the withdrawal reach DenCRM" has to be answerable, and an HTTP 200 that nobody
-- wrote down is not an answer. This is the same argument as enforcement_decision: a platform
-- that takes an action and keeps no evidence of it can describe what it did and cannot prove
-- it. When a principal complains that they were called after withdrawing, the question is
-- whether the group told its own systems — and this table is where that is either shown or
-- conspicuously absent.
-- =====================================================================================

create table webhook_subscription (
    subscription_id varchar(64) primary key,
    entity_id       varchar(64) not null references fiduciary_entity (entity_id),
    -- Which stream. Subscriptions are per topic AND per entity: a Denave endpoint must not
    -- receive Matrix's withdrawals, and topic alone would deliver every entity's events to
    -- whoever subscribed first.
    topic           varchar(64) not null,
    url             text        not null,
    -- HMAC-SHA256 shared secret, hashed. The receiver holds the plaintext; this side holds
    -- enough to sign and nothing that reads usefully in a database dump.
    --
    -- Stored rather than derived because it has to be given to the receiving team once, out of
    -- band, and rotating it is a deliberate act with a date rather than a redeploy.
    secret          text        not null,
    active          boolean     not null default true,
    description     text,
    created_at      timestamptz not null default now(),
    constraint uq_webhook_subscription unique (entity_id, topic, url)
);

create index idx_webhook_subscription_active on webhook_subscription (topic, entity_id)
    where active;

comment on column webhook_subscription.url is
    'Where to POST. https in any real deployment — the payload names a data principal''s subject '
    'reference and what they decided, which is personal data in transit.';


-- One row per attempt, not per message. A message that succeeded on the third try is a message
-- that failed twice, and both facts matter: the first when explaining a delay, the second when
-- deciding whether a downstream endpoint is reliable enough to keep on the list.
create table webhook_delivery (
    delivery_id     bigserial   primary key,
    subscription_id varchar(64) not null references webhook_subscription (subscription_id),
    entity_id       varchar(64) not null references fiduciary_entity (entity_id),
    outbox_id       bigint      not null,
    attempt         int         not null,
    status          varchar(16) not null,
    response_code   int,
    error           text,
    delivered_at    timestamptz not null default now(),
    constraint ck_webhook_delivery_status check (status in ('DELIVERED', 'FAILED'))
);

create index idx_webhook_delivery_outbox on webhook_delivery (outbox_id);
create index idx_webhook_delivery_failed on webhook_delivery (subscription_id, delivered_at desc)
    where status = 'FAILED';

comment on table webhook_delivery is
    'Evidence that a consent change was pushed to a downstream system, or was not. Answers "did '
    'the withdrawal reach DenCRM" — which an HTTP 200 nobody wrote down cannot.';

do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'uds_consent_app') then
        raise notice 'role uds_consent_app not present; webhook guards NOT applied.';
        return;
    end if;

    -- Append-only, like enforcement_decision and for the same reason: a delivery record that
    -- could be edited after a complaint is not evidence of anything.
    execute 'revoke update, delete on webhook_delivery from uds_consent_app';

    alter table webhook_delivery enable row level security;
    create policy uds_entity_isolation on webhook_delivery
        using (uds_entity_claim() is null or entity_id = uds_entity_claim())
        with check (uds_entity_claim() is null or entity_id = uds_entity_claim());

    alter table webhook_subscription enable row level security;
    create policy uds_entity_isolation on webhook_subscription
        using (uds_entity_claim() is null or entity_id = uds_entity_claim())
        with check (uds_entity_claim() is null or entity_id = uds_entity_claim());
end
$$;
