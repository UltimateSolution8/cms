package com.uds.consent.service.it;

import com.sun.net.httpserver.HttpServer;
import com.uds.consent.ledger.store.OutboxStore;
import com.uds.consent.ledger.store.PropagationCoverageStore;
import com.uds.consent.ledger.store.PropagationGapStore;
import com.uds.consent.ledger.store.PropagationTargetStore;
import com.uds.consent.ledger.store.PropagationSystemStore;
import com.uds.consent.ledger.store.WebhookStore;
import com.uds.consent.service.events.OutboxRelay;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The platform can now say who was <em>not</em> told.
 *
 * <p>{@code /phase-gate} step 5 asks seven adversarial questions and the platform could answer six.
 * The second it could not: <em>a principal withdrew — prove it reached every consuming system, and
 * name the link that is assumed rather than evidenced.</em> The assumed link was
 * {@code WebhookEventPublisher} returning normally when nothing was subscribed, after which
 * {@code markPublished} recorded success. {@code event_outbox.published_at} meant "the publisher did
 * not throw", and a {@code webhook_delivery} row was structurally impossible for a system nobody had
 * registered.
 *
 * <p>This suite runs under the <strong>webhook</strong> publisher except where it deliberately does
 * not, because the reason recorded for a gap depends on whether the configured publisher writes
 * delivery evidence at all — and asserting the reason rather than the row count is what stops this
 * passing against a reconciler that ignores {@code webhook_delivery} entirely.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "uds.consent.events.publisher=webhook",
                // Driven by hand. A timer firing mid-assertion would drain a message the test has
                // not finished setting up.
                "uds.consent.events.relay-interval=PT1H"
        })
class PropagationIT extends PostgresIntegrationTest {

    private static final String ENTITY = "DENAVE_IN";
    private static final String TOPIC = "uds.consent.events";

    @Autowired
    private PropagationSystemStore propagationSystems;

    @Autowired
    private PropagationTargetStore targets;

    @Autowired
    private PropagationCoverageStore coverage;

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

    @Autowired
    private com.uds.consent.service.events.PropagationReconciler reconciler;

