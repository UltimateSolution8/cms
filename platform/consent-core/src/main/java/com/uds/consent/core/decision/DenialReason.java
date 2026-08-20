package com.uds.consent.core.decision;

/**
 * Why processing was denied.
 *
 * <p>These codes are part of the platform's public contract. They are logged on every denial and
 * aggregated in the compliance console, which is how the group learns that (say) forty per cent
 * of a Denave campaign list is failing on provenance rather than on withdrawal.
 */
public enum DenialReason {

    /** Not a denial. */
    NONE,

    /** No consent interaction has ever been recorded for this subject and purpose. */
    NO_CONSENT_RECORD,

    /** The subject was asked and declined. */
    CONSENT_DENIED,

    /** Consent was given and withdrawn. */
    CONSENT_WITHDRAWN,

    /**
     * Consent lapsed under the purpose's expiry policy — most often TRAI's seven-day window for
     * explicit transactional consent, or the end of the contractual relationship supporting
     * inferred consent.
     */
    CONSENT_EXPIRED,

    /** Consent was struck down: superseded notice, failed provenance, or regulator direction. */
    CONSENT_INVALIDATED,

    /** Two surfaces disagree and the conflict could not be resolved. Raises an alert. */
    CONSENT_CONFLICTED,

    /** Captured offline and not yet synced, on a purpose that fails closed. */
    CONSENT_PENDING_SYNC,

    /** Subject is on a statutory do-not-contact registry for this channel. */
    SUPPRESSED_STATUTORY,

    /** Subject opted out through a UDS surface, or a client supplied the suppression. */
    SUPPRESSED_OPT_OUT,

    /**
     * The record came from a bulk load and carries no substantiated provenance. Such records are
     * quarantined rather than grandfathered.
     */
    NO_PROVENANCE,

    /** The purpose code is not in the registry. */
    PURPOSE_UNKNOWN,

    /** The purpose has been retired and may not be relied on for new processing. */
    PURPOSE_RETIRED,

    /** The purpose has no lawful basis in the requesting jurisdiction. */
    PURPOSE_NOT_PERMITTED_IN_JURISDICTION,

    /** The purpose does not permit this channel. */
    CHANNEL_NOT_PERMITTED,

    /** The subject is under eighteen and the purpose is not permitted for children (DPDP s.9). */
    CHILD_SUBJECT_RESTRICTED,

    /**
     * The subject is a child and the consent being relied on records no guardian verification.
     *
     * <p>Distinct from {@link #CHILD_SUBJECT_RESTRICTED}, and the distinction is the point. That one
     * says the purpose is closed to children however consent was given (DPDP s.9(3)). This one says
     * the purpose <em>is</em> open to children and the consent relied upon was not captured as
     * verifiably given by a parent or lawful guardian — s.9(1) with Rule 10, where the diligence is
     * the obligation and the consent is only its output.
     *
     * <p>The live case is a subject whose minority was established <strong>after</strong> capture.
     * The capture path already refuses an unevidenced parental consent; nothing asked the question
     * again at the decision, so a consent given when nobody knew the person was a minor kept being
     * acted upon after they were recorded as one.
     */
    CHILD_GUARDIAN_NOT_EVIDENCED,

    /** The consent on record was given against a notice version since materially superseded. */
    NOTICE_SUPERSEDED,

    /** The calling application is not authorised for this purpose. */
    APPLICATION_NOT_AUTHORISED,

    /** The receiving vendor is not authorised for this purpose or data category. */
    VENDOR_NOT_AUTHORISED,

    /**
     * The subject opted out recently enough that they may not be re-solicited.
     *
     * <p>TRAI's February 2025 amendment sets a ninety-day cooling-off before consent may be
     * sought again from someone who opted out. A prohibition rather than advice, which is why it
     * is a denial reason and not an obligation string — an obligation is something the caller is
     * told to do, and this is something they may not do.
     */
    WITHIN_COOLING_OFF_PERIOD,

    /**
     * A relay arrived claiming to be a Consent Manager that is not registered, or no longer is.
     *
     * <p>Recorded as a denial rather than logged, because it is the exact shape of the attack the
     * Rule 4 framework invites: an inbound channel that writes consent, authenticated by a claim
     * about who the caller is. A refusal that leaves no evidence is one nobody can count, and the
     * question after an incident is how many times it was attempted and against whom.
     */
    CONSENT_MANAGER_NOT_REGISTERED,

    /**
     * A relay named a registration the calling credential does not hold.
     *
     * <p>Separate from {@link #CONSENT_MANAGER_NOT_REGISTERED} because it is a different event with
     * a different response. That one is a caller nobody has heard of; this one is a caller the
     * platform <em>has</em> heard of, authenticated correctly, and which then named somebody else's
     * Board registration number — a registered Consent Manager writing consent into the ledger under
     * a competitor's identity, or a leaked credential probing which numbers exist.
     *
     * <p>The distinction is invisible to the caller and must stay that way: all three refusals
     * answer the same opaque 403, or the endpoint becomes a way of enumerating the register. It
     * exists so that the evidence plane can tell them apart afterwards, which is the only place the
     * difference matters and the only place anyone is entitled to it.
     */
    CONSENT_MANAGER_NOT_BOUND,

    /** State was indeterminate and the purpose fails closed. */
    FAIL_CLOSED_DEFAULT,

    /** The policy engine could not evaluate. Always denies; raises an alert. */
    POLICY_ERROR
}
