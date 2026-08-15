package com.uds.consent.service.events;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Publishes outbox messages to Kafka.
 *
 * <p>Sends synchronously and waits for the broker's acknowledgement. That is slower than
 * fire-and-forget and it is the right trade here: the relay marks a message published only after
 * this returns, so an unacknowledged send has to fail rather than silently succeed. The outbox
 * then retries it.
 */
@Component
@ConditionalOnProperty(name = "uds.consent.events.publisher", havingValue = "kafka")
public class KafkaEventPublisher implements EventPublisher {

    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, String> kafka;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    @Override
    public void publish(String topic, String key, String payload) {
        try {
            kafka.send(topic, key, payload).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while publishing to " + topic, e);
        } catch (Exception e) {
            throw new IllegalStateException("failed to publish to " + topic, e);
        }
    }
}
