package com.uds.consent.ledger.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Receipts as issued.
 *
 * <p>The payload is stored and returned verbatim rather than regenerated on read. Regeneration
 * would answer today's version of a question the subject asked last year: the purpose registry
 * moves, the entity's DPO contact changes, and a consent that was live in March has since expired.
 * All of those would silently rewrite a document somebody is holding a printed copy of, which is
 * the opposite of what a receipt is for.
 */
@Repository
public class ReceiptStore {

    private final JdbcClient jdbc;

    public ReceiptStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public void save(StoredReceipt receipt) {
        jdbc.sql("""
                        insert into consent_receipt (receipt_id, entity_id, subject_id, issued_at,
                                                     payload, payload_hash, evidence_hash,
                                                     notice_id, notice_version, language_tag,
                                                     purpose_count)
                        values (:receiptId, :entityId, :subjectId, :issuedAt, :payload, :hash,
                                :evidenceHash, :noticeId, :noticeVersion, :languageTag, :purposes)
                        """)
                .param("receiptId", receipt.receiptId())
                .param("entityId", receipt.entityId())
                .param("subjectId", receipt.subjectId())
                .param("issuedAt", java.sql.Timestamp.from(receipt.issuedAt()))
                .param("payload", receipt.payload())
                .param("hash", receipt.payloadHash())
                .param("evidenceHash", receipt.evidenceHash())
                .param("noticeId", receipt.noticeId())
                .param("noticeVersion", receipt.noticeVersion())
                .param("languageTag", receipt.languageTag())
                .param("purposes", receipt.purposeCount())
                .update();
    }

    public Optional<StoredReceipt> find(String receiptId) {
        return jdbc.sql(SELECT + " where receipt_id = :receiptId")
                .param("receiptId", receiptId)
                .query(ReceiptStore::map)
                .optional();
    }

    /** Every receipt issued to a subject, newest first. What a preference centre lists. */
    public List<StoredReceipt> findForSubject(String entityId, String subjectId, int limit) {
        return findForSubject(entityId, subjectId, limit, 0);
    }

    /**
     * A page of a subject's receipts, newest first.
     *
     * <p>The offset exists because the evidence bundle now tells a reader where the receipts it
     * could not fit are, and a route that returns only the newest page would make that pointer
     * false. Ordered by {@code issued_at desc} with the primary key as a tiebreak, so two receipts
     * issued in the same millisecond cannot swap places between pages and cause one to be missed.
     */
    public List<StoredReceipt> findForSubject(String entityId, String subjectId, int limit,
                                              int offset) {
        return jdbc.sql(SELECT + " where entity_id = :entityId and subject_id = :subjectId "
                        + "order by issued_at desc, receipt_id desc limit :limit offset :offset")
                .param("entityId", entityId)
                .param("subjectId", subjectId)
                .param("limit", limit)
                .param("offset", offset)
                .query(ReceiptStore::map)
                .list();
    }

    private static final String SELECT = """
            select receipt_id, entity_id, subject_id, issued_at, payload, payload_hash,
                   evidence_hash, notice_id, notice_version, language_tag, purpose_count
              from consent_receipt
            """;

    private static StoredReceipt map(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new StoredReceipt(rs.getString("receipt_id"), rs.getString("entity_id"),
                rs.getString("subject_id"), rs.getTimestamp("issued_at").toInstant(),
                rs.getString("payload"), rs.getString("payload_hash"),
                rs.getString("evidence_hash"), rs.getString("notice_id"),
                (Integer) rs.getObject("notice_version"), rs.getString("language_tag"),
                rs.getInt("purpose_count"));
    }

    /**
     * @param payload     the canonical JSON as issued, byte for byte
     * @param payloadHash SHA-256 of that payload, so a subject or an auditor can check a copy
     *                    they were sent against what the platform holds — using the same
     *                    verification path the ledger's hash chain uses
     */
    public record StoredReceipt(String receiptId, String entityId, String subjectId,
                                Instant issuedAt, String payload, String payloadHash,
                                String evidenceHash, String noticeId, Integer noticeVersion,
                                String languageTag, int purposeCount) {
    }
}
