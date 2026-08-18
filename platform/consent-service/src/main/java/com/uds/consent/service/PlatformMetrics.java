package com.uds.consent.service;

import com.uds.consent.core.decision.DenialReason;
import com.uds.consent.ledger.store.OutboxStore;
import com.uds.consent.ledger.store.PartitionStore;
import com.uds.consent.ledger.store.PropagationCoverageStore;
import com.uds.consent.service.events.PropagationReconciler;
import com.uds.consent.ledger.store.RightsRequestStore;
import com.uds.consent.ledger.store.SigningKeyStore;
import com.uds.consent.policy.capture.CaptureViolation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the platform measures about itself.
 *
 * <p>{@code OPERATIONS.md} §6 commits to p95 under 30 ms on the decision API and 99.99%
 * availability. Nothing measured either, which made both aspirations rather than facts — and an
 * SLO nobody measures is a number that gets quoted in a client contract and discovered to be wrong
 * during the incident review.
 *
 * <p>Everything here rides on the {@code micrometer-core} that Actuator already brings, so it is
 * visible at {@code /actuator/metrics} with no new dependency. Meters are resolved once and cached
 * rather than looked up per call: a registry lookup on the hot path to record a timing about the
 * hot path is a self-defeating measurement.
 *
 * <p><strong>Cardinality is deliberately bounded.</strong> Denial reasons and violation codes are
 * enums, so those tags have a fixed and small range. Nothing here is tagged by entity, subject,
 * campaign or purpose — a per-subject tag would create one time series per person, which is how a
 * metrics backend falls over and, incidentally, how personal data ends up in a monitoring system
 * that was never designed to hold it.
 */
@Component
@DependsOnDatabaseInitialization
public class PlatformMetrics {

    private final MeterRegistry registry;
    private final Timer decisionTimer;
    private final Timer captureTimer;
    private final Timer scrubTimer;
    private final Map<DenialReason, Counter> denials = new ConcurrentHashMap<>();
    private final Map<CaptureViolation.Code, Counter> violations = new ConcurrentHashMap<>();
    private final Counter allowances;
    private final Counter capturesAccepted;

    public PlatformMetrics(MeterRegistry registry, OutboxStore outbox,
                           RightsRequestStore rights, EnforcementRecorder recorder,
                           PartitionStore partitions, SigningKeyStore keys,
                           PropagationCoverageStore propagation,
                           PropagationReconciler reconciler) {
        this.registry = registry;

        this.decisionTimer = Timer.builder("uds.consent.decision")
                .description("Time to answer one decision request")
                // Percentiles computed in-process. Without them the only available statistic is a
                // mean, and a mean cannot answer the question the SLO actually asks.
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        this.captureTimer = Timer.builder("uds.consent.capture")
                .description("Time to validate and record one consent submission")
                .publishPercentiles(0.5, 0.95)
                .register(registry);

        this.scrubTimer = Timer.builder("uds.consent.scrub")
                .description("Time to screen one campaign list")
                .publishPercentiles(0.5, 0.95)
                .register(registry);

        this.allowances = Counter.builder("uds.consent.decision.outcome")
                .tag("outcome", "ALLOW")
                .description("Decisions that permitted the processing")
                .register(registry);

        this.capturesAccepted = Counter.builder("uds.consent.capture.outcome")
                .tag("outcome", "ACCEPTED")
                .description("Consent submissions accepted")
                .register(registry);

        // Gauges over things that already had a report and no home. OutboxStore.pendingCount was
        // documented as "exposed as a metric" and was exposed nowhere.
        registry.gauge("uds.consent.outbox.pending", outbox, OutboxStore::pendingCount);
        registry.gauge("uds.consent.rights.overdue", rights,
                store -> store.findOverdue(Instant.now(), 1000).size());
        registry.gauge("uds.consent.enforcement.failed_writes", recorder,
                EnforcementRecorder::failedWrites);

        // A system that must be told about a withdrawal and is not reachable. Read over the
        // REGISTER — tens of rows — rather than over the outbox and delivery tables, which is what
        // makes it cheap enough for every scrape and every probe.
        //
        // The reachability of zero is the whole design. An earlier draft counted unreconciled
        // messages, which can never fall: a message published before a subscription existed is
        // never re-published, so the count would have been permanently non-zero, the critical alert
        // would have fired for the life of the database, and it would have been muted inside a
        // week. This counts configuration, so fixing the configuration fixes the number.
        registry.gauge("uds.consent.propagation.uncovered", propagation,
                PropagationCoverageStore::uncoveredCount);

        // Evidence about propagation that the platform could not write. Same shape and same reason
        // as enforcement.failed_writes: above zero means consent changes are going out and what
        // could not be shown to have arrived is not being recorded.
        registry.gauge("uds.consent.propagation.failed_writes", reconciler,
                PropagationReconciler::failedWrites);

        // Open rights requests whose clock started on an instant nobody verified. V30 built the
        // index for this question and nothing ever asked it.
        registry.gauge("uds.consent.rights.unverified_open", rights,
                RightsRequestStore::countOpenUnverified);

        // Two slow-moving conditions that have no symptom until they are catastrophic, which is
        // exactly the shape of thing a gauge is for. Both are reported on /actuator/health too;
        // health tells an operator looking at one instance, a series tells an alert rule.
        //
        // Partition runway: the sweeper provisions three months ahead and never drops, so this
        // only falls because the job stopped. It is silent for a quarter, and then on the first of
        // a month every denial fails to record.
        registry.gauge("uds.consent.partition.months_ahead", partitions,
                store -> store.monthsProvisionedAhead("enforcement_decision", LocalDate.now()));

        // Signing key age: nothing at all breaks on day 91, which is precisely why it needs
        // watching. A control whose violation produces no symptom is one that quietly stops being
        // followed, and the first evidence is a key that has signed two years of snapshots.
        registry.gauge("uds.consent.signing_key.age_days", keys, store -> {
            Instant activated = store.oldestActiveActivation();
            return activated == null ? 0 : Duration.between(activated, Instant.now()).toDays();
        });
    }

