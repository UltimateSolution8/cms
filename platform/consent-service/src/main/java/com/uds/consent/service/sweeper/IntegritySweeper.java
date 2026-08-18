package com.uds.consent.service.sweeper;

import com.uds.consent.ledger.service.LedgerIntegrityVerifier;
import com.uds.consent.service.config.PlatformProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verifies the whole ledger, nightly.
 *
 * <p>This is the control that makes the group's integrity claim testable rather than merely
 * asserted. Triggers and revoked grants stop the application and an ordinary operator from
 * editing history; neither stops a superuser, who can disable a trigger. What a superuser cannot
 * do is alter one row without invalidating every hash after it — so a sweep that finds the chains
 * intact is evidence, and one that does not is an incident.
 *
 * <p>Also run after every restore from backup. A backup that restores cleanly but fails
 * verification is a backup of a compromised ledger.
 */
@Component
public class IntegritySweeper {

    private static final Logger log = LoggerFactory.getLogger(IntegritySweeper.class);

    private final LedgerIntegrityVerifier verifier;
    private final PlatformProperties properties;
    private final SweepLock lock;
    private final AtomicReference<Report> lastReport = new AtomicReference<>(Report.notYetRun());

    public IntegritySweeper(LedgerIntegrityVerifier verifier, PlatformProperties properties,
                            SweepLock lock) {
        this.verifier = verifier;
        this.properties = properties;
        this.lock = lock;
    }

    /** Runs at 02:15 local time, outside the field force's working day. */
    @Scheduled(cron = "${uds.consent.sweeper.integrity-cron:0 15 2 * * *}")
    public void sweep() {
        if (!properties.getSweeper().isIntegrityEnabled()) {
            return;
        }
        // The most expensive job the platform runs — it walks every chain. Running it once per
        // replica at 02:15 multiplies that cost by the replica count for no additional assurance.
        lock.runExclusively("integrity", this::run);
    }

    /** Runs the sweep and records the outcome. Exposed so an operator can trigger it on demand. */
    public Report run() {
        Instant startedAt = Instant.now();
        LedgerIntegrityVerifier.SweepResult result =
                verifier.verifyAll(properties.getSweeper().getIntegrityPageSize());

        long tampered = result.failures().stream()
                .filter(LedgerIntegrityVerifier.ChainVerification::tampered)
                .count();

        if (result.allIntact()) {
            log.info("ledger integrity sweep complete: {} chains verified, all intact",
                    result.chainsChecked());
        } else if (tampered > 0) {
            // Distinguished from schema drift because these two want very different responses:
            // one is a ticket, the other is an incident with a regulatory dimension.
            log.error("LEDGER INTEGRITY FAILURE: {} of {} chains show evidence of alteration. "
                            + "Treat as a security incident: preserve the database, do not "
                            + "restore over it, and escalate to the DPO.",
                    tampered, result.chainsChecked());
        } else {
            log.warn("ledger integrity sweep: {} of {} chains show payload divergence without "
                            + "chain breaks. Most likely schema drift after a migration; verify "
                            + "against the migration history before treating as tampering.",
                    result.failures().size(), result.chainsChecked());
        }

        Report report = new Report(startedAt, Instant.now(), result.chainsChecked(),
                result.failures().size(), tampered);
        lastReport.set(report);
        return report;
    }

    /** The most recent sweep, for the health endpoint and the compliance console. */
    public Report lastReport() {
        return lastReport.get();
    }

    /**
     * @param chainsChecked   subjects whose chains were walked
     * @param chainsWithFindings chains with any finding
     * @param chainsTampered  chains whose findings indicate alteration rather than schema drift
     */
    public record Report(Instant startedAt, Instant finishedAt, int chainsChecked,
                         int chainsWithFindings, long chainsTampered) {

        static Report notYetRun() {
            return new Report(null, null, 0, 0, 0);
        }

        public boolean healthy() {
            return chainsTampered == 0;
        }
    }
}
