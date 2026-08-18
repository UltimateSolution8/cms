package com.uds.consent.ledger.store;

import com.uds.consent.core.model.IdentifierType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
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

    /**
     * Existing subject for a hashed identifier, if one is known.
     *
     * <p>Returns the <em>canonical</em> subject: if this identifier's subject has since been
     * merged into another, the answer is the surviving one. Resolving here rather than at each
     * caller is what makes the merge take effect everywhere at once — capture, decision,
     * suppression and rights intake all funnel through this method, so none of them had to learn
     * about aliases and none of them can forget to.
     */
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
                .optional()
                .map(this::canonical);
    }

    /**
     * The surviving subject id, following any merge.
     *
     * <p>A single hop in the ordinary case, because a merge refuses a subject that is already
     * superseded — so aliases point at live subjects rather than forming chains. The loop is
     * still bounded, because "the invariant holds" and "the data cannot violate the invariant"
     * are different claims, and an unbounded walk over a cycle introduced by a manual fix would
     * hang the decision path rather than fail it.
     *
     * <p>Unknown ids come back unchanged. A caller asking about a subject that does not exist
     * should get the empty answer their own query gives, not an exception from a lookup they did
     * not know was happening.
     */
    public String canonical(String subjectId) {
        String current = subjectId;
        for (int hop = 0; hop < 10; hop++) {
            Optional<String> next = jdbc.sql(
                            "select canonical_subject_id from subject_alias "
                                    + "where superseded_subject_id = :id")
                    .param("id", current)
                    .query(String.class)
                    .optional();
            if (next.isEmpty()) {
                return current;
            }
            current = next.get();
        }
        return current;
    }

    /**
     * Every id whose history belongs to this subject: the canonical one and everything merged
     * into it.
     *
     * <p>What the evidence bundle, the consent record and the receipt read. The ledger is
     * append-only, so the events that happened under a superseded id stay there — assembling a
     * person's history therefore means a union, and a read that forgot it would go back to
     * returning half a person, which is the defect the merge exists to fix.
     *
     * <p>Canonical id first, so a caller taking {@code get(0)} gets the live subject.
     */
    public List<String> historyIdsFor(String entityId, String subjectId) {
        String canonicalId = canonical(subjectId);
        List<String> ids = new java.util.ArrayList<>();
        ids.add(canonicalId);
        ids.addAll(jdbc.sql("""
                        select superseded_subject_id from subject_alias
                         where entity_id = :entityId and canonical_subject_id = :canonicalId
                         order by merged_at
                        """)
                .param("entityId", entityId)
                .param("canonicalId", canonicalId)
                .query(String.class)
                .list());
        return List.copyOf(ids);
    }

    /**
     * Records that two subjects are the same person, and re-points every identifier.
     *
     * <p>Three things happen and the order matters. The identifiers move first, so that the very
     * next decision for either of them lands on the surviving subject — a merge that recorded the
     * relationship but left the identifiers pointing at the old id would leave a withdrawal
     * unreachable for exactly as long as nobody noticed. The alias row goes in second, so reads
     * that assemble history start unioning. The {@code SUBJECT_MERGED} ledger event is appended by
     * the caller, outside this method, because it belongs to the evidence plane and this class
     * writes to the control plane.
     *
     * <p><strong>What it refuses, and why each refusal is load-bearing.</strong> Merging a subject
     * into itself is a no-op that would write a self-referential alias. Merging a subject that is
     * already superseded would build a chain, and a chain is a cycle waiting for one bad edit.
     * Merging across entities would move one fiduciary's data principal into another's evidence
     * plane, which is the exact thing two layers of isolation exist to prevent.
     *
     * @return how many identifiers moved. Zero is legitimate — the superseded subject may have
     *         been created by a rights request that never carried an identifier — and is worth
     *         returning rather than swallowing, because it is also what a caller sees when they
     *         merged the wrong way round
     */
    public int merge(String entityId, String supersededSubjectId, String canonicalSubjectId,
                     String mergedBy, String reason) {
        if (supersededSubjectId.equals(canonicalSubjectId)) {
            throw new IllegalArgumentException("a subject cannot be merged into itself");
        }
        requireLiveSubject(entityId, supersededSubjectId, "the subject being merged away");
        requireLiveSubject(entityId, canonicalSubjectId, "the surviving subject");

        int moved = jdbc.sql("""
                        update subject_identifier
                           set subject_id = :canonicalId
                         where entity_id = :entityId and subject_id = :supersededId
                        """)
                .param("entityId", entityId)
                .param("supersededId", supersededSubjectId)
                .param("canonicalId", canonicalSubjectId)
                .update();

        jdbc.sql("""
                        insert into subject_alias (entity_id, superseded_subject_id,
                                                   canonical_subject_id, merged_by, reason)
                        values (:entityId, :supersededId, :canonicalId, :mergedBy, :reason)
                        """)
                .param("entityId", entityId)
                .param("supersededId", supersededSubjectId)
                .param("canonicalId", canonicalSubjectId)
                .param("mergedBy", mergedBy)
                .param("reason", reason)
                .update();

        return moved;
    }

    private void requireLiveSubject(String entityId, String subjectId, String role) {
        Integer present = jdbc.sql(
                        "select count(*) from subject where subject_id = :id "
                                + "and entity_id = :entityId")
                .param("id", subjectId)
                .param("entityId", entityId)
                .query(Integer.class)
                .single();
        if (present == null || present == 0) {
            throw new IllegalArgumentException(
                    role + " (" + subjectId + ") does not exist at " + entityId
                            + ". Merging across entities is refused: it would move one "
                            + "fiduciary's data principal into another's evidence plane.");
        }
        if (!subjectId.equals(canonical(subjectId))) {
            throw new IllegalArgumentException(
                    role + " (" + subjectId + ") has already been merged into another subject. "
                            + "Merge into the surviving id instead — chaining aliases is refused "
                            + "because a chain is a cycle waiting for one bad edit.");
        }
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

    /**
     * Sets the current-state flag the decision path reads.
     *
     * <p>Prefer {@link #assertAge} — this writes the read model and leaves no history, which is
     * exactly the gap {@code subject_age_assertion} was added to close. Kept because a projection
     * rebuild needs to set the flag without re-asserting anything, and because a caller that
     * genuinely has nothing to say about provenance should be visible as such rather than
     * inventing a source to satisfy an API.
     */
    public void markChild(String subjectId, boolean isChild) {
        jdbc.sql("update subject set is_child = :isChild where subject_id = :subjectId")
                .param("isChild", isChild)
                .param("subjectId", subjectId)
                .update();
    }

    /**
     * Records what was asserted about a subject's minority, and updates the flag to match.
     *
     * <p>Two writes, one meaning. The assertion row is the evidence and can never be altered; the
     * flag is the read model the decision engine consults on the hot path. Keeping both is
     * deliberate — reconstructing an age from a history on every decision would be the wrong trade
     * for a path that runs on every outbound contact, and having only the flag was the wrong trade
     * for a platform whose job is to be able to say what was known and when.
     *
     * <p>The question this exists to answer is not "is this subject a child" but "was this subject
     * a child on the day we made that decision about them". A boolean cannot answer the second,
     * and the second is the only one that ever comes up after the fact.
     */
    public void assertAge(String entityId, String subjectId, boolean isChild, String source,
                          Instant assertedAt, String actorType, String actorId, String note) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException(
                    "an age assertion needs a source; an unsourced one is the record this "
                            + "replaced");
        }

        // The subject row may not exist, and that is not an error.
        //
        // consent_event carries no foreign key to `subject` on purpose: a capture surface that
        // already holds a subject id posts it straight through, and only the identifier-resolution
        // path in resolveOrCreate() ever inserts here. The consequence nobody had noticed is that
        // for every such subject, `subject.is_child` was a flag on a row that did not exist —
        // markChild updated nothing and isChild answered false, whatever the surface had declared.
        // A child protection that silently does nothing for a whole class of subjects is worse
        // than one that is absent, because it reads as present.
        //
        // So the assertion path materialises the row. Not as a workaround: this table's foreign
        // key is worth keeping, because an assertion about a subject the platform has never heard
        // of describes nobody.
        jdbc.sql("""
                        insert into subject (subject_id, entity_id) values (:subjectId, :entityId)
                        on conflict (subject_id) do nothing
                        """)
                .param("subjectId", subjectId)
                .param("entityId", entityId)
                .update();

        jdbc.sql("""
                        insert into subject_age_assertion (entity_id, subject_id, is_child, source,
                                                           asserted_at, actor_type, actor_id, note)
                        values (:entityId, :subjectId, :isChild, :source, :assertedAt, :actorType,
                                :actorId, :note)
                        """)
                .param("entityId", entityId)
                .param("subjectId", subjectId)
                .param("isChild", isChild)
                .param("source", source)
                .param("assertedAt", Timestamp.from(assertedAt))
                .param("actorType", actorType)
                .param("actorId", actorId)
                .param("note", note)
                .update();
        markChild(subjectId, isChild);
    }

    /**
     * What has been asserted about this subject's minority, most recent first.
     *
     * <p>Read when an auditor asks what the group knew and when it knew it — which, for a
     * behavioural-tracking decision taken three years ago about someone now an adult, is the only
     * form of the question that can be answered honestly.
     */
    public List<AgeAssertion> ageAssertionsFor(String entityId, String subjectId) {
        return jdbc.sql("""
                        select is_child, source, asserted_at, recorded_at, actor_type, actor_id,
                               note
                          from subject_age_assertion
                         where entity_id = :entityId and subject_id = :subjectId
                         order by asserted_at desc, id desc
                        """)
                .param("entityId", entityId)
                .param("subjectId", subjectId)
                .query((rs, n) -> new AgeAssertion(
                        rs.getBoolean("is_child"), rs.getString("source"),
                        rs.getTimestamp("asserted_at").toInstant(),
                        rs.getTimestamp("recorded_at").toInstant(),
                        rs.getString("actor_type"), rs.getString("actor_id"),
                        rs.getString("note")))
                .list();
    }

    /**
     * Whether the subject was asserted to be a child as at a given instant.
     *
     * <p>The historical counterpart of {@link #isChild(String)}. Returns the most recent assertion
     * made on or before {@code at}, or empty where nothing had been asserted by then — and empty is
     * a genuinely different answer from false. "Nobody had told us" and "we were told they were an
     * adult" carry different weight when the question is why a fifteen-year-old was profiled.
     */
    public Optional<Boolean> wasChildAt(String entityId, String subjectId, Instant at) {
        return jdbc.sql("""
                        select is_child from subject_age_assertion
                         where entity_id = :entityId and subject_id = :subjectId
                           and asserted_at <= :at
                         order by asserted_at desc, id desc
                         limit 1
                        """)
                .param("entityId", entityId)
                .param("subjectId", subjectId)
                .param("at", Timestamp.from(at))
                .query(Boolean.class)
                .optional();
    }

    /**
     * @param source     what said so — a capture surface, an administrative correction
     * @param assertedAt when the assertion was made about the subject, which on an offline capture
     *                   is earlier than when it reached the platform
     * @param recordedAt when the platform durably wrote it
     */
    public record AgeAssertion(boolean isChild, String source, Instant assertedAt,
                               Instant recordedAt, String actorType, String actorId, String note) {
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
