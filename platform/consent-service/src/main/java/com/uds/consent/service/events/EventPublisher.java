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

    /**
     * Publishes one message, with the outbox row it came from.
     *
     * <p>A default that discards the extra arguments, so the log and Kafka publishers are
     * unchanged: a broker does not care which row a message came from, and threading an id through
     * them to satisfy one implementation would be the wrong direction of coupling.
     *
     * <p>The webhook publisher does care. It writes a delivery record per attempt, and "which
     * message, on which try" is the whole content of that record — an HTTP 200 with no idea what
     * it acknowledged cannot answer "did the withdrawal reach DenCRM".
     *
     * @param outboxId the row being drained
     * @param attempt  how many times it has already been tried, so the record shows a message that
     *                 succeeded on the third go for what it is: one that failed twice
     */
    default void publish(String topic, String key, String payload, long outboxId, int attempt) {
        publish(topic, key, payload);
    }
}
