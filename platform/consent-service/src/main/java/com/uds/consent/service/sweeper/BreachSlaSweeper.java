package com.uds.consent.service.sweeper;

import com.uds.consent.ledger.store.BreachStore;
import com.uds.consent.service.config.PlatformProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Watches the breach clocks.
 *
 * <p>Beside {@link RightsSlaSweeper} and built the same way, with one difference that matters: an
 * obligation marked immediate — DPDP Rule 7's "without delay" leg — is reported as overdue from
 * the moment the breach is filed. That is not an off-by-one. There is no window during which
 * having told nobody is compliant, and a dashboard that showed such an obligation as "on schedule"
 * for its first hour would be inventing a grace period no regulator has offered.
 *
 * <p>Runs far more often than the rights sweeper by default. A rights deadline is measured in
 * weeks and a fifteen-minute cadence is ample; a breach deadline is seventy-two hours, of which
 * the first stage has no allowance at all, so a sweep that fires four times an hour is the
 * difference between noticing on the day and noticing on the day after.
 */
@Component
public class BreachSlaSweeper {

    private static final Logger log = LoggerFactory.getLogger(BreachSlaSweeper.class);

    private final BreachStore store;
    private final PlatformProperties properties;
    private final SweepLock lock;

    private volatile Report lastReport = new Report(Instant.EPOCH, 0, 0, List.of());

    public BreachSlaSweeper(BreachStore store, PlatformProperties properties, SweepLock lock) {
        this.store = store;
        this.properties = properties;
        this.lock = lock;
    }

    @Scheduled(fixedDelayString = "${uds.consent.sweeper.breach-sla-interval:PT5M}")
    public void sweep() {
        if (!properties.getSweeper().isBreachSlaEnabled()) {
            return;
        }
        // Locked for the same reason the rights sweep is: this pages people, and the same breach
        // reported once per replica every five minutes is how a real alert ends up filtered.
        lock.runExclusively("breach-sla", () -> run(Instant.now()));
    }

    /** Checks every outstanding obligation as at {@code asOf}. Driven directly by the tests. */
    public Report run(Instant asOf) {
        int limit = properties.getSweeper().getBreachSlaBatchSize();
        List<BreachStore.Notification> outstanding = store.outstanding(limit);

        int overdue = 0;
        int pending = 0;
        List<String> overdueBreaches = new java.util.ArrayList<>();

        for (BreachStore.Notification notification : outstanding) {
            if (notification.overdueAt(asOf)) {
                overdue++;
                overdueBreaches.add(notification.breachId() + ":" + notification.party());
                if (notification.immediate()) {
                    log.error("BREACH NOTIFICATION OUTSTANDING: {} owes {} an intimation without "
                                    + "delay and has not recorded one. Basis: {}",
                            notification.breachId(), notification.party(), notification.basis());
                } else {
                    log.error("STATUTORY BREACH: {} owes {} a report that was due {} — overdue by "
                                    + "{}. Basis: {}",
                            notification.breachId(), notification.party(), notification.dueAt(),
                            Duration.between(notification.dueAt(), asOf), notification.basis());
                }
            } else {
                pending++;
                log.warn("breach {} owes {} a report by {} — {} remaining",
                        notification.breachId(), notification.party(), notification.dueAt(),
                        Duration.between(asOf, notification.dueAt()));
            }
        }

        if (outstanding.isEmpty()) {
            log.debug("breach SLA sweep at {}: no outstanding obligations", asOf);
        }

        Report report = new Report(asOf, overdue, pending, List.copyOf(overdueBreaches));
        this.lastReport = report;
        return report;
    }

    public Report lastReport() {
        return lastReport;
    }

    /**
     * @param overdueObligations named rather than counted, because the response to this is always
     *                           "who is picking these up" and a count cannot be assigned to anyone
     */
    public record Report(Instant sweptAt, int overdue, int pending,
                         List<String> overdueObligations) {

        public boolean clean() {
            return overdue == 0;
        }
    }
}
