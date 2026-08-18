package com.uds.consent.core.model;

import java.time.Instant;
import java.util.Objects;

/**
 * The due diligence performed on a parent or lawful guardian before consent was accepted on a
 * child's behalf.
 *
 * <p>Before this existed the platform recorded {@link CaptureMethod#PARENTAL_VERIFIED} and
 * {@link ActorType#GUARDIAN} and nothing else — which is to say it recorded the capture surface's
 * own claim that it had done the work, and kept no evidence that anything was checked. The group
 * could produce a consent and could not produce the diligence, and under DPDP s.9 read with Rule 10
 * the diligence is the obligation. The consent is only its output.
 *
 * <p><strong>The reference is a hash.</strong> Whatever identified the guardian — an account id, a
 * DigiLocker token — is peppered and hashed before it reaches this record, on the same reasoning
 * that keeps phone numbers out of the ledger: the evidence plane must be able to prove a check
 * happened without becoming a second directory of the people it happened to. A guardian is not the
 * data principal here and has even less business being identifiable in this store than the child.
 *
 * @param method       which of Rule 10's routes was taken
 * @param referenceHash peppered hash of whatever the check was performed against — the parent's
 *                     verified account, the virtual token, the reference of the documented check.
 *                     Never the raw value. Required: a method with nothing behind it is the same
 *                     assertion this record exists to replace
 * @param verifiedAt   when the check was performed. Not the same instant as the capture — a parent
 *                     verified at registration in March and consenting for their child in August
 *                     is the ordinary shape of {@link GuardianVerificationMethod#EXISTING_VERIFIED_ACCOUNT},
 *                     and an auditor asking how stale the check was needs both dates
 * @param verifiedBy   the system or person that performed it, for attribution
 */
public record GuardianVerification(
        GuardianVerificationMethod method,
        String referenceHash,
        Instant verifiedAt,
        String verifiedBy) {

    /** Attribute key carrying the method onto the ledger event, and so into the hash chain. */
    public static final String ATTR_METHOD = "guardian.verification.method";

    /** Attribute key carrying the hashed reference. */
    public static final String ATTR_REFERENCE = "guardian.verification.referenceHash";

    /** Attribute key carrying when the check was performed. */
    public static final String ATTR_VERIFIED_AT = "guardian.verification.verifiedAt";

    /** Attribute key carrying who performed it. */
    public static final String ATTR_VERIFIED_BY = "guardian.verification.verifiedBy";

    public GuardianVerification {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(verifiedAt, "verifiedAt");
        if (referenceHash == null || referenceHash.isBlank()) {
            throw new IllegalArgumentException(
                    "guardian verification requires a reference; a method with nothing behind it "
                            + "is an assertion, not evidence");
        }
    }

    /**
     * Whether this block is complete enough to stand as evidence.
     *
     * <p>The compact constructor already refuses an incomplete one, so this is true for any
     * instance that exists. It is here so that capture validation can express "present and
     * sufficient" as one condition against a possibly-null field, rather than every call site
     * re-deriving what sufficient means.
     */
    public boolean isEvidenced() {
        return true;
    }
}
