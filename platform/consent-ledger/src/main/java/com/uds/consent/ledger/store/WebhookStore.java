package com.uds.consent.ledger.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;

/**
 * Who should be told about a consent change, and whether they were.
 *
 * <p>The outbox has always worked — enqueued on every event, drained every two seconds, retried on
 * failure, escalated after ten attempts — and published to a broker nobody consumes. The platform's
 * answer to "has this person withdrawn" was correct, immediate and entirely passive: every
 * downstream system had to ask, and any that forgot kept calling somebody who had opted out while
 * the platform recorded nothing about it.
 *
 * <p>Two tables. {@code webhook_subscription} is who to tell, per entity and per topic — per entity
 * because a Denave endpoint must not receive Matrix's withdrawals. {@code webhook_delivery} is one
 * row per attempt, which is the part that turns "we pushed it" into something a complaint can be
 * answered with.
 */
@Repository
public class WebhookStore {

    private final JdbcClient jdbc;

    public WebhookStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * Active subscriptions for a topic and entity.
     *
     * <p>Both, never topic alone. A subscription matched on topic would deliver every group
     * company's consent changes to whichever team registered an endpoint first — a cross-entity
     * disclosure created by the very mechanism meant to honour a withdrawal.
     */
    public List<Subscription> activeFor(String topic, String entityId) {
        return jdbc.sql("""
                        select subscription_id, entity_id, topic, url, secret, active, description
                          from webhook_subscription
                         where topic = :topic and entity_id = :entityId and active
                         order by subscription_id
                        """)
                .param("topic", topic)
                .param("entityId", entityId)
                .query(WebhookStore::map)
                .list();
    }

    public List<Subscription> forEntity(String entityId) {
        return jdbc.sql("""
                        select subscription_id, entity_id, topic, url, secret, active, description
                          from webhook_subscription
                         where entity_id = :entityId
                         order by topic, subscription_id
                        """)
                .param("entityId", entityId)
                .query(WebhookStore::map)
                .list();
    }

    public void upsert(String subscriptionId, String entityId, String topic, String url,
                       String secret, boolean active, String description) {
        jdbc.sql("""
                        insert into webhook_subscription (subscription_id, entity_id, topic, url,
                                                          secret, active, description)
                        values (:id, :entityId, :topic, :url, :secret, :active, :description)
                        on conflict (subscription_id) do update
                           set url = excluded.url, secret = excluded.secret,
                               active = excluded.active, description = excluded.description
                        """)
                .param("id", subscriptionId)
                .param("entityId", entityId)
                .param("topic", topic)
                .param("url", url)
                .param("secret", secret)
                .param("active", active)
                .param("description", description)
                .update();
    }

    /** Records one attempt. Append-only — a delivery record that could be edited is not evidence. */
    public void recordDelivery(String subscriptionId, String entityId, long outboxId, int attempt,
                               String status, Integer responseCode, String error, Instant at) {
        jdbc.sql("""
                        insert into webhook_delivery (subscription_id, entity_id, outbox_id,
                                                      attempt, status, response_code, error,
                                                      delivered_at)
                        values (:subscriptionId, :entityId, :outboxId, :attempt, :status,
                                :responseCode, :error, :at)
                        """)
                .param("subscriptionId", subscriptionId)
                .param("entityId", entityId)
                .param("outboxId", outboxId)
                .param("attempt", attempt)
                .param("status", status)
                .param("responseCode", responseCode)
                .param("error", error)
                .param("at", java.sql.Timestamp.from(at))
                .update();
    }

    /** Delivery attempts for one outbox message. What answers "did it reach DenCRM". */
    public List<Delivery> deliveriesFor(long outboxId) {
        return jdbc.sql("""
                        select delivery_id, subscription_id, entity_id, outbox_id, attempt, status,
                               response_code, error, delivered_at
                          from webhook_delivery
                         where outbox_id = :outboxId
                         order by delivered_at, delivery_id
                        """)
                .param("outboxId", outboxId)
                .query((rs, n) -> new Delivery(rs.getLong("delivery_id"),
                        rs.getString("subscription_id"), rs.getString("entity_id"),
                        rs.getLong("outbox_id"), rs.getInt("attempt"), rs.getString("status"),
                        rs.getObject("response_code") == null ? null : rs.getInt("response_code"),
                        rs.getString("error"), rs.getTimestamp("delivered_at").toInstant()))
                .list();
    }

    private static Subscription map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Subscription(rs.getString("subscription_id"), rs.getString("entity_id"),
                rs.getString("topic"), rs.getString("url"), rs.getString("secret"),
                rs.getBoolean("active"), rs.getString("description"));
    }

    public record Subscription(String subscriptionId, String entityId, String topic, String url,
                               String secret, boolean active, String description) {
    }

    public record Delivery(long deliveryId, String subscriptionId, String entityId, long outboxId,
                           int attempt, String status, Integer responseCode, String error,
                           Instant deliveredAt) {
    }
}
