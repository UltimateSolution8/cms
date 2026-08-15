package com.uds.consent.ledger.store;

import com.uds.consent.core.crypto.CanonicalJson;
import com.uds.consent.core.crypto.Hashes;
import com.uds.consent.core.model.ActorType;
import com.uds.consent.core.model.CaptureMethod;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.ConsentEvent;
import com.uds.consent.core.model.ConsentEventType;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.LegalBasis;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The append-only evidence store.
 *
 * <p>Writes go through {@link #append} and nowhere else. There is deliberately no update or
 * delete method on this class — and the database rejects both regardless, so a future
 * contributor who adds one will find it fails at runtime rather than quietly working.
 */
@Repository
public class ConsentEventStore {

    private static final String INSERT_SQL = """
            insert into consent_event (
                event_id, entity_id, subject_id, purpose_code, purpose_version, event_type,
                legal_basis, notice_id, notice_version, language_tag, capture_method, channel,
                application_id, jurisdiction, occurred_at, recorded_at, expires_at, actor_type,
                actor_id, reason, evidence_ref, idempotency_key, attributes, sequence_number,
                previous_hash, event_hash, canonical_payload)
            values (
                :eventId, :entityId, :subjectId, :purposeCode, :purposeVersion, :eventType,
                :legalBasis, :noticeId, :noticeVersion, :languageTag, :captureMethod, :channel,
                :applicationId, :jurisdiction, :occurredAt, :recordedAt, :expiresAt, :actorType,
                :actorId, :reason, :evidenceRef, :idempotencyKey, cast(:attributes as jsonb),
                :sequenceNumber, :previousHash, :eventHash, :canonicalPayload)
            """;

    private static final String SELECT_COLUMNS = """
            event_id, entity_id, subject_id, purpose_code, purpose_version, event_type,
            legal_basis, notice_id, notice_version, language_tag, capture_method, channel,
            application_id, jurisdiction, occurred_at, recorded_at, expires_at, actor_type,
            actor_id, reason, evidence_ref, idempotency_key, attributes, sequence_number,
            previous_hash, event_hash, canonical_payload
            """;

    private final JdbcClient jdbc;

    public ConsentEventStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * Appends an event to a subject's chain and returns it with its assigned sequence number and
     * hashes.
     *
     * <p>Must run inside a transaction. Sequence allocation takes a row lock on the chain head,
     * which is what makes the sequence strictly monotonic and prevents two concurrent writers
     * forking the chain — a real possibility when a subject withdraws on the web at the same
     * moment a field device syncs a queued capture for them.
     *
     * <p>Replaying an event that carries an idempotency key already seen returns the original
     * event unchanged rather than writing a second one. Offline field devices retry aggressively
     * on flaky reconnects, and a duplicate consent event is not a harmless duplicate: it would
     * appear in an audit as two separate acts by the subject.
     */
    public AppendResult append(ConsentEvent candidate) {
        if (candidate.idempotencyKey() != null) {
            Optional<ConsentEvent> existing =
                    findByIdempotencyKey(candidate.entityId(), candidate.idempotencyKey());
            if (existing.isPresent()) {
                return new AppendResult(existing.get(), true);
            }
        }

        ChainHead head = lockChainHead(candidate.entityId(), candidate.subjectId());
        long sequence = head.lastSequence() + 1;
        Instant recordedAt = Instant.now();

        // The payload must be built from the event as it will be stored, sequence included,
        // because the sequence is part of what the hash attests to.
        ConsentEvent sequenced = candidate.withChain(sequence, head.lastHash(), null, recordedAt);
        String canonicalPayload = CanonicalJson.serialize(sequenced.hashableBody());
        String eventHash = Hashes.chain(head.lastHash(), canonicalPayload);
        ConsentEvent stored =
                sequenced.withChain(sequence, head.lastHash(), eventHash, recordedAt);

        jdbc.sql(INSERT_SQL)
                .param("eventId", UUID.fromString(stored.eventId()))
                .param("entityId", stored.entityId())
                .param("subjectId", stored.subjectId())
                .param("purposeCode", stored.purposeCode())
                .param("purposeVersion", stored.purposeVersion())
                .param("eventType", stored.type().name())
                .param("legalBasis", name(stored.legalBasis()))
                .param("noticeId", stored.noticeId())
                .param("noticeVersion", stored.noticeVersion())
                .param("languageTag", stored.languageTag())
                .param("captureMethod", name(stored.captureMethod()))
                .param("channel", name(stored.channel()))
                .param("applicationId", stored.applicationId())
                .param("jurisdiction", name(stored.jurisdiction()))
                .param("occurredAt", Timestamp.from(stored.occurredAt()))
                .param("recordedAt", Timestamp.from(recordedAt))
                .param("expiresAt", stored.expiresAt() == null ? null : Timestamp.from(stored.expiresAt()))
                .param("actorType", name(stored.actorType()))
                .param("actorId", stored.actorId())
                .param("reason", stored.reason())
                .param("evidenceRef", stored.evidenceRef())
                .param("idempotencyKey", stored.idempotencyKey())
                .param("attributes", CanonicalJson.serialize(stored.attributes()))
                .param("sequenceNumber", sequence)
                .param("previousHash", head.lastHash())
                .param("eventHash", eventHash)
                .param("canonicalPayload", canonicalPayload)
                .update();

        jdbc.sql("""
                        update consent_chain_head
                           set last_sequence = :seq, last_hash = :hash, updated_at = now()
                         where entity_id = :entityId and subject_id = :subjectId
                        """)
                .param("seq", sequence)
                .param("hash", eventHash)
                .param("entityId", stored.entityId())
                .param("subjectId", stored.subjectId())
                .update();

        return new AppendResult(stored, false);
    }

    /**
     * Outcome of an append.
     *
     * @param event  the event now in the ledger — the newly written one, or the original where
     *               this was a replay
     * @param replay true when an event with the same idempotency key was already present, so
     *               nothing new was written. Callers must not re-publish on a replay: a
     *               downstream system seeing the same withdrawal twice is a real defect, and
     *               field devices retry aggressively over flaky connections.
     */
    public record AppendResult(ConsentEvent event, boolean replay) {
    }

    /**
     * Takes the per-subject write lock, creating the chain head on first use.
     *
     * <p>The insert-then-lock ordering matters: two concurrent first-writes for the same subject
     * would otherwise both find no head and both start a chain at sequence one. {@code on
     * conflict do nothing} lets the loser proceed to the same lock the winner holds.
     */
    private ChainHead lockChainHead(String entityId, String subjectId) {
        jdbc.sql("""
                        insert into consent_chain_head (entity_id, subject_id, last_sequence, last_hash)
                        values (:entityId, :subjectId, 0, :genesis)
                        on conflict (entity_id, subject_id) do nothing
                        """)
                .param("entityId", entityId)
                .param("subjectId", subjectId)
                .param("genesis", ConsentEvent.GENESIS_HASH)
                .update();

        return jdbc.sql("""
                        select last_sequence, last_hash from consent_chain_head
                         where entity_id = :entityId and subject_id = :subjectId
                         for update
                        """)
                .param("entityId", entityId)
                .param("subjectId", subjectId)
                .query((rs, n) -> new ChainHead(rs.getLong("last_sequence"), rs.getString("last_hash")))
                .single();
    }

    /** Every event for a subject, oldest first. The input to chain verification and replay. */
    public List<StoredEvent> findChain(String entityId, String subjectId) {
        return jdbc.sql("select " + SELECT_COLUMNS + """
                         from consent_event
                        where entity_id = :entityId and subject_id = :subjectId
                        order by sequence_number asc
                        """)
                .param("entityId", entityId)
                .param("subjectId", subjectId)
                .query(ConsentEventStore::mapStored)
                .list();
    }

    /** Every event for a subject and purpose, oldest first. */
    public List<StoredEvent> findChainForPurpose(String entityId, String subjectId, String purposeCode) {
        return jdbc.sql("select " + SELECT_COLUMNS + """
                         from consent_event
                        where entity_id = :entityId and subject_id = :subjectId
                          and purpose_code = :purposeCode
                        order by sequence_number asc
                        """)
                .param("entityId", entityId)
                .param("subjectId", subjectId)
                .param("purposeCode", purposeCode)
                .query(ConsentEventStore::mapStored)
                .list();
    }

    public Optional<ConsentEvent> findByIdempotencyKey(String entityId, String idempotencyKey) {
        return jdbc.sql("select " + SELECT_COLUMNS + """
                         from consent_event
                        where entity_id = :entityId and idempotency_key = :key
                        """)
                .param("entityId", entityId)
                .param("key", idempotencyKey)
                .query(ConsentEventStore::mapStored)
                .optional()
                .map(StoredEvent::event);
    }

    /** Subjects holding at least one event, for the integrity sweep. */
    public List<String[]> findAllChainKeys(int limit, int offset) {
        return jdbc.sql("""
                        select entity_id, subject_id from consent_chain_head
                        order by entity_id, subject_id
                        limit :limit offset :offset
                        """)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, n) -> new String[]{rs.getString("entity_id"), rs.getString("subject_id")})
                .list();
    }

    /**
     * Consent that has lapsed but has no EXPIRED event yet. The sweeper turns these into durable
     * events so that the ledger tells the whole story; the decision engine already treats them as
     * expired without waiting, since a consent that lapsed an hour ago is gone whether or not a
     * batch job has run.
     */
    public List<String[]> findLapsedArtefacts(Instant asOf, int limit) {
        return jdbc.sql("""
                        select entity_id, subject_id, purpose_code from consent_artefact
                         where status = 'GRANTED' and expires_at is not null and expires_at <= :asOf
                         order by expires_at asc
                         limit :limit
                        """)
                .param("asOf", Timestamp.from(asOf))
                .param("limit", limit)
                .query((rs, n) -> new String[]{
                        rs.getString("entity_id"), rs.getString("subject_id"),
                        rs.getString("purpose_code")})
                .list();
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static StoredEvent mapStored(ResultSet rs, int rowNum) throws SQLException {
        @SuppressWarnings("unchecked")
        Map<String, String> attributes =
                CanonicalJson.parse(rs.getString("attributes"), Map.class);

        ConsentEvent event = new ConsentEvent(
                rs.getString("event_id"),
                rs.getString("entity_id"),
                rs.getString("subject_id"),
                rs.getString("purpose_code"),
                rs.getInt("purpose_version"),
                ConsentEventType.valueOf(rs.getString("event_type")),
                enumOrNull(LegalBasis.class, rs.getString("legal_basis")),
                rs.getString("notice_id"),
                (Integer) rs.getObject("notice_version"),
                rs.getString("language_tag"),
                enumOrNull(CaptureMethod.class, rs.getString("capture_method")),
                enumOrNull(Channel.class, rs.getString("channel")),
                rs.getString("application_id"),
                enumOrNull(Jurisdiction.class, rs.getString("jurisdiction")),
                instant(rs.getTimestamp("occurred_at")),
                instant(rs.getTimestamp("recorded_at")),
                instant(rs.getTimestamp("expires_at")),
                enumOrNull(ActorType.class, rs.getString("actor_type")),
                rs.getString("actor_id"),
                rs.getString("reason"),
                rs.getString("evidence_ref"),
                rs.getString("idempotency_key"),
                attributes,
                rs.getLong("sequence_number"),
                rs.getString("previous_hash"),
                rs.getString("event_hash"));

        return new StoredEvent(event, rs.getString("canonical_payload"));
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record ChainHead(long lastSequence, String lastHash) {
    }
}
