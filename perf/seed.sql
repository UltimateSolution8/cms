-- =====================================================================================
-- A realistic artefact population, so the load test measures something.
--
-- A decision against an empty table measures an index that fits entirely in the buffer cache
-- and answers a question nobody asked. The number that comes out is fast, honest about
-- nothing, and — because it is the number that gets quoted — actively misleading.
--
-- Run against a LOAD TEST DATABASE. Never against production, and never against a database
-- that will later be produced as evidence: everything here is fabricated, and a ledger
-- containing fabricated consent is a ledger with a fatal problem no verification will catch,
-- because the hashes will be perfectly valid.
--
--   psql -h <host> -U uds_consent_owner -d uds_consent -f perf/seed.sql
--
-- Writes projections and suppressions only, not consent_event. Seeding the ledger would mean
-- either computing a valid hash chain per subject in SQL — reimplementing ConsentLedger badly,
-- in the one component whose correctness everything rests on — or writing a broken chain that
-- makes the nightly integrity sweep scream. The decision path reads consent_artefact, which is
-- the projection, so this is sufficient for what it is for and stops short of what it is not.
-- =====================================================================================

\set subject_count 1000000
\set suppression_rate 0.15

begin;

-- -------------------------------------------------------------------------------------
-- Subjects, and the identifiers they resolve by.
-- -------------------------------------------------------------------------------------
insert into subject (subject_id, entity_id, is_child)
select
    'perf-' || lpad(n::text, 9, '0'),
    'DENAVE_IN',
    false
from generate_series(1, :subject_count) n
on conflict do nothing;

-- Hashes rather than numbers, because that is what the platform stores and what the index is
-- built on. Any 64 hex characters will do: the scrub path looks up by hash and never inspects
-- it, so a fabricated one exercises exactly the same index as a real one.
insert into subject_identifier (entity_id, identifier_type, identifier_hash, subject_id)
select
    'DENAVE_IN',
    'PHONE',
    md5('perf-phone-' || n) || md5('perf-salt-' || n),
    'perf-' || lpad(n::text, 9, '0')
from generate_series(1, :subject_count) n
on conflict do nothing;

-- -------------------------------------------------------------------------------------
-- Consent artefacts — the projection the decision path actually reads.
-- -------------------------------------------------------------------------------------
--
-- sequence_number and last_event_hash are NOT NULL and are supplied here as what they are:
-- fabricated. They exist on the projection so a reader can tell which ledger event produced the
-- row, and this seed writes no ledger events at all — so they point at nothing, on purpose, and a
-- run of this file against a database anyone will later rely on is the thing the header forbids.
--
-- The version of this insert that shipped omitted both columns and could not have executed. It was
-- written, reviewed, cited by CAPACITY.md §4 and OPERATIONS.md §6 as the thing that would settle
-- the SLO, and never run once.
insert into consent_artefact (
    entity_id, subject_id, purpose_code, purpose_version, status, legal_basis,
    jurisdiction, channel, application_id, notice_id, notice_version, language_tag,
    capture_method, granted_at, expires_at, last_event_at, sequence_number, last_event_hash)
select
    'DENAVE_IN',
    'perf-' || lpad(n::text, 9, '0'),
    'MKT_OUTBOUND_CALL',
    1,
    'GRANTED',
    'CONSENT',
    -- Populated rather than left null because the decision path reads them. An artefact with no
    -- jurisdiction takes a different branch through the policy engine than a real one, and a load
    -- test measuring that branch is measuring a row shape production does not have.
    'IN',
    'VOICE_CALL',
    'ATHENA_DIALER',
    'NOTICE_DENAVE_B2B',
    1,
    'en',
    'CHECKBOX_OPT_IN',
    now() - (n % 400 || ' days')::interval,
    -- Spread across the expiry boundary on purpose. A population that is uniformly live
    -- measures one branch of the decision; TRAI's expiry semantics are a large part of what the
    -- engine does, and a load test that never reaches them is testing half the code.
    now() + (365 - (n % 400) || ' days')::interval,
    now() - (n % 400 || ' days')::interval,
    1,
    md5('perf-event-' || n) || md5('perf-chain-' || n)
from generate_series(1, :subject_count) n
on conflict do nothing;

