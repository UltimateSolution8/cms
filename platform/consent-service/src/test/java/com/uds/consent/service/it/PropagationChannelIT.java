package com.uds.consent.service.it;

import com.uds.consent.ledger.store.OutboxStore;
import com.uds.consent.ledger.store.PropagationGapStore;
import com.uds.consent.ledger.store.PropagationTargetStore;
import com.uds.consent.ledger.store.PropagationSystemStore;
import com.uds.consent.ledger.store.WebhookStore;
import com.uds.consent.service.events.OutboxRelay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Under the publisher the pilot actually runs, the platform says it cannot see — it does not guess.
 *
 * <p>A separate suite because the reason a gap records depends on the configured publisher, and
 * {@code PropagationIT} pins {@code webhook} for its whole class. **This is the branch the whole
 * design was rewritten around** — review finding D2 — and it had no test at all until this file:
 * the reconciler chooses between recording *"nobody was reachable"*, *"we have no way to know"* and
 * *"a subscription exists and produced no successful delivery"*, and only one of those three is a
 * claim about a downstream system.
 *
 * <p>{@code webhook_delivery} is written by the webhook publisher and by nothing else.
 * {@code LoggingEventPublisher} and {@code KafkaEventPublisher} discard {@code outboxId}
 * deliberately, and {@code log} is the default — so under the shipped configuration the platform
 * cannot observe delivery. Recording {@code NOT_DELIVERED} there would assert, once a day per
 * target and permanently, that a system was not told when the truth is that nobody here can know.
 * Under {@code kafka}, with a consumer processing everything correctly, it would simply be false.
 *
 * <p>That is the same false statement as answering "no recipients" on a receipt where the truth is
 * that nobody wrote the recipients down — the defect
 * {@code .claude/rules/consent-management.md} §1 spends a paragraph refusing.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // The default, and what the Denave pilot runs. The point of the suite.
                "uds.consent.events.publisher=log",
                "uds.consent.events.relay-interval=PT1H"
        })
class PropagationChannelIT extends PostgresIntegrationTest {

    private static final String ENTITY = "DENAVE_IN";
    private static final String TOPIC = "uds.consent.events";

    @Autowired
    private PropagationSystemStore propagationSystems;

    @Autowired
    private PropagationTargetStore targets;

    @Autowired
    private PropagationGapStore gaps;

    @Autowired
    private WebhookStore webhooks;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxStore outbox;

    @Autowired
    private OutboxRelay relay;

    @Test
    @DisplayName("with no delivery channel the platform records that it cannot see, not that nobody was told")
    void theReasonIsNoDeliveryChannelAndNotNotDelivered() {
        String system = "CHANIT_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // A subscription that MATCHES the target, so NO_SUBSCRIPTION is ruled out and the choice is
        // genuinely between the two remaining reasons. Without this the test would pass against a
        // reconciler that never consults the publisher at all.
        declare(system.toUpperCase(java.util.Locale.ROOT));
        webhooks.upsert(system, ENTITY, TOPIC, "http://127.0.0.1:1/hook", "secret", true,
                "matched but unobservable under the log publisher");
        declare(system);
        targets.upsert(ENTITY, TOPIC, system, true, true, "channel fixture");

        drain(enqueue());

        assertThat(gapsFor(system))
                .withFailMessage("no gap was recorded at all; an unobservable channel is still an "
                        + "obligation the platform cannot show was met")
                .isNotEmpty()
                .allSatisfy(gap -> assertThat(gap.reason())
                        .withFailMessage("the platform asserted a downstream system was NOT told, "
                                + "when the configured publisher writes no delivery evidence and it "
                                + "has no way to know — a false statement in an append-only table")
                        .isEqualTo(PropagationGapStore.Reason.NO_DELIVERY_CHANNEL));
    }

    @Test
    @DisplayName("an unregistered system is still NO_SUBSCRIPTION, whatever the channel")
    void anUnreachableTargetOutranksTheChannel() {
        // The one reason that is a fact about configuration rather than about observability: if
        // nothing is subscribed, the platform knows nobody was reachable regardless of publisher.
        // Ordering matters — checking the channel first would mask every unregistered system.
        String system = "CHANIT_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        declare(system);
        targets.upsert(ENTITY, TOPIC, system, true, true, "unregistered under log");

        drain(enqueue());

        assertThat(gapsFor(system))
                .isNotEmpty()
                .allSatisfy(gap -> assertThat(gap.reason())
                        .isEqualTo(PropagationGapStore.Reason.NO_SUBSCRIPTION));
    }

    // -----------------------------------------------------------------------------------

    private long enqueue() {
        outbox.enqueue(TOPIC, ENTITY + '|' + "chan-subject-" + UUID.randomUUID(),
                Map.of("eventType", "WITHDRAWN", "purposeCode", "MKT_OUTBOUND_CALL"));
        return outbox.fetchUnpublished(1000).stream()
                .mapToLong(OutboxStore.PendingMessage::id)
                .max()
                .orElseThrow();
    }

    private void drain(long outboxId) {
        for (int pass = 0; pass < 25; pass++) {
            boolean pending = outbox.fetchUnpublished(1000).stream()
                    .anyMatch(message -> message.id() == outboxId);
            if (!pending) {
                return;
            }
            relay.relay();
        }
        throw new AssertionError("outbox message " + outboxId + " could not be drained");
    }

    private List<PropagationGapStore.Gap> gapsFor(String systemCode) {
        for (int attempt = 0; attempt < 50; attempt++) {
            List<PropagationGapStore.Gap> found = gaps.forEntity(ENTITY, null, 1000, 0).stream()
                    .filter(gap -> gap.systemCode().equals(systemCode))
                    .toList();
            if (!found.isEmpty()) {
                return found;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return List.of();
    }

    /**
     * Declares a system code before it is used on either side of the propagation join.
     *
     * <p>{@code V33} put a foreign key on {@code propagation_system} from both
     * {@code propagation_target} and {@code webhook_subscription}, so a code the entity has not
     * declared is refused by the database rather than silently producing a daily gap row for a
     * system that may be perfectly reachable. Fixtures declare theirs the way an operator would.
     */
    private void declare(String systemCode) {
        propagationSystems.upsert(ENTITY, systemCode, "test fixture", true);
    }
}
