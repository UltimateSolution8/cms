package com.uds.consent.service.sweeper;

import io.swagger.v3.oas.annotations.media.Schema;
import com.uds.consent.ledger.service.ProjectionReconciler;
import com.uds.consent.service.config.PlatformProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verifies that {@code consent_artefact} still agrees with the chain that produced it.
 *
 * <p>The integrity sweep proves the ledger; this proves the projection every decision is taken
 * against. Until it existed the platform could show that no event had been altered and could say
 * nothing at all about whether the derived row matched them — and it is the derived row that
 * {@code PolicyEngine} reads, {@code ReceiptService} renders and the evidence bundle reports.
 *
 * <p>The reasoning, the fold-reuse argument and the report-never-repair decision are in
 * {@link ProjectionReconciler}. This class is the schedule, the lock and the report.
 *
 * <p><strong>No table.</strong> A divergence is current state that returns to zero once it is
 * fixed; an append-only count of divergences can only grow, so an alert on it fires forever and is
 * muted inside a week — which is the trap the propagation register was redesigned to avoid before
 * it shipped. The chain is already the evidence. This reports what disagrees <em>now</em>.
 */
@Component
public class ProjectionReconciliationSweeper {

    private static final Logger log = LoggerFactory.getLogger(ProjectionReconciliationSweeper.class);

    /** The name this job records itself under in {@code sweep_run}, and the staleness gauge's tag. */
    public static final String SWEEP_NAME = "projection-reconciliation";

    private final ProjectionReconciler reconciler;
    private final PlatformProperties properties;
    private final SweepLock lock;
    private final AtomicReference<Report> lastReport = new AtomicReference<>(Report.notYetRun());

    /**
     * The divergences the last sweep kept, capped.
     *
     * <p>Held apart from the report because the report is group-wide and these carry subject
     * identifiers, which only ever leave through the entity-scoped route.
     */
    private final AtomicReference<List<ProjectionReconciler.Divergence>> retained =
            new AtomicReference<>(List.of());

    public ProjectionReconciliationSweeper(ProjectionReconciler reconciler,
                                           PlatformProperties properties, SweepLock lock) {
        this.reconciler = reconciler;
        this.properties = properties;
        this.lock = lock;
    }

    /**
     * Runs at 03:15 local time — after the integrity sweep at 02:15, deliberately.
     *
     * <p>If the chain itself is broken, "the projection disagrees with the chain" is the second
     * finding and not the first, and an operator reading two alerts wants them in that order.
     */
    @Scheduled(cron = "${uds.consent.sweeper.projection-reconciliation-cron:0 15 3 * * *}")
    public void sweep() {
        if (!properties.getSweeper().isProjectionReconciliationEnabled()) {
            return;
        }
        // Re-derives every chain in the database. Running it once per replica multiplies the cost
        // for no additional assurance — the same argument as the integrity sweep.
        lock.runExclusively(SWEEP_NAME, this::run);
    }

