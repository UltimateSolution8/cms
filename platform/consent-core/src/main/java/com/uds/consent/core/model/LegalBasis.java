package com.uds.consent.core.model;

/**
 * The lawful ground a processing activity rests on, in a given jurisdiction.
 *
 * <p>A purpose does not have <em>one</em> legal basis — it has one per jurisdiction. Sending a
 * marketing email to a UK prospect may rest on legitimate interest; the same email to an Indian
 * prospect needs consent. That mapping lives in the purpose registry, not here.
 */
public enum LegalBasis {

    /** DPDP s.6 / GDPR Art.6(1)(a). Free, specific, informed, unconditional, unambiguous. */
    CONSENT(true, false),

    /**
     * TRAI TCCCPR: consent implied by an existing contractual relationship, valid only for the
     * duration of that relationship. Requires a consent record carrying the relationship's end
     * date, so it is treated as a consent basis with a contract-lifetime expiry.
     */
    INFERRED_CONSENT(true, false),

    /**
     * DPDP s.7(i) — employment purposes, and safeguarding the employer from loss or liability.
     * No consent needed. This is why roughly 76,000 UDS workforce records are a notice,
     * retention and rights problem rather than a consent problem.
     */
    LEGITIMATE_USE_EMPLOYMENT(false, false),

    /** DPDP s.7(a) — data voluntarily provided for a purpose the subject has not objected to. */
    LEGITIMATE_USE_VOLUNTARY(false, false),

    /** GDPR Art.6(1)(b) — necessary to perform a contract with the subject. */
    CONTRACT_PERFORMANCE(false, false),

    /** GDPR Art.6(1)(c) / DPDP s.7(b) — compliance with law. Survives withdrawal. */
    LEGAL_OBLIGATION(false, false),

    /**
     * GDPR Art.6(1)(f). Permits processing until the subject objects, and only with a documented
     * Legitimate Interests Assessment on file. Has no equivalent under DPDP — do not map an
     * Indian purpose to this basis.
     */
    LEGITIMATE_INTEREST(false, true),

    /** GDPR Art.6(1)(d) / DPDP s.7(d) — medical emergency, threat to life. */
    VITAL_INTEREST(false, false),

    /** GDPR Art.6(1)(e) / DPDP s.7(e)-(f) — state function, public interest. */
    PUBLIC_INTEREST(false, false);

    private final boolean requiresConsentRecord;
    private final boolean honoursObjection;

    LegalBasis(boolean requiresConsentRecord, boolean honoursObjection) {
        this.requiresConsentRecord = requiresConsentRecord;
        this.honoursObjection = honoursObjection;
    }

    /**
     * Whether processing on this basis needs a consent artefact in the ledger. Where this is
     * false the decision engine allows without one — but still enforces suppression and
     * retention.
     */
    public boolean requiresConsentRecord() {
        return requiresConsentRecord;
    }

    /**
     * Whether an opt-out defeats this basis even though no consent was required to begin with.
     * True for legitimate interest: the subject's objection ends it.
     */
    public boolean honoursObjection() {
        return honoursObjection;
    }
}
