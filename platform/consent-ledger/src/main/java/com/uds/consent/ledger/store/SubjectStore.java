package com.uds.consent.ledger.store;

import com.uds.consent.core.model.IdentifierType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves real-world identifiers to the privacy-minimal subject references the ledger uses.
 *
 * <p>Only hashes cross this boundary. Callers hash with the peppered
 * {@code IdentifierHasher} before calling in, so a plaintext phone number never reaches the
 * evidence plane at all — not in a query parameter, not in a bind log, not in a slow-query trace.
 */
@Repository
public class SubjectStore {

    private final JdbcClient jdbc;

    public SubjectStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** Existing subject for a hashed identifier, if one is known. */
    public Optional<String> resolve(String entityId, IdentifierType type, String identifierHash) {
        return jdbc.sql("""
                        select subject_id from subject_identifier
                         where entity_id = :entityId and identifier_type = :type
                           and identifier_hash = :hash
                        """)
                .param("entityId", entityId)
                .param("type", type.name())
                .param("hash", identifierHash)
                .query(String.class)
                .optional();
    }

    /**
     * Resolves an identifier, creating the subject if it is new.
     *
     * <p>Not used on the scrub path. Checking whether a number is suppressed must not create a
     * subject as a side effect, or the act of complying would grow the database it is meant to
     * constrain.
     */
    public String resolveOrCreate(String entityId, IdentifierType type, String identifierHash) {
        Optional<String> existing = resolve(entityId, type, identifierHash);
        if (existing.isPresent()) {
            return existing.get();
        }

        String subjectId = UUID.randomUUID().toString();
        jdbc.sql("insert into subject (subject_id, entity_id) values (:subjectId, :entityId)")
                .param("subjectId", subjectId)
                .param("entityId", entityId)
                .update();

        jdbc.sql("""
                        insert into subject_identifier (entity_id, identifier_type, identifier_hash, subject_id)
                        values (:entityId, :type, :hash, :subjectId)
                        on conflict (entity_id, identifier_type, identifier_hash) do nothing
                        """)
                .param("entityId", entityId)
                .param("type", type.name())
                .param("hash", identifierHash)
                .param("subjectId", subjectId)
                .update();

        // Another request may have created the same subject concurrently and won the insert.
        // Re-reading rather than trusting the id we generated keeps one identifier to one subject.
        return resolve(entityId, type, identifierHash).orElse(subjectId);
    }

    /** Adds another identifier for a subject already known by a different one. */
    public void linkIdentifier(String entityId, String subjectId, IdentifierType type,
                               String identifierHash) {
        jdbc.sql("""
                        insert into subject_identifier (entity_id, identifier_type, identifier_hash, subject_id)
                        values (:entityId, :type, :hash, :subjectId)
                        on conflict (entity_id, identifier_type, identifier_hash) do nothing
                        """)
                .param("entityId", entityId)
                .param("type", type.name())
                .param("hash", identifierHash)
                .param("subjectId", subjectId)
                .update();
    }

    /**
     * Whether the subject is under eighteen.
     *
     * <p>Drives DPDP s.9: verifiable parental consent, and an absolute bar on behavioural
     * tracking or targeted advertising. Defaults to false for an unknown subject, which is why
     * capture surfaces that could plausibly reach children must declare the age band rather than
     * relying on this alone.
     */
    public boolean isChild(String subjectId) {
        return jdbc.sql("select coalesce(max(case when is_child then 1 else 0 end), 0) "
                        + "from subject where subject_id = :subjectId")
                .param("subjectId", subjectId)
                .query(Integer.class)
                .single() == 1;
    }

    public void markChild(String subjectId, boolean isChild) {
        jdbc.sql("update subject set is_child = :isChild where subject_id = :subjectId")
                .param("isChild", isChild)
                .param("subjectId", subjectId)
                .update();
    }

    /** Every identifier hash on record for a subject, for suppression fan-out on withdrawal. */
    public List<IdentifierRef> identifiersFor(String entityId, String subjectId) {
        return jdbc.sql("""
                        select identifier_type, identifier_hash from subject_identifier
                         where entity_id = :entityId and subject_id = :subjectId
                        """)
                .param("entityId", entityId)
                .param("subjectId", subjectId)
                .query((rs, n) -> new IdentifierRef(
                        IdentifierType.valueOf(rs.getString("identifier_type")),
                        rs.getString("identifier_hash")))
                .list();
    }

    public record IdentifierRef(IdentifierType type, String hash) {
    }
}
