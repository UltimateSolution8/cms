package com.uds.consent.service.it;

import com.sun.net.httpserver.HttpServer;
import com.uds.consent.ledger.store.OutboxStore;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A withdrawal now reaches something.
 *
 * <p>This is the difference between a consent system of record and a consent management platform.
 * The outbox always worked — enqueued on every event, drained every two seconds, retried on
 * failure, escalated after ten attempts — and published to a broker nobody consumes, with
 * {@code log} as the default publisher. So the platform's answer to "has this person withdrawn" was
 * correct, immediate and entirely passive: DenCRM, the HRMS, Athena and the campaign tools each had
 * to *ask*, and any one that forgot kept calling somebody who had opted out while the platform
 * recorded nothing about it, because from its point of view nothing had happened.
 *
 * <p>Against a real HTTP server rather than a mocked client, because the things most likely to be
 * wrong are the things a mock cannot be wrong about: the exact bytes signed, the header name the
 * receiver looks for, and whether a non-2xx actually raises.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "uds.consent.events.publisher=webhook",
                // Driven by hand, so a timer firing mid-assertion cannot deliver a message the test
                // has not set up yet.
                "uds.consent.events.relay-interval=PT1H"
        })
class WebhookDeliveryIT extends PostgresIntegrationTest {

    private static final String ENTITY = "DENAVE_IN";
    private static final String TOPIC = "uds.consent.events";
    private static final String SECRET = "webhook-suite-shared-secret";

    @Autowired
    private PropagationSystemStore propagationSystems;

    @Autowired
    private WebhookStore webhooks;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxStore outbox;

    @Autowired
    private OutboxRelay relay;

