package com.uds.consent.core.model;

import java.util.Set;

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

    /**
     * The statuses this one may move to.
     *
     * <p>Written here rather than in the service because the statuses live here, and a state
     * machine kept in a different module from the states it governs is a state machine that gets
     * half-updated. Until this existed the only rules were "the request must be open" and "a
     * terminal status needs a resolution", which permitted a request to bounce between
     * {@code RECEIVED} and {@code AWAITING_SUBJECT} indefinitely — each bounce resetting nothing,
     * the clock running throughout, and the queue reading as active work.
     *
     * <p><strong>{@code RECEIVED → FULFILLED} is deliberately legal</strong>, and it is the
     * transition a reader would expect to be barred. A request satisfied on the call that reported
     * it is a real thing that happens — a principal asks what is held about them and the agent
     * reads it out — and forbidding it would not produce more work, it would teach operators to
     * click through {@code IN_PROGRESS} on the way past to satisfy the machine. A state machine
     * that people route around records less than one that admits the shortcut.
     *
     * <p>Moving backwards from {@code IN_PROGRESS} to {@code RECEIVED} is <em>not</em> legal:
     * "nobody has looked at this yet" stops being true the moment somebody has, and un-assigning a
     * request is what {@code assignedTo} is for. Terminal statuses go nowhere at all — a correction
     * is a new request, which is what the service already told the caller.
     */
    public Set<RightsRequestStatus> permittedNext() {
        return switch (this) {
            case RECEIVED -> Set.of(IN_PROGRESS, AWAITING_SUBJECT, FULFILLED, REJECTED, WITHDRAWN);
            case IN_PROGRESS -> Set.of(AWAITING_SUBJECT, FULFILLED, REJECTED, WITHDRAWN);
            case AWAITING_SUBJECT -> Set.of(IN_PROGRESS, FULFILLED, REJECTED, WITHDRAWN);
            case FULFILLED, REJECTED, WITHDRAWN -> Set.of();
        };
    }

    /** Whether this request may move to {@code next}. */
    public boolean canMoveTo(RightsRequestStatus next) {
        return permittedNext().contains(next);
    }
}
