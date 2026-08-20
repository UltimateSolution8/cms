package com.uds.consent.service.events;

import com.uds.consent.ledger.store.OutboxStore;
import com.uds.consent.ledger.store.SweepRunStore;
import com.uds.consent.service.config.PlatformProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
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
 *
 * <p><strong>That concession is about the crash window, and it was never meant to cover the steady
 * state.</strong> Until {@link OutboxStore#claimUnpublished(int)} existed the batch was selected
 * with no lock while three replicas ran the same schedule, so every subscriber received each event
 * up to three times as a matter of course and {@code webhook_delivery} carried up to three rows per
 * attempt. The claim is now {@code for update skip locked} inside this method's transaction, so the
 * replicas take disjoint batches.
 *
 * <p>The relay records itself in {@code sweep_run} like the seven sweepers, but does so directly:
 * it deliberately does <em>not</em> take a {@code SweepLock}, because that would serialise fan-out
 * onto one instance.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Attempts after which a message is reported rather than retried quietly. */
    private static final int ALERT_AFTER_ATTEMPTS = 10;

    /** The name this job records itself under in {@code sweep_run}, and the staleness gauge's tag. */
    public static final String SWEEP_NAME = "outbox-relay";

    private final OutboxStore outbox;
    private final EventPublisher publisher;
    private final PlatformProperties properties;
    private final PropagationReconciler reconciler;
    private final SweepRunStore runs;
    private final String instance = hostname();

    public OutboxRelay(OutboxStore outbox, EventPublisher publisher,
                       PlatformProperties properties, PropagationReconciler reconciler,
                       SweepRunStore runs) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.properties = properties;
        this.reconciler = reconciler;
        this.runs = runs;
    }

    /**
     * Transactional so the {@code skip locked} claim holds for the batch.
     *
     * <p>The publish call happens inside it, which is the standard outbox trade: the rows stay
     * locked for as long as one batch takes to send, and in exchange no other replica can send the
     * same message. The loop breaks on the first failure, so a broker that is down costs one
     * attempt rather than a whole batch held open.
     */
    @Scheduled(fixedDelayString = "${uds.consent.events.relay-interval:PT2S}")
    @Transactional
    public void relay() {
        List<OutboxStore.PendingMessage> pending =
                outbox.claimUnpublished(properties.getEvents().getRelayBatchSize());
        if (pending.isEmpty()) {
            return;
        }
        // Recorded only when there was something to do. A relay that ticks every two seconds over
        // an empty outbox would otherwise rewrite this row 43,000 times a day to say nothing.
        recordRun(true);

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
        recordRun(false);
    }

    /**
     * Records the relay in {@code sweep_run}, swallowing any failure.
     *
     * <p>Bookkeeping about the job, not the job's own work: letting a failed write abort a
     * withdrawal's fan-out would trade the thing that matters for the thing that describes it.
     *
     * <p><strong>{@code REQUIRES_NEW}, and the catch alone was not enough.</strong> {@code relay()}
     * is {@code @Transactional}, and in PostgreSQL a failed statement aborts the whole transaction —
     * so catching the exception here left the batch's transaction poisoned and every later
     * statement in it, including {@code markPublished} and the delivery writes, failed and rolled
     * back. The javadoc claimed the opposite trade and the code delivered it. A separate
     * transaction is what actually makes the swallow true. {@code SweepLock} needs none of this
     * because nothing surrounds it.
     */
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    void recordRun(boolean starting) {
        try {
            if (starting) {
                runs.started(SWEEP_NAME, instance, Instant.now());
            } else {
                runs.finished(SWEEP_NAME, Instant.now(), true);
            }
        } catch (RuntimeException e) {
            log.warn("could not record the outbox relay run: {}", e.getMessage());
        }
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
