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
 * <p><strong>Null is not the same as empty.</strong> Several fields below can be either, and the
 * difference is the whole point of having them. {@code null} means nobody ever recorded the fact —
 * no processing activity describes this purpose, no vendor is mapped to it. An empty list means it
 * was recorded as none. A receipt that answered "no recipients" where the truth is "nobody has
 * written down who the recipients are" would be a false statement issued to a data principal under
 * the platform's name, and worse than an omission because it looks like an answer.
 *
 * @param schemaVersion   which shape of receipt this is. TS 27560's conformance indicator, and the
 *                        field that lets a holder of a receipt issued years ago know which fields
 *                        to expect. Absent on receipts issued before the platform carried it, which
 *                        is itself the correct reading of a missing version
 * @param receiptId       stable identifier the subject can quote in a grievance
 * @param consentRecordId the record this receipt attests to, stable across every receipt issued to
 *                        the same principal for the same fiduciary. {@code receiptId} identifies
 *                        the document; this identifies the thing the document is about, which is
 *                        what an auditor holding two receipts needs in order to know they describe
 *                        one consent history rather than two
 * @param consentManagerRegistrationId
 *                        the Board registration of the Consent Manager the consent arrived through,
 *                        or null for a first-party capture. On the receipt because the principal
 *                        chose an intermediary and is entitled to see, on the artefact they hold,
 *                        that the fiduciary knows which one
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
 * @param parentalVerification
 *                        how the parent or lawful guardian was verified, where this consent was
 *                        given on a child's behalf, or null where it was not. Named as a route
 *                        rather than described in full: the hashed reference behind it stays in
 *                        the ledger and is deliberately not reproduced here, because a receipt is
 *                        a document that gets emailed and printed, and putting a third party's
 *                        identifier on it would add nothing the reader can use. What the reader
 *                        can use is the knowledge that a check was made and which kind
 */
public record ConsentReceipt(
        String schemaVersion,
        String receiptId,
        String consentRecordId,
        String consentManagerRegistrationId,
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
        String evidenceHash,
        GuardianVerificationMethod parentalVerification) {

    /**
     * The shape this platform issues today.
     *
     * <p>Deliberately not the bare string "27560", and deliberately no longer the bare
     * {@code iso-27560:2023} this field carried until Phase 15. That value named none of the four
     * profiles the specification's conformance indicator is meant to reference, so the one field
     * whose job is to tell a holder which fields to expect resolved to nothing checkable.
     *
     * <p>What {@code -receipt-subset} claims, precisely: conformance to the <strong>receipt
     * metadata</strong> structure — schema version, receipt identifier and the associated consent
     * record — plus a documented subset of the record's own fields. §9 of the W3C DPVCG rendering
     * (<em>Consent Records and Receipts as per ISO/IEC TS 27560:2023 using DPV</em>, Final
     * Community Group Report 15 February 2026, accessed 17 August 2026) states that a receipt "may
     * contain all, some, or no information from the consent record"; the suffix says which of those
     * readings this is.
     *
     * <p>It is <strong>not</strong> the full-record reading, under which the record's mandatory
     * fields would also be mandatory here. {@code recipients} and {@code retentionPeriod} are
     * deliberately nullable — see the class javadoc, and note that the reasoning there is better
     * privacy engineering than the structure's own {@code 1..*}, which forces a guess — and party
     * postal addresses, typed party roles, enumerated rights, consent type and expression method
     * are not emitted at all.
     *
     * <p>The ISO text is paywalled and this project does not hold it, so every claim above is
     * against the free rendering and named as such. The field-by-field position, and the nine
     * questions the paid text would settle, are in {@code docs/standards/iso-27560-consent-records.md}.
     */
    public static final String SCHEMA_VERSION =
            "uds-consent-receipt/1;iso-27560:2023-receipt-subset";

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
     * @param withdrawnAt    when it was withdrawn, or null where it has not been. On the receipt
     *                       because a document reading {@code "status": "WITHDRAWN"} beside a grant
     *                       date and no withdrawal date does not tell a person the one date a
     *                       grievance turns on. The ISO/IEC TS 27560 record structure carries a
     *                       whole Events *history* per purpose; this is the single event from it
     *                       that a data principal actually needs, and the rest is recorded as a gap
     *                       in {@code docs/standards/iso-27560-consent-records.md} rather than
     *                       half-built here
     * @param recipients     who else receives the data for this purpose — the processors, joint
     *                       controllers and recipients on record, together with the third parties
     *                       named on the processing activity. Null where the entity has recorded
     *                       neither; empty where it has recorded that there are none
     * @param crossBorderCountries
     *                       countries outside India the data reaches for this purpose. Null where
     *                       nothing is on record. DPDP §16 lets the Government restrict transfers
     *                       to named countries, so this is the field that tells a principal whether
     *                       a future restriction touches them
     * @param retentionPeriod how long the data is kept, as an ISO-8601 duration ({@code P365D}).
     *                       Null where no retention rule is recorded — which is a compliance gap in
     *                       the RoPA and is reported as one there, rather than being papered over
     *                       here with a guess
     * @param sensitive      whether the purpose touches data the registry marks sensitive. Null
     *                       where the purpose is no longer in the registry, which happens on a
     *                       receipt reproduced years after a purpose was retired
     */
    public record Entry(
            String purposeCode,
            int purposeVersion,
            String purposeName,
            List<String> dataCategories,
            LegalBasis legalBasis,
            ConsentStatus status,
            Instant grantedAt,
            Instant expiresAt,
            Instant withdrawnAt,
            List<String> recipients,
            List<String> crossBorderCountries,
            String retentionPeriod,
            Boolean sensitive) {

        public Entry {
            dataCategories = dataCategories == null ? List.of() : List.copyOf(dataCategories);
            // Not defaulted to empty, unlike dataCategories above. Every purpose has data
            // categories or it would not be a purpose; not every one has a recorded recipient, and
            // collapsing "unrecorded" into "none" here would destroy the distinction at the one
            // point where it is still visible.
            recipients = recipients == null ? null : List.copyOf(recipients);
            crossBorderCountries = crossBorderCountries == null
                    ? null : List.copyOf(crossBorderCountries);
        }
    }
}
