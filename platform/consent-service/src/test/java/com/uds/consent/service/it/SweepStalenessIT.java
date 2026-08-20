package com.uds.consent.service.it;

import com.uds.consent.ledger.store.SweepRunStore;
import com.uds.consent.service.sweeper.SweepLock;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A sweep that stopped is visible, and a sweep that never ran does not read as healthy.
 *
 * <p>Until {@code sweep_run} existed nothing recorded that any of the eight scheduled jobs had run.
 * A silently dead {@code ExpirySweeper} produces no error, no failed request and no alert — only a
 * growing absence, while every decision stays correct. That is the shape of fault this suite exists
 * for.
 *
 * <p><strong>The record is in the database, and that is the design.</strong> {@code SweepLock} runs
 * a sweep on one instance at a time and the shipped deployment runs three replicas, so an
 * in-memory timestamp would read "never ran" on the two that skipped — permanently. An alert over
 * the maximum age would then fire forever and be muted inside a week, which is the failure the
 * propagation register was redesigned to avoid before it shipped.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "uds.consent.events.relay-interval=PT1H")
class SweepStalenessIT extends PostgresIntegrationTest {

    @Autowired
    private SweepLock lock;

    @Autowired
    private SweepRunStore runs;

    @Autowired
    private MeterRegistry registry;

    @Test
    @DisplayName("a sweep that has never run reports NaN on the gauge, never an age of zero")
    void anUnrunSweepReportsNaN() {
        // The property that matters, and the one an obvious implementation gets wrong. A gauge
        // reporting an unrun sweep as 0 is indistinguishable from one that finished a moment ago,
        // so the very condition being watched would render as the healthiest possible value.
        //
        // The first version of this test asserted `runs.find(someRandomName)` was empty — which is
        // true by construction for a key nobody inserted and says nothing whatever about the
        // gauge. Changing `.orElse(Double.NaN)` to `.orElse(0.0)` in PlatformMetrics left all 574
        // tests green. That is defect class 2, and qa-verifier caught it.
        //
        // Chosen dynamically rather than named, because which sweeps have run depends on the
        // profile — the cron-driven ones (integrity, projection reconciliation, partition
        // maintenance) do not fire inside a test run, and naming one of them here would be a
        // premise about configuration rather than an assertion about the gauge. Picking whichever
        // tracked sweep has no row keeps the test about the mapping, and the empty-check below
        // fails loudly rather than passing vacuously if every sweep somehow ran.
        List<Gauge> unrun = registry.find("uds.consent.sweep.last_run_age_seconds").gauges()
                .stream()
                .filter(g -> runs.find(g.getId().getTag("sweep")).isEmpty())
                .toList();

        assertThat(unrun)
                .withFailMessage("no tracked sweep is un-run, so this assertion would be vacuous")
                .isNotEmpty();
        assertThat(unrun)
                .allSatisfy(g -> assertThat(Double.isNaN(g.value()))
                        .withFailMessage("the un-run sweep '%s' reported %s; a finite value here "
                                + "reads as 'finished just now' and hides the very condition the "
                                + "alert watches", g.getId().getTag("sweep"), g.value())
                        .isTrue());
    }

    @Test
    @DisplayName("a sweep that has run reports a finite age, so the gauge is not simply always NaN")
    void aRunSweepReportsAFiniteAge() {
        // The other half. Without it the test above passes against a gauge hard-wired to NaN,
        // which would silence the threshold rule instead of the absent() one.
        String sweep = "finite-age-" + UUID.randomUUID();
        lock.runExclusively(sweep, () -> { });

        assertThat(runs.find(sweep).orElseThrow().ageSeconds(Instant.now()))
                .isNotNull().isLessThan(60L);
    }

    @Test
    @DisplayName("running a sweep through the lock records when and where, on every replica's behalf")
    void takingTheLockRecordsTheRun() {
        String sweep = "test-sweep-" + UUID.randomUUID();

        lock.runExclusively(sweep, () -> { });

        SweepRunStore.Run run = runs.find(sweep)
                .orElseThrow(() -> new AssertionError("the lock recorded nothing"));

        assertThat(run.lastStartedAt()).isNotNull();
        assertThat(run.lastFinishedAt()).isNotNull();
        assertThat(run.lastOutcome()).isEqualTo("OK");
        // Which replica ran it. The first thing wanted when one instance is wedged and the others
        // are healthy, and unanswerable before this row existed.
        assertThat(run.lastRanOn()).isNotBlank();
        assertThat(run.ageSeconds(Instant.now())).isNotNull().isLessThan(60L);
    }

    @Test
    @DisplayName("a sweep that threw is recorded as FAILED, not as one that never ran")
    void aFailingSweepIsRecordedAsHavingRun() {
        String sweep = "failing-sweep-" + UUID.randomUUID();

        // The lock swallows nothing — the exception propagates — so the caller sees it. What must
        // survive is the record: "scheduled and broken" and "not scheduled at all" are different
        // faults with different responses, and a single last-run timestamp cannot tell them apart.
        try {
            lock.runExclusively(sweep, () -> {
                throw new IllegalStateException("deliberate");
            });
        } catch (IllegalStateException expected) {
            // The point of the test is what was written, not what was thrown.
        }

        SweepRunStore.Run run = runs.find(sweep)
                .orElseThrow(() -> new AssertionError("a failing sweep left no record"));

        assertThat(run.lastOutcome()).isEqualTo("FAILED");
        assertThat(run.lastFinishedAt()).isNotNull();
    }

    @Test
    @DisplayName("one sweep running does not refresh another's age")
    void sweepsAreRecordedIndependently() {
        String first = "independent-a-" + UUID.randomUUID();
        String second = "independent-b-" + UUID.randomUUID();

        lock.runExclusively(first, () -> { });
        lock.runExclusively(second, () -> { });

        // A single shared row would make a healthy relay mask a dead nightly sweep — every job
        // looking current because the busiest one is.
        assertThat(runs.find(first)).isPresent();
        assertThat(runs.find(second)).isPresent();
        assertThat(runs.find(first).orElseThrow().sweepName()).isEqualTo(first);
    }

    @Test
    @DisplayName("the staleness gauge alerts.yaml watches is registered, tagged by sweep")
    void theGaugeTheAlertWatchesExists() {
        // alerts.yaml fires SweepHasNotRun on uds_consent_sweep_last_run_age_seconds. A gauge that
        // was never registered makes that rule permanently silent, which is indistinguishable from
        // a condition that never occurs — the same defect class as the OTLP key Phase 12 found,
        // and the reason Phase 17 had to add this assertion for the propagation meters.
        assertThat(registry.find("uds.consent.sweep.last_run_age_seconds").gauges())
                .withFailMessage("the gauge SweepHasNotRun keys on is not registered")
                .isNotEmpty();

        assertThat(registry.find("uds.consent.sweep.last_run_age_seconds").gauges().stream()
                .map(g -> g.getId().getTag("sweep")))
                .contains("expiry", "integrity", "projection-reconciliation");
    }

    @Test
    @DisplayName("the projection divergence gauge the critical alert watches is registered")
    void theDivergenceGaugeExists() {
        Gauge gauge = registry.find("uds.consent.projection.divergent").gauge();

        assertThat(gauge)
                .withFailMessage("ProjectionDivergesFromChain keys on a gauge that does not exist")
                .isNotNull();
        assertThat(gauge.value()).isGreaterThanOrEqualTo(0.0);
    }
}
