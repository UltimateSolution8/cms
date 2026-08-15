package com.uds.consent.core.model;

/**
 * Where a contact record came from.
 *
 * <p>A controlled vocabulary rather than free text, for the same reason purposes are: the whole
 * value of the provenance store is the quarantine report grouped by source, and a report grouped
 * by free text degrades into thirty spellings of "purchased list" within a quarter. A source that
 * does not fit belongs here as a new constant, discussed, and not as a typo nobody notices.
 *
 * <p>The ordering below is roughly descending confidence, which is also roughly the order in which
 * a triage exercise should work through the backlog.
 */
public enum ProvenanceSourceType {

    /** Collected on a UDS surface by the subject themselves. The consent event is the evidence. */
    DIRECT_COLLECTION(false),

    /**
     * Supplied by a client under contract — Denave's normal mode for campaign work.
     *
     * <p>Substantiable in principle: the contract and the client's own consent evidence exist.
     * Whether they can actually be produced on request is the question triage has to answer.
     */
    CLIENT_SUPPLIED(true),

    /** Given by an existing contact who named someone else. The named person did not consent. */
    REFERRAL(true),

    /** Collected at an event or trade show, usually against a paper or badge-scan record. */
    EVENT_OR_TRADESHOW(true),

    /** Bought from a data vendor. The record most likely to fail substantiation. */
    PURCHASED_LIST(true),

    /** Fields added to an existing record by a third-party enrichment service. */
    APPENDED(true),

    /** Taken from a published source — a company register, a public directory. */
    PUBLIC_SOURCE(true),

    /** Harvested from a website. Rarely substantiable, and named plainly so it cannot hide. */
    WEB_SCRAPED(true),

    /**
     * In the database before anyone tracked origin.
     *
     * <p>Exists so that a migration does not have to guess. A record here is not a record with an
     * unknown source that might turn out fine — it is a record the group cannot currently defend,
     * and it should be treated as the largest line item in the remediation budget.
     */
    LEGACY_UNKNOWN(true);

    private final boolean requiresSubstantiation;

    ProvenanceSourceType(boolean requiresSubstantiation) {
        this.requiresSubstantiation = requiresSubstantiation;
    }

    /**
     * Whether a record from this source has to be affirmatively substantiated before use.
     *
     * <p>Only {@link #DIRECT_COLLECTION} does not, because for it the consent event in the ledger
     * <em>is</em> the provenance and asking for a second record would be asking the same question
     * twice.
     */
    public boolean requiresSubstantiation() {
        return requiresSubstantiation;
    }
}
