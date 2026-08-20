package com.uds.consent.service.config;

import com.uds.consent.ledger.store.PropagationTargetStore;
import com.uds.consent.service.sweeper.RetentionSweeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Names any propagation target registered against a topic the platform never publishes.
 *
 * <p>The register is joined on {@code (entity_id, topic, system_code)}, and the topic comes from
 * {@code uds.consent.events.topic} — a property whose javadoc on {@code ConsentLedger} openly invites
 * a group to change it. Change it, and every existing target stops matching anything the relay ever
 * drains: the reconciler finds no mandatory targets for the new topic, writes nothing, the uncovered
 * gauge reads zero and <strong>the alert says all clear</strong>.
 *
 * <p>That is a register which fails open and looks exactly like success, which is the worst
 * available failure mode for a control whose entire job is to say when something was not done. The
 * {@code PUT} route validates the topic on the way in; this catches the other direction, where the
 * targets were right and the configuration moved underneath them.
 *
 * <p><strong>WARN rather than a refusal to start</strong>, on the same reasoning as
 * {@link EntityContactCheck}: a mis-targeted register makes evidence thin, it does not make decisions
 * wrong, and taking the decision API out of service over a configuration gap would turn a
 * bookkeeping failure into an outage. The pepper is a hard gate because running without it corrupts
 * evidence; this only leaves it incomplete, and says so.
 */
@Component
public class PropagationTopicCheck {

    private static final Logger log = LoggerFactory.getLogger(PropagationTopicCheck.class);

    private final PropagationTargetStore targets;
    private final PlatformProperties properties;

    public PropagationTopicCheck(PropagationTargetStore targets, PlatformProperties properties) {
        this.targets = targets;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        // Every topic the platform actually enqueues to, not just the consent stream. Checking
        // against one of two would have named uds.consent.retention as unpublished, which is false
        // — and a check that raises a false warning is one an operator learns to ignore.
        List<String> published = List.of(properties.getEvents().getTopic(),
                RetentionSweeper.TOPIC_RETENTION);
        List<String> orphaned = targets.distinctTopics().stream()
                .filter(topic -> !published.contains(topic))
                .toList();

        reportUnobservableChannel();

        if (orphaned.isEmpty()) {
            return;
        }

        log.warn("""
                        {} propagation target topic(s) are not published by this platform: {}. \
                        The platform publishes to {}.

                        Targets on a topic nothing is enqueued to can never be reconciled: the \
                        relay drains no message matching them, no gap is ever recorded, and \
                        uds.consent.propagation.uncovered reads zero — which is indistinguishable \
                        from full coverage. Either uds.consent.events.topic was changed after the \
                        register was populated, or the targets were registered against the wrong \
                        stream. Correct one of the two; do not leave it reading as satisfied.""",
                orphaned.size(), orphaned, published);
    }

    /**
     * Names the condition where targets are registered and the platform cannot evidence delivery.
     *
     * <p>{@code webhook_delivery} is written by the webhook publisher and by nothing else. The
     * default is {@code log}, which is what the Denave pilot runs — so with mandatory targets
     * registered, every gap row reads {@code NO_DELIVERY_CHANNEL}: honest, and not evidence of
     * anything.
     *
     * <p><strong>The instruments look identical to full coverage.</strong>
     * {@code uds.consent.propagation.uncovered} reads zero the moment a subscription exists,
     * whether or not the configured publisher can ever observe a delivery through it, and nothing
     * else says otherwise. A Board asking "prove the withdrawal reached DENCRM" would get "we
     * published it, and we have no way to see whether anyone received it" — which is the right
     * answer and is not the one the dashboard implies.
     *
     * <p>An empty register is deliberately <em>not</em> a finding. Nobody has claimed an obligation
     * yet, so there is nothing the platform is failing to evidence.
     */
    private void reportUnobservableChannel() {
        long registered = targets.distinctTopics().isEmpty() ? 0 : 1;
        if (registered == 0) {
            return;
        }

        // The BOUND value, not the property's presence. A check asserting that a publisher
        // property exists would pass under every configuration, which is defect class 4 — the
        // OTLP endpoint key is the precedent this project already paid for once.
        String publisher = properties.getEvents().getPublisher();
        if (publisher != null && publisher.toLowerCase(java.util.Locale.ROOT).contains("webhook")) {
            return;
        }

        log.warn("""
                        Propagation targets are registered and the configured publisher is '{}',                         which writes no delivery evidence.

                        webhook_delivery is written by the webhook publisher alone. Under 'log'                         and 'kafka' the platform can say it published a consent change and cannot                         say that any system received it, so every gap row will read                         NO_DELIVERY_CHANNEL and the uncovered gauge will read zero — a set of                         instruments indistinguishable from full coverage. Set                         uds.consent.events.publisher to include 'webhook' before treating the                         register as evidence. REGULATORY_HANDOFF.md 8.7.""",
                publisher);
    }
}
