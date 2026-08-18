package com.uds.consent.service.sweeper;

import com.uds.consent.ledger.store.RightsRequestStore;
import com.uds.consent.service.config.PlatformProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Watches the statutory clocks and says so out loud when one runs out.
 *
 * <p>The point of this class is that nobody has to remember to look. A rights request has a
 * deadline fixed by statute, and the failure mode is not that somebody decides to miss it — it is
 * that a request sits in a queue nobody opened for six weeks. That failure is silent by nature,
 * which is why the countermeasure has to be noisy.
 *
 * <p>Two levels, deliberately different:
 *
 * <ul>
 *   <li><strong>Approaching</strong> logs at WARN. There is still time to answer.</li>
 *   <li><strong>Breached</strong> logs at ERROR. There is not. Each line is a statutory breach
 *       that has already happened, and it stays in the log every pass until the request is closed
 *       — repetition being the only property that reliably gets something looked at.</li>
 * </ul>
 *
 * <p>Wire the ERROR level to the on-call channel, per the operations notes. A sweeper whose output
 * goes only to a log file is a sweeper that has been run rather than read.
 */
@Component
public class RightsSlaSweeper {

    private static final Logger log = LoggerFactory.getLogger(RightsSlaSweeper.class);

    private final RightsRequestStore store;
    private final PlatformProperties properties;
    private final SweepLock lock;

    private volatile Report lastReport = new Report(Instant.EPOCH, 0, 0, List.of());

    public RightsSlaSweeper(RightsRequestStore store, PlatformProperties properties,
                            SweepLock lock) {
        this.store = store;
        this.properties = properties;
        this.lock = lock;
    }

    @Scheduled(fixedDelayString = "${uds.consent.sweeper.rights-sla-interval:PT15M}")
    public void sweep() {
        if (!properties.getSweeper().isRightsSlaEnabled()) {
            return;
        }
        // Locked because this one pages people. The same breach reported once per replica, every
        // fifteen minutes, is how a real alert gets filtered into a folder nobody opens — which is
        // precisely the failure this sweeper exists to prevent.
        lock.runExclusively("rights-sla", () -> run(Instant.now()));
    }

    /**
     * Checks every open request as at {@code asOf}.
     *
     * <p>Separate from the scheduled entry point so tests can drive it with a controlled clock.
     * Deadline behaviour that can only be exercised by waiting a month is deadline behaviour that
     * does not get tested.
     */
    public Report run(Instant asOf) {
        int limit = properties.getSweeper().getRightsSlaBatchSize();
        List<RightsRequestStore.Request> breached = store.findOverdue(asOf, limit);
        Duration warnWindow = properties.getSweeper().getRightsSlaWarningWindow();
        List<RightsRequestStore.Request> approaching =
                store.findDueWithin(asOf, asOf.plus(warnWindow), limit);

        for (RightsRequestStore.Request request : breached) {
            // The subject id is a privacy-minimal reference, not an identifier — safe to log, and
            // necessary, because the first thing anyone acting on this needs is which request.
            log.error("STATUTORY BREACH: rights request {} ({}, {}) for entity {} was due {} and "
                            + "is still {}. Overdue by {}. Basis: {}",
                    request.requestId(), request.type(), request.jurisdiction(),
                    request.entityId(), request.dueAt(), request.status(),
                    Duration.between(request.dueAt(), asOf), request.dueAtBasis());
        }

        for (RightsRequestStore.Request request : approaching) {
            log.warn("rights request {} ({}) for entity {} is due {} — {} remaining, status {}",
                    request.requestId(), request.type(), request.entityId(), request.dueAt(),
                    Duration.between(asOf, request.dueAt()), request.status());
        }

        if (breached.isEmpty() && approaching.isEmpty()) {
            log.debug("rights SLA sweep at {}: nothing overdue or approaching", asOf);
        }

        Report report = new Report(asOf, breached.size(), approaching.size(),
                breached.stream().map(RightsRequestStore.Request::requestId).toList());
        this.lastReport = report;
        return report;
    }

    public Report lastReport() {
        return lastReport;
    }

    /**
     * @param breachedRequestIds named rather than counted, because a count cannot be assigned to
     *                           anyone and the response to this is always "who is picking these up"
     */
    public record Report(Instant sweptAt, int breached, int approaching,
                         List<String> breachedRequestIds) {

        public boolean clean() {
            return breached == 0;
        }
    }
}
