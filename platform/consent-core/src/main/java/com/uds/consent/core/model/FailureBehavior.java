package com.uds.consent.core.model;

/**
 * What the enforcement plane does for a purpose when it cannot reach a confident answer —
 * the ledger is unreachable, a snapshot has expired, or the subject is unknown.
 *
 * <p>This is configured per purpose and signed off by legal. It is deliberately not a per-service
 * runtime choice, because the failure mode of leaving it to callers is that every team quietly
 * picks fail-open under incident pressure.
 */
public enum FailureBehavior {

    /**
     * Permit processing when consent state is indeterminate. Legitimate only for purposes that
     * do not themselves require consent — security, authentication, fraud prevention — and only
     * under written policy.
     */
    FAIL_OPEN,

    /**
     * Deny processing when consent state is indeterminate. The default, and mandatory for
     * marketing, analytics, profiling and location purposes.
     */
    FAIL_CLOSED
}
