package com.uds.consent.core.snapshot;

import java.time.Instant;
import java.util.Map;

/**
 * A small, cryptographically signed statement of one subject's consent standing, issued to a
 * device or a downstream service so that it can decide without a network call.
 *
 * <p>This is what makes the offline story real rather than aspirational. Denave's field force runs
 * iSFA and retail-audit tools in places with no usable connectivity; a design that requires a
 * round trip before each action either blocks the work or gets bypassed. A signed snapshot lets
 * the device answer in microseconds and still be accountable, because the signature ties the
 * answer to a policy version the server can reproduce.
 *
 * @param snapshotId    stable id, logged alongside any decision made from it
 * @param entityId      issuing UDS entity
 * @param subjectId     privacy-minimal subject reference
 * @param issuedAt      when the server issued it
 * @param expiresAt     after which the device must refresh; short by design
 * @param policyVersion version of the policy bundle folded into these states
 * @param purposes      purpose code to state
 */
public record ConsentSnapshot(
        String snapshotId,
        String entityId,
        String subjectId,
        Instant issuedAt,
        Instant expiresAt,
        String policyVersion,
        Map<String, PurposeState> purposes) {

    public ConsentSnapshot {
        purposes = purposes == null ? Map.of() : Map.copyOf(purposes);
    }

    /** Whether this snapshot is past its refresh deadline as at {@code at}. */
    public boolean isStaleAt(Instant at) {
        return expiresAt == null || !at.isBefore(expiresAt);
    }

    /** State for a purpose, or {@code null} if the snapshot does not carry it. */
    public PurposeState purpose(String purposeCode) {
        return purposes.get(purposeCode);
    }
}
