package com.uds.consent.core.model;

/**
 * The kinds of identifier that can resolve to a subject.
 *
 * <p>The ledger stores these hashed, never in the clear. It needs to answer "did subject X
 * consent to purpose Y" and nothing more; it must not accumulate into a second master customer
 * database sitting beside the CRM.
 */
public enum IdentifierType {

    /** Normalised to E.164 before hashing. */
    PHONE,

    /** Lower-cased and trimmed before hashing. */
    EMAIL,

    /** Workforce identifier, scoped to the employing entity. */
    EMPLOYEE_ID,

    /** Candidate reference in a background-verification workflow. */
    CANDIDATE_ID,

    /** Device or installation identifier reported by a field application. */
    DEVICE_ID,

    /** CRM or client-supplied key, scoped to the owning entity. */
    EXTERNAL_ID
}
