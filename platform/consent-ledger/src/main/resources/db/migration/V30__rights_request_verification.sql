-- =====================================================================================
-- What the statutory clock's start instant rests on.
--
-- rights_request.received_at is the input to StatutoryClock, and the deadline computed from
-- it is stored rather than derived — a fact about what the group committed to on the day the
-- request arrived. V6 made the deadline reconstructable by recording due_at_basis, the rule
-- it came from. This records the other half: where received_at itself came from.
--
-- The gap being closed. POST /v1/rights takes a caller-supplied receivedAt and computes the
-- Rule 14(3) deadline straight from it, with no bound and nothing on the row saying whether
-- the person filing was ever established to be the principal. Two consequences, in opposite
-- directions:
--
--   * A forward-dated instant moves the deadline outward. In a dispute about lateness, the
--     group's own record would be evidence supplied by the party the dispute is with.
--   * A far-backdated instant is refused as a sanity bound only. It was written here as
--     "manufacturing a statutory breach", and that reasoning was wrong: every period the
--     platform computes is shorter than the bound, so a filing between the applicable period
--     and the bound is accepted and *is* overdue on arrival. Accepting those is correct — a
--     letter found in a postbag is a real filing — and the distinction is recorded on the
--     audit event as bornOverdue instead. Corrected in Phase 16's closure, C1.
--
-- Both are now refused in RightsService.intake — forward beyond the shared clock-skew window,
-- backward beyond uds.consent.rights.max-backdate. This migration records the provenance that
-- refusal cannot supply: whether anybody checked, and how.
--
-- WHAT THIS IS NOT. Not a gate. An UNVERIFIED request still gets a clock, still enters the
-- queue and is still answered. Parking requests outside the clock until somebody fills in a
-- field would produce exactly the outcome Rule 14(3) penalises. Same posture as the fulfilment
-- register in V26: record the silence; do not let it read as diligence.
--
-- THE BACKFILL DEFAULT IS 'UNVERIFIED', AND THAT IS THE POINT. Every existing row was filed
-- through the administrative route before this column existed, so nobody recorded having
-- checked anything. Defaulting to PORTAL_TOKEN would claim a verification that never happened;
-- defaulting to OPERATOR_ASSERTED would claim an operator's assurance nobody gave. Either
-- would be a false statement written into the evidence plane by a migration, which is the
-- worst possible place to put one.
-- =====================================================================================

alter table rights_request
    add column if not exists verification_method varchar(24) not null default 'UNVERIFIED',
    -- When identity was established. Null exactly when nothing was.
    add column if not exists verified_at timestamptz,
    -- How, in the operator's words: a call-back to a number already on file, an employee ID
    -- checked at a desk, a document reference. The platform records the claim and who made it;
    -- the substance of it is theirs.
    add column if not exists verification_detail text;

alter table rights_request
    drop constraint if exists chk_rights_request_verification;

alter table rights_request
    add constraint chk_rights_request_verification check (
        verification_method in ('PORTAL_TOKEN', 'OPERATOR_ASSERTED', 'UNVERIFIED'));

-- A biconditional rather than two loose columns. "Verified, but we did not record when" and
-- "not verified, but here is the time we verified it" are both incoherent, and the drift V6's
-- closure constraint was written to prevent is the same drift here: a column that is sometimes
-- null for no reason is one that makes a compliance report quietly wrong for a year.
alter table rights_request
    drop constraint if exists chk_rights_request_verified_at;

alter table rights_request
    add constraint chk_rights_request_verified_at check (
        (verification_method = 'UNVERIFIED' and verified_at is null)
        or (verification_method <> 'UNVERIFIED' and verified_at is not null));

comment on constraint chk_rights_request_verification on rights_request is
    'Mirrors com.uds.consent.core.model.RightsVerificationMethod. Extend both together.';

comment on column rights_request.verification_method is
    'How identity was established before the clock started. PORTAL_TOKEN is the only one the '
    'platform establishes for itself; OPERATOR_ASSERTED is a named person''s claim; UNVERIFIED '
    'means nobody recorded having checked, which is the default and is not a defect in itself.';

-- "How many of our open requests started on an instant nobody verified" in one query rather
-- than a table scan. That number is the one a Board question turns on, and it belongs on a
-- dashboard rather than in an ad-hoc export written under time pressure.
create index if not exists idx_rights_open_verification
    on rights_request (entity_id, verification_method)
 where status in ('RECEIVED', 'IN_PROGRESS', 'AWAITING_SUBJECT');

-- No row-level-security policy is added here, and that is not an omission: rights_request is
-- already inside the protected set (V13, uds_entity_claim()), and RowLevelSecurityIT derives
-- that set from information_schema rather than from a list. Adding columns to a covered table
-- changes nothing about its coverage.
