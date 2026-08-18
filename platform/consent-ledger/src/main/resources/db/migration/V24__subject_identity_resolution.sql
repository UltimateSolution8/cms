-- =====================================================================================
-- One person, one subject — without rewriting a word of history.
--
-- The platform resolves one identifier to one subject and says so in a comment on
-- SubjectStore.resolveOrCreate. A person the group knows by a phone number and by an email
-- address is therefore TWO subjects, with two consent records, two hash chains and two
-- evidence bundles.
--
-- That was a defensible pilot position and it has a cost that is not hypothetical: a principal
-- who withdraws by email leaves their phone record contactable, because nothing links the two.
-- The evidence bundle returns one identifier's worth of a person, so a Board complaint answered
-- from it is answered incompletely and in good faith. linkIdentifier() has existed and been
-- wired to nothing since V1.
--
-- -------------------------------------------------------------------------------------
-- Why an alias table and not an update.
--
-- consent_event is append-only, enforced by triggers and by revoked grants. Its subject_id can
-- never be rewritten — which is correct, and which means "merge two subjects" cannot mean what
-- it means in an ordinary CRM. The events that happened under the old id happened under the
-- old id, and the chain that hashes them would stop verifying if a single byte moved.
--
-- So the merge is recorded rather than applied. subject_identifier rows are re-pointed, so
-- every future resolution lands on the canonical subject and the decision path is correct from
-- the next call onwards. The old id becomes an alias, and reads that assemble history — the
-- evidence bundle, the consent record, the receipt — union across it.
--
-- The merge is itself appended to the canonical subject's chain as a SUBJECT_MERGED event, so
-- the reason two chains became one is inside the evidence rather than beside it.
--
-- -------------------------------------------------------------------------------------
-- What this deliberately does NOT do.
--
-- It does not infer. Nothing here matches on names, or on a normalised phone number across
-- entities, or on any similarity measure. Fuzzy matching would merge two people eventually and
-- the first evidence of it would be a phone call to somebody who withdrew — which is the worst
-- thing this platform can do, and strictly worse than the incompleteness it would be fixing.
--
-- A merge happens because a caller asserted, at capture, that two identifiers belong to the
-- same person; or because a named administrator said so on the record. Both are assertions by
-- somebody who knows, and both are attributable.
--
-- Whether Denave's sources can make that assertion at all — what makes two records the same
-- contact across DenCRM, the HRMS and a purchased list — is still their answer and not ours.
-- This ships the mechanism and a safe default. REGULATORY_HANDOFF.md §8.1 holds the question.
-- =====================================================================================

create table subject_alias (
    entity_id             varchar(64) not null references fiduciary_entity (entity_id),
    -- The id that stops being used. Primary key on its own rather than with the entity: a
    -- subject id is a UUID and belongs to exactly one entity, and a composite key would allow
    -- the same superseded id to point at two different canonical subjects in two entities,
    -- which is a state no correct read could untangle.
    superseded_subject_id varchar(64) primary key references subject (subject_id),
    canonical_subject_id  varchar(64) not null references subject (subject_id),
    merged_at             timestamptz not null default now(),
    merged_by             text        not null,
    reason                text        not null,
    -- Belt and braces against the one-step cycle. Longer cycles are prevented by refusing to
    -- merge a subject that is already superseded, which is enforced in SubjectStore.
    constraint ck_subject_alias_not_self check (superseded_subject_id <> canonical_subject_id)
);

create index idx_subject_alias_canonical on subject_alias (canonical_subject_id);

comment on table subject_alias is
    'Subjects that turned out to be the same person. The superseded id keeps its events — the '
    'ledger is append-only and nothing rewrites it — and reads that assemble history union '
    'across this table. Future identifier resolution lands on the canonical id because the '
    'merge re-points subject_identifier.';

comment on column subject_alias.merged_by is
    'The person who asserted the merge, not the credential. A merge joins two people''s records '
    'if it is wrong, so it is the single administrative action most worth attributing.';

comment on column subject_alias.reason is
    'Why these are the same person. Required, and free text on purpose: the answer is "the CRM '
    'id matched" or "the principal told us on a grievance call", and constraining it to a code '
    'list would lose the only part a reviewer needs.';

-- Append-only, like the ledger it explains. A merge that could be quietly deleted would leave
-- two chains that used to be one and no record of why they separated — and the evidence bundle
-- would silently start returning half a person again.
do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'uds_consent_app') then
        raise notice 'role uds_consent_app not present; subject_alias guards NOT applied.';
        return;
    end if;

    execute 'revoke update, delete on subject_alias from uds_consent_app';

    alter table subject_alias enable row level security;
    create policy uds_entity_isolation on subject_alias
        using (uds_entity_claim() is null or entity_id = uds_entity_claim())
        with check (uds_entity_claim() is null or entity_id = uds_entity_claim());
end
$$;
