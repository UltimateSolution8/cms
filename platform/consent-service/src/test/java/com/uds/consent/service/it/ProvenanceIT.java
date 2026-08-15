package com.uds.consent.service.it;

import com.uds.consent.core.model.IdentifierType;
import com.uds.consent.core.model.LegalBasis;
import com.uds.consent.core.model.ProvenanceSourceType;
import com.uds.consent.ledger.store.AdminAuditStore;
import com.uds.consent.ledger.store.ProvenanceStore;
import com.uds.consent.service.ProvenanceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The prospect-database backfill, exercised end to end.
 *
 * <p>This is the exercise the plan calls the single biggest commercial risk in the programme, and
 * the number it produces — how many contacts the group cannot lawfully use — is a budget line
 * rather than a metric. So the properties tested here are the ones that decide whether that number
 * is trustworthy: that imports land quarantined and cannot self-certify, that a re-run does not
 * inflate the count, and that clearing a record leaves a named human's fingerprints on it.
 */
class ProvenanceIT extends PostgresIntegrationTest {

    private static final String ENTITY = "DENAVE_IN";
    private static final Instant ACQUIRED = Instant.parse("2024-03-01T00:00:00Z");

    @Autowired
    private ProvenanceService provenance;

    @Autowired
    private ProvenanceStore store;

    @Autowired
    private AdminAuditStore audit;

    @Test
    @DisplayName("an imported record lands quarantined and its subject is not contactable")
    void importsLandQuarantined() {
        String subject = newSubject();

        ProvenanceService.Result result = provenance.record(
                submission(subject, ProvenanceSourceType.PURCHASED_LIST, "AcmeData Q1 list"),
                "importer");

        assertThat(result.accepted()).isTrue();
        assertThat(result.inserted()).isTrue();
        assertThat(store.find(result.id())).get()
                .satisfies(record -> {
                    assertThat(record.quarantined()).isTrue();
                    assertThat(record.substantiated()).isFalse();
                });

        // The decision engine reads exactly this. A quarantined record is not contactable however
        // clean everything else about the subject looks.
        assertThat(store.isContactable(ENTITY, subject)).isFalse();
    }

    @Test
    @DisplayName("re-running an import does not inflate the quarantine count")
    void importIsIdempotent() {
        // The failure this prevents is not a duplicate row. It is a quarantine count that grows
        // every time somebody re-runs a job they were not sure had finished — and that count is
        // what a re-permissioning campaign is budgeted against.
        String subject = newSubject();
        ProvenanceService.Submission submission =
                submission(subject, ProvenanceSourceType.CLIENT_SUPPLIED, "Microsoft APAC list");

        ProvenanceService.Result first = provenance.record(submission, "importer");
        ProvenanceService.Result second = provenance.record(submission, "importer");

        assertThat(first.inserted()).isTrue();
        assertThat(second.inserted()).isFalse();
        assertThat(second.id()).isEqualTo(first.id());
    }

    @Test
    @DisplayName("a bad row is rejected on its own without failing the rest of the batch")
    void oneBadRowDoesNotFailTheBatch() {
        // An import of two hundred thousand contacts that aborts on row three tells the operator
        // nothing except to try again.
        String good = newSubject();
        String alsoGood = newSubject();

        List<ProvenanceService.Submission> batch = List.of(
                submission(good, ProvenanceSourceType.PURCHASED_LIST, "AcmeData Q2"),
                // No subject id and no identifier: nothing to attach the assertion to.
                new ProvenanceService.Submission(ENTITY, null, null, null,
                        ProvenanceSourceType.PURCHASED_LIST, "AcmeData Q2", ACQUIRED, null, null,
                        null),
                submission(alsoGood, ProvenanceSourceType.PURCHASED_LIST, "AcmeData Q2"));

        ProvenanceService.BatchResult result =
                provenance.recordBatch(ENTITY, batch, "acme-q2.csv", "importer");

        assertThat(result.submitted()).isEqualTo(3);
        assertThat(result.inserted()).isEqualTo(2);
        assertThat(result.rejected()).isEqualTo(1);
        assertThat(store.isContactable(ENTITY, good)).isFalse();
        assertThat(store.isContactable(ENTITY, alsoGood)).isFalse();
    }

    @Test
    @DisplayName("a batch writes one audit entry naming the file, not one per row")
    void batchIsAuditedOnce() {
        String batchRef = "denave-legacy-" + UUID.randomUUID();
        provenance.recordBatch(ENTITY, List.of(
                        submission(newSubject(), ProvenanceSourceType.LEGACY_UNKNOWN, "pre-2020 CRM"),
                        submission(newSubject(), ProvenanceSourceType.LEGACY_UNKNOWN, "pre-2020 CRM")),
                batchRef, "compliance-console");

        List<AdminAuditStore.Entry> entries = audit.recent(ENTITY, 200);

        assertThat(entries)
                .filteredOn(entry -> "PROVENANCE_BATCH_IMPORTED".equals(entry.action())
                        && batchRef.equals(entry.targetId()))
                .singleElement()
                .satisfies(entry -> assertThat(entry.detailJson())
                        .contains("\"inserted\"").contains("\"2\""));
    }

