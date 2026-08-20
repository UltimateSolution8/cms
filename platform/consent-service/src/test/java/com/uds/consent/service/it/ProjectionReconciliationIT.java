package com.uds.consent.service.it;

import com.uds.consent.core.model.ActorType;
import com.uds.consent.core.model.CaptureMethod;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.ConsentEvent;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.ledger.service.ProjectionReconciler;
import com.uds.consent.ledger.store.SweepRunStore;
import com.uds.consent.policy.capture.CaptureSubmission;
import com.uds.consent.service.ConsentCaptureService;
import com.uds.consent.service.config.PlatformProperties;
import com.uds.consent.service.sweeper.ProjectionReconciliationSweeper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The projection is checked against the chain that produced it.
 *
 * <p>{@code IntegritySweeper} proves {@code consent_event}. Nothing proved {@code consent_artefact}
 * — and the artefact is what the decision engine reads, what a receipt renders and what the
 * evidence bundle reports. Two of this programme's last three headline defects were projection
 * defects and every control passed both, because {@code last_event_hash} is <em>copied</em> onto
 * the artefact rather than derived from its {@code status}: a wrong projection stays perfectly
 * self-consistent and every hash verifies.
 *
 * <p><strong>Every assertion here edits the artefact directly and asks the sweep to notice.</strong>
 * That is deliberate. A test that folded the chain itself and compared the two folds would agree
 * with the sweep by construction and prove nothing — it would pass just as happily with the
 * projector wrong, which is the case that matters.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "uds.consent.events.relay-interval=PT1H")
class ProjectionReconciliationIT extends PostgresIntegrationTest {

    private static final String ENTITY = "DENAVE_IN";
    private static final String APP = "DENAVE_WEB";
    private static final String NOTICE = "NOTICE_DENAVE_B2B";
    private static final String PURPOSE = "MKT_OUTBOUND_CALL";

    @Autowired
    private ConsentCaptureService capture;

    @Autowired
    private ProjectionReconciler reconciler;

    @Autowired
    private ProjectionReconciliationSweeper sweeper;

    @Autowired
    private SweepRunStore sweeps;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private PlatformProperties properties;

