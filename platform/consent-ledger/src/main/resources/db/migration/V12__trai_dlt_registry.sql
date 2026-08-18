-- =====================================================================================
-- TRAI as it actually stands, after the February 2025 amendment.
--
-- The platform modelled the 2018 regulation plus the seven-day transactional window and
-- nothing the amendment added. In force since 6 May 2025 and material to this group:
--
--   * message-category header suffixes (-P promotional, -S service/transactional,
--     -T transactional-OTP, -G governmental)
--   * the 140- and 1600- numbering series, separating promotional from transactional traffic
--   * a mandatory opt-out facility in every promotional message
--   * a NINETY-DAY COOLING-OFF before re-soliciting consent from someone who opted out
--
-- The cooling-off is the sharp one and it is why this migration exists now rather than later.
-- The re-permissioning campaign against Denave's quarantined records is the entire commercial
-- point of the provenance work, and re-soliciting consent inside ninety days of an opt-out is
-- exactly the activity it restricts. Modelled in TccprModule rather than here, because it is a
-- rule rather than a registry — but it needs the opt-out dates this schema already holds.
--
-- -------------------------------------------------------------------------------------
-- What the registry buys.
--
-- Before it, a decision on an SMS purpose returned the obligations "use-dlt-registered-header"
-- and "use-dlt-registered-template". Both tell the sender something it already knew and
-- neither tells it WHICH. So an outbound message could not be tied to a registered template
-- and a live consent and a preference check in one place, which is precisely the join a TRAI
-- investigation asks about.
-- =====================================================================================

create table dlt_header (
    header_id  varchar(64) primary key,
    entity_id  varchar(64) not null references fiduciary_entity (entity_id),
    -- The six-character sender id as registered with the DLT platform, e.g. DENAVE.
    header     varchar(16) not null,
    -- P, S, T or G. Determines which header may carry which traffic, and a mis-send is
    -- caught on exactly this.
    category   varchar(1)  not null,
    -- The originating series. 140- for promotional, 1600- for transactional; null for a
    -- header used only for SMS, where the series does not apply.
    series     varchar(8),
    registered_at date,
    active     boolean     not null default true,
    constraint ck_dlt_header_category check (category in ('P', 'S', 'T', 'G')),
    constraint uq_dlt_header unique (entity_id, header, category)
);

comment on table dlt_header is
    'Sender identities registered on the DLT platform. The category is not decoration: a '
    'promotional message sent under a service header is the mis-send TRAI acts on.';

create table dlt_template (
    template_id  varchar(64) primary key,
    entity_id    varchar(64) not null references fiduciary_entity (entity_id),
    header_id    varchar(64) not null references dlt_header (header_id),
    -- Tied to a purpose, which is what makes the join possible: given a consent decision the
    -- sender is told which registered template it may use, rather than being told that one
    -- exists.
    purpose_code varchar(64) not null references purpose (code),
    -- The id the DLT platform issued. This is the value that has to appear on the wire.
    template_ref varchar(64) not null,
    description  text,
    registered_at date,
    active       boolean     not null default true,
    constraint uq_dlt_template unique (entity_id, purpose_code, template_ref)
);

create index idx_dlt_template_lookup on dlt_template (entity_id, purpose_code)
 where active = true;

-- -------------------------------------------------------------------------------------
-- Seed: Denave's registrations.
--
-- Placeholder references, deliberately obvious. Real header and template ids are issued by
-- the DLT platform and have to be entered by whoever holds that account — a plausible-looking
-- fake would be worse than an obvious one, because it would be believed and sent.
-- -------------------------------------------------------------------------------------

insert into dlt_header (header_id, entity_id, header, category, series, active) values
    ('DLT_DENAVE_P', 'DENAVE_IN', 'DENAVE', 'P', '140', true),
    ('DLT_DENAVE_S', 'DENAVE_IN', 'DENSRV', 'S', '1600', true)
on conflict do nothing;

insert into dlt_template (template_id, entity_id, header_id, purpose_code, template_ref,
                          description, active)
values
    ('DLT_TPL_MKT_SMS', 'DENAVE_IN', 'DLT_DENAVE_P', 'MKT_OUTBOUND_SMS',
     'PENDING_REGISTRATION', 'Promotional SMS. Replace template_ref with the id the DLT '
     'platform issues before any send.', true),
    ('DLT_TPL_TXN_SMS', 'DENAVE_IN', 'DLT_DENAVE_S', 'TXN_SERVICE_SMS',
     'PENDING_REGISTRATION', 'Transactional service message. Replace template_ref before any '
     'send.', true)
on conflict do nothing;

comment on column dlt_template.template_ref is
    'PENDING_REGISTRATION means exactly that: no real template has been registered and any '
    'send under it will be rejected by the operator. The decision API surfaces the value so '
    'the gap is visible before a campaign rather than during one.';