-- -------------------------------------------------------------------------------------
-- Suppressions, at a realistic rate.
--
-- 15%, which matters more than it looks. An allowance writes nothing; a denial writes an
-- enforcement_decision row. So the suppressed fraction is the fraction of the load that is
-- secretly a write workload, and a seed with no suppressions would produce a beautiful p95 for
-- a profile that does not exist in production.
-- -------------------------------------------------------------------------------------
-- Two things the first version of this insert got wrong, both of which made it silently useless
-- rather than loudly broken — which is why they survived:
--
--   scope 'PURPOSE' is not a scope. SuppressionScope is GLOBAL | ENTITY | CLIENT | CAMPAIGN, and
--   SuppressionStore's SCOPE_PREDICATE matches on exactly those four. Every row seeded at
--   'PURPOSE' would have been invisible to every lookup, so decision-deny.js would have run
--   against subjects that were never suppressed and reported the allow path under another name.
--
--   identifier_type and identifier_hash are NOT NULL and were absent. The subject_id lookup is
--   what the decision path uses, but the identifier lookup is what a scrub by phone number uses,
--   and seeding a suppression that only one of the two can find would make the two paths disagree
--   about the same person. Same hash as subject_identifier above, so they agree.
-- Cleared first, because `on conflict do nothing` does nothing here: suppression_entry's only
-- unique constraint is its surrogate id, so there is no conflict for it to catch and a second run
-- of this file simply inserts another hundred and fifty thousand rows. The first re-run doubled
-- the suppressed population without a word, which is the kind of thing that turns a measurement
-- into a number nobody can reproduce.
delete from suppression_entry where reason = 'perf seed';

insert into suppression_entry (
    entity_id, subject_id, channel, scope, source,
    identifier_type, identifier_hash, reason, effective_from)
select
    'DENAVE_IN',
    'perf-' || lpad(n::text, 9, '0'),
    'VOICE_CALL',
    'ENTITY',
    'INBOUND_OPT_OUT',
    'PHONE',
    md5('perf-phone-' || n) || md5('perf-salt-' || n),
    'perf seed',
    now() - interval '30 days'
from generate_series(1, :subject_count) n
where n % 100 < (:suppression_rate * 100);

commit;

analyze subject;
analyze subject_identifier;
analyze consent_artefact;
analyze suppression_entry;

-- ANALYZE is not housekeeping here. Without fresh statistics the planner will sequential-scan a
-- million-row table it should be index-scanning, and the load test will report a p95 in the
-- hundreds of milliseconds against a platform that is working perfectly — sending everyone to
-- optimise the wrong thing.

-- =====================================================================================
-- The subject lists k6 reads.
--
-- These live here rather than in a script of their own for one reason: perf/k6/decision.js
-- opened './subjects.json' from the day it was written, and nothing produced that file — so
-- the profile that OPERATIONS.md §6 pointed at as the thing that would settle the SLO would
-- have failed before its first request. Emitting the lists from the seed that creates the rows
-- is what stops the two drifting apart again.
--
-- SAMPLED, not dumped. k6's SharedArray holds the parsed array in memory once per process; a
-- million ids is tens of megabytes to hold and to parse before the first request goes out. The
-- sample exists only to defeat buffer-cache locality — a run that hammers a hundred subjects
-- measures a hot index and reports it as the SLO — and fifty thousand spread at random across
-- the whole id range does that completely.
--
-- Paths are client-side and relative to psql's working directory, which is why the invocation
-- in perf/README.md runs from the repository root. Run from elsewhere and these two lines are
-- the ones that fail.
-- =====================================================================================

\copy (select json_agg(subject_id) from (select subject_id from subject where entity_id = 'DENAVE_IN' and subject_id like 'perf-%' order by random() limit 50000) sampled) to 'perf/k6/subjects.json'

-- The suppressed fifteen percent, separately.
--
-- decision-deny.js needs subjects that are certain to be denied, because a denial is the branch
-- that WRITES — one enforcement_decision row per refusal — and mixing the two paths in one
-- scenario averages a read workload with a write workload and reports a number that describes
-- neither. Drawn from suppression_entry rather than recomputed from n % 100, so it stays correct
-- if the seed's suppression rule changes.
--
-- order by random() again, and not the cheaper lexicographic order: the suppressed rows are the
-- low fifteen of every hundred, so taking the first fifty thousand in id order would hand k6 a
-- contiguous block at the start of the range — the most cache-friendly slice of the table there
-- is, which is the opposite of what this sample is for.

-- The distinct is nested rather than combined with the ordering: PostgreSQL refuses
-- `select distinct … order by random()` because the sort expression is not in the select list,
-- and random() could not be added to it without changing what distinct means.

\copy (select json_agg(subject_id) from (select subject_id from (select distinct subject_id from suppression_entry where entity_id = 'DENAVE_IN' and subject_id like 'perf-%') d order by random() limit 50000) suppressed) to 'perf/k6/subjects-suppressed.json'
