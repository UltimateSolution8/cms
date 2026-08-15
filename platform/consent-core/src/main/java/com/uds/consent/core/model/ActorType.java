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
    REGULATOR
}
