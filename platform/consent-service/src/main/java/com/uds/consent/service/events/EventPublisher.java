package com.uds.consent.service.events;

/**
 * Sends an outbox message to whatever the group's downstream systems listen on.
 *
 * <p>An interface rather than a direct broker call so that a developer machine and the test suite
 * need no broker, and so that the choice of broker never leaks into the ledger. The ledger's job
 * ends when the outbox row is committed.
 */
public interface EventPublisher {

    /**
     * Publishes one message.
     *
     * @param topic    destination
     * @param key      partition key; consent events are keyed by entity and subject so that one
     *                 subject's events stay in order for every consumer
     * @param payload  the message body, already serialised
     * @throws RuntimeException if publication failed; the relay leaves the message unpublished
     *                          and retries, which is why the outbox exists
     */
    void publish(String topic, String key, String payload);
}