    /** Records one decision: how long it took, and what it answered. */
    public void decision(long nanos, DenialReason reason, boolean allowed) {
        decisionTimer.record(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        if (allowed) {
            allowances.increment();
        } else {
            denials.computeIfAbsent(reason, this::denialCounter).increment();
        }
    }

    /** Records one capture, counting each violation that caused a rejection. */
    public void capture(long nanos, java.util.List<CaptureViolation> found) {
        captureTimer.record(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        if (found.isEmpty()) {
            capturesAccepted.increment();
            return;
        }
        // Every violation rather than only the first. A surface producing three problems is a
        // surface whose integrator should see three, and the counters are what tell an
        // operations team which of the group's surfaces is misbehaving without reading logs.
        for (CaptureViolation violation : found) {
            violations.computeIfAbsent(violation.code(), this::violationCounter).increment();
        }
    }

    public void scrub(long nanos, int submitted, int excluded) {
        scrubTimer.record(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        registry.counter("uds.consent.scrub.identifiers", "outcome", "SUBMITTED")
                .increment(submitted);
        registry.counter("uds.consent.scrub.identifiers", "outcome", "EXCLUDED")
                .increment(excluded);
    }

    /**
     * Counts a request the rate limiter refused.
     *
     * <p>Tagged by route class only — {@code PUBLIC}, {@code DECISION}, {@code BATCH},
     * {@code CAPTURE}, {@code ADMIN} — and never by client or IP. Tagging by caller would answer a
     * more useful question and would put an unbounded, attacker-chosen set of values into the
     * metrics backend, which is both how a backend falls over and how a scanner's chosen strings
     * end up stored somewhere nobody is watching. Which caller is being limited belongs in the log
     * line, which is bounded by retention and is not a permanent time series.
     */
    public void rateLimited(String routeClass) {
        registry.counter("uds.consent.ratelimit.refused", "route", routeClass).increment();
    }

    private Counter denialCounter(DenialReason reason) {
        return Counter.builder("uds.consent.decision.outcome")
                .tag("outcome", "DENY")
                .tag("reason", reason.name())
                .description("Decisions that refused the processing, by reason")
                .register(registry);
    }

    private Counter violationCounter(CaptureViolation.Code code) {
        return Counter.builder("uds.consent.capture.outcome")
                .tag("outcome", "REJECTED")
                .tag("violation", code.name())
                .description("Consent submissions refused, by violation")
                .register(registry);
    }
}
