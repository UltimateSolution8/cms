package com.uds.consent.service.config;

import com.uds.consent.ledger.store.ConsentManagerStore;
import com.uds.consent.ledger.store.OutboxStore;
import com.uds.consent.ledger.store.PropagationCoverageStore;
import com.uds.consent.ledger.store.ReconfirmationStore;
import com.uds.consent.ledger.store.SigningKeyStore;
import com.uds.consent.ledger.store.SweepRunStore;
import com.uds.consent.service.EnforcementRecorder;
import com.uds.consent.service.SdfObligationService;
import com.uds.consent.service.sweeper.IntegritySweeper;
import com.uds.consent.service.sweeper.ProjectionReconciliationSweeper;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Health, in the compliance sense rather than the uptime sense.
 *
 * <p>Three facts, each of which already had a report and no home. {@code IntegritySweeper.Report}
 * has a {@code healthy()} method nobody called. {@code OutboxStore.pendingCount()} is documented as
 * "exposed as a metric" and was exposed nowhere. {@code EnforcementRecorder} counts evidence writes
 * it could not complete, which is a silent state by construction.
 *
 * <p>What it reports DOWN for is deliberately narrow: an integrity sweep that found a broken hash
 * chain, and nothing else. That is the one condition meaning the ledger can no longer be offered as
 * evidence, and it should stop a deployment. A deep outbox is a backlog and a failed evidence write
 * is a compliance incident on a delay — both are serious, both belong in the payload, and neither
 * should take the service out of the load balancer, because removing the only instance that can
 * answer decision requests makes every one of those problems worse rather than better.
 */
@Component
public class PlatformHealthIndicator implements HealthIndicator {

    /**
     * Outbox depth above which the detail says so.
     *
     * <p>A number rather than a ratio because the relay drains at a fixed rate; what matters is
     * whether the queue is growing faster than that, and at this depth it is.
     */
    private static final long OUTBOX_BACKLOG_THRESHOLD = 10_000;

    private final IntegritySweeper integrity;
    private final OutboxStore outbox;
    private final EnforcementRecorder recorder;
    private final ConsentManagerStore managers;
    private final ReconfirmationStore reconfirmations;
    private final SdfObligationService sdf;
    private final SigningKeyStore signingKeys;
    private final PropagationCoverageStore propagation;

    private final ProjectionReconciliationSweeper projections;
    private final SweepRunStore sweeps;

    public PlatformHealthIndicator(IntegritySweeper integrity, OutboxStore outbox,
                                   EnforcementRecorder recorder, ConsentManagerStore managers,
                                   ReconfirmationStore reconfirmations,
                                   SdfObligationService sdf, SigningKeyStore signingKeys,
                                   PropagationCoverageStore propagation,
                                   ProjectionReconciliationSweeper projections,
                                   SweepRunStore sweeps) {
        this.propagation = propagation;
        this.projections = projections;
        this.sweeps = sweeps;
        this.integrity = integrity;
        this.outbox = outbox;
        this.recorder = recorder;
        this.managers = managers;
        this.reconfirmations = reconfirmations;
        this.sdf = sdf;
        this.signingKeys = signingKeys;
    }