    /** Runs the sweep and records the outcome. Exposed so an operator can trigger it on demand. */
    public Report run() {
        Instant startedAt = Instant.now();
        int pageSize = properties.getSweeper().getProjectionReconciliationPageSize();
        int reportLimit = properties.getSweeper().getProjectionReconciliationReportLimit();
        int cap = properties.getSweeper().getProjectionDivergenceCap();

        int subjects = 0;
        long divergentFromChains = 0;
        // Bounded during the walk, not only at the end. A systemic projector defect produces one
        // divergence per artefact, so accumulating every page and capping afterwards would
        // materialise a million objects on the sweeping replica to keep five hundred of them —
        // and the peak, not the resident set, is what runs an instance out of heap.
        List<ProjectionReconciler.Divergence> divergences = new ArrayList<>();
        for (int offset = 0; ; offset += pageSize) {
            ProjectionReconciler.Result page = reconciler.reconcile(pageSize, offset);
            subjects += page.subjectsChecked();
            divergentFromChains += page.divergences().size();
            for (ProjectionReconciler.Divergence d : page.divergences()) {
                if (divergences.size() < cap) {
                    divergences.add(d);
                }
            }
            if (page.subjectsChecked() < pageSize) {
                break;
            }
        }

        // Artefacts with no chain at all. Separate from the paged walk because that walk starts
        // from chains and is therefore structurally blind to a row that was inserted rather than
        // projected — which is the direction that AUTHORISES processing rather than refusing it.
        // Counted, then sampled. The sample is what an operator reads; the count is what the gauge
        // and the alert read, and it must not be the sample's size — a bulk insert of forged
        // artefacts would then report the page size, every time, however large it actually was.
        long fabricated = reconciler.countFabricated();
        for (ProjectionReconciler.Divergence d : reconciler.findFabricated(pageSize)) {
            if (divergences.size() < cap) {
                divergences.add(d);
            }
        }
        long divergent = divergentFromChains + fabricated;

        // On the exact count, never on the sample: the sample is capped, so an
        // emptied-by-configuration sample must not be logged as a clean database.
        if (divergent == 0) {
            log.info("projection reconciliation complete: {} subject(s) re-derived, "
                    + "every artefact agrees with its chain", subjects);
        } else {
            // ERROR, and the wording is deliberate. This is either a projector defect affecting
            // every subject at once or a direct edit of the evidence plane's read model, and the
            // platform cannot tell which — which is precisely why it must not repair it silently.
            log.error("PROJECTION DIVERGENCE: {} artefact(s) across {} subject(s) do not agree "
                            + "with the chain that produced them. Do NOT re-project before "
                            + "establishing why: a projector defect and a direct database edit "
                            + "produce the same divergence, and one of them is a security "
                            + "incident. See docs/OPERATIONS.md §3.",
                    divergent, subjects);
            divergences.stream().limit(reportLimit).forEach(d ->
                    log.error("  divergent: entity={} subject={} purpose={} — {}",
                            d.entityId(), d.subjectId(), d.purposeCode(), d.detail()));
            if (divergent > reportLimit) {
                log.error("  ...and {} more; a sample is on GET /v1/admin/projection/"
                        + "divergences?entityId=, which is entity-scoped",
                        divergent - reportLimit);
            }
        }

        // The count is exact and the retained list is not. Capping the count instead would make a
        // systemic projector defect — the case this control exists for — look smaller than it is.
        retained.set(List.copyOf(divergences));

        Report report = new Report(startedAt, Instant.now(), subjects,
                divergent, fabricated, divergent > divergences.size(), cap);
        lastReport.set(report);
        return report;
    }

    /** The most recent sweep, for the health endpoint and the compliance console. */
    public Report lastReport() {
        return lastReport.get();
    }

    /**
     * The retained divergences for one entity, in sweep order.
     *
     * <p>Filtered here rather than in the query because the sweep is group-wide by necessity — it
     * re-derives every chain in the database — while the <em>answer</em> must not be. A group-wide
     * route returning another fiduciary's subject identifiers sits outside both isolation layers:
     * {@code EntityAccessGuard} sees no entity to read, and RLS is not in the path because the
     * sweep, not the request, gathered the rows.
     */
    public List<ProjectionReconciler.Divergence> retainedFor(String entityId) {
        return retained.get().stream()
                .filter(d -> d.entityId().equals(entityId))
                .toList();
    }

    /** Whether the last sweep found more divergences than it kept. */
    public boolean retentionTruncated() {
        return lastReport.get().retentionTruncated();
    }

    /**
     * Counts only, deliberately.
     *
     * <p>This route is group-wide, so it must not carry a subject identifier: the only shipped
     * ADMIN credential is group-wide by design, but the configuration supports a per-entity one,
     * and rules §9 refuses exactly this shape for the evidence bundle. The identifiers are on
     * {@code GET /v1/admin/projection/divergences}, which is scoped by {@code ?entityId=}. The
     * shape follows {@code IntegritySweeper.Report}, which is the sibling this pair should have
     * copied from the start.
     *
     * @param subjectsChecked    subjects whose chains were re-derived
     * @param divergent          artefacts that do not agree with their chain. <strong>Exact</strong>,
     *                           and deliberately not the size of the retained sample: capping the
     *                           count would understate a systemic projector defect or a bulk insert
     *                           of forged rows, which are the two cases this control exists for
     * @param fabricated         of those, rows with no chain behind them at all — inserted rather
     *                           than projected, and the direction that authorises processing.
     *                           Counted by its own query for the same reason
     * @param retentionTruncated whether more were found than were kept for the detail route
     */
    @Schema(name = "ProjectionReconciliationReport")
    public record Report(Instant startedAt, Instant finishedAt, int subjectsChecked,
                         long divergent, long fabricated, boolean retentionTruncated,
                         int retentionCap) {

        static Report notYetRun() {
            return new Report(null, null, 0, 0L, 0L, false, 0);
        }

        public boolean healthy() {
            return divergent == 0;
        }
    }
}
