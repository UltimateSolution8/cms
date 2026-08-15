package com.uds.consent.ledger.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Where a contact record came from, and whether that can be substantiated.
 *
 * <p>This is the capability no commercial consent platform offers, and the one that carries the
 * most commercial weight for this group. Denave's prospect database was not all collected
 * directly; parts of it were purchased, appended or supplied by clients. For each such record the
 * group must be able to say what the original lawful basis was and produce the evidence.
 *
 * <p>Records default to quarantined and must be affirmatively substantiated to leave that state.
 * That default is the whole point: unsubstantiable records are quarantined, never grandfathered,
 * and making the safe state the automatic one means nobody has to remember to choose it.
 */
@Repository
public class ProvenanceStore {

    private final JdbcClient jdbc;

    public ProvenanceStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * Whether this subject has at least one substantiated, non-quarantined provenance record.
     *
     * <p>A subject with no provenance record at all is not quarantined — that is the ordinary
     * case for someone who gave consent directly on a UDS surface, where the consent event is
     * itself the provenance. Quarantine applies to records that came from somewhere else.
     */
    public boolean isContactable(String entityId, String subjectId) {
        Integer quarantined = jdbc.sql("""
                        select count(*) from provenance_record
                         where entity_id = :entityId and subject_id = :subjectId
                           and quarantined = true
                        """)
                .param("entityId", entityId)
                .param("subjectId", subjectId)
                .query(Integer.class)
                .single();
        return quarantined == 0;
    }

    public long record(String entityId, String subjectId, String sourceType, String sourceName,
                       Instant acquiredAt, String originalLegalBasis, String evidenceRef,
                       String contractRef, boolean substantiated, String substantiationNote,
                       String reviewedBy) {
        return jdbc.sql("""
                        insert into provenance_record (
                            entity_id, subject_id, source_type, source_name, acquired_at,
                            original_legal_basis, original_consent_evidence_ref, contract_ref,
                            substantiated, substantiation_note, quarantined, reviewed_by, reviewed_at)
                        values (
                            :entityId, :subjectId, :sourceType, :sourceName, :acquiredAt,
                            :originalLegalBasis, :evidenceRef, :contractRef, :substantiated,
                            :note, :quarantined, :reviewedBy, :reviewedAt)
                        returning id
                        """)
                .param("entityId", entityId)
                .param("subjectId", subjectId)
                .param("sourceType", sourceType)
                .param("sourceName", sourceName)
                .param("acquiredAt", Timestamp.from(acquiredAt))
                .param("originalLegalBasis", originalLegalBasis)
                .param("evidenceRef", evidenceRef)
                .param("contractRef", contractRef)
                .param("substantiated", substantiated)
                .param("note", substantiationNote)
                // Substantiation and quarantine are two sides of one decision, so they are set
                // together rather than left to drift apart.
                .param("quarantined", !substantiated)
                .param("reviewedBy", reviewedBy)
                .param("reviewedAt", substantiated ? Timestamp.from(Instant.now()) : null)
                .query(Long.class)
                .single();
    }

    /**
     * Records provenance, or returns the id of the row that already says the same thing.
     *
     * <p>The import that populates this table is a bulk job over a file, and bulk jobs get re-run:
     * a truncated file, a corrected mapping, an operator who is not sure the first run finished.
     * Without idempotency each re-run inflates the quarantine count, and that count is what the
     * group budgets a re-permissioning campaign against.
     *
     * <p>Returns {@code inserted = false} when the row was already present. The caller needs to
     * know the difference in order to report it, but neither outcome is an error.
     */
    public Ingestion recordIdempotent(String entityId, String subjectId, String sourceType,
                                      String sourceName, Instant acquiredAt,
                                      String originalLegalBasis, String evidenceRef,
                                      String contractRef) {
        Optional<Long> existing = jdbc.sql("""
                        select id from provenance_record
                         where entity_id = :entityId and subject_id = :subjectId
                           and source_type = :sourceType and source_name = :sourceName
                           and acquired_at = :acquiredAt
                        """)
                .param("entityId", entityId)
                .param("subjectId", subjectId)
                .param("sourceType", sourceType)
                .param("sourceName", sourceName)
                .param("acquiredAt", Timestamp.from(acquiredAt))
                .query(Long.class)
                .optional();
        if (existing.isPresent()) {
            return new Ingestion(existing.get(), false);
        }

        // Note what is not a parameter: substantiated. Ingestion cannot self-certify. A bulk
        // importer able to declare its own rows substantiated would defeat the entire control,
        // and the one place that could is the one place under the least human scrutiny.
        long id = record(entityId, subjectId, sourceType, sourceName, acquiredAt,
                originalLegalBasis, evidenceRef, contractRef, false, null, null);
        return new Ingestion(id, true);
    }

