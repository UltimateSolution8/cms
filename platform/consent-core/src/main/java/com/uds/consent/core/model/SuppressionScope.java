package com.uds.consent.core.model;

/**
 * How far a suppression entry reaches.
 *
 * <p>Scope matters because Denave and Athena run campaigns on behalf of clients. A subject who
 * opts out of one client's campaign has not opted out of every UDS communication, and a subject
 * on the national registry has opted out of all of them.
 */
public enum SuppressionScope {

    /** Applies across every entity and every campaign. Statutory registries land here. */
    GLOBAL,

    /** Applies to one UDS entity. */
    ENTITY,

    /** Applies to work carried out for one client of an entity. */
    CLIENT,

    /** Applies to a single campaign. */
    CAMPAIGN
}
