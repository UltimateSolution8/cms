package com.uds.consent.service.events;

import com.uds.consent.ledger.store.WebhookStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Pushes consent changes to the systems that need to act on them.
 *
 * <p>The gap this closes is the one that decides whether this is a consent management platform or a
 * consent system of record. The outbox has always worked; it published to a broker nobody consumes,
 * and the default publisher was {@code log}. So the platform's answer to "has this person withdrawn"
 * was correct, immediate, and entirely passive — DenCRM, the HRMS, Athena and the campaign tools
 * each had to *ask*, and any one that forgot kept calling somebody who had opted out while the
 * platform recorded nothing about it, because from here nothing had happened.
 *
 * <p><strong>Why HTTP and not a broker consumer.</strong> A consumer is a second deployable per
 * downstream system, with its own lifecycle, credentials and on-call, written in whatever language
 * that team uses. An outbound POST needs the receiving system to expose one endpoint. Kafka stays
 * available and is the better answer at high fan-out; this is the answer that gets a withdrawal
 * into DenCRM before the pilot.
 *
 * <p><strong>Signed, because the receiver has to be able to tell.</strong> Every request carries
 * {@code X-UDS-Signature}: HMAC-SHA256 of the body under a shared secret. Without it the receiving
 * endpoint has to accept "this person withdrew" from anyone who can reach it, which is a way to
 * suppress a competitor's contact list from outside — or, worse in the other direction, to forge a
 * grant.
 *
 * <p><strong>At least once, and duplicates are expected.</strong> A POST that the receiver
 * processed but whose response was lost is retried by the outbox, and every subscription for that
 * message is retried, not only the one that failed. Consumers must be idempotent; consent events
 * carry an event id and a per-subject sequence number exactly so that they can be.
 */
@Component
@ConditionalOnProperty(name = "uds.consent.events.publisher", havingValue = "webhook")
public class WebhookEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventPublisher.class);

    /** Signature header. Named for the platform rather than generically, so a receiver can tell. */
    private static final String SIGNATURE_HEADER = "X-UDS-Signature";

    /**
     * How long to wait on a downstream endpoint.
     *
     * <p>Short on purpose. The relay drains serially and stops on the first failure, so a
     * downstream system that hangs would hold up every other subscriber's withdrawals behind it —
     * turning one slow endpoint into a group-wide propagation stall. Five seconds is generous for
     * an endpoint whose only job is to accept a small JSON body, and anything slower is a system
     * with a problem of its own that should not become this platform's.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final WebhookStore subscriptions;
    private final RestClient http;

    public WebhookEventPublisher(WebhookStore subscriptions, RestClient.Builder builder) {
        this.subscriptions = subscriptions;
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) TIMEOUT.toMillis());
        factory.setReadTimeout((int) TIMEOUT.toMillis());
        this.http = builder.requestFactory(factory).build();
    }

    /** This is the one publisher that writes a delivery row per attempt. */
    @Override
    public boolean writesDeliveryEvidence() {
        return true;
    }

    @Override
    public void publish(String topic, String key, String payload) {
        publish(topic, key, payload, 0L, 0);
    }

    @Override
    public void publish(String topic, String key, String payload, long outboxId, int attempt) {
        String entityId = OutboxKey.entityFrom(key);
        String subjectId = OutboxKey.subjectFrom(key);
        List<WebhookStore.Subscription> targets = subscriptions.activeFor(topic, entityId);

        if (targets.isEmpty()) {
            // Deliberately silent here, and that is a change of position rather than an oversight.
            //
            // This branch used to log at debug under a comment claiming it was "not an error and
            // not silent". com.uds.consent is INFO in every profile but local, so the line did not
            // exist in production and the comment was false — the exact defect class this
            // programme keeps correcting, sitting on the branch that decides whether a withdrawal
            // reaches anybody.
            //
            // PropagationReconciler now records the fact as a row against the register, which is
            // evidence rather than a log line, and it does so once a day per target rather than
            // once per event. A WARN here would be that flood; the row is the record.
            return;
        }

        RuntimeException firstFailure = null;
        for (WebhookStore.Subscription target : targets) {
            try {
                deliver(target, subjectId, payload, outboxId, attempt);
            } catch (RuntimeException e) {
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }

        // Every subscriber is attempted before anything is rethrown. Stopping at the first failure
        // would let one broken endpoint block every other downstream system from hearing about the
        // same withdrawal — and the systems that were reachable would be punished for the one that
        // was not. Rethrowing afterwards is what makes the outbox retry, which is correct: the
        // message is not fully delivered until all of them have it.
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private void deliver(WebhookStore.Subscription target, String subjectId, String payload,
                         long outboxId, int attempt) {
        Instant now = Instant.now();
        try {
            var response = http.post()
                    .uri(target.url())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(SIGNATURE_HEADER, sign(payload, target.secret()))
                    // Propagated so a receiving system's logs and this platform's line up. A
                    // withdrawal that went missing is investigated across two systems, and without
                    // a shared id that investigation is two people reading timestamps.
                    .header("X-Correlation-Id", correlationId())
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();

            subscriptions.recordDelivery(target.subscriptionId(), target.entityId(), subjectId,
                    outboxId, attempt, "DELIVERED", response.getStatusCode().value(), null, now);

        } catch (RestClientResponseException e) {
            subscriptions.recordDelivery(target.subscriptionId(), target.entityId(), subjectId,
                    outboxId, attempt, "FAILED", e.getStatusCode().value(), e.getMessage(), now);
            throw e;
        } catch (RuntimeException e) {
            // Connection refused, DNS failure, timeout — no status code to record, which is itself
            // the useful distinction from a 500: one endpoint is broken, the other is absent.
            subscriptions.recordDelivery(target.subscriptionId(), target.entityId(), subjectId,
                    outboxId, attempt, "FAILED", null, e.getMessage(), now);
            throw e;
        }
    }

    /**
     * HMAC-SHA256 of the exact bytes sent, hex encoded.
     *
     * <p>Over the body rather than over a canonical form of it. The receiver verifies what arrived,
     * and any transformation between signing and sending — a re-serialisation, a whitespace
     * difference — would produce a signature that fails for a message nobody tampered with, which
     * is the worst failure mode a signature can have.
     */
    private static String sign(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("could not sign the webhook payload", e);
        }
    }

    private static String correlationId() {
        String value = MDC.get("correlationId");
        return value == null ? "outbox-relay" : value;
    }
}
