-- =====================================================================================
-- Guardian verification, and the end of subject.is_child as the only record of minority.
--
-- -------------------------------------------------------------------------------------
-- What was wrong.
--
-- A child capture was accepted on the strength of two values the capture surface chose for
-- itself: capture_method = PARENTAL_VERIFIED, and actor_type = GUARDIAN. Nothing recorded how
-- the guardian had been verified, or whether anybody had verified one at all. The group could
-- produce a consent and could not produce the diligence — and under DPDP s.9 read with Rule 10
-- the diligence is the obligation. The consent is only its output.
--
-- Rule 10 asks the Data Fiduciary to observe due diligence that the individual identifying as
-- a parent is an adult who is identifiable, by reference either to identity and age details
-- the fiduciary reliably holds, or to a virtual token issued by a Digital Locker service
-- provider. Those are two different evidentiary positions and the ledger could not tell them
-- apart, because it recorded neither.
--
-- The verification itself now travels on the consent event's attributes, which means it is
-- inside canonical_payload and therefore inside the hash chain — no schema change to
-- consent_event, and no way to alter the record of the diligence without breaking the chain
-- from that point forward. That is the strongest place in this system to put a fact, and it
-- costs nothing here.
--
-- -------------------------------------------------------------------------------------
-- Why this table exists as well.
--
-- subject.is_child is a mutable boolean with a setter and no history. It answers "is this
-- subject a minor now". The question anyone actually asks is "was this subject a minor on the
-- day we tracked them", and the column cannot answer it: a subject who turned eighteen last
-- year has is_child = false, and every behavioural decision taken about them while they were
-- fifteen now looks lawful.
--
-- So minority becomes an append-only assertion with a date and a source, and the column
-- becomes what it always should have been — a read model the decision path can consult
-- cheaply. Both are kept. Making the decision engine reconstruct an age from a history on
-- every call would be the wrong trade for the hot path; losing the history was the wrong trade
-- for the evidence plane.
--
-- Not folded into consent_event, deliberately. That table is keyed by purpose and chained per
-- subject-and-purpose; minority is a fact about the person, not about any purpose, and giving
-- it a synthetic purpose_code to fit would put a lie in the column that the whole decision
-- path reads.
-- =====================================================================================

create table subject_age_assertion (
    id           bigserial   primary key,
    entity_id    varchar(64) not null references fiduciary_entity (entity_id),
    subject_id   varchar(64) not null references subject (subject_id),
    -- What was asserted. Deliberately not an age or a date of birth: the platform has no need
    -- of either, and holding a child's date of birth to prove it protects children would be a
    -- fair summary of how privacy systems go wrong.
    is_child     boolean     not null,
    -- Where the assertion came from — the capture surface, an administrative correction, a
    -- guardian's own statement. Free text because the set is open and a constrained vocabulary
    -- would push the real answer into a comment.
    source       text        not null,
    asserted_at  timestamptz not null,
    recorded_at  timestamptz not null default now(),
    actor_type   varchar(32),
    actor_id     text,
    note         text
);

create index idx_subject_age_assertion_subject
    on subject_age_assertion (entity_id, subject_id, asserted_at desc);

comment on table subject_age_assertion is
    'Append-only history of what was asserted about a subject''s minority, and when. subject.is_child '
    'is the current-state read model folded from this; this is the record that can answer whether a '
    'subject was a minor on the day a decision was taken about them.';

comment on column subject_age_assertion.source is
    'Who says so. An assertion with no source is the failure this table replaced.';

-- Append-only, on the same footing as the rest of the evidence plane. V2 established both
-- halves of this pattern and both are needed: the trigger stops the owner and any future
-- superuser session, the revoked grant stops the application even if a trigger is dropped.
create trigger trg_subject_age_assertion_no_update
    before update on subject_age_assertion
    for each row execute function evidence_row_is_immutable();

create trigger trg_subject_age_assertion_no_delete
    before delete on subject_age_assertion
    for each row execute function evidence_row_is_immutable();

do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'uds_consent_app') then
        raise notice
            'role uds_consent_app not present; grants and isolation policy for '
            'subject_age_assertion NOT applied. Create the role and re-run before production.';
        return;
    end if;

    execute 'grant select, insert on subject_age_assertion to uds_consent_app';
    execute 'grant usage, select on sequence subject_age_assertion_id_seq to uds_consent_app';
    execute 'revoke update, delete, truncate on subject_age_assertion from uds_consent_app';

    -- Entity-scoped, and covered by the isolation policy in the same shape as V13 and V16 use.
    -- A table naming which of an entity''s subjects are children is about as sensitive as this
    -- platform gets; RowLevelSecurityIT enumerates every table both migrations name, so a new
    -- one that is entity-scoped and unpolicied fails the build rather than waiting to be found.
    execute 'alter table subject_age_assertion enable row level security';
    execute $f$
        create policy uds_entity_isolation on subject_age_assertion
            using (uds_entity_claim() is null or entity_id = uds_entity_claim())
            with check (uds_entity_claim() is null or entity_id = uds_entity_claim())
    $f$;
end
$$;
