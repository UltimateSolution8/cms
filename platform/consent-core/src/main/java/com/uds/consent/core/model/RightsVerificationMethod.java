package com.uds.consent.core.model;

/**
 * How the platform came to believe that the person who filed a rights request is the principal.
 *
 * <p>Recorded on the request because the statutory deadline is computed from {@code received_at},
 * and a deadline is only as defensible as the instant it was computed from. DPDP Rule 14(3) sets a
 * ceiling of ninety days and the group publishes thirty; if the start instant was supplied by
 * whoever filed the request, then in any dispute about lateness the group's own record is evidence
 * offered by the counterparty. That is not a reason to refuse the request — it is a reason to say
 * on the row which of the three below applies.
 *
 * <p><strong>None of these is a gate.</strong> An {@link #UNVERIFIED} request still gets a clock,
 * still appears in the queue, and is still answered. Parking requests outside the clock until
 * somebody fills in a field would produce the one outcome Rule 14(3) actually penalises. This
 * follows the same posture as the fulfilment register: record the silence, and do not let it read
 * as diligence.
 */
public enum RightsVerificationMethod {

    /**
     * A single-use token sent out of band to the identifier claimed, and returned.
     *
     * <p>The only one of the three the platform establishes for itself. {@code /v1/portal/**} mints
     * the token, holds only its hash, and files the request at the moment the token comes back — so
     * {@code received_at} and {@code verified_at} are the same instant by construction rather than
     * by an operator's assurance that they agree.
     */
    PORTAL_TOKEN,

    /**
     * A named operator states that they established identity, and says how.
     *
     * <p>The detail is theirs, not the platform's: a call-back to a number already on file, an
     * employee ID checked at a desk, a document reference. The platform records the claim and who
     * made it. That is weaker than {@link #PORTAL_TOKEN} and stronger than nothing, and the whole
     * value of the distinction is that a reviewer can tell which one they are looking at.
     */
    OPERATOR_ASSERTED,

    /**
     * Nobody recorded having checked.
     *
     * <p>The default, deliberately. Silence is not read as diligence, and a request whose start
     * instant rests on nothing should say so rather than inherit the appearance of a verified one.
     * Every request filed before this column existed reads {@code UNVERIFIED} for the same reason:
     * a migration that labelled them otherwise would be writing a false statement into evidence.
     */
    UNVERIFIED;

    /** Whether this method carries a verification instant. Mirrors the V30 check constraint. */
    public boolean isVerified() {
        return this != UNVERIFIED;
    }
}
