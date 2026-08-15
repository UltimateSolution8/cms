-- =====================================================================================
-- Provenance ingestion: idempotency and a source vocabulary.
--
-- The prospect-database backfill is a bulk import that will be run more than once. It will
-- be run again because a file was truncated, because a mapping was wrong, because someone
-- re-ran the job to be sure. Without a natural key, every re-run inflates the quarantine
-- count — and the quarantine count is the number the group is budgeting a re-permissioning
-- campaign against. A wrong number there is a wrong commercial decision.
--
-- The natural key is deliberately (entity, subject, source type, source name, acquired at)
-- rather than a surrogate the importer supplies. An importer that chooses its own key can
-- collide with itself; these five fields are what actually make two rows the same fact.
-- =====================================================================================

create unique index if not exists uq_provenance_natural_key
    on provenance_record (entity_id, subject_id, source_type, source_name, acquired_at);

-- Reading the triage queue means filtering by source while ordering by age. The existing
-- idx_provenance_quarantined covers the filter but leaves the sort to a heap read once the
-- backlog is measured in millions, which is the size it is expected to be.
create index if not exists idx_provenance_triage
    on provenance_record (entity_id, source_type, acquired_at)
    where quarantined = true;

-- Source types are a controlled vocabulary in the application (ProvenanceSourceType). The
-- constraint is stated here as well because the database is the layer that survives a code
-- path nobody remembered — a direct INSERT during a migration, a fix-up script at 2am.
--
-- Adding a value means a migration, which is intentional: a new provenance source is a
-- compliance conversation, not a string literal.
alter table provenance_record
    drop constraint if exists chk_provenance_source_type;

alter table provenance_record
    add constraint chk_provenance_source_type check (source_type in (
        'DIRECT_COLLECTION', 'CLIENT_SUPPLIED', 'REFERRAL', 'EVENT_OR_TRADESHOW',
        'PURCHASED_LIST', 'APPENDED', 'PUBLIC_SOURCE', 'WEB_SCRAPED', 'LEGACY_UNKNOWN'));

comment on constraint chk_provenance_source_type on provenance_record is
    'Mirrors com.uds.consent.core.model.ProvenanceSourceType. Extend both together.';
