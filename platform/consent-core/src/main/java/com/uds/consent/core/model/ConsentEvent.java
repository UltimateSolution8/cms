package com.uds.consent.core.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * One immutable fact in the consent ledger.
 *
 * <p>Events are append-only and hash-chained per subject within an entity. {@code previousHash}
 * points at the prior event's {@code eventHash}, so altering any historical row breaks the chain
 * from that point forward and the verifier detects it.
 *
 * <p>{@code sequenceNumber} — not {@code occurredAt} — resolves conflicts. A field force of
 * several thousand Android devices across five countries will have clock skew; treating wall
 * time as authoritative would let a stale device silently overwrite a fresh withdrawal.
 *
 * @param eventId        stable identifier for this event
 * @param entityId       the UDS entity acting as Data Fiduciary
 * @param subjectId      privacy-minimal subject reference; never a name, phone or email
 * @param purposeCode    the registry code this consent is for
 * @param purposeVersion the exact purpose version the subject consented against
 * @param type           the fact recorded
 * @param legalBasis     the basis relied on at the time of capture
 * @param noticeId       notice shown, or {@code null} where none applies
 * @param noticeVersion  exact notice version rendered, so it can be reproduced years later
 * @param languageTag    BCP 47 tag of the language the notice was rendered in
 * @param captureMethod  what the subject actually did
 * @param channel        medium of capture
 * @param applicationId  application or surface that captured it
 * @param jurisdiction   jurisdiction whose rules governed the capture
 * @param occurredAt     when the subject acted (may be offline, and earlier than recordedAt)
 * @param recordedAt     when the ledger durably wrote it
 * @param expiresAt      when this consent lapses, or {@code null} if it does not
 * @param actorType      who caused the event
 * @param actorId        attributable identity of the actor
 * @param reason         required for INVALIDATED; free text for the audit trail
 * @param evidenceRef    pointer into the evidence store (recording, signed form, DOM snapshot)
 * @param idempotencyKey client-supplied key that makes offline replay safe
 * @param attributes     additional captured context, e.g. contract end date or campaign id
 * @param sequenceNumber monotonic per (entityId, subjectId); the conflict-resolution key
 * @param previousHash   {@code eventHash} of the prior event in this subject's chain
 * @param eventHash      SHA-256 over {@code previousHash} and this event's canonical payload
 */
