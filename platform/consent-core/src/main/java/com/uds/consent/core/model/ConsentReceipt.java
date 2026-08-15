package com.uds.consent.core.model;

import java.time.Instant;
import java.util.List;

/**
 * The individual-facing record of a consent transaction, shaped along ISO/IEC TS 27560:2023 and
 * descended from the Kantara Consent Receipt specification.
 *
 * <p>This is what the subject receives and what the platform can reproduce years later. It is
 * deliberately not the same object as {@link ConsentArtefact}: the artefact is internal state,
 * the receipt is a statement issued to a person.
 *
 * <p>Note for anyone reading the earlier research drafts: consent receipts are a Kantara/ISO
 * lineage. IAB's Transparency and Consent Framework is an adtech signalling protocol and is not
 * the same thing — it can only ever be an adapter at the edge, never the record format.
 *
 * @param receiptId       stable identifier the subject can quote in a grievance
 * @param issuedAt        when the receipt was generated
 * @param fiduciaryName   the UDS entity's registered name
 * @param fiduciaryId     internal entity id
 * @param dpoContact      published contact point for the Data Protection Officer
 * @param subjectId       privacy-minimal subject reference
 * @param jurisdiction    jurisdiction whose rules governed the capture
 * @param languageTag     language the notice was rendered in
 * @param noticeId        notice identifier
 * @param noticeVersion   exact notice version rendered
 * @param entries         one entry per purpose; never collapsed into a single line
 * @param withdrawalUri   specific link at which consent may be withdrawn
 * @param rightsUri       specific link at which rights may be exercised
 * @param grievanceUri    specific link at which a complaint may be made
 * @param evidenceHash    hash of the ledger event this receipt attests to
 */
public record ConsentReceipt(
        String receiptId,
        Instant issuedAt,
        String fiduciaryName,
        String fiduciaryId,
        String dpoContact,
        String subjectId,
        Jurisdiction jurisdiction,
        String languageTag,
        String noticeId,
        Integer noticeVersion,
        List<Entry> entries,
        String withdrawalUri,
        String rightsUri,
        String grievanceUri,
        String evidenceHash) {

    public ConsentReceipt {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /**
     * One purpose within a receipt.
     *
     * @param purposeCode    registry code
     * @param purposeVersion version consented against
     * @param purposeName    plain-language name as shown to the subject
     * @param dataCategories categories of personal data used for this purpose
     * @param legalBasis     basis relied on
     * @param status         state at time of issue
     * @param grantedAt      when consent was given, if it was
     * @param expiresAt      when it lapses, if it does
     */
    public record Entry(
            String purposeCode,
            int purposeVersion,
            String purposeName,
            List<String> dataCategories,
            LegalBasis legalBasis,
            ConsentStatus status,
            Instant grantedAt,
            Instant expiresAt) {

        public Entry {
            dataCategories = dataCategories == null ? List.of() : List.copyOf(dataCategories);
        }
    }
}
