-- =====================================================================================
-- Personal data breaches, and the clocks they start.
--
-- Modelled on rights_request, which works: a deadline fixed at intake, stored on the row
-- rather than recomputed on read, and a sweeper that logs at ERROR until it is closed.
-- Recomputing a deadline on every read means the deadline changes when the code changes,
-- and a statutory clock that silently moves is worse than no clock.
--
-- The argument for holding this in the platform at all, rather than only in the group's
-- incident process: the 72-hour report DPDP Rule 7 requires must carry a summary of the
-- intimations given to data principals. That means somebody has to be able to say which
-- consents and purposes were live for each affected subject AT THE TIME OF THE BREACH — not
-- now, after three days of withdrawals prompted by the notification itself. The ledger can
-- answer that and no incident-management tool can. A breach report scoped to whatever the
-- person filing it estimated is not evidence of anything.
--
-- Penalty ceilings, for the priority argument: 200 crore for failing to notify a breach,
-- 250 crore for failing to take reasonable security safeguards.
-- =====================================================================================

create table personal_data_breach (
    breach_id        varchar(64) primary key,
    entity_id        varchar(64) not null references fiduciary_entity (entity_id),
    jurisdiction     varchar(8)  not null,
    -- When it happened, and when the group found out. Both, because they are different dates
    -- doing different jobs: the clock runs from awareness, and the affected population is
    -- computed as at occurrence.
    occurred_at      timestamptz not null,
    detected_at      timestamptz,
    aware_at         timestamptz not null,
    description      text        not null,
    -- The categories involved, so the assessment does not have to be reconstructed from prose.
    data_categories  jsonb       not null default '[]'::jsonb,
    purpose_codes    jsonb       not null default '[]'::jsonb,
    affected_subjects int,
    -- Whether the breach is likely to result in a risk. Drives Art.33's carve-out and Rule 7's
    -- content, and is a human judgement recorded with its reasoning rather than derived.
    risk_assessment  text,
    severity         varchar(16) not null default 'UNASSESSED',
    status           varchar(24) not null default 'REPORTED',
    reported_by      text        not null,
    reported_at      timestamptz not null default now(),
    closed_at        timestamptz,
    closure_note     text,
    constraint ck_breach_status check (
        status in ('REPORTED', 'ASSESSING', 'NOTIFYING', 'CLOSED', 'NOT_NOTIFIABLE')),
    constraint ck_breach_severity check (
        severity in ('UNASSESSED', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
);

comment on column personal_data_breach.occurred_at is
    'When the breach happened. The affected population is computed as at this instant, not as '
    'at now — a subject who withdrew after the breach was still affected by it.';

comment on column personal_data_breach.aware_at is
    'When the group became aware. Every reporting clock runs from here, not from occurrence: a '
    'breach found in a log review three weeks later starts its countdown at the review.';

-- One row per party that has to be told. Separate from the breach because the parties have
-- different deadlines under the same event — Rule 7 gives the principals "without delay" and
-- the Board a further detailed report at 72 hours — and a single notified_at column on the
-- breach could only ever record one of them.
create table breach_notification (
    id             bigserial   primary key,
    breach_id      varchar(64) not null references personal_data_breach (breach_id)
                                   on delete cascade,
    party          varchar(24) not null,
    -- Null for an obligation that must be discharged "without delay". Rule 7's first stage has
    -- no hour figure, and inventing one would be the platform granting a grace period no
    -- regulator has offered. The immediate flag carries the distinction instead.
    due_at         timestamptz,
    immediate      boolean     not null default false,
    basis          text        not null,
    notified_at    timestamptz,
    notified_by    text,
    method         text,
    reference      text,
    recipient_count int,
    note           text,
    constraint ck_breach_party check (
        party in ('REGULATOR', 'DATA_PRINCIPALS', 'CLIENT')),
    constraint uq_breach_party unique (breach_id, party, basis)
);

comment on table breach_notification is
    'One row per party per obligation. An obligation with immediate = true is discharged or '
    'outstanding; it is never on schedule.';

create index idx_breach_entity on personal_data_breach (entity_id, reported_at desc);
create index idx_breach_open on personal_data_breach (status, aware_at)
 where status <> 'CLOSED' and status <> 'NOT_NOTIFIABLE';
create index idx_breach_notification_outstanding on breach_notification (breach_id)
 where notified_at is null;

-- Deliberately NOT append-only, unlike the ledger and the enforcement log.
--
-- A breach record is a working document for the first 72 hours: the severity is revised as the
-- assessment progresses, the affected count firms up, the description gets corrected. Freezing
-- it would force the team to open a second breach record to correct the first, and an incident
-- with three records is an incident nobody can report on.
--
-- What must not be silently editable is the evidence that a notification happened — and that
-- lands in admin_audit_event, which is append-only, on every transition. So the mutable
-- document and the immutable trail sit side by side, which is the right split.
