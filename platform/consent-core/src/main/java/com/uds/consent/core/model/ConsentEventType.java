package com.uds.consent.core.model;

/**
 * The immutable facts recorded in the consent ledger.
 *
 * <p>These are the only things that are ever written. Current state ({@link ConsentStatus}) is
 * folded from them. Nothing in the platform may update or delete an event — the database
 * revokes those grants outright rather than relying on application discipline.
 */
public enum ConsentEventType {

    /** Subject gave consent by a clear affirmative action. */
    GRANTED(ConsentStatus.GRANTED),

    /** Subject was asked and declined. */
    DENIED(ConsentStatus.DENIED),

    /** Scope changed within the same purpose — e.g. channel narrowed from call+SMS to SMS. */
    MODIFIED(ConsentStatus.GRANTED),

    /** Subject withdrew. Takes effect immediately and fans out over the event bus. */
    WITHDRAWN(ConsentStatus.WITHDRAWN),

    /** Lapsed under the purpose's expiry policy. Written by the expiry sweeper, not by a user. */
    EXPIRED(ConsentStatus.EXPIRED),

    /**
     * Struck down by the fiduciary — superseded notice, failed provenance substantiation, or a
     * regulator direction. Requires an actor and a reason; never silent.
     */
    INVALIDATED(ConsentStatus.INVALIDATED),

    /**
     * Recorded when a notice was served but no consent was sought (a s.7 legitimate use, or a
     * transparency-only interaction). Keeps the evidence trail complete.
     */
    NOTICE_SERVED(ConsentStatus.NOT_ASKED);

    private final ConsentStatus resultingStatus;

    ConsentEventType(ConsentStatus resultingStatus) {
        this.resultingStatus = resultingStatus;
    }

    /** The status a subject/purpose pair holds immediately after this event. */
    public ConsentStatus resultingStatus() {
        return resultingStatus;
    }
}
