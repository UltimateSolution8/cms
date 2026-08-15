package com.uds.consent.core.model;

/**
 * What a data principal is asking for.
 *
 * <p>Deliberately not one generic "DSR" type. The statutory clocks differ, the fulfilment work
 * differs, and a platform that records only "a request came in" cannot tell an auditor whether the
 * group met the deadline that actually applied.
 */
public enum RightsRequestType {

    /** DPDP s.11 — a summary of the personal data being processed and to whom it was shared. */
    ACCESS,

    /** DPDP s.12 — correction of inaccurate or misleading data. */
    CORRECTION,

    /** DPDP s.12 — completion of incomplete data, and updating of data gone stale. */
    COMPLETION,

    /**
     * DPDP s.12 — erasure.
     *
     * <p>Not automatic. Data retained under a legal obligation survives an erasure request, and
     * the platform records the refusal and its ground rather than pretending the request was met.
     */
    ERASURE,

    /** DPDP s.14 — nomination of someone to exercise rights on the principal's behalf. */
    NOMINATION,

    /**
     * DPDP s.13 — grievance redressal.
     *
     * <p>Kept separate from the substantive rights above because it is the one a principal escalates
     * to the Board when it goes unanswered, which makes its clock the one that turns into an
     * enforcement action rather than a complaint.
     */
    GRIEVANCE,

    /**
     * Withdrawal of consent handled as a request rather than through a preference centre.
     *
     * <p>Recorded here for the SLA, but fulfilment still means appending a withdrawal event to the
     * ledger. Nothing about arriving by email makes it a different fact.
     */
    CONSENT_WITHDRAWAL,

    /** GDPR Art. 20 — portability. No DPDP equivalent; applies to the UK and EU flows. */
    PORTABILITY,

    /** CCPA/CPRA — opt out of sale or sharing. Applies to the California surface only. */
    OPT_OUT_OF_SALE
}
