-- =====================================================================================
-- A correction, made openly.
--
-- V20 cited "DPDP Rule 14" for the power to name categories of personal data that a
-- Significant Data Fiduciary may not transfer outside India. That citation is wrong, in the
-- file header, in a section comment and in a column comment, and it was repeated in
-- ProcessingActivityStore, RopaService, SdfObligationIT, the README, OPERATIONS.md and the
-- regulatory hand-off — thirteen places in eight files.
--
-- The notified DPDP Rules 2025 number it as follows:
--
--   Rule 13    Obligations of a Significant Data Fiduciary.
--   Rule 13(4) The Central Government may, on the recommendation of the committee constituted
--              under Rule 13(5), specify categories of personal data — and the traffic data
--              pertaining to its flow — that shall not be transferred outside India.
--   Rule 13(5) The committee that makes that recommendation.
--   Rule 14    Rights of Data Principals: publication of the means of exercising them, the
--              particulars required to identify the principal, grievance redressal and
--              nomination. Nothing to do with transfers.
--   Rule 15    Transfer of personal data outside India. The general restriction, and it binds
--              EVERY Data Fiduciary — not only the Significant ones.
--
-- -------------------------------------------------------------------------------------
-- Why this is a migration rather than an edit.
--
-- Flyway checksums applied migrations. Editing V20's file header would change its checksum
-- and every environment that has already run it would refuse to start — including the ones
-- this platform is supposed to be safe to operate. So V20's header stays as delivered, wrong,
-- with this file as its correction. That is the honest arrangement: an applied migration is a
-- historical record of what was believed at the time, and rewriting history in a compliance
-- platform is exactly the habit the append-only ledger exists to refuse.
--
-- The column comments, by contrast, are current documentation rather than history, and
-- `comment on` is idempotent. They are reissued below with the right rule.
--
-- -------------------------------------------------------------------------------------
-- What the correction changes about behaviour: nothing.
--
-- No column, constraint, index or policy is altered here. The hook was built to the right
-- substance — a named-category prohibition, empty until notified, consulted by the RoPA
-- cross-border report — and only its citation was wrong. Which is the good version of this
-- mistake, and still worth fixing at the first opportunity, because a wrong rule number in a
-- compliance platform is the thing an auditor checks first and the thing that makes them
-- doubt everything after it.
-- =====================================================================================

comment on column data_category.transfer_restricted is
    'DPDP Rule 13(4): whether the Central Government, on the recommendation of the Rule 13(5) '
    'committee, has specified this category as one a Significant Data Fiduciary may not transfer '
    'outside India. False on every row as at August 2026 because no categories are notified — '
    'checked, not assumed. The RoPA cross-border report reads this column already, so honouring a '
    'future notification is an update rather than a release. Distinct from Rule 15, which is the '
    'general restriction on transfer outside India and binds every Data Fiduciary. Cited as '
    '"Rule 14" in V20, which is wrong: Rule 14 is rights and grievance redressal.';

comment on column data_category.transfer_restriction_ref is
    'The notification that imposed the Rule 13(4) restriction. A restriction nobody can cite is '
    'one nobody can lift when it is withdrawn.';

-- -------------------------------------------------------------------------------------
-- The half of Rule 13(4) this platform does not cover, said out loud.
--
-- Rule 13(4) reaches "the personal data and the traffic data pertaining to its flow". The hook
-- above models the personal data half, by data category. Traffic data — which carrier a message
-- travelled through, at what time, to which destination — is not a data category and is not held
-- here at all: this platform holds consent evidence, not message logs. The DLT registry knows
-- header and template registrations, not deliveries.
--
-- So the traffic-data limb is out of scope, and it is recorded as out of scope rather than left
-- looking covered. Whoever operates the CPaaS and dialer logs owns it, and the hand-off says so.
-- -------------------------------------------------------------------------------------

comment on table sdf_obligation is
    'DPDP Rule 13: the annual DPIA, the annual independent audit, and the algorithmic due '
    'diligence a Significant Data Fiduciary owes, with observations furnished to the Board. Empty '
    'for every entity the Government has not notified, which is the correct answer rather than a '
    'hidden one. Rule 13(4)''s localisation power is modelled separately, on '
    'data_category.transfer_restricted.';