public record ConsentEvent(
        String eventId,
        String entityId,
        String subjectId,
        String purposeCode,
        int purposeVersion,
        ConsentEventType type,
        LegalBasis legalBasis,
        String noticeId,
        Integer noticeVersion,
        String languageTag,
        CaptureMethod captureMethod,
        Channel channel,
        String applicationId,
        Jurisdiction jurisdiction,
        Instant occurredAt,
        Instant recordedAt,
        Instant expiresAt,
        ActorType actorType,
        String actorId,
        String reason,
        String evidenceRef,
        String idempotencyKey,
        Map<String, String> attributes,
        long sequenceNumber,
        String previousHash,
        String eventHash) {

    /**
     * The value {@code purposeVersion} carries when the event asserts no version at all.
     *
     * <p>Purpose versions start at 1, so {@code 0} cannot collide with a real one. It is written by
     * {@code ExpirySweeper} and by {@code ConsentCaptureService.invalidate}, because an expiry and an
     * invalidation <em>end</em> an agreement without restating its terms — there is no version for
     * them to assert, and inventing one would put an affirmative false claim in an append-only
     * table.
     *
     * <p><strong>A sentinel, not a defect, and the distinction matters twice.</strong> An unstated
     * version is a weak value; a wrong version is a false statement, which is why
     * {@code ArtefactProjector} carries the prior version forward for these types rather than
     * projecting the zero. And it is why anything comparing a projection against the chain — the
     * reconciliation sweep — must read this value as "no assertion" rather than as a disagreement,
     * or every expired artefact in the database reports as divergent.
     *
     * <p>Named here rather than left as a bare literal at two write sites and a third reader: until
     * it was, a reader had no way to tell it from a real version.
     */
    public static final int NO_PURPOSE_VERSION_ASSERTED = 0;

    /** Genesis value for the first event in a subject's chain. */
    public static final String GENESIS_HASH = "0".repeat(64);

    public ConsentEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(purposeCode, "purposeCode");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(occurredAt, "occurredAt");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);

        // Truncated to microseconds, which is the resolution PostgreSQL's timestamptz actually
        // stores. Without this the hashed payload records a precision the evidence plane cannot
        // hold: `Instant.now()` on a modern JVM yields nanoseconds, the payload is serialised
        // from it, the columns round-trip through the database at microseconds, and re-serialising
        // the columns produces a different string from the one that was hashed.
        //
        // The consequence was not a broken chain — the chain hashes the payload, and the payload
        // is stored verbatim — but a permanent PAYLOAD_DIVERGENCE finding on every event captured
        // with sub-microsecond precision. A tamper check that fires on everything detects nothing,
        // and this one is the check that would notice somebody editing the structured columns
        // without being able to forge the payload.
        //
        // Found by running the platform by hand rather than by any test: every integration suite
        // passes an instant truncated to seconds or a fixed literal, so none of them ever carried
        // a nanosecond component into the ledger.
        occurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS);
        recordedAt = recordedAt == null ? null : recordedAt.truncatedTo(ChronoUnit.MICROS);
        expiresAt = expiresAt == null ? null : expiresAt.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * The fields covered by {@link #eventHash}, in a deterministic order.
     *
     * <p>Excludes {@code eventHash} itself and {@code previousHash} — the latter is mixed into
     * the digest separately by the chaining function rather than serialised into the payload,
     * which keeps the payload a self-contained description of what happened.
     */
    public SortedMap<String, Object> hashableBody() {
        SortedMap<String, Object> body = new TreeMap<>();
        body.put("eventId", eventId);
        body.put("entityId", entityId);
        body.put("subjectId", subjectId);
        body.put("purposeCode", purposeCode);
        body.put("purposeVersion", purposeVersion);
        body.put("type", type.name());
        body.put("legalBasis", legalBasis == null ? null : legalBasis.name());
        body.put("noticeId", noticeId);
        body.put("noticeVersion", noticeVersion);
        body.put("languageTag", languageTag);
        body.put("captureMethod", captureMethod == null ? null : captureMethod.name());
        body.put("channel", channel == null ? null : channel.name());
        body.put("applicationId", applicationId);
        body.put("jurisdiction", jurisdiction == null ? null : jurisdiction.name());
        body.put("occurredAt", occurredAt.toString());
        body.put("recordedAt", recordedAt == null ? null : recordedAt.toString());
        body.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
        body.put("actorType", actorType == null ? null : actorType.name());
        body.put("actorId", actorId);
        body.put("reason", reason);
        body.put("evidenceRef", evidenceRef);
        body.put("idempotencyKey", idempotencyKey);
        body.put("attributes", new TreeMap<>(attributes));
        body.put("sequenceNumber", sequenceNumber);
        return body;
    }

    /** The status this event leaves the subject/purpose pair in. */
    public ConsentStatus resultingStatus() {
        return type.resultingStatus();
    }

    /** Copy of this event carrying chain fields, used by the ledger at write time. */
    public ConsentEvent withChain(long seq, String prevHash, String hash, Instant recordedAt) {
        return new ConsentEvent(eventId, entityId, subjectId, purposeCode, purposeVersion, type,
                legalBasis, noticeId, noticeVersion, languageTag, captureMethod, channel,
                applicationId, jurisdiction, occurredAt, recordedAt, expiresAt, actorType, actorId,
                reason, evidenceRef, idempotencyKey, attributes, seq, prevHash, hash);
    }
}