    @Override
    public Health health() {
        IntegritySweeper.Report lastSweep = integrity.lastReport();
        long pending = outbox.pendingCount();
        long failedEvidenceWrites = recorder.failedWrites();

        Health.Builder builder = lastSweep.healthy() ? Health.up() : Health.down();

        return builder
                .withDetail("ledgerIntegrity", lastSweep.healthy() ? "VERIFIED" : "BROKEN")
                // Null until the first sweep runs, which is itself worth surfacing: a platform
                // reporting VERIFIED without having verified anything is reporting the default,
                // not a finding.
                .withDetail("lastIntegritySweep",
                        lastSweep.finishedAt() == null ? "NOT_YET_RUN" : lastSweep.finishedAt())
                .withDetail("chainsChecked", lastSweep.chainsChecked())
                // How long the oldest key still signing has been in service. Not a DOWN
                // condition — an old key is a governance problem, not a broken one — but the
                // number nobody would otherwise ever look at. OPERATIONS.md §2.2 has had a
                // rotation procedure since before there was a mechanism to rotate with; this is
                // what makes "when did we last rotate" answerable without a person remembering.
                .withDetail("signingKeyAgeDays", signingKeyAgeDays())
                // Systems that must be told about a consent change and that nothing can currently
                // reach. A DETAIL, never a DOWN condition — and the distinction matters more here
                // than it looks. Draining a healthy instance out of the load balancer because a
                // downstream system is unregistered turns an evidence problem into an availability
                // one, and the decision path would stop for every entity to protest about
                // somebody else's configuration. The only DOWN is a broken chain.
                .withDetail("propagationUncovered", propagation.uncoveredCount())
                // WHICH entities, not just how many. The gauge is group-wide and untagged to keep
                // entity cardinality off a series read on every scrape — so the critical alert
                // names no entity, and the responder's first step would otherwise be one call per
                // fiduciary. Bounded at fifteen, so it costs nothing here.
                .withDetail("propagationUncoveredEntities", propagation.uncoveredEntities())
                // Artefacts that disagree with the chain that produced them, as at the last
                // reconciliation sweep. A DETAIL and not a DOWN condition, deliberately: the
                // divergence is already in the database and refusing traffic does not un-diverge
                // it, while draining every replica would take the decision path down over a
                // finding that needs a person to read it. The alert is what escalates this; health
                // is what an operator sees when they look at one instance.
                .withDetail("projectionDivergences", projections.lastReport().divergent())
                .withDetail("lastProjectionSweep",
                        projections.lastReport().finishedAt() == null
                                ? "NOT_YET_RUN" : projections.lastReport().finishedAt())
                // When each scheduled sweep last finished, read out of sweep_run so the answer is
                // the same on every replica. A sweep that has never run reports NOT_YET_RUN rather
                // than an age, because an age of zero would read as "just finished".
                .withDetail("sweepLastRun", sweepAges())
                .withDetail("outboxPending", pending)
                .withDetail("outboxBacklog", pending > OUTBOX_BACKLOG_THRESHOLD)
                // Anything above zero means the platform is currently taking decisions it cannot
                // prove it took. Not fatal to serving traffic, and not something to discover in a
                // log six weeks later either.
                .withDetail("failedEvidenceWrites", failedEvidenceWrites)
                .withDetail("recordedDenials", recorder.recordedDenials())
                // How stale UDS's copy of the Board's Consent Manager register is. There is no feed
                // to poll — the Board publishes no API — so this reports when a person last compared
                // the two, and which entries nobody ever has. A copy with no staleness signal rots
                // in a way that looks exactly like normal operation, and the failure it produces is
                // honouring relays from a Consent Manager the Board removed last month.
                //
                // Not a DOWN condition. A stale register is a governance problem with a date on it,
                // and taking the service out of the load balancer over it would stop the decision
                // path for every entity to protest about paperwork.
                .withDetail("consentManagerRegisterLastReconciled",
                        managers.oldestReconciliation().map(Object::toString).orElse("NEVER"))
                // Listed by name rather than counted. These are the ones somebody has to act on,
                // and a count of three tells nobody which three — including the CM-TEST fixtures,
                // which are meant to keep appearing here until they are retired before go-live.
                .withDetail("consentManagersNeverReconciled", managers.neverReconciled())
                // Korea, Network Act Enforcement Decree Art. 62-3. Consents whose two-yearly
                // confirmation has fallen due and has not been sent.
                //
                // Not a DOWN condition either, and for a sharper reason than the register above:
                // the platform deliberately does not treat an unanswered confirmation as a
                // withdrawal, so a number here is an obligation somebody owes rather than a
                // consent being relied on unlawfully. It is on the health payload because an
                // obligation with a queue and no counter is one that ages quietly — which is
                // exactly how a two-year clock gets missed.
                .withDetail("koreanReconfirmationsOverdue",
                        reconfirmations.countOverdue(Instant.now()))
                // DPDP Rule 13. Zero when no group entity is notified a Significant Data
                // Fiduciary, which is the position today — and zero for that reason rather than
                // because nothing was looked at, since the count walks every entity carrying the
                // designation and there are none.
                .withDetail("sdfObligationsOverdue", sdf.countOverdueAcrossGroup(Instant.now()))
                .build();
    }

    /**
     * Age in days of the oldest key still signing, or null before one is registered.
     *
     * <p>Oldest rather than newest on purpose: a fleet where one instance was restarted onto a
     * fresh key and the rest were not is exactly what an age check should catch, and asking about
     * the newest would report the one instance that is fine.
     */
    private Long signingKeyAgeDays() {
        try {
            Instant oldest = signingKeys.oldestActiveActivation();
            return oldest == null
                    ? null
                    : java.time.Duration.between(oldest, Instant.now()).toDays();
        } catch (RuntimeException e) {
            // Health must not fail because a detail could not be computed. An indicator that
            // throws takes the instance out of the load balancer over a metric.
            return null;
        }
    }

    /**
     * When each sweep last finished, or {@code NOT_YET_RUN}.
     *
     * <p>Read out of {@code sweep_run} rather than out of any sweeper's memory: {@code SweepLock}
     * runs a sweep on one instance at a time, so an in-memory answer would say "never" on every
     * replica that skipped.
     */
    private Map<String, Object> sweepAges() {
        Map<String, Object> ages = new java.util.TreeMap<>();
        for (SweepRunStore.Run run : sweeps.all()) {
            Long age = run.ageSeconds(Instant.now());
            ages.put(run.sweepName(), age == null ? "NOT_YET_RUN" : age + "s ago");
        }
        return ages;
    }
}