    private HttpServer server;
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);

    @BeforeEach
    void startReceiver() throws IOException {
        // Subscriptions are configuration and outlive a test class. Any left active by an earlier
        // suite points at an HTTP server that has since stopped, so the relay's first delivery
        // attempt throws, it breaks out of the batch, and this suite's message is never drained —
        // which presents as "no gap was recorded" and looks exactly like the feature not working.
        // Deactivating them is fixture hygiene, not a workaround for the relay's break-on-failure,
        // which is correct behaviour and is asserted elsewhere.
        deactivateEverySubscription();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/hook", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopReceiver() {
        // Drain what this suite enqueued before the receiver goes away.
        //
        // aFailedDeliveryIsNotPropagation deliberately leaves a message undelivered, and the relay
        // correctly breaks on it. Left there, it is drained by whichever suite runs next — whose
        // receiver then counts a message it never sent, which is how one test's fixture becomes
        // another's phantom failure. It cost two green suites to learn that here.
        status.set(200);
        for (int pass = 0; pass < 25 && !outbox.fetchUnpublished(1).isEmpty(); pass++) {
            relay.relay();
        }

        // Then leave the world as it was found. A subscription left active here points at the
        // server stopped on the next line, so the NEXT suite's relay throws on its first delivery
        // and breaks out of the batch before reaching its own message — which presents there as
        // "nothing arrived at the subscriber" and is completely opaque from inside that suite.
        deactivateEverySubscription();
        server.stop(0);
    }

    /**
     * Deactivates every subscription this suite's entities hold.
     *
     * <p>Subscriptions are configuration: they outlive a test class, and each one points at an
     * ephemeral HTTP server that stops when its class finishes. Both directions matter — inherited
     * ones would break this suite, and ones left behind break the next.
     */
    private void deactivateEverySubscription() {
        // EVERY entity, not a named couple. Subscriptions belong to whichever suite created them
        // and there are fifteen fiduciaries; a message for an entity whose endpoint died with some
        // other test class breaks the relay just as effectively, and the relay stops the whole
        // batch on the first failure.
        jdbc.update("update webhook_subscription set active = false where active");
    }

    @Test
    @DisplayName("the register decides what is recorded, not the traffic")
    void anEmptyRegisterIsANoOp() {
        // The same deliberate no-op as an unconfigured fulfilment_target register, and it is the
        // state every entity is in until UDS populates it. A platform that invented targets would
        // be asserting an obligation nobody declared.
        //
        // This test used to look up a system that had never been registered and assert its gap
        // count had not changed — a count that was zero by construction, so the assertion held
        // under a reconciler that did nothing at all. What makes it a no-OP rather than a no-op is
        // the control: the same message, in the same relay pass, must produce a gap for a system
        // that IS registered. Three systems, one message, three different outcomes decided by the
        // register alone.
        String registered = uniqueSystem();
        String neverRegistered = uniqueSystem();
        String deactivated = uniqueSystem();

        declare(registered);

        targets.upsert(ENTITY, TOPIC, registered, true, true, "the control");
        declare(deactivated);
        targets.upsert(ENTITY, TOPIC, deactivated, true, false, "registered, then switched off");

        subscribe();
        drain(enqueue(ENTITY, withdrawal()));

        // The control. Without it every assertion below passes against a reconciler that never ran.
        assertThat(awaitGapsFor(registered))
                .withFailMessage("the message produced no gap for a mandatory registered system, "
                        + "so the two absences below prove nothing")
                .isNotEmpty();

        assertThat(gapsFor(neverRegistered))
                .withFailMessage("a system nobody registered was recorded as untold, which asserts "
                        + "an obligation UDS never declared")
                .isEmpty();

        assertThat(gapsFor(deactivated))
                .withFailMessage("an inactive target was recorded, so the register's active flag "
                        + "is not being read")
                .isEmpty();
    }

    @Test
    @DisplayName("an unreachable target is uncovered, and registering the subscription clears it")
    void uncoveredReturnsToZero() {
        // The assertion the first design of this phase could not have passed, and the reason it was
        // rebuilt. That design counted unreconciled messages — a count that can never fall, because
        // a published message is never re-published. Register DENCRM at 09:00, register its
        // subscription at 09:05, and the events in between would have been open forever: the gauge
        // never returns to zero, the critical alert fires for the life of the database, and it is
        // muted inside a week.
        //
        // Current state is derived from the register instead, so fixing the configuration fixes the
        // number. That reachability is the whole point of splitting state from evidence.
        String system = uniqueSystem();
        declare(system);
        targets.upsert(ENTITY, TOPIC, system, true, true, "reachability fixture");

        assertThat(coverage.uncovered(ENTITY))
                .withFailMessage("a mandatory target with no subscription did not read as uncovered")
                .extracting(PropagationCoverageStore.Uncovered::systemCode)
                .contains(system);

        declare(system.toUpperCase(java.util.Locale.ROOT));

        webhooks.upsert(system, ENTITY, TOPIC, hookUrl(), "secret-" + system, true, "now reachable");

        assertThat(coverage.uncovered(ENTITY))
                .withFailMessage("uncovered did not return to zero after the subscription was "
                        + "registered, so the alert built on it could never clear")
                .extracting(PropagationCoverageStore.Uncovered::systemCode)
                .doesNotContain(system);
    }

    @Test
    @DisplayName("a system nobody registered is recorded as NO_SUBSCRIPTION, against the subject")
    void anUnregisteredSystemIsRecorded() {
        String system = uniqueSystem();
        declare(system);
        targets.upsert(ENTITY, TOPIC, system, true, true, "unregistered fixture");
        subscribe();

        // Probe the register directly first. Without this, a failure below cannot distinguish
        // "the reconciler never saw this target" from "the reconciler saw it and did nothing".
        assertThat(targets.mandatoryFor(ENTITY, TOPIC))
                .withFailMessage("the register does not report the target the test just wrote")
                .anySatisfy(target -> {
                    assertThat(target.systemCode()).isEqualTo(system);
                    assertThat(target.subscriptionId()).isNull();
                });

        long failuresBefore = reconciler.failedWrites();
        drain(enqueue(ENTITY, withdrawal()));

        // The reconciler swallows its own failures so an evidence write can never cause a second
        // POST downstream. That makes a broken write silent, so the suite asserts the counter too —
        // otherwise "no gap recorded" and "the insert threw" are indistinguishable.
        assertThat(reconciler.failedWrites())
                .withFailMessage("the reconciler could not write; the gap below is missing because "
                        + "the write failed, not because nothing was found")
                .isEqualTo(failuresBefore);

        assertThat(awaitGapsFor(system))
                .withFailMessage("a mandatory target with no subscription left no trace of not "
                        + "having been told — which is the finding this phase exists to close")
                .isNotEmpty()
                .allSatisfy(gap -> {
                    assertThat(gap.reason()).isEqualTo(PropagationGapStore.Reason.NO_SUBSCRIPTION);
                    // Carried on the row rather than recovered by joining event_outbox, which has
                    // no entity_id, no subject_id and no RLS policy.
                    assertThat(gap.subjectId()).isNotNull();
                    // So a missed withdrawal and a missed grant are not the same alert.
                    assertThat(gap.eventType()).isEqualTo("WITHDRAWN");
                });
    }

    @Test
    @DisplayName("a FAILED delivery does not satisfy the obligation")
    void aFailedDeliveryIsNotPropagation() {
        // The distinction RightsFulfilmentStore already makes with status = 'COMPLETED'. A
        // connection refusal writes a FAILED row, so a check for "a delivery row exists" would be
        // satisfied by a failure — the platform would report a system told when it was unreachable.
        String system = uniqueSystem();
        String subscriptionId = system;
        declare(subscriptionId.toUpperCase(java.util.Locale.ROOT));
        webhooks.upsert(subscriptionId, ENTITY, TOPIC, hookUrl(), "secret", true, "failing endpoint");
        declare(system);
        targets.upsert(ENTITY, TOPIC, system, true, true, "failure fixture");

        status.set(503);
        long outboxId = enqueue(ENTITY, withdrawal());
        relay.relay();

        // The 503 leaves the message unpublished and the relay breaks, so the reconciler does not
        // run for it — which is the asymmetry OPERATIONS.md §4 now records rather than hides. Drain
        // it successfully and the delivery evidence for THIS attempt is what decides the outcome.
        assertThat(webhooks.deliveriesFor(outboxId))
                .withFailMessage("the failing attempt was not recorded")
                .isNotEmpty()
                .allSatisfy(d -> assertThat(d.status()).isEqualTo("FAILED"));

        assertThat(webhooks.delivered(outboxId, subscriptionId))
                .withFailMessage("a FAILED delivery counted as arrival, so the platform would "
                        + "report a system told that was never reached")
                .isFalse();
    }

    @Test
    @DisplayName("reconciling the same message twice leaves one gap row, not two")
    void theRecordIsIdempotentUnderConcurrentRelays() {
        // OutboxStore.fetchUnpublished takes no lock and OutboxRelay — unlike all seven sweepers —
        // takes no SweepLock, while deploy/k8s ships replicas: 3. So three relays reconcile the same
        // message. Idempotency comes from the daily unique key, never from a lock: asserting a lock
        // was taken would be asserting the mechanism.
        //
        // Reconciling ONE message twice, which is the property the plan asked for. An earlier
        // version of this test enqueued two messages for two DIFFERENT subjects and asserted one
        // row — so it passed by demonstrating that the second principal's unmet obligation was
        // discarded, under a name claiming the opposite. That is the daily key working as designed
        // and it is not idempotency; the limit is now stated on the column instead of hidden in a
        // green test. See theDailyGrainKeepsOnlyTheFirstMessageOfADay below.
        String system = uniqueSystem();
        declare(system);
        targets.upsert(ENTITY, TOPIC, system, true, true, "idempotency fixture");
        subscribe();

        String key = ENTITY + "|idem-subject-" + UUID.randomUUID();
        reconciler.reconcile(TOPIC, key, "{}", 999_000_001L);
        reconciler.reconcile(TOPIC, key, "{}", 999_000_001L);

        assertThat(gapsFor(system))
                .withFailMessage("the same unmet obligation was recorded twice; the register would "
                        + "over-count every gap by the number of relay replicas")
                .hasSize(1);
    }

    @Test
    @DisplayName("a subscription exists and produced no successful delivery, so the reason is NOT_DELIVERED")
    void aSubscriptionWithoutSuccessfulDeliveryIsNotDelivered() {
        // The third reason, and the one that is hardest to reach in the shipped topology: a failing
        // delivery throws, so the message stays unpublished and the reconciler never runs for it.
        // Reached here by reconciling an outbox id that has no delivery row against a subscription
        // that does match the target — which is the state after a delivery the platform has no
        // successful record of.
        //
        // Asserting the REASON, not the row count. A count assertion passes against a reconciler
        // that never consults webhook_delivery at all, which is precisely how D3 could have shipped
        // broken.
        String system = uniqueSystem();
        declare(system.toUpperCase(java.util.Locale.ROOT));
        webhooks.upsert(system, ENTITY, TOPIC, hookUrl(), "secret-" + system, true,
                "matched, but with no successful delivery for this message");
        declare(system);
        targets.upsert(ENTITY, TOPIC, system, true, true, "not-delivered fixture");

        reconciler.reconcile(TOPIC, ENTITY + "|nd-subject-" + UUID.randomUUID(), "{}", 999_000_002L);

        assertThat(gapsFor(system))
                .isNotEmpty()
                .allSatisfy(gap -> assertThat(gap.reason())
                        .withFailMessage("a matched subscription with no DELIVERED row did not "
                                + "produce NOT_DELIVERED, so the coverage check is not reading "
                                + "delivery evidence at all")
                        .isEqualTo(PropagationGapStore.Reason.NOT_DELIVERED));
    }

    @Test
    @DisplayName("the daily grain keeps the first message of a day and discards the rest")
    void theDailyGrainKeepsOnlyTheFirstMessageOfADay() {
        // Not a defect — a deliberate bound, and the reason the evidence bundle must not present
        // this table as per-principal evidence. Admitting subject_id to the unique key would make
        // growth targets × subjects × days, unbounded by population, which is exactly what the
        // daily grain exists to avoid.
        //
        // Asserted rather than left in a comment, because a limit nobody has written a test for is
        // one somebody later "fixes" by widening the key, and the growth characteristic goes with
        // it. If this test starts failing, that trade has been re-made and REGULATORY_HANDOFF §8.7
        // and the bundle's javadoc both need revisiting.
        String system = uniqueSystem();
        declare(system);
        targets.upsert(ENTITY, TOPIC, system, true, true, "grain fixture");
        subscribe();

        reconciler.reconcile(TOPIC, ENTITY + "|grain-first-" + UUID.randomUUID(), "{}", 999_000_003L);
        reconciler.reconcile(TOPIC, ENTITY + "|grain-second-" + UUID.randomUUID(), "{}", 999_000_004L);

        assertThat(gapsFor(system))
                .withFailMessage("the daily grain changed; the bundle's propagation section and "
                        + "handoff §8.7 both describe the old one")
                .hasSize(1);
    }

    @Test
    @DisplayName("another entity's gaps are invisible")
    void gapsAreScopedToTheirEntity() {
        String system = uniqueSystem();
        declare(system);
        targets.upsert(ENTITY, TOPIC, system, true, true, "isolation fixture");
        subscribe();
        drain(enqueue(ENTITY, withdrawal()));

        assertThat(gaps.forEntity("MATRIX", null, 500, 0))
                .withFailMessage("Denave's propagation gaps are readable under Matrix — a map of "
                        + "one group company's integration estate, disclosed to another")
                .noneMatch(gap -> gap.systemCode().equals(system));
    }

    // -----------------------------------------------------------------------------------

    private static java.util.Map<String, Object> withdrawal() {
        return java.util.Map.of("eventType", "WITHDRAWN", "purposeCode", "MKT_OUTBOUND_CALL");
    }

    /**
     * Waits for the gap this test expects, rather than for the message to be published.
     *
     * <p>The reconciler runs <strong>after</strong> {@code markPublished} — deliberately, so that a
     * failing evidence write can never mark a delivered message failed and re-POST it. That means
     * "the message is published" does not imply "the gap is written", in this suite or in
     * production, and a test that drains and asserts immediately is asserting the wrong thing. It
     * also loses a race against the scheduled relay, which drains the same batch on its own thread.
     */
    private List<PropagationGapStore.Gap> awaitGapsFor(String systemCode) {
        for (int attempt = 0; attempt < 50; attempt++) {
            List<PropagationGapStore.Gap> found = gapsFor(systemCode);
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
        return gapsFor(systemCode);
    }

    /** A fresh system code per test, so one test's register cannot decide another's outcome. */
    private static String uniqueSystem() {
        return "PROPIT_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private String hookUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    /** One always-reachable subscription, so the relay has somewhere to publish. */
    private void subscribe() {
        declare("hook-propagation-it".toUpperCase(java.util.Locale.ROOT));
        webhooks.upsert("hook-propagation-it", ENTITY, TOPIC, hookUrl(), "propagation-it-secret",
                true, "propagation suite receiver");
    }

    private List<PropagationGapStore.Gap> gapsFor(String systemCode) {
        return gaps.forEntity(ENTITY, null, 1000, 0).stream()
                .filter(gap -> gap.systemCode().equals(systemCode))
                .toList();
    }

    /**
     * Enqueues one consent event.
     *
     * <p>The payload is a {@link Map}, not a JSON string, and that is not cosmetic.
     * {@code OutboxStore.enqueue} takes an {@code Object} and serialises it — hand it a string and
     * it stores a quoted JSON <em>scalar</em> rather than an object, so the reconciler's parse
     * correctly fails and every gap is filed with a null event type. The first draft of this suite
     * did exactly that and the reconciler behaved correctly throughout.
     */
    private long enqueue(String entityId, java.util.Map<String, Object> payload) {
        outbox.enqueue(TOPIC, entityId + '|' + "prop-subject-" + UUID.randomUUID(), payload);
        return outbox.fetchUnpublished(1000).stream()
                .mapToLong(OutboxStore.PendingMessage::id)
                .max()
                .orElseThrow();
    }

    /**
     * Relays until the named message has actually been drained.
     *
     * <p>One {@code relay()} is not enough and assuming it is produced a test that failed for a
     * reason that had nothing to do with propagation. The relay takes a bounded batch and
     * <strong>breaks on the first failure</strong> — correct behaviour, asserted in
     * {@code WebhookDeliveryIT} — so a backlog left unpublished by an earlier suite starves this
     * suite's message and the reconciler never sees it. That presents as "no gap was recorded",
     * which is indistinguishable from the feature being broken.
     *
     * <p>Fails loudly rather than looping forever, so a genuinely undrainable message is a visible
     * failure rather than a hang.
     */
    private void drain(long outboxId) {
        for (int pass = 0; pass < 25; pass++) {
            boolean pending = outbox.fetchUnpublished(1000).stream()
                    .anyMatch(message -> message.id() == outboxId);
            if (!pending) {
                return;
            }
            relay.relay();
        }
        throw new AssertionError("outbox message " + outboxId + " could not be drained in 25 passes; "
                + "the relay is breaking on an earlier message that never succeeds");
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
