package com.uds.consent.core.model;

/**
 * Where a rights request has got to.
 *
 * <p>{@link #AWAITING_SUBJECT} exists because verification is the usual reason a request stalls,
 * and a request stalled on the principal reads very differently from one stalled on the group.
 * Collapsing the two would let genuine delay hide inside a status that sounds reasonable.
 */
public enum RightsRequestStatus {

    /** Logged, clock running, nobody has looked at it yet. */
    RECEIVED(true),

    /** Someone owns it and is working it. */
    IN_PROGRESS(true),

    /**
     * Blocked on the principal — usually identity verification.
     *
     * <p>The clock keeps running. Whether the statute permits a pause is a question for legal per
     * jurisdiction, and the safe implementation is the one that does not quietly grant an extension
     * the law may not allow.
     */
    AWAITING_SUBJECT(true),

    /** Done, and what was done is recorded. */
    FULFILLED(false),

    /**
     * Refused, with a ground.
     *
     * <p>A legitimate outcome — an erasure request against data held under a legal obligation must
     * be refused — but never a silent one. The resolution text is what the principal is owed and
     * what the Board would ask to see.
     */
    REJECTED(false),

    /** The principal withdrew the request. */
    WITHDRAWN(false);

    private final boolean open;

    RightsRequestStatus(boolean open) {
        this.open = open;
    }

    /** Whether the statutory clock is still running against this request. */
    public boolean isOpen() {
        return open;
    }
}
