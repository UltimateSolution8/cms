package com.uds.consent.service.it;

import com.uds.consent.core.crypto.CanonicalJson;
import com.uds.consent.core.crypto.Hashes;
import com.uds.consent.core.model.ActorType;
import com.uds.consent.core.model.CaptureMethod;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.ConsentReceipt;
import com.uds.consent.core.model.ConsentStatus;
import com.uds.consent.core.model.ExpiryPolicy;
import com.uds.consent.core.model.FailureBehavior;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.LegalBasis;
import com.uds.consent.ledger.store.ConsentManagerStore;
import com.uds.consent.ledger.store.ProcessingActivityStore;
import com.uds.consent.ledger.store.PurposeRegistryStore;
import com.uds.consent.ledger.store.ReceiptStore;
import com.uds.consent.ledger.store.VendorStore;
import com.uds.consent.policy.capture.CaptureSubmission;
import com.uds.consent.service.ConsentCaptureService;
import com.uds.consent.service.ReceiptService;
import com.uds.consent.service.adapter.CachingPurposeCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;


import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A receipt number worth quoting.
 *
 * <p>{@code ConsentReceipt}'s javadoc has always called {@code receiptId} "a stable identifier the
 * subject can quote in a grievance". It was neither stable nor lookupable: {@code issue} minted a
 * fresh UUID per call and persisted nothing, so two requests a second apart produced two
 * identifiers for the same facts and neither named anything. A principal reading their receipt
 * number down the phone to the grievance officer was reading out a number that existed nowhere.
 *
 * <p>The assertion that matters most is {@link #aReproducedReceiptIsByteIdentical}. Fetching a
 * receipt must return what was issued, not what the same query would answer today — the purpose
 * registry moves, the DPO contact changes, and a consent live in March has since expired. Any of
 * those would quietly contradict the copy the subject is holding.
 */
class ReceiptIT extends PostgresIntegrationTest {

    private static final String ENTITY = "DENAVE_IN";
    private static final String APP = "DENAVE_WEB";
    private static final String NOTICE = "NOTICE_DENAVE_B2B";

    @Autowired
    private ReceiptService receipts;

    @Autowired
    private ConsentCaptureService capture;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private VendorStore vendors;

    @Autowired
    private ProcessingActivityStore activities;

    @Autowired
    private ConsentManagerStore managers;

    @Autowired
    private PurposeRegistryStore registry;

    @Autowired
    private CachingPurposeCatalog catalogue;

    @Test
    @DisplayName("an issued receipt can be fetched by its own number")
    void theIdentifierNamesSomething() {
        String subject = grant("MKT_OUTBOUND_CALL");
        ConsentReceipt issued = receipts.issue(ENTITY, subject, Instant.now());

        assertThat(issued.receiptId()).isNotBlank();
        assertThat(receipts.reproduce(issued.receiptId()).receiptId())
                .isEqualTo(issued.receiptId());
    }

    @Test
    @DisplayName("a reproduced receipt is byte-identical to the one issued")
    void aReproducedReceiptIsByteIdentical() {
        String subject = grant("MKT_OUTBOUND_CALL");
        ConsentReceipt issued = receipts.issue(ENTITY, subject, Instant.now());

        // Canonicalised by the same code the ledger hashes its chain with, so "identical" here
        // means what it means for an event: the same bytes, verifiable by the same path.
        String issuedJson = CanonicalJson.serialize(issued);
        String fetchedJson = CanonicalJson.serialize(receipts.reproduce(issued.receiptId()));

        assertThat(fetchedJson).isEqualTo(issuedJson);

        ReceiptStore.StoredReceipt stored = receipts.findStored(issued.receiptId());
        assertThat(stored.payloadHash()).isEqualTo(Hashes.sha256Hex(stored.payload()));
    }

    @Test
    @DisplayName("a receipt does not change when the consent it describes does")
    void theDocumentIsNotRewrittenByLaterEvents() {
        // The reason the payload is stored rather than regenerated, stated as a test. A subject
        // holding a printed receipt that says GRANTED must not find the platform's copy quietly
        // reading WITHDRAWN — the receipt is a statement about a moment, and the withdrawal is a
        // later fact with its own evidence.
        String subject = grant("MKT_OUTBOUND_CALL");
        ConsentReceipt issued = receipts.issue(ENTITY, subject, Instant.now());

        assertThat(issued.entries()).anySatisfy(entry ->
                assertThat(entry.status()).isEqualTo(ConsentStatus.GRANTED));

        capture.withdraw(ENTITY, subject, List.of("MKT_OUTBOUND_CALL"), Channel.WEB, APP,
                ActorType.SUBJECT, subject, Jurisdiction.IN, Instant.now(), "wd-" + subject,
                "changed their mind");

        ConsentReceipt fetched = receipts.reproduce(issued.receiptId());
        assertThat(fetched.entries()).anySatisfy(entry ->
                assertThat(entry.status()).isEqualTo(ConsentStatus.GRANTED));

        // And a receipt issued now correctly says otherwise. Both documents are true about their
        // own instants, which is what makes either of them evidence.
        assertThat(receipts.issue(ENTITY, subject, Instant.now()).entries())
                .anySatisfy(entry -> assertThat(entry.status())
                        .isEqualTo(ConsentStatus.WITHDRAWN));
    }

    @Test
    @DisplayName("every purpose gets its own entry; nothing is collapsed into one line")
    void purposesAreItemised() {
        String subject = grant("MKT_OUTBOUND_CALL", "MKT_OUTBOUND_EMAIL");
        ConsentReceipt issued = receipts.issue(ENTITY, subject, Instant.now());

        // A receipt that says "you agreed to our terms" tells the subject nothing and proves
        // nothing. Itemisation is the whole substance of the ISO 27560 shape.
        assertThat(issued.entries()).hasSizeGreaterThanOrEqualTo(2)
                .extracting(ConsentReceipt.Entry::purposeCode)
                .contains("MKT_OUTBOUND_CALL", "MKT_OUTBOUND_EMAIL");
        assertThat(issued.entries()).allSatisfy(entry -> {
            assertThat(entry.purposeName()).isNotBlank();
            assertThat(entry.legalBasis()).isNotNull();
            assertThat(entry.purposeVersion()).isPositive();
        });
    }

    @Test
    @DisplayName("the receipt carries the Rule 3 links and the notice it was given against")
    void ruleThreeLinksTravelWithTheReceipt() {
        String subject = grant("MKT_OUTBOUND_CALL");
        ConsentReceipt issued = receipts.issue(ENTITY, subject, Instant.now());

        assertThat(issued.noticeId()).isEqualTo(NOTICE);
        assertThat(issued.noticeVersion()).isNotNull();
        // Specific links rather than a general contact page, which is what Rule 3 requires and
        // what makes the receipt actionable rather than decorative.
        assertThat(issued.withdrawalUri()).startsWith("https://");
        assertThat(issued.rightsUri()).startsWith("https://");
        assertThat(issued.grievanceUri()).startsWith("https://");
        // Ties the document back into the hash chain, so a receipt is not a free-standing claim.
        assertThat(issued.evidenceHash()).isNotBlank();
    }

    @Test
    @DisplayName("an unknown receipt number is a 404, not an empty 200")
    void anUnknownNumberIsNotFound() {
        assertThatThrownBy(() -> receipts.reproduce("RCPT-" + UUID.randomUUID()))
                .isInstanceOf(ReceiptService.ReceiptNotFoundException.class);

        assertThat(rest.withBasicAuth("compliance-console", "admin-secret")
                .getForEntity("/v1/receipts/does-not-exist", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a stored receipt cannot be edited or deleted")
    void receiptsAreAppendOnly() {
        String subject = grant("MKT_OUTBOUND_CALL");
        ConsentReceipt issued = receipts.issue(ENTITY, subject, Instant.now());

        // A receipt the platform can quietly amend is a receipt whose number is worth nothing to
        // the person holding it.
        assertThatThrownBy(() -> jdbc.update(
                "update consent_receipt set payload = '{}' where receipt_id = ?",
                issued.receiptId()))
                .rootCause().hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbc.update(
                "delete from consent_receipt where receipt_id = ?", issued.receiptId()))
                .rootCause().hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("receipts are listed newest first for a subject")
    void receiptsAreListedForASubject() {
        String subject = grant("MKT_OUTBOUND_CALL");
        receipts.issue(ENTITY, subject, Instant.now().minus(1, ChronoUnit.HOURS));
        ConsentReceipt latest = receipts.issue(ENTITY, subject, Instant.now());

        List<ReceiptStore.StoredReceipt> listed = receipts.forSubject(ENTITY, subject, 10);
        assertThat(listed).hasSize(2);
        assertThat(listed.getFirst().receiptId()).isEqualTo(latest.receiptId());
    }

    @Test
    @DisplayName("fetching a receipt needs a credential")
    void receiptsAreNotPublic() {
        String subject = grant("MKT_OUTBOUND_CALL");
        ConsentReceipt issued = receipts.issue(ENTITY, subject, Instant.now());
        String path = "/v1/receipts/" + issued.receiptId();

        // A receipt names a subject and every purpose they agreed to. An unauthenticated endpoint
        // keyed on an identifier would be a disclosure channel, however long the identifier is.
        assertThat(rest.getForEntity(path, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.withBasicAuth("denave-web", "capture-secret")
                .getForEntity(path, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("the verification endpoint returns the payload and its hash")
    void aHolderCanCheckTheirCopy() {
        String subject = grant("MKT_OUTBOUND_CALL");
        ConsentReceipt issued = receipts.issue(ENTITY, subject, Instant.now());

        String body = rest.withBasicAuth("compliance-console", "admin-secret")
                .getForObject("/v1/receipts/" + issued.receiptId() + "/verification", String.class);

        assertThat(body).contains("payloadHash").contains("SHA-256")
                .contains(issued.receiptId());
    }

    @Test
    @DisplayName("the receipt states who receives the data, where it goes and how long it is kept")
    void theReceiptCarriesTheTwentySevenFiveSixtyFields() {
        // The facts were all in the platform already — the vendor registry knows the processors,
        // the RoPA knows the retention rule and the transfers, the purpose registry knows what is
        // sensitive — and none of them reached the one artefact the data principal actually holds.
        // A receipt naming a purpose but not who the data goes to answers the easy half of the
        // question the subject is asking.
        // MKT_OUTBOUND_SMS rather than the call purpose every other suite reaches for. RopaIT
        // registers vendors and activities against MKT_OUTBOUND_CALL, and a receipt test asserting
        // an exact recipient list on a shared purpose passes alone and fails in the full build —
        // which is the worst way to find out, because the suite that broke it is not the one that
        // fails.
        String purpose = "MKT_OUTBOUND_SMS";
        String vendorId = "rc-vendor-" + UUID.randomUUID();
        vendors.upsert(new VendorStore.Vendor(vendorId, ENTITY, "Athena Dialer Ops", "PROCESSOR",
                List.of("IN", "SG"), "DPA-RC-1", LocalDate.parse("2026-01-05"), true));
        vendors.setPurposes(vendorId, List.of(purpose));

        activities.create(new ProcessingActivityStore.Activity(null, ENTITY,
                "Outbound B2B calling (receipt test)", "Telemarketing to business contacts",
                purpose, "Athena", List.of("CONTACT"), List.of("Client sales teams"),
                List.of("US"), 365, "Contractual record-keeping", "privacy@uds.example",
                Instant.now()));

        String subject = grant(purpose);
        ConsentReceipt issued = receipts.issue(ENTITY, subject, Instant.now());

        assertThat(issued.schemaVersion()).isEqualTo(ConsentReceipt.SCHEMA_VERSION);
        // Stable across documents, unlike receiptId. Two receipts for one principal must be
        // recognisable as describing one consent history.
        assertThat(issued.consentRecordId()).isEqualTo(ENTITY + ":" + subject);
        assertThat(receipts.issue(ENTITY, subject, Instant.now()).consentRecordId())
                .isEqualTo(issued.consentRecordId());

        assertThat(issued.entries())
                .filteredOn(entry -> purpose.equals(entry.purposeCode()))
                .singleElement()
                .satisfies(entry -> {
                    // The vendor's role travels with the name: "shared with Athena" and "shared
                    // with Athena, who is a processor" are different statements to a principal.
                    assertThat(entry.recipients())
                            .contains("Athena Dialer Ops (PROCESSOR)", "Client sales teams");
                    // SG from the vendor, US from the activity. IN is filtered out — a domestic
                    // processor is not a transfer, and saying so would tell the principal their
                    // data left the country when it did not.
                    assertThat(entry.crossBorderCountries())
                            .containsExactly("SG", "US");
                    assertThat(entry.retentionPeriod()).isEqualTo("P365D");
                    assertThat(entry.sensitive()).isNotNull();
                });
    }

    @Test
    @DisplayName("a fact nobody recorded is absent, not asserted to be none")
    void unrecordedFactsAreNotAssertedAsAbsences() {
        // The distinction this whole shape turns on. "No recipients" and "nobody has written down
        // who the recipients are" are different claims, and only one of them is true here. A
        // receipt that made the first claim would be a false statement issued to a data principal
        // under the platform's name — worse than an omission, because it looks like an answer.
        String purpose = "MKT_OUTBOUND_WHATSAPP";

        // Stated as a precondition rather than assumed. This test is only meaningful while nothing
        // has been recorded for this purpose, and if a future suite registers a vendor against it
        // the failure should say so here rather than surface as an unexplained null.
        assertThat(vendors.vendorsForPurpose(ENTITY, purpose))
                .withFailMessage("this test needs a purpose with nothing on record")
                .isEmpty();
        assertThat(activities.findForPurpose(ENTITY, purpose))
                .withFailMessage("this test needs a purpose with nothing on record")
                .isEmpty();

        String subject = grant(purpose);
        ConsentReceipt issued = receipts.issue(ENTITY, subject, Instant.now());

        assertThat(issued.entries())
                .filteredOn(entry -> purpose.equals(entry.purposeCode()))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.recipients()).isNull();
                    assertThat(entry.crossBorderCountries()).isNull();
                    assertThat(entry.retentionPeriod()).isNull();
                });

        // And null survives the round trip as null. Serialising it as [] on the way out would
        // reintroduce exactly the false claim, one layer further down where nobody would look.
        assertThat(receipts.reproduce(issued.receiptId()).entries())
                .filteredOn(entry -> purpose.equals(entry.purposeCode()))
                .singleElement()
                .satisfies(entry -> assertThat(entry.recipients()).isNull());
    }

    @Test
    @DisplayName("a receipt issued before these fields existed still reproduces")
    void aPreChangeReceiptStillReproduces() {
        // The one regression this change could cause, pinned rather than reasoned about. Receipts
        // are stored as canonical payloads and reproduced by parsing them, so every receipt issued
        // before today lacks schemaVersion, consentRecordId and the four new per-purpose fields. If
        // the deserialiser refused them, the platform would have silently destroyed its own
        // evidence for every principal who took consent before this deployment.
        //
        // The payload below is the exact shape ReceiptService produced last month, written by hand
        // rather than generated, because a fixture built from today's record would drift into the
        // new shape the moment somebody regenerated it and stop testing anything.
        String receiptId = UUID.randomUUID().toString();
        String subject = "rc-legacy-" + UUID.randomUUID();
        String payload = """
                {"dpoContact":"dpo@denave.example","entries":[{"dataCategories":["CONTACT"],\
                "expiresAt":null,"grantedAt":"2026-07-01T09:00:00Z","legalBasis":"CONSENT",\
                "purposeCode":"MKT_OUTBOUND_CALL","purposeName":"Outbound marketing calls",\
                "purposeVersion":1,"status":"GRANTED"}],"evidenceHash":"deadbeef",\
                "fiduciaryId":"DENAVE_IN","fiduciaryName":"Denave India Private Limited",\
                "grievanceUri":"https://denave.example/grievance","issuedAt":"2026-07-01T09:00:00Z",\
                "jurisdiction":"IN","languageTag":"en","noticeId":"NOTICE_DENAVE_B2B",\
                "noticeVersion":1,"receiptId":"%s","rightsUri":"https://denave.example/rights",\
                "subjectId":"%s","withdrawalUri":"https://denave.example/withdraw"}\
                """.formatted(receiptId, subject);

        jdbc.update("""
                insert into consent_receipt (receipt_id, entity_id, subject_id, issued_at, payload,
                                             payload_hash, evidence_hash, notice_id, notice_version,
                                             language_tag, purpose_count)
                values (?, ?, ?, timestamptz '2026-07-01T09:00:00Z', ?, ?, 'deadbeef',
                        'NOTICE_DENAVE_B2B', 1, 'en', 1)
                """, receiptId, ENTITY, subject, payload, Hashes.sha256Hex(payload));

        ConsentReceipt reproduced = receipts.reproduce(receiptId);

        assertThat(reproduced.receiptId()).isEqualTo(receiptId);
        // Absent, and absent is the correct reading: this document really was issued under no
        // declared schema, and claiming today's version for it would misdate the conformance.
        assertThat(reproduced.schemaVersion()).isNull();
        assertThat(reproduced.consentRecordId()).isNull();
        assertThat(reproduced.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.purposeCode()).isEqualTo("MKT_OUTBOUND_CALL");
            assertThat(entry.status()).isEqualTo(ConsentStatus.GRANTED);
            assertThat(entry.recipients()).isNull();
            assertThat(entry.sensitive()).isNull();
        });

        // And it is still byte-identical to what the subject was given, which is the property the
        // stored-payload design exists for.
        assertThat(receipts.findStored(receiptId).payload()).isEqualTo(payload);
    }

    @Test
    @DisplayName("a consent that arrived through a Consent Manager says so on the receipt")
    void theReceiptNamesTheConsentManager() {
        // Invisible on the artefact until now, which is the one place it most obviously belongs:
        // the principal chose an intermediary, and is entitled to see on their own copy that the
        // fiduciary knows which one. It is also what an auditor follows back to the register entry
        // that made the relay legitimate.
        String subject = grant("MKT_OUTBOUND_CALL");
        managers.link(ENTITY, subject, "CM-TEST-0001", "cm-ref-" + subject, Instant.now());

        assertThat(receipts.issue(ENTITY, subject, Instant.now())
                .consentManagerRegistrationId()).isEqualTo("CM-TEST-0001");

        // And a link the principal has since ended is not reported. They took their consent
        // management elsewhere; a receipt issued today naming the intermediary they left would
        // misdescribe the arrangement they are actually in.
        managers.unlink(ENTITY, subject, "CM-TEST-0001", Instant.now());
        assertThat(receipts.issue(ENTITY, subject, Instant.now())
                .consentManagerRegistrationId()).isNull();

        // A first-party capture leaves it null rather than empty, for the same reason every other
        // unknown on this document is null.
        assertThat(receipts.issue(ENTITY, grant("MKT_OUTBOUND_CALL"), Instant.now())
                .consentManagerRegistrationId()).isNull();
    }

    @Test
    @DisplayName("the receipt describes the version the subject agreed to, not the current one")
    void theReceiptDescribesTheVersionTheSubjectSaw() {
        // The defect this replaces was quiet and it was on the document a data principal is handed
        // and a regulator is shown. The receipt emitted artefact.purposeVersion() — the version the
        // subject actually consented to — and beside it rendered the name, data categories and
        // sensitivity flag from the cached current-version catalogue. So a receipt reproduced after
        // a purpose was re-published said "version 1" in one field and described version 2 in the
        // next.
        //
        // The direction of the error is what makes it worth a test rather than a note. A purpose is
        // re-published precisely because its scope changed, so the wrong description is
        // systematically the broader one: the subject's copy grows new data categories they never
        // agreed to, and the fiduciary's evidence appears to authorise them.
        String purposeCode = aPurposeOfItsOwn();
        String subject = grant(purposeCode);

        broadenTo(purposeCode, 2);

        ConsentReceipt receipt = receipts.issue(ENTITY, subject, Instant.now());

        assertThat(receipt.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.purposeVersion()).isEqualTo(1);
            assertThat(entry.purposeName())
                    .withFailMessage("the receipt named version 2 beside 'purposeVersion: 1'")
                    .isEqualTo("Receipt fixture purpose v1")
                    .isNotEqualTo("Receipt fixture purpose v2, broadened");
            assertThat(entry.dataCategories())
                    .withFailMessage("the receipt listed a category the subject never agreed to")
                    .containsExactly("CONTACT_BUSINESS");
            // The sharpest of the three. Sensitive data carries its own obligations, and a
            // receipt asserting the subject consented to sensitive processing when they did not
            // is evidence of a consent that was never taken.
            assertThat(entry.sensitive())
                    .withFailMessage("the receipt marked a v1 consent as touching sensitive data "
                            + "because v2 does")
                    .isFalse();
        });
    }

    @Test
    @DisplayName("withdrawing after a re-publish does not rewrite the version that was agreed to")
    void aWithdrawalDoesNotRestateTheTermsItEnds() {
        // The same defect as above, reached by the other door, and it survived that test because
        // that test never withdrew.
        //
        // ConsentCaptureService.withdraw stamped purposes.find(code).version() — the *current*
        // registry version — onto the WITHDRAWN event, and ArtefactProjector wrote it straight
        // onto the artefact. So a taxonomy change landing between the grant and the withdrawal
        // silently restated the agreement it was ending: grant v1, re-publish v2, withdraw, and
        // the receipt reads version 2 and renders v2's broadened wording as the terms the
        // principal accepted.
        //
        // Nothing catches this. The ledger still holds the GRANTED event at version 1, the chain
        // is untouched and every hash verifies — which is precisely the case CLAUDE.md warns is
        // invisible to an integrity sweep.
        //
        // A grant or a modification states terms and brings its own version. A withdrawal ends an
        // agreement without restating it, and so carries the version forward.
        String purposeCode = aPurposeOfItsOwn();
        String subject = grant(purposeCode);

        broadenTo(purposeCode, 2);

        capture.withdraw(ENTITY, subject, List.of(purposeCode), Channel.WEB, APP,
                ActorType.SUBJECT, subject, Jurisdiction.IN, Instant.now(), "wd-v-" + subject,
                "withdrawn after the purpose was broadened");

        ConsentReceipt receipt = receipts.issue(ENTITY, subject, Instant.now());

        assertThat(receipt.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.status()).isEqualTo(ConsentStatus.WITHDRAWN);
            assertThat(entry.purposeVersion())
                    .withFailMessage("the withdrawal rewrote the version the subject agreed to")
                    .isEqualTo(1);
            assertThat(entry.purposeName())
                    .withFailMessage("the receipt described v2's broadened scope as what was "
                            + "consented to, on the document the principal is handed")
                    .isEqualTo("Receipt fixture purpose v1");
            assertThat(entry.dataCategories()).containsExactly("CONTACT_BUSINESS");
            assertThat(entry.sensitive()).isFalse();
        });

        // The event is evidence in its own right, not only an input to the projection: a WITHDRAWN
        // row naming a version the subject never held is a false statement in an append-only table.
        Integer onTheEvent = jdbc.queryForObject(
                "select purpose_version from consent_event where entity_id = ? and subject_id = ? "
                        + "and purpose_code = ? and event_type = 'WITHDRAWN'",
                Integer.class, ENTITY, subject, purposeCode);
        assertThat(onTheEvent)
                .withFailMessage("the WITHDRAWN event recorded the registry's current version "
                        + "rather than the one being withdrawn from")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a refusal states the terms it refuses, and keeps the version it was shown")
    void aRefusalCarriesTheVersionItWasShown() {
        // The other half of the same switch, and the one the first fix got wrong.
        //
        // Carrying the version forward is right for WITHDRAWN, EXPIRED and INVALIDATED, which end
        // an agreement without restating it. The first implementation reached those three through
        // a `default` branch — which also caught DENIED and NOTICE_SERVED, and both of those DO
        // restate terms: ConsentCaptureService stamps purpose.version() on each.
        //
        // So a principal shown the broadened v2 notice and refusing it had their refusal recorded
        // against v1. The receipt then renders v1's narrower wording, its data categories and its
        // sensitive flag as what was declined — a document telling the person they refused
        // something other than what they were actually shown. Same defect as the withdrawal one,
        // one event type over, and every hash still verifies.
        String purposeCode = aPurposeOfItsOwn();
        String subject = grant(purposeCode);

        broadenTo(purposeCode, 2);
        decline(subject, purposeCode);

        ConsentReceipt receipt = receipts.issue(ENTITY, subject, Instant.now());

        assertThat(receipt.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.status()).isEqualTo(ConsentStatus.DENIED);
            assertThat(entry.purposeVersion())
                    .withFailMessage("the refusal was recorded against a version the subject was "
                            + "never shown")
                    .isEqualTo(2);
            assertThat(entry.purposeName()).isEqualTo("Receipt fixture purpose v2, broadened");
            // v2 adds GOVERNMENT_ID, which V3 marks sensitive. If the version were carried
            // forward these would read as v1's — the receipt would understate what was refused.
            assertThat(entry.dataCategories())
                    .containsExactlyInAnyOrder("CONTACT_BUSINESS", "GOVERNMENT_ID");
            assertThat(entry.sensitive()).isTrue();
        });

        Integer onTheEvent = jdbc.queryForObject(
                "select purpose_version from consent_event where entity_id = ? and subject_id = ? "
                        + "and purpose_code = ? and event_type = 'DENIED'",
                Integer.class, ENTITY, subject, purposeCode);
        assertThat(onTheEvent).isEqualTo(2);
    }

    @Test
    @DisplayName("a version that has gone leaves the purpose named and undescribed, not misdescribed")
    void aMissingVersionDegradesToThePurposeCodeAlone() {
        // loadVersion returns empty if that row is not there. Nothing in the platform deletes from
        // purpose_version — it is append-only by intent — but a DBA clearing "old" rows is exactly
        // the kind of thing that happens once, quietly, years in.
        //
        // The fallback is the part of this change most likely to be got wrong, because the
        // comfortable answer is to fall through to the current catalogue and the comfortable answer
        // reintroduces the defect in the one case nobody can check. Degrading to the purpose code
        // alone is honest: a receipt that names a purpose it cannot describe is visibly incomplete,
        // and a reader can tell that something is missing rather than believing something false.
        String purposeCode = aPurposeOfItsOwn();
        String subject = grant(purposeCode);
        broadenTo(purposeCode, 2);

        for (String child : List.of("purpose_data_category", "purpose_legal_basis",
                "purpose_channel")) {
            jdbc.update("delete from " + child + " where purpose_version_id in "
                    + "(select id from purpose_version where purpose_code = ? and version = 1)",
                    purposeCode);
        }
        jdbc.update("delete from purpose_version where purpose_code = ? and version = 1",
                purposeCode);

        ConsentReceipt receipt = receipts.issue(ENTITY, subject, Instant.now());

        assertThat(receipt.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.purposeVersion()).isEqualTo(1);
            // The code, not version 2's name.
            assertThat(entry.purposeName()).isEqualTo(purposeCode);
            assertThat(entry.dataCategories()).isEmpty();
            // Null, not false. "We do not know whether this touched sensitive data" and "it did
            // not" are different statements, and only one of them is true here.
            assertThat(entry.sensitive())
                    .withFailMessage("an unknown sensitivity was asserted as an absence")
                    .isNull();
        });
    }

    @Test
    @DisplayName("a receipt issued after a withdrawal still carries the notice and the Rule 3 links")
    void withdrawalDoesNotStripTheNoticeFromTheReceipt() {
        // Found by walking the platform by hand rather than by any suite here, and it is the case
        // that matters most: the receipt a data principal is most likely to ask for is the one
        // issued *after* they withdrew, and that was exactly the one arriving with no noticeId, no
        // language, no evidence hash and none of the three Rule 3 links.
        //
        // The cause is reasonable and the effect was not. ConsentArtefact is a projection of
        // current state; a withdrawal is served under no notice, so after one the artefact carries
        // no noticeId — and the receipt was reading the notice off the artefact. It now falls back
        // to the chain, which still holds what the person was actually shown.
        String subject = grant("MKT_OUTBOUND_CALL");

        ConsentReceipt before = receipts.issue(ENTITY, subject, Instant.now());
        assertThat(before.noticeId()).isEqualTo(NOTICE);

        capture.withdraw(ENTITY, subject, List.of("MKT_OUTBOUND_CALL"), Channel.WEB, APP,
                ActorType.SUBJECT, subject, Jurisdiction.IN, Instant.now(), "wd-" + subject,
                "asked to stop being called");

        ConsentReceipt after = receipts.issue(ENTITY, subject, Instant.now());

        assertThat(after.entries()).singleElement()
                .satisfies(entry -> assertThat(entry.status()).isEqualTo(ConsentStatus.WITHDRAWN));
        assertThat(after.noticeId())
                .withFailMessage("the receipt lost the notice the subject was served")
                .isEqualTo(before.noticeId());
        assertThat(after.noticeVersion()).isEqualTo(before.noticeVersion());
        assertThat(after.languageTag()).isEqualTo(before.languageTag());
        // Rule 3 asks for a specific link for each of these, not a general contact page. They are
        // read off the notice version, so losing the notice lost all three.
        assertThat(after.withdrawalUri()).isEqualTo(before.withdrawalUri()).isNotNull();
        assertThat(after.rightsUri()).isEqualTo(before.rightsUri()).isNotNull();
        assertThat(after.grievanceUri()).isEqualTo(before.grievanceUri()).isNotNull();
        assertThat(after.evidenceHash())
                .withFailMessage("the receipt lost its tie back to the chain")
                .isNotNull();
    }

    @Test
    @DisplayName("a withdrawn entry carries the date it was withdrawn")
    void aWithdrawnEntryNamesTheDateOfWithdrawal() {
        // The gap the ISO/IEC TS 27560 field review found, and the sharpest of them: the receipt
        // read "status": "WITHDRAWN" beside a grantedAt with no withdrawal date anywhere on the
        // document, so a person holding it could not tell when they withdrew. That is the one date
        // a grievance turns on.
        //
        // Asserted as the property rather than the mechanism, and the property is not "a date is
        // present": it is that the date is the one the *subject acted*. The withdrawal is therefore
        // made offline — occurredAt well before recordedAt, which is the whole reason those are two
        // fields — and the receipt must show the subject's instant, not the server's. A test that
        // withdrew at Instant.now() would pass against either behaviour and prove neither.
        // grant() captures at a fixed 2026-08-15T09:00:00Z, so this withdrawal genuinely occurred
        // after the grant and days before it was recorded — a field-force device syncing late.
        String subject = grant("MKT_OUTBOUND_CALL");
        Instant actedAt = Instant.parse("2026-08-16T14:30:00Z");

        ConsentReceipt granted = receipts.issue(ENTITY, subject, Instant.now());
        assertThat(granted.entries()).singleElement()
                .satisfies(entry -> assertThat(entry.withdrawnAt())
                        .withFailMessage("a live consent must not name a withdrawal date")
                        .isNull());

        capture.withdraw(ENTITY, subject, List.of("MKT_OUTBOUND_CALL"), Channel.WEB, APP,
                ActorType.SUBJECT, subject, Jurisdiction.IN, actedAt, "wd2-" + subject,
                "asked to stop being called");

        assertThat(receipts.issue(ENTITY, subject, Instant.now()).entries()).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.status()).isEqualTo(ConsentStatus.WITHDRAWN);
                    assertThat(entry.withdrawnAt())
                            .withFailMessage("a withdrawn entry does not say when it was withdrawn")
                            .isNotNull()
                            .isEqualTo(actedAt);
                });
    }

    @Test
    @DisplayName("a consent given again after a withdrawal carries no withdrawal date")
    void aRegrantedEntryDoesNotCarryTheOldWithdrawalDate() {
        // ArtefactProjector carries withdrawnAt forward across a later GRANTED — correct for a
        // projection, wrong for a document. Without this, a person who withdrew and then consented
        // again holds a receipt reading GRANTED with a withdrawal date on the same line, and
        // neither they nor the Board can tell from it whether consent was live in between.
        String subject = grant("MKT_OUTBOUND_CALL");

        capture.withdraw(ENTITY, subject, List.of("MKT_OUTBOUND_CALL"), Channel.WEB, APP,
                ActorType.SUBJECT, subject, Jurisdiction.IN, Instant.parse("2026-08-16T09:00:00Z"),
                "wd3-" + subject, "changed their mind");

        ConsentCaptureService.Result again = capture.capture(new CaptureSubmission(ENTITY, subject,
                Jurisdiction.IN, "en", Channel.WEB, APP, CaptureMethod.CHECKBOX_OPT_IN,
                ActorType.SUBJECT, subject, NOTICE, 1,
                List.of(CaptureSubmission.PurposeChoice.acceptedSeparately("MKT_OUTBOUND_CALL")),
                true, Instant.parse("2026-08-17T09:00:00Z"), "rg-" + subject, null, Map.of()));
        assertThat(again.isAccepted())
                .withFailMessage("re-grant rejected: %s", again.violations())
                .isTrue();

        assertThat(receipts.issue(ENTITY, subject, Instant.now()).entries()).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.status()).isEqualTo(ConsentStatus.GRANTED);
                    assertThat(entry.withdrawnAt())
                            .withFailMessage("a live consent still names a withdrawal date")
                            .isNull();
                });
    }

    @Test
    @DisplayName("the schema version says receipt-subset, and says it in the document")
    void theConformanceClaimIsPinnedToItsExactWording() {
        // Asserting the literal, not the constant. ReceiptIT already checks that the service copies
        // SCHEMA_VERSION onto the document — which can only fail if the copy breaks, never if the
        // claim itself becomes untrue. docs/openapi.json carries no value for this field, so the
        // contract test will not catch it either, and the invariant against restating it as a bare
        // iso-27560:2023 was guarded only by prose.
        //
        // The suffix is the whole point: recipients and retentionPeriod are 1..* in the record
        // structure and nullable here, deliberately, so the full-record reading of §9 of the DPV
        // rendering is not met and the document must not claim it.
        assertThat(ConsentReceipt.SCHEMA_VERSION)
                .isEqualTo("uds-consent-receipt/1;iso-27560:2023-receipt-subset");
        assertThat(receipts.issue(ENTITY, grant("MKT_OUTBOUND_CALL"), Instant.now()).schemaVersion())
                .isEqualTo("uds-consent-receipt/1;iso-27560:2023-receipt-subset");
    }

    /**
     * A purpose that exists only for one test, published at version 1.
     *
     * <p>Its own purpose rather than a second version of {@code MKT_OUTBOUND_CALL}, for two
     * reasons. The integration suites share a container, so re-publishing a seeded purpose would
     * make one suite's fixture visible to another's assertions; and the missing-version case below
     * deletes a version row, which against a seeded purpose would quietly change what every other
     * suite's captures mean.
     */
    private String aPurposeOfItsOwn() {
        String code = "RC_FIXTURE_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        registry.createPurpose(code, "Receipt fixture purpose", "receipt-it");
        registry.publishVersion(new PurposeRegistryStore.NewPurposeVersion(
                code, 1, "Receipt fixture purpose v1",
                "Version one, as the subject agreed to it.",
                Map.of(Jurisdiction.IN, new PurposeRegistryStore.BasisEntry(
                        LegalBasis.CONSENT, null, null)),
                Set.of("CONTACT_BUSINESS"),
                Set.of(Channel.WEB, Channel.VOICE_CALL),
                ExpiryPolicy.NONE, null, FailureBehavior.FAIL_CLOSED, NOTICE,
                true, false, false, false),
                "receipt-it");
        catalogue.refresh();
        return code;
    }

    /**
     * Publishes a broader version and makes the cache serve it.
     *
     * <p>The refresh is the part that gives these tests their teeth. Without it the cached
     * catalogue still holds version 1, and the old implementation — which read the catalogue —
     * would have passed by accident.
     */
    private void broadenTo(String purposeCode, int version) {
        registry.publishVersion(new PurposeRegistryStore.NewPurposeVersion(
                purposeCode, version, "Receipt fixture purpose v2, broadened",
                "Broadened after the original capture, which is the whole point of this fixture.",
                Map.of(Jurisdiction.IN, new PurposeRegistryStore.BasisEntry(
                        LegalBasis.CONSENT, null, null)),
                // GOVERNMENT_ID is marked sensitive in V3's controlled vocabulary, so adding it
                // flips touchesSensitiveData from false to true.
                Set.of("CONTACT_BUSINESS", "GOVERNMENT_ID"),
                Set.of(Channel.WEB, Channel.VOICE_CALL),
                ExpiryPolicy.NONE, null, FailureBehavior.FAIL_CLOSED, NOTICE,
                true, false, true, false),
                "receipt-it");
        catalogue.refresh();
    }

    /** Refuses a purpose the subject already answered, later, so it supersedes. */
    private void decline(String subject, String purposeCode) {
        ConsentCaptureService.Result result = capture.capture(new CaptureSubmission(ENTITY, subject,
                Jurisdiction.IN, "en", Channel.WEB, APP, CaptureMethod.CHECKBOX_OPT_IN,
                ActorType.SUBJECT, subject, NOTICE, 1,
                List.of(CaptureSubmission.PurposeChoice.declined(purposeCode)), true,
                Instant.parse("2026-08-16T14:30:00Z"), "rc-deny-" + subject, null, Map.of()));

        assertThat(result.isAccepted())
                .withFailMessage("refusal rejected: %s", result.violations())
                .isTrue();
    }

    @Test
    @DisplayName("a re-served notice moves the receipt's notice and leaves the consent alone")
    void aReServedNoticeUpdatesTheNoticeAndNotTheConsent() {
        // The behaviour change Phase 18 made to the projector, asserted where a data principal
        // would see it. Before it, serving a notice for a purpose somebody had already granted
        // wiped the grant out of consent_artefact — which is what ReceiptService renders — so the
        // receipt would have said NOT_ASKED for a consent still recorded in the ledger.
        //
        // What the receipt now says is that they agreed, and that the most recent notice they were
        // shown is the newer one. Both are true, and the second is a change worth pinning: this is
        // a document handed to a person, and the notice it names is the one a grievance is read
        // against.
        String subject = grant("MKT_OUTBOUND_CALL");

        capture.recordNoticeServed(ENTITY, subject, "MKT_OUTBOUND_CALL", "NOTICE_UDS_WORKFORCE", 1,
                "en", Jurisdiction.IN, APP, Instant.parse("2026-08-15T11:00:00Z"),
                "rc-notice-" + subject);

        ConsentReceipt receipt = receipts.issue(ENTITY, subject, Instant.now());

        assertThat(receipt.entries())
                .filteredOn(entry -> entry.purposeCode().equals("MKT_OUTBOUND_CALL"))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.status()).isEqualTo(ConsentStatus.GRANTED);
                    assertThat(entry.grantedAt()).isEqualTo(Instant.parse("2026-08-15T09:00:00Z"));
                });
        assertThat(receipt.noticeId()).isEqualTo("NOTICE_UDS_WORKFORCE");
    }

    private String grant(String... purposeCodes) {
        String subject = "rc-" + UUID.randomUUID();
        List<CaptureSubmission.PurposeChoice> choices = java.util.Arrays.stream(purposeCodes)
                .map(CaptureSubmission.PurposeChoice::acceptedSeparately)
                .toList();

        ConsentCaptureService.Result result = capture.capture(new CaptureSubmission(ENTITY, subject,
                Jurisdiction.IN, "en", Channel.WEB, APP, CaptureMethod.CHECKBOX_OPT_IN,
                ActorType.SUBJECT, subject, NOTICE, 1, choices, true,
                Instant.parse("2026-08-15T09:00:00Z"), "rc-" + subject, null, Map.of()));

        assertThat(result.isAccepted())
                .withFailMessage("capture rejected: %s", result.violations())
                .isTrue();
        return subject;
    }
}