    @Test
    @DisplayName("substantiating releases the record and records who accepted the evidence")
    void substantiationIsAttributable() {
        String subject = newSubject();
        ProvenanceService.Result result = provenance.record(
                submission(subject, ProvenanceSourceType.EVENT_OR_TRADESHOW, "TechEd Mumbai 2024"),
                "importer");

        assertThat(store.isContactable(ENTITY, subject)).isFalse();

        ProvenanceStore.Record after = provenance.substantiate(result.id(),
                "Signed badge-scan consent form, scanned to evidence://tradeshow/2024/mumbai",
                "priya.compliance");

        assertThat(after.quarantined()).isFalse();
        assertThat(after.substantiated()).isTrue();
        assertThat(store.isContactable(ENTITY, subject)).isTrue();

        assertThat(audit.recent(ENTITY, 200))
                .filteredOn(entry -> "PROVENANCE_SUBSTANTIATED".equals(entry.action())
                        && String.valueOf(result.id()).equals(entry.targetId()))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.actorId()).isEqualTo("priya.compliance");
                    assertThat(entry.detailJson()).contains("badge-scan");
                });
    }

    @Test
    @DisplayName("substantiating a record that does not exist fails rather than passing quietly")
    void substantiatingNothingIsAnError() {
        assertThatThrownBy(() -> provenance.substantiate(-1L, "note", "someone"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no provenance record");
    }

    @Test
    @DisplayName("one quarantined record among several keeps the subject uncontactable")
    void anyQuarantinedRecordBlocksTheSubject() {
        // A contact whose details were partly bought and partly given directly is still partly
        // bought. Releasing them because one clean record exists would be the group deciding that
        // the cleanest available provenance is the one that counts.
        String subject = newSubject();
        ProvenanceService.Result clean = provenance.record(
                submission(subject, ProvenanceSourceType.CLIENT_SUPPLIED, "HUL supplied"),
                "importer");
        provenance.substantiate(clean.id(), "Client DPA clause 7 and their consent export",
                "priya.compliance");

        assertThat(store.isContactable(ENTITY, subject)).isTrue();

        provenance.record(new ProvenanceService.Submission(ENTITY, subject, null, null,
                        ProvenanceSourceType.APPENDED, "ZoomInfo enrichment",
                        ACQUIRED.plus(30, ChronoUnit.DAYS), null, null, null),
                "importer");

        assertThat(store.isContactable(ENTITY, subject)).isFalse();
    }

    @Test
    @DisplayName("the source summary is the report leadership is given")
    void summaryGroupsBySource() {
        String sourceName = "Vendor-" + UUID.randomUUID();
        provenance.record(submission(newSubject(), ProvenanceSourceType.PURCHASED_LIST, sourceName),
                "importer");
        ProvenanceService.Result released = provenance.record(
                submission(newSubject(), ProvenanceSourceType.PURCHASED_LIST, sourceName),
                "importer");
        provenance.substantiate(released.id(), "Vendor produced their opt-in log", "priya");

        assertThat(provenance.summariseBySource(ENTITY))
                .filteredOn(summary -> sourceName.equals(summary.sourceName()))
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.total()).isEqualTo(2);
                    assertThat(summary.quarantined()).isEqualTo(1);
                    assertThat(summary.contactable()).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("an identifier is hashed on the way in and resolves to a stable subject")
    void identifiersAreResolvedAndHashed() {
        String phone = "+9198" + (10_000_000 + (int) (Math.random() * 89_999_999));

        ProvenanceService.Result first = provenance.record(new ProvenanceService.Submission(
                ENTITY, null, IdentifierType.PHONE, phone, ProvenanceSourceType.REFERRAL,
                "Partner referral", ACQUIRED, LegalBasis.CONSENT, null, null), "importer");

        ProvenanceService.Result second = provenance.record(new ProvenanceService.Submission(
                ENTITY, null, IdentifierType.PHONE, phone, ProvenanceSourceType.REFERRAL,
                "Partner referral", ACQUIRED, LegalBasis.CONSENT, null, null), "importer");

        assertThat(second.subjectId()).isEqualTo(first.subjectId());
        assertThat(second.inserted()).isFalse();
        // The plaintext number never becomes the subject id — that is the whole point of the
        // peppered hash sitting between them.
        assertThat(first.subjectId()).doesNotContain(phone);
    }

    // -------------------------------------------------------------------------------------------

    private static ProvenanceService.Submission submission(String subjectId,
                                                           ProvenanceSourceType sourceType,
                                                           String sourceName) {
        return new ProvenanceService.Submission(ENTITY, subjectId, null, null, sourceType,
                sourceName, ACQUIRED, null, null, null);
    }

    private static String newSubject() {
        return "it-prov-" + UUID.randomUUID();
    }
}