    @Test
    @DisplayName("an artefact edited behind the ledger's back is reported, naming both statuses")
    void anEditedArtefactIsReported() {
        String subject = grant();

        // Precondition, and it is the half of this test that would be missing if it only asserted
        // the finding: the same subject must be clean BEFORE the edit, or a sweep that reports
        // everything would pass too.
        assertThat(reconciler.reconcileSubject(ENTITY, subject)).isEmpty();

        // The attack this control exists for. The chain is untouched, every hash still verifies,
        // and IntegritySweeper will report the ledger intact — which is exactly why nothing caught
        // this class of defect before.
        jdbc.update("update consent_artefact set status = 'WITHDRAWN' "
                + "where entity_id = ? and subject_id = ? and purpose_code = ?",
                ENTITY, subject, PURPOSE);

        List<ProjectionReconciler.Divergence> found = reconciler.reconcileSubject(ENTITY, subject);

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().purposeCode()).isEqualTo(PURPOSE);
        assertThat(found.getFirst().impliedStatus().name()).isEqualTo("GRANTED");
        assertThat(found.getFirst().projectedStatus().name()).isEqualTo("WITHDRAWN");
        // The detail names the field, so an operator does not need a second query to know what to
        // look at. A finding that says only "something differs" is one that gets triaged last.
        assertThat(found.getFirst().detail()).contains("status");
    }

    @Test
    @DisplayName("an artefact deleted from the projection is reported, not silently skipped")
    void aMissingArtefactIsReported() {
        String subject = grant();

        jdbc.update("delete from consent_artefact "
                + "where entity_id = ? and subject_id = ? and purpose_code = ?",
                ENTITY, subject, PURPOSE);

        List<ProjectionReconciler.Divergence> found = reconciler.reconcileSubject(ENTITY, subject);

        // The failure mode this covers is the quiet one: with no row, every decision for this
        // subject reads NOT_ASKED and denies, and the person's recorded consent has silently
        // stopped counting. Absence has to be a finding or the reconciler only checks rows that
        // happen to exist.
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().projectedStatus()).isNull();
        assertThat(found.getFirst().detail()).contains("no artefact row");
    }

    @Test
    @DisplayName("a purpose version of zero is an unstated version, never a divergence")
    void theUnstatedVersionSentinelIsNotADivergence() {
        String subject = grant();

        // ExpirySweeper writes NO_PURPOSE_VERSION_ASSERTED because an expiry ends an agreement
        // without restating its terms, and the projector correctly carries the prior version
        // forward rather than projecting the zero. A reconciler comparing the raw values would
        // report every expired artefact in the database as divergent — a false finding at
        // population scale, which is the fastest way to get a new control switched off.
        assertThat(ConsentEvent.NO_PURPOSE_VERSION_ASSERTED).isZero();

        jdbc.update("update consent_artefact set purpose_version = ? "
                + "where entity_id = ? and subject_id = ? and purpose_code = ?",
                ConsentEvent.NO_PURPOSE_VERSION_ASSERTED, ENTITY, subject, PURPOSE);

        assertThat(reconciler.reconcileSubject(ENTITY, subject))
                .withFailMessage("the no-version sentinel was read as a disagreement")
                .isEmpty();
    }

    @Test
    @DisplayName("the sweep records itself in sweep_run, so a stopped sweep is answerable")
    void theSweepRecordsThatItRan() {
        sweeper.run();

        // run() is the body; sweep() takes the lock. Exercise the scheduled entry point too, or
        // the recording — which lives in SweepLock — is never covered.
        sweeper.sweep();

        SweepRunStore.Run run = sweeps.find(ProjectionReconciliationSweeper.SWEEP_NAME)
                .orElseThrow(() -> new AssertionError("the sweep left no record that it ran"));

        assertThat(run.lastFinishedAt()).isNotNull();
        assertThat(run.lastOutcome()).isEqualTo("OK");
        assertThat(run.ageSeconds(Instant.now())).isNotNull().isLessThan(120L);
    }

    @Test
    @DisplayName("the group-wide report carries counts and no subject identifier")
    void theSummaryNamesNobody() {
        String subject = grant();
        jdbc.update("update consent_artefact set status = 'WITHDRAWN' "
                + "where entity_id = ? and subject_id = ? and purpose_code = ?",
                ENTITY, subject, PURPOSE);

        ResponseEntity<String> summary = rest.withBasicAuth("compliance-console", "admin-secret")
                .postForEntity("/v1/admin/projection/sweep", null, String.class);

        assertThat(summary.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The finding is reported — the route still works.
        assertThat(summary.getBody()).contains("\"divergent\"");
        // ...and it names nobody. This is the assertion that fails if the identifiers are ever
        // restored to the summary: the route is group-wide, so a per-entity ADMIN credential —
        // which the configuration supports — would read every fiduciary's subjects from it.
        assertThat(summary.getBody())
                .withFailMessage("the group-wide report leaked a subject identifier: %s",
                        summary.getBody())
                .doesNotContain(subject);

        // The identifiers live on the entity-scoped route, or the control has simply been deleted
        // rather than scoped.
        String scoped = rest.withBasicAuth("compliance-console", "admin-secret")
                .getForObject("/v1/admin/projection/divergences?entityId=" + ENTITY, String.class);
        assertThat(scoped).contains(subject).contains(PURPOSE).contains("WITHDRAWN");
    }

    @Test
    @DisplayName("a credential scoped to one entity cannot read another's divergences")
    void theDivergenceRouteIsEntityScoped() {
        String subject = grant();
        jdbc.update("update consent_artefact set status = 'WITHDRAWN' "
                + "where entity_id = ? and subject_id = ? and purpose_code = ?",
                ENTITY, subject, PURPOSE);
        sweeper.run();

        // MATRIX asking for DENAVE_IN. Layer one reads the entityId query parameter on any path,
        // which is why this route needs no ENTITY_PATH_PREFIXES entry — asserted rather than
        // assumed, because that reasoning is exactly what a later refactor would break.
        assertThat(rest.withBasicAuth("matrix-console", "matrix-secret")
                .getForEntity("/v1/admin/projection/divergences?entityId=" + ENTITY, String.class)
                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // ...and its own entity answers, so the refusal above is scoping rather than a broken route.
        ResponseEntity<String> own = rest.withBasicAuth("matrix-console", "matrix-secret")
                .getForEntity("/v1/admin/projection/divergences?entityId=MATRIX", String.class);
        assertThat(own.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(own.getBody()).doesNotContain(subject);
    }

    @Test
    @DisplayName("the retained list is capped and says so; the count stays exact")
    void theCountIsNotCappedWhenTheListIs() {
        // Three divergences against a cap of one. The cap is set on the property rather than by
        // creating five hundred subjects, which would measure the seed rather than the control.
        List<String> subjects = List.of(grant(), grant(), grant());
        for (String subject : subjects) {
            jdbc.update("update consent_artefact set status = 'WITHDRAWN' "
                    + "where entity_id = ? and subject_id = ? and purpose_code = ?",
                    ENTITY, subject, PURPOSE);
        }

        int originalCap = properties.getSweeper().getProjectionDivergenceCap();
        int originalPage = properties.getSweeper().getProjectionDivergencePageSize();
        try {
            properties.getSweeper().setProjectionDivergenceCap(1);
            properties.getSweeper().setProjectionDivergencePageSize(1);

            ProjectionReconciliationSweeper.Report report = sweeper.run();

            // The property that matters. A capped count would make a systemic projector defect —
            // the case this control exists for — look smaller than it is, which is the opposite of
            // what an operator needs from it.
            assertThat(report.divergent()).isGreaterThanOrEqualTo(3);
            assertThat(report.retentionTruncated()).isTrue();
            assertThat(report.retentionCap()).isEqualTo(1);

            String scoped = rest.withBasicAuth("compliance-console", "admin-secret")
                    .getForObject("/v1/admin/projection/divergences?entityId=" + ENTITY,
                            String.class);

            // It returns what it kept, and it says what it left out — including a request that
            // can actually be run, which is the whole of rules §9's pointer discipline. The
            // remainder here is genuinely unpageable (the sweep discarded it), so the pointer names
            // the sweep with a larger cap rather than an offset that could never resolve.
            assertThat(scoped).contains("\"truncation\"").contains("\"retained\"");
            assertThat(scoped).contains("projection-divergence-cap");
            // And the answer says when it was produced, so an empty one on a replica that never
            // swept cannot be read as "this entity is clean".
            assertThat(scoped).contains("\"sweptAt\"");

            // The page pointer, on its own branch: a cap of 1 with three divergences pages.
            properties.getSweeper().setProjectionDivergenceCap(10);
            sweeper.run();
            String firstPage = rest.withBasicAuth("compliance-console", "admin-secret")
                    .getForObject("/v1/admin/projection/divergences?entityId=" + ENTITY,
                            String.class);
            assertThat(firstPage).contains("offset=1");
            String secondPage = rest.withBasicAuth("compliance-console", "admin-secret")
                    .getForObject("/v1/admin/projection/divergences?entityId=" + ENTITY
                            + "&offset=1", String.class);
            assertThat(secondPage).contains("\"offset\":1");
        } finally {
            properties.getSweeper().setProjectionDivergenceCap(originalCap);
            properties.getSweeper().setProjectionDivergencePageSize(originalPage);
        }
    }

    @Test
    @DisplayName("fabricated artefacts are counted, not sampled — the page size is not the answer")
    void theFabricatedCountIsNotThePageSize() {
        // Found by hand against the Compose stack, not by a test: perf/seed.sql writes artefacts
        // with no chain behind them, and the sweep reported `fabricated: 200` against 20,000 of
        // them — because findFabricated takes a limit and its size() was being reported as the
        // count. A bulk insert of forged GRANTED rows is the single worst thing that can happen to
        // this table, and it would have read as a small, stable problem however large it got.
        int pageSize = properties.getSweeper().getProjectionReconciliationPageSize();
        int forged = 4;

        List<String> subjects = List.of("fab-" + UUID.randomUUID(), "fab-" + UUID.randomUUID(),
                "fab-" + UUID.randomUUID(), "fab-" + UUID.randomUUID());
        for (String subject : subjects) {
            // Straight into the projection, with no event anywhere. This is the shape the integrity
            // sweep is structurally unable to see: there is no chain to verify, so every hash it
            // checks is valid and the row is invisible to it.
            jdbc.update("insert into consent_artefact (entity_id, subject_id, purpose_code, "
                    + "purpose_version, status, legal_basis, capture_method, jurisdiction, "
                    + "sequence_number, last_event_hash, last_event_at) "
                    + "values (?, ?, ?, 1, 'GRANTED', 'CONSENT', 'CHECKBOX_OPT_IN', 'IN', 1, "
                    + "repeat('0', 64), now())", ENTITY, subject, PURPOSE);
        }

        int originalReport = pageSize;
        try {
            // A page smaller than the number of forged rows, so size() and the count must differ.
            properties.getSweeper().setProjectionReconciliationPageSize(2);

            ProjectionReconciliationSweeper.Report report = sweeper.run();

            assertThat(report.fabricated())
                    .withFailMessage("fabricated reported %d against %d forged artefacts — the "
                                    + "page size is being reported as the count",
                            report.fabricated(), forged)
                    .isGreaterThanOrEqualTo(forged);
            assertThat(report.divergent()).isGreaterThanOrEqualTo(report.fabricated());
        } finally {
            properties.getSweeper().setProjectionReconciliationPageSize(originalReport);
            for (String subject : subjects) {
                jdbc.update("delete from consent_artefact where entity_id = ? and subject_id = ?",
                        ENTITY, subject);
            }
        }
    }

    private String grant() {
        String subject = "pr-" + UUID.randomUUID();
        ConsentCaptureService.Result result = capture.capture(new CaptureSubmission(ENTITY, subject,
                Jurisdiction.IN, "en", Channel.WEB, APP, CaptureMethod.CHECKBOX_OPT_IN,
                ActorType.SUBJECT, subject, NOTICE, 1,
                List.of(CaptureSubmission.PurposeChoice.acceptedSeparately(PURPOSE)),
                true, Instant.now().truncatedTo(ChronoUnit.SECONDS), "pr-" + subject, null,
                Map.of()));

        assertThat(result.isAccepted())
                .withFailMessage("capture rejected: %s", result.violations())
                .isTrue();
        return subject;
    }
}
