package com.uds.consent.service.events;

import com.uds.consent.core.crypto.CanonicalJson;
import com.uds.consent.ledger.store.PropagationGapStore;
import com.uds.consent.ledger.store.PropagationTargetStore;
import com.uds.consent.ledger.store.WebhookStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * After a message is published, records which systems that had to hear about it cannot be shown to
 * have heard.
 *
 * <p>This is the answer to the one adversarial question in {@code /phase-gate} step 5 the platform
 * could not answer: <em>a principal withdrew — prove it reached every consuming system, and name the
 * link that is assumed rather than evidenced.</em> The assumed link was here.
 * {@code WebhookEventPublisher} returned normally when no subscription matched, so
 * {@code OutboxRelay} called {@code markPublished} — and {@code event_outbox.published_at} has
 * therefore always meant <em>"the publisher did not throw"</em>, never <em>"anything received it"</em>.
 * A {@code webhook_delivery} row was structurally impossible for a system nobody registered, because
 * that row requires a {@code subscription_id}.
 *
 * <p><strong>It runs after {@code markPublished}, and that ordering is not incidental.</strong>
 * Between {@code publish} and {@code markPublished} a throwing evidence write would land in the
 * relay's catch block, mark the message failed, and cause <em>a second POST to DENCRM because an
 * evidence write failed</em>. Recording that a system was not told must never be able to tell it
 * twice.
 *
 * <p><strong>It must not throw.</strong> Failures are counted and logged, exactly as
 * {@code EnforcementRecorder.failedWrites} is, and for the same reason: a platform whose evidence
 * writes are failing should say so on a gauge rather than take the propagation path down with it.
 *
 * <p><strong>It records observations, never conclusions.</strong> See
 * {@link PropagationGapStore.Reason}. The default publisher writes no delivery evidence at all, and
 * saying "not delivered" there would be a false statement about a system that may well have received
 * everything.
 */
@Component
public class PropagationReconciler {

    private static final Logger log = LoggerFactory.getLogger(PropagationReconciler.class);

    private final PropagationTargetStore targets;
    private final PropagationGapStore gaps;
    private final WebhookStore deliveries;
    private final EventPublisher publisher;

    private final AtomicLong failedWrites = new AtomicLong();

    public PropagationReconciler(PropagationTargetStore targets, PropagationGapStore gaps,
                                 WebhookStore deliveries, EventPublisher publisher) {
        this.targets = targets;
        this.gaps = gaps;
        this.deliveries = deliveries;
        this.publisher = publisher;
    }

    /**
     * Reconciles one drained message against the register.
     *
     * <p>Called by {@link OutboxRelay} once the message is marked published. Swallows everything:
     * the relay's job is propagation, and this one's is bookkeeping about propagation.
     */
    public void reconcile(String topic, String eventKey, String payload, long outboxId) {
        try {
            reconcileOrThrow(topic, eventKey, payload, outboxId);
        } catch (RuntimeException e) {
            failedWrites.incrementAndGet();
            // Not rethrown, deliberately. See the class javadoc: throwing here would mark a
            // successfully delivered message as failed and re-POST it.
            log.error("could not reconcile propagation for outbox message {} on topic {}; "
                    + "the register is not being updated and uds.consent.propagation.failed_writes "
                    + "is rising", outboxId, topic, e);
        }
    }

    private void reconcileOrThrow(String topic, String eventKey, String payload, long outboxId) {
        // One resolver, shared with the publisher. The relay runs group-level, so row-level
        // security would happily accept a gap filed against the wrong fiduciary — rules §2 is
        // explicit that two resolvers are how two layers come to disagree.
        String entityId = OutboxKey.entityFrom(eventKey);
        if (entityId.isEmpty()) {
            // rights.verification.requested is keyed on the request reference alone, so it carries
            // no entity and can never route to a subscription. Structurally uncoverable rather than
            // unconfigured, and recorded as such in REGULATORY_HANDOFF §8.7 — not a gap row,
            // because there is no entity to file one against.
            return;
        }

        List<PropagationTargetStore.Coverage> mandatory = targets.mandatoryFor(entityId, topic);
        if (mandatory.isEmpty()) {
            // The same deliberate no-op as an empty fulfilment_target register: the platform cannot
            // know which of the group's systems hold a principal's data and will not invent them.
            // This is the state for every entity today.
            return;
        }

        String subjectId = OutboxKey.subjectFrom(eventKey);
        String eventType = eventTypeFrom(payload);
        boolean evidenced = publisher.writesDeliveryEvidence();

        for (PropagationTargetStore.Coverage target : mandatory) {
            PropagationGapStore.Reason reason;
            if (target.subscriptionId() == null) {
                // Nobody is registered to reach this system. The platform knows this one.
                reason = PropagationGapStore.Reason.NO_SUBSCRIPTION;
            } else if (!evidenced) {
                // A subscription exists, but the configured publisher writes no delivery rows, so
                // there is nothing to check it against. Do not infer non-delivery.
                reason = PropagationGapStore.Reason.NO_DELIVERY_CHANNEL;
            } else if (!deliveries.delivered(outboxId, target.subscriptionId())) {
                // A DELIVERED row, not merely a row: a connection refusal writes FAILED, and a
                // failed attempt must not satisfy the obligation.
                reason = PropagationGapStore.Reason.NOT_DELIVERED;
            } else {
                continue;
            }

            gaps.record(entityId, subjectId, topic, target.systemCode(), eventType, reason);
        }
    }

    /**
     * The event type from the payload, so a missed withdrawal and a missed grant are not one alert.
     *
     * <p>The field is {@code eventType}, not {@code type} — {@code ConsentLedger.publishablePayload}
     * spells it out, and the plan for this phase said {@code type}. Reading the wrong key would have
     * filed every gap with a null type, silently, because the column is nullable.
     *
     * <p>Null rather than a guess where the payload is not a consent event or does not parse: a
     * retention or verification message has no event type, and inventing one would put a fabricated
     * value into an append-only table.
     */
    private static String eventTypeFrom(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            Map<?, ?> parsed = CanonicalJson.parse(payload, Map.class);
            Object type = parsed.get("eventType");
            return type == null ? null : type.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Evidence writes this component could not complete.
     *
     * <p>Published as {@code uds.consent.propagation.failed_writes}, the same shape as
     * {@code uds.consent.enforcement.failed_writes}. Anything above zero means the platform is
     * propagating consent changes and is not recording what it could not show reached anybody.
     */
    public long failedWrites() {
        return failedWrites.get();
    }
}
