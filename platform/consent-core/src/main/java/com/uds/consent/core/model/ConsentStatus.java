package com.uds.consent.core.model;

/**
 * The state of a subject's consent for one purpose, as seen by the enforcement plane.
 *
 * <p>This is a <em>derived</em> value. It is never stored as the authoritative fact — it is a
 * materialised view over the append-only event stream in the evidence plane. See
 * {@link ConsentEventType} for the facts that produce it.
 */
public enum ConsentStatus {

    /** Affirmative, unambiguous consent is on record and currently in force. */
    GRANTED(true),

    /** The subject was asked and declined. Distinct from {@link #NOT_ASKED}. */
    DENIED(false),

    /** Consent was given and later withdrawn. DPDP s.6(6): withdrawal is as easy as giving. */
    WITHDRAWN(false),

    /** No consent interaction has ever occurred for this subject and purpose. */
    NOT_ASKED(false),

    /**
     * Consent was valid but has lapsed under the purpose's expiry policy — e.g. TRAI's
     * seven-day window for explicit transactional consent, or the end of the contractual
     * relationship that supported inferred consent.
     */
    EXPIRED(false),

    /**
     * Consent is no longer relied upon because the notice or purpose it was given against has
     * been materially superseded, or because provenance could not be substantiated.
     */
    INVALIDATED(false),

    /**
     * Captured offline on a field device and not yet durably recorded in the ledger. Treated as
     * permissive only where the purpose's failure behaviour explicitly allows it.
     */
    PENDING_SYNC(false),

    /**
     * Two capture surfaces disagree and the conflict could not be resolved by sequence number.
     * Always denies; raises an operational alert.
     */
    CONFLICTED(false),

    /** State could not be determined (store unreachable, subject unknown). Never permissive. */
    UNKNOWN(false);

    private final boolean permissive;

    ConsentStatus(boolean permissive) {
        this.permissive = permissive;
    }

    /**
     * Whether this status, on its own, permits processing. Note that a permissive status is
     * necessary but not sufficient — the decision engine still applies suppression lists,
     * jurisdiction rules and application authorisation on top.
     */
    public boolean isPermissive() {
        return permissive;
    }
}
