package com.uds.consent.service.events;

import com.uds.consent.ledger.store.OutboxStore;
import com.uds.consent.service.config.PlatformProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Drains the outbox to the broker.
 *
 * <p>Runs on a short cycle because the latency between a subject withdrawing and every downstream
 * system knowing is the thing this platform is judged on. A withdrawal that takes an hour to
 * propagate is a withdrawal the group has failed to honour for an hour.
 *
 * <p>Failures leave the message unpublished so the next pass retries it. Duplicate delivery is
 * possible — a send that succeeded at the broker but failed before the row was marked will be
 * sent again — so consumers must be idempotent. Consent events carry an event id and a per-subject
 * sequence number precisely so that they can be.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Attempts after which a message is reported rather than retried quietly. */
    private static final int ALERT_AFTER_ATTEMPTS = 10;

    private final OutboxStore outbox;
    private final EventPublisher publisher;
    private final PlatformProperties properties;
    private final PropagationReconciler reconciler;

    public OutboxRelay(OutboxStore outbox, EventPublisher publisher,
                       PlatformProperties properties, PropagationReconciler reconciler) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.properties = properties;
        this.reconciler = reconciler;
    }

    @Scheduled(fixedDelayString = "${uds.consent.events.relay-interval:PT2S}")
    public void relay() {
        List<OutboxStore.PendingMessage> pending =
                outbox.fetchUnpublished(properties.getEvents().getRelayBatchSize());
        if (pending.isEmpty()) {
            return;
        }

        int published = 0;
        for (OutboxStore.PendingMessage message : pending) {
            try {
                publisher.publish(message.topic(), message.eventKey(), message.payload(),
                        message.id(), message.attempts());
                outbox.markPublished(message.id());
                published++;

                // AFTER markPublished, never between it and publish(). Between the two, a throwing
                // evidence write would land in the catch below, mark the message failed, and cause
                // a second POST to a downstream system because recording a gap failed. The
                // reconciler swallows its own failures for the same reason and counts them instead.
                reconciler.reconcile(message.topic(), message.eventKey(), message.payload(),
                        message.id());
            } catch (RuntimeException e) {
                outbox.markFailed(message.id(), e.getMessage());
                if (message.attempts() + 1 >= ALERT_AFTER_ATTEMPTS) {
                    log.error("outbox message {} has failed {} times and is still undelivered; "
                                    + "downstream systems are not seeing consent changes",
                            message.id(), message.attempts() + 1, e);
                } else {
                    log.warn("outbox message {} failed to publish, will retry: {}",
                            message.id(), e.getMessage());
                }
                // Stop on first failure. Continuing would burn the whole batch against a broker
                // that is plainly unavailable, and would reorder messages for a subject whose
                // earlier event has just failed.
                break;
            }
        }

        if (published > 0) {
            log.debug("relayed {} consent event(s)", published);
        }
    }
}