    private HttpServer server;
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final List<String> signatures = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);

    @BeforeEach
    void startReceiver() throws IOException {
        // Start from an empty outbox, with nothing subscribed.
        //
        // Every suite that captures or withdraws consent enqueues an outbox row, and this class's
        // assertions count what arrived at one receiver. So its results depended on how many
        // messages happened to be undrained when it ran — it passed for as long as it ran early
        // enough, and adding a suite that enqueues anything moved it. That is an order-dependent
        // test rather than a wrong one, and the fix belongs here rather than in whichever suite
        // happens to expose it next.
        //
        // Subscriptions are deactivated first so draining the backlog delivers nowhere; each test
        // re-registers its own with subscribe().
        // EVERY entity, not a named couple. Subscriptions belong to whichever suite created them
        // and there are fifteen fiduciaries; a message for an entity whose endpoint died with some
        // other test class breaks the relay just as effectively, and the relay stops the whole
        // batch on the first failure.
        jdbc.update("update webhook_subscription set active = false where active");
        for (int pass = 0; pass < 25 && !outbox.fetchUnpublished(1).isEmpty(); pass++) {
            relay.relay();
        }

        received.clear();
        signatures.clear();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/hook", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            String signature = exchange.getRequestHeaders().getFirst("X-UDS-Signature");
            signatures.add(signature == null ? "" : signature);
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopReceiver() {
        server.stop(0);
    }

    @Test
    @DisplayName("a consent event is pushed to the subscriber and the delivery is recorded")
    void anEventReachesTheSubscriber() {
        String subscriptionId = subscribe();
        long outboxId = enqueue(ENTITY, "{\"type\":\"WITHDRAWN\"}");

        relay.relay();

        assertThat(received)
                .withFailMessage("nothing arrived at the subscriber; the platform is still "
                        + "pull-only and a withdrawal reaches nobody")
                .hasSize(1);

        // The delivery record is the half that makes this evidence. An HTTP 200 nobody wrote down
        // cannot answer "did the withdrawal reach DenCRM" when a principal complains that they were
        // called afterwards.
        assertThat(webhooks.deliveriesFor(outboxId))
                .singleElement()
                .satisfies(delivery -> {
                    assertThat(delivery.status()).isEqualTo("DELIVERED");
                    assertThat(delivery.responseCode()).isEqualTo(200);
                    assertThat(delivery.subscriptionId()).isEqualTo(subscriptionId);
                });
    }

    @Test
    @DisplayName("the payload is signed over the exact bytes sent")
    void theBodyIsSigned() {
        // Without a signature the receiving endpoint has to accept "this person withdrew" from
        // anyone who can reach it — a way to suppress a competitor's contact list from outside, or,
        // worse in the other direction, to forge a grant.
        subscribe();
        enqueue(ENTITY, "{\"type\":\"WITHDRAWN\",\"purposeCode\":\"MKT_OUTBOUND_CALL\"}");

        relay.relay();

        assertThat(signatures).hasSize(1);
        assertThat(signatures.get(0))
                .withFailMessage("the signature does not match an HMAC of the body that arrived, "
                        + "so a receiver verifying it would reject a message nobody tampered with")
                .isEqualTo(hmac(received.get(0)));
    }

    @Test
    @DisplayName("a failing endpoint is recorded and the message stays unpublished for retry")
    void aFailureIsRecordedAndRetried() {
        subscribe();
        long outboxId = enqueue(ENTITY, "{\"type\":\"WITHDRAWN\"}");
        status.set(503);

        relay.relay();

        assertThat(webhooks.deliveriesFor(outboxId))
                .singleElement()
                .satisfies(delivery -> {
                    assertThat(delivery.status()).isEqualTo("FAILED");
                    assertThat(delivery.responseCode()).isEqualTo(503);
                });

        // Unpublished, so the next pass tries again. A message marked delivered on a 503 would be a
        // withdrawal the group believes it propagated and did not — which is worse than never
        // having tried, because nothing afterwards looks wrong.
        status.set(200);
        relay.relay();

        assertThat(webhooks.deliveriesFor(outboxId))
                .withFailMessage("the message was not retried after a 503")
                .hasSize(2)
                .last()
                .satisfies(delivery -> assertThat(delivery.status()).isEqualTo("DELIVERED"));
    }

    @Test
    @DisplayName("another entity's subscriber receives nothing")
    void subscriptionsAreScopedToTheirEntity() {
        // A subscription matched on topic alone would deliver every group company's consent changes
        // to whichever team registered an endpoint first — a cross-entity disclosure created by the
        // very mechanism meant to honour a withdrawal.
        subscribe();
        enqueue("MATRIX", "{\"type\":\"WITHDRAWN\"}");

        relay.relay();

        assertThat(received)
                .withFailMessage("Denave's endpoint received a Matrix consent event")
                .isEmpty();
    }

    // -----------------------------------------------------------------------------------

    /**
     * One subscription for the whole class, re-pointed at each test's server.
     *
     * <p>A fresh id per test would leave the previous tests' subscriptions active and pointing at
     * servers that have since stopped — so every later test would deliver to one live endpoint and
     * two refused connections, and the assertions would drift as the class grew. Subscriptions are
     * configuration and outlive a test; treating them as fixtures is what causes that.
     */
    private String subscribe() {
        String subscriptionId = "hook-webhook-delivery-it";
        declare(subscriptionId.toUpperCase(java.util.Locale.ROOT));
        webhooks.upsert(subscriptionId, ENTITY, TOPIC,
                "http://localhost:" + server.getAddress().getPort() + "/hook",
                SECRET, true, "webhook suite fixture");
        return subscriptionId;
    }

    /** Enqueues one outbox row and returns its id, keyed the way ConsentLedger keys events. */
    private long enqueue(String entityId, String payload) {
        outbox.enqueue(TOPIC, entityId + "|subject-" + UUID.randomUUID(),
                Map.of("raw", payload));
        return outbox.fetchUnpublished(100).stream()
                .mapToLong(OutboxStore.PendingMessage::id)
                .max()
                .orElseThrow();
    }

    private static String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
