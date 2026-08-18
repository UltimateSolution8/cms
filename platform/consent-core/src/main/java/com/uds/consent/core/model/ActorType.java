package com.uds.consent.core.model;

/**
 * Who caused a ledger event. Recorded so that an auditor can distinguish a withdrawal the
 * subject made from one an administrator made on their behalf — and so that administrative
 * action on consent records is itself visible.
 */
public enum ActorType {

    /** The data principal, acting directly. */
    SUBJECT,

    /** A parent or lawful guardian acting for a subject under eighteen (DPDP s.9). */
    PARENT_GUARDIAN,

    /** A UDS employee acting through the compliance console. Always attributable to a user id. */
    ADMIN,

    /** An agent capturing consent on a call, attributable to an agent id. */
    AGENT,

    /** A scheduled platform job — the expiry sweeper, the blast-radius invalidator. */
    SYSTEM,

    /** A bulk load. Requires a provenance record. */
    IMPORT,

    /** Action taken in response to a direction from a regulator or the Data Protection Board. */
    REGULATOR,

    /**
     * A Consent Manager registered with the Board, relaying what the principal did there.
     *
     * <p>A first-class actor rather than an attribute on the event, because the provenance of a
     * consent is precisely what the evidence plane exists to record: "the principal did this at
     * their Consent Manager and we were told" is a different fact from "the principal did this on
     * our own form", and only one of them can be evidenced by anything UDS holds. An attribute map
     * is where facts go to become unqueryable.
     *
     * <p>The actor id carries the Board registration number, so an auditor can go from an event to
     * the register entry that made the relay legitimate. See {@code V14__consent_manager.sql}.
     */
    CONSENT_MANAGER
}
