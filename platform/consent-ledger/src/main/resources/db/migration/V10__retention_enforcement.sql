-- =====================================================================================
-- Retention, enforced rather than merely documented.
--
-- processing_activity.retention_period_days has existed since V1 and fed exactly one thing:
-- a gap report saying which activities had no rule. Nothing ever acted on the rule where one
-- existed, which made the RoPA a description of an intention rather than of a practice.
-- DPDP s.8(7) requires erasure once the purpose is no longer served, and an entry in a
-- spreadsheet is not erasure.
--
-- -------------------------------------------------------------------------------------
-- What this does NOT do, and why.
--
-- It proposes. It does not delete. Two structural reasons, neither of them caution:
--
--   1. The personal data is not here. It lives in DenCRM, the HRMS, the BGV workflow and a
--      dozen client systems. This platform holds consent evidence about people, not the
--      people's records. A sweeper that deleted from here would erase the proof that the
--      erasure was lawful while leaving the data itself untouched — precisely backwards.
--
--   2. The ledger is append-only by design. The evidence of a consent interaction has to
--      outlive the personal data it concerned, or the group loses its ability to show that
--      it held the data lawfully for as long as it did.
--
-- So the platform's job is to say what is due, emit it, record that it was done, and keep the
-- gap between "due" and "done" visible to somebody. That gap is the compliance position.
--
-- -------------------------------------------------------------------------------------
-- Rule 8 and the ordering that is easy to get wrong.
--
-- DPDP Rules 2025, Rule 8 requires the fiduciary to inform the data principal BEFORE erasing
-- their data — at least forty-eight hours before the retention period ends — so that they can
-- act to keep the account alive if they wish. The consequence for this table is that the date
-- the sweeper acts on is the NOTICE date, not the erasure date. Computing only the erasure
-- date and notifying on it produces a platform that erases punctually and unlawfully.
--
-- Hence two columns and two nulls to chase: notice_due_at, then erase_due_at.
-- =====================================================================================

create table retention_action (
    id             bigserial   primary key,
    entity_id      varchar(64) not null references fiduciary_entity (entity_id),
    activity_id    bigint      references processing_activity (id) on delete set null,
    purpose_code   varchar(64) not null,
    -- The opaque subject reference. This table names who is due for erasure, so it must not
    -- become a contact list any more than the rest of the evidence plane may.
    subject_id     varchar(64) not null,
    -- The last consent interaction for this subject and purpose. The retention period runs
    -- from here rather than from record creation: a subject who re-consented last month has
    -- not been dormant for three years however old their first row is.
    last_activity_at timestamptz not null,
    -- Rule 8 first, then s.8(7).
    notice_due_at  timestamptz not null,
    erase_due_at   timestamptz not null,
    status         varchar(24) not null default 'DUE',
    -- The system that actually holds the data and has to do the deleting.
    system_name    text,
    notified_at    timestamptz,
    erased_at      timestamptz,
    confirmed_by   text,
    note           text,
    raised_at      timestamptz not null default now(),
    constraint ck_retention_status check (
        status in ('DUE', 'NOTICE_SENT', 'ERASED', 'RETAINED', 'CANCELLED')),
    -- One open action per subject, purpose and erasure date. A sweeper running every hour must
    -- not raise the same proposal twenty-four times a day; re-raising it after the owning
    -- system acts is a different action with a different date.
    constraint uq_retention_action unique (entity_id, subject_id, purpose_code, erase_due_at)
);

comment on table retention_action is
    'Proposed erasures, and whether they happened. The platform proposes; the system holding '
    'the data disposes. The gap between DUE and ERASED is the compliance position.';

comment on column retention_action.status is
    'RETAINED is not a failure state — it records a documented decision to keep the data on a '
    'basis other than the expired one (a legal hold, a live contract). An undocumented '
    'retention is a DUE that nobody actioned, and the two must not look alike.';

create index idx_retention_action_open on retention_action (entity_id, notice_due_at)
 where status in ('DUE', 'NOTICE_SENT');
create index idx_retention_action_subject on retention_action (entity_id, subject_id);
