package com.uds.consent.core.decision;

/**
 * The answer the enforcement plane gives to "may I do this, to this person, right now".
 *
 * <p>Deliberately binary. Callers must not be handed a maybe, because in practice a maybe is
 * resolved by whoever is under the most delivery pressure.
 */
public enum DecisionOutcome {

    /** Processing is permitted. Any accompanying obligations must still be honoured. */
    ALLOW,

    /** Processing is not permitted. The reason code says why, and is safe to log. */
    DENY
}