    /** Releases a record from quarantine once evidence has been produced and reviewed. */
    public void substantiate(long id, String note, String reviewedBy) {
        jdbc.sql("""
                        update provenance_record
                           set substantiated = true, quarantined = false,
                               substantiation_note = :note, reviewed_by = :reviewedBy,
                               reviewed_at = now()
                         where id = :id
                        """)
                .param("id", id)
                .param("note", note)
                .param("reviewedBy", reviewedBy)
                .update();
    }

    /** The Phase 0 triage queue: everything that cannot currently be contacted. */
    public List<Record> findQuarantined(String entityId, int limit, int offset) {
        return jdbc.sql("""
                        select id, entity_id, subject_id, source_type, source_name, acquired_at,
                               original_legal_basis, contract_ref, substantiated, quarantined
                          from provenance_record
                         where entity_id = :entityId and quarantined = true
                         order by acquired_at asc
                         limit :limit offset :offset
                        """)
                .param("entityId", entityId)
                .param("limit", limit)
                .param("offset", offset)
                .query(ProvenanceStore::map)
                .list();
    }

    /** One record by id. Needed before substantiating, so the audit entry can name the entity. */
    public Optional<Record> find(long id) {
        return jdbc.sql("""
                        select id, entity_id, subject_id, source_type, source_name, acquired_at,
                               original_legal_basis, contract_ref, substantiated, quarantined
                          from provenance_record
                         where id = :id
                        """)
                .param("id", id)
                .query(ProvenanceStore::map)
                .optional();
    }

    public Optional<Record> findLatestForSubject(String entityId, String subjectId) {
        return jdbc.sql("""
                        select id, entity_id, subject_id, source_type, source_name, acquired_at,
                               original_legal_basis, contract_ref, substantiated, quarantined
                          from provenance_record
                         where entity_id = :entityId and subject_id = :subjectId
                         order by acquired_at desc
                         limit 1
                        """)
                .param("entityId", entityId)
                .param("subjectId", subjectId)
                .query(ProvenanceStore::map)
                .optional();
    }

    /**
     * Quarantine counts by source, for the leadership report.
     *
     * <p>This query is the one that turns a vague worry into a number that can be budgeted for,
     * which is why the plan puts prospect-database triage in Phase 0 rather than leaving it to
     * surface mid-pilot.
     */
    public List<SourceSummary> summariseBySource(String entityId) {
        return jdbc.sql("""
                        select source_type, source_name,
                               count(*) filter (where quarantined)     as quarantined,
                               count(*) filter (where not quarantined) as contactable,
                               count(*)                                as total
                          from provenance_record
                         where entity_id = :entityId
                         group by source_type, source_name
                         order by quarantined desc
                        """)
                .param("entityId", entityId)
                .query((rs, n) -> new SourceSummary(rs.getString("source_type"),
                        rs.getString("source_name"), rs.getLong("quarantined"),
                        rs.getLong("contactable"), rs.getLong("total")))
                .list();
    }

    private static Record map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Record(rs.getLong("id"), rs.getString("entity_id"), rs.getString("subject_id"),
                rs.getString("source_type"), rs.getString("source_name"),
                rs.getTimestamp("acquired_at").toInstant(), rs.getString("original_legal_basis"),
                rs.getString("contract_ref"), rs.getBoolean("substantiated"),
                rs.getBoolean("quarantined"));
    }

    public record Record(long id, String entityId, String subjectId, String sourceType,
                         String sourceName, Instant acquiredAt, String originalLegalBasis,
                         String contractRef, boolean substantiated, boolean quarantined) {
    }

    public record SourceSummary(String sourceType, String sourceName, long quarantined,
                                long contactable, long total) {
    }

    /**
     * @param id       the provenance record, whether it was just written or already existed
     * @param inserted false when an identical record was already on file
     */
    public record Ingestion(long id, boolean inserted) {
    }
}
