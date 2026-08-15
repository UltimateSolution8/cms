package com.uds.consent.core.model;

import java.time.Instant;

/**
 * Current consent state for one subject and one purpose — a materialised projection of the
 * event chain, shaped along the lines of ISO/IEC TS 27560:2023.
 *
 * <p>This is a cache of a conclusion, not a fact. If it is ever lost it can be rebuilt entirely
 * by replaying {@link ConsentEvent}s. Nothing may write to it except the projector.
 *
 * @param entityId        the UDS entity acting as Data Fiduciary
 * @param subjectId       privacy-minimal subject reference
 * @param purposeCode     registry code
 * @param purposeVersion  purpose version the subject last consented against
 * @param status          current derived status
 * @param legalBasis      basis currently relied on
 * @param noticeId        notice last shown
 * @param noticeVersion   exact notice version last shown
 * @param languageTag     language the notice was rendered in
 * @param captureMethod   how the standing consent was obtained
 * @param channel         channel of capture
 * @param applicationId   surface that captured it
 * @param jurisdiction    governing jurisdiction
 * @param grantedAt       when consent was last granted, or {@code null}
 * @param expiresAt       when it lapses, or {@code null} if it does not
 * @param withdrawnAt     when it was withdrawn, or {@code null}
 * @param lastEventAt     timestamp of the most recent event folded into this projection
 * @param sequenceNumber  sequence of that event; the conflict-resolution key
 * @param lastEventHash   hash of that event, tying the projection back to the chain
 */
public record ConsentArtefact(
        String entityId,
        String subjectId,
        String purposeCode,
        int purposeVersion,
        ConsentStatus status,
        LegalBasis legalBasis,
        String noticeId,
        Integer noticeVersion,
        String languageTag,
        CaptureMethod captureMethod,
        Channel channel,
        String applicationId,
        Jurisdiction jurisdiction,
        Instant grantedAt,
        Instant expiresAt,
        Instant withdrawnAt,
        Instant lastEventAt,
        long sequenceNumber,
        String lastEventHash) {

    /**
     * The artefact for a subject and purpose with no interaction on record. Returned instead of
     * null so that callers cannot accidentally treat absence as permission.
     */
    public static ConsentArtefact notAsked(String entityId, String subjectId, String purposeCode) {
        return new ConsentArtefact(entityId, subjectId, purposeCode, 0, ConsentStatus.NOT_ASKED,
                null, null, null, null, null, null, null, null, null, null, null, null, -1L, null);
    }

    /** Whether this artefact has lapsed as at {@code at}, irrespective of its stored status. */
    public boolean isExpiredAt(Instant at) {
        return expiresAt != null && !at.isBefore(expiresAt);
    }

    /**
     * Effective status as at {@code at}: the stored status, downgraded to
     * {@link ConsentStatus#EXPIRED} where the validity window has passed.
     *
     * <p>The expiry sweeper writes a durable EXPIRED event, but a decision must not wait for the
     * sweeper to run — a consent that lapsed thirty seconds ago is already gone.
     */
    public ConsentStatus effectiveStatus(Instant at) {
        if (status == ConsentStatus.GRANTED && isExpiredAt(at)) {
            return ConsentStatus.EXPIRED;
        }
        return status;
    }
}
