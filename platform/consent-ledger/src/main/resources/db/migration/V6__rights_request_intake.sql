-- =====================================================================================
-- Rights requests: the columns intake needs, and constraints on the ones already there.
--
-- V1 created rights_request with a due_at column and an index built for breach alerting,
-- then nothing was ever written to it. This migration makes the table usable and closes the
-- gap between what the schema implies and what it can actually enforce.
--
-- The statutory period differs per jurisdiction — one month under GDPR, forty-five days
-- under CPRA, ten under Korea's PIPA — so the jurisdiction has to be on the row. Without it
-- the deadline is a number nobody can check the working of, which is the opposite of what an
-- evidence plane is for.
-- =====================================================================================

alter table rights_request
    add column if not exists jurisdiction varchar(8),
    add column if not exists details text,
    add column if not exists acknowledged_at timestamptz,
    -- Which rule produced due_at. Recorded because a deadline nobody can reconstruct is a
    -- deadline that gets argued about, and the argument happens years later when the person
    -- who configured it has left.
    add column if not exists due_at_basis text;

update rights_request set jurisdiction = 'IN' where jurisdiction is null;

alter table rights_request
    alter column jurisdiction set not null;

alter table rights_request
    drop constraint if exists chk_rights_request_type;

alter table rights_request
    add constraint chk_rights_request_type check (request_type in (
        'ACCESS', 'CORRECTION', 'COMPLETION', 'ERASURE', 'NOMINATION', 'GRIEVANCE',
        'CONSENT_WITHDRAWAL', 'PORTABILITY', 'OPT_OUT_OF_SALE'));

alter table rights_request
    drop constraint if exists chk_rights_request_status;

alter table rights_request
    add constraint chk_rights_request_status check (status in (
        'RECEIVED', 'IN_PROGRESS', 'AWAITING_SUBJECT', 'FULFILLED', 'REJECTED', 'WITHDRAWN'));

-- A closed request must say when it closed, and an open one must not claim to have.
-- Stated as a constraint rather than trusted to the service layer because "closed_at is
-- sometimes null on a fulfilled request" is exactly the kind of drift that makes an SLA
-- report quietly wrong for a year.
alter table rights_request
    drop constraint if exists chk_rights_request_closure;

alter table rights_request
    add constraint chk_rights_request_closure check (
        (status in ('FULFILLED', 'REJECTED', 'WITHDRAWN') and closed_at is not null)
        or (status in ('RECEIVED', 'IN_PROGRESS', 'AWAITING_SUBJECT') and closed_at is null));

comment on constraint chk_rights_request_type on rights_request is
    'Mirrors com.uds.consent.core.model.RightsRequestType. Extend both together.';

comment on constraint chk_rights_request_status on rights_request is
    'Mirrors com.uds.consent.core.model.RightsRequestStatus. Extend both together.';

-- The breach query: open requests past their deadline, oldest first. V1's idx_rights_due
-- covers due_at alone; this one lets the sweeper skip closed requests entirely, which is
-- most of the table after the first quarter.
create index if not exists idx_rights_open_due
    on rights_request (due_at)
 where status in ('RECEIVED', 'IN_PROGRESS', 'AWAITING_SUBJECT');

create index if not exists idx_rights_subject
    on rights_request (entity_id, subject_id, received_at desc);
