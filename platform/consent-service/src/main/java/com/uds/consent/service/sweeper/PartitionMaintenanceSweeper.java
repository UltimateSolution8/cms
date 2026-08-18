package com.uds.consent.service.sweeper;

import com.uds.consent.ledger.store.PartitionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Keeps enforcement_decision's partitions ahead of the calendar.
 *
 * <p>A range-partitioned table with no partition for next month is a table that starts writing
 * everything into its default partition on the first, silently, and stays healthy-looking while it
 * does. The evidence still lands — that is what the default is for — but the pruning the
 * partitioning exists to buy quietly stops, and the discovery is a slow query six months later.
 *
 * <p>So this runs daily and provisions several months ahead. Several rather than one, because the
 * failure being defended against is nobody noticing this stopped: at one month of headroom, a
 * sweeper that has been broken for five weeks is already writing to the default. At three, there is
 * a quarter of warning and the health payload shows it shrinking.
 *
 * <p><strong>It does not drop anything.</strong> Detaching a partition past its retention ceiling
 * is a decision with a legal dimension — Rule 6 sets a one-year floor and the evidence value of a
 * refusal outlasts it — and a scheduled job that silently deletes evidence is the last thing this
 * platform should own. Detachment is a runbook step with a person's name against it; see
 * {@code OPERATIONS.md}.
 */
@Component
public class PartitionMaintenanceSweeper {

    private static final Logger log = LoggerFactory.getLogger(PartitionMaintenanceSweeper.class);

    /**
     * Months provisioned beyond the current one.
     *
     * <p>Three, for the reason above: it is the headroom that turns "the sweeper stopped" from a
     * silent degradation into something visible for a quarter before it costs anything.
     */
    private static final int MONTHS_AHEAD = 3;

    private final PartitionStore partitions;
    private final SweepLock lock;

    public PartitionMaintenanceSweeper(PartitionStore partitions, SweepLock lock) {
        this.partitions = partitions;
        this.lock = lock;
    }

    @Scheduled(cron = "${uds.consent.sweeper.partition-cron:0 40 2 * * *}")
    public void sweep() {
        // Locked, because two replicas creating the same partition at the same moment is a
        // duplicate-object error rather than a race with consequences — but an error logged nightly
        // is an error people stop reading.
        lock.runExclusively("partition-maintenance", () -> run(Instant.now()));
    }

    /** Runs one pass as at {@code asOf}. Driven directly by the tests with a controlled clock. */
    public List<String> run(Instant asOf) {
        LocalDate month = asOf.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        List<String> created = new java.util.ArrayList<>();

        for (int ahead = 0; ahead <= MONTHS_AHEAD; ahead++) {
            LocalDate target = month.plusMonths(ahead);
            if (partitions.ensureMonthlyPartition("enforcement_decision", target)) {
                created.add(target.toString());
            }
        }

        if (!created.isEmpty()) {
            log.info("created enforcement_decision partition(s) for {}", created);
        }

        long stranded = partitions.defaultPartitionRows("enforcement_decision");
        if (stranded > 0) {
            // The signal that this job stopped and nobody noticed. Rows in the default partition
            // are correctly recorded evidence sitting where no pruning will ever reach them, and
            // the longer they accumulate the more expensive the eventual repartitioning becomes.
            log.warn("{} enforcement decision(s) are in the default partition — a month was "
                            + "written without a partition to hold it. Provisioning is fixed as of "
                            + "this pass; the stranded rows need a manual move.", stranded);
        }
        return List.copyOf(created);
    }
}
