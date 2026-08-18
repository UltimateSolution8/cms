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

    /**
     * Registers or re-points a subscription.
     *
     * <p><strong>{@code system_code} defaults to the upper-cased subscription id</strong>, matching
     * what {@code V31} backfilled onto the existing rows. Without a default here, every subscription
     * registered after that migration would carry a null — and null never joins, so
     * {@code propagation_target} would report a permanent gap for a system that was in fact
     * perfectly reachable. A register that cries wolf on correct configuration gets switched off.
     *
     * <p>Not overwritten on conflict, deliberately: re-pointing an endpoint at a new URL must not
     * silently reset which downstream system it is understood to reach. Changing that is a separate
     * and more consequential act than changing where the POST goes.
     */
    public void upsert(String subscriptionId, String entityId, String topic, String url,
                       String secret, boolean active, String description) {
        jdbc.sql("""
                        insert into webhook_subscription (subscription_id, entity_id, topic, url,
                                                          secret, active, description, system_code)
                        values (:id, :entityId, :topic, :url, :secret, :active, :description,
                                upper(:id))
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
    public void recordDelivery(String subscriptionId, String entityId, String subjectId,
                               long outboxId, int attempt, String status, Integer responseCode,
                               String error, Instant at) {
        jdbc.sql("""
                        insert into webhook_delivery (subscription_id, entity_id, subject_id,
                                                      outbox_id, attempt, status, response_code,
                                                      error, delivered_at)
                        values (:subscriptionId, :entityId, :subjectId, :outboxId, :attempt,
                                :status, :responseCode, :error, :at)
                        """)
                .param("subscriptionId", subscriptionId)
                .param("entityId", entityId)
                .param("subjectId", subjectId == null || subjectId.isBlank() ? null : subjectId)
                .param("outboxId", outboxId)
                .param("attempt", attempt)
                .param("status", status)
                .param("responseCode", responseCode)
                .param("error", error)
                .param("at", java.sql.Timestamp.from(at))
                .update();
    }

    /**
     * Whether this subscription has a <strong>successful</strong> delivery for this message.
     *
     * <p>{@code status = 'DELIVERED'} is load-bearing and is the same move
     * {@link RightsFulfilmentStore#outstandingTargets(String, String, String)} makes with
     * {@code status = 'COMPLETED'}: a connection refusal writes a {@code FAILED} row, so a test for
     * "a delivery row exists" would be satisfied by a failure. The row proves an attempt was made;
     * only {@code DELIVERED} proves it arrived.
     *
     * <p>Note the asymmetry this leaves, which is recorded rather than fixed: a persistently failing
     * endpoint leaves the message <em>unpublished</em>, so the reconciler never runs for it and it
     * produces no gap row at all. It is visible instead as {@code FAILED} rows here and as
     * {@code ALERT_AFTER_ATTEMPTS} in the relay. The two artefacts answer different questions —
     * {@code OPERATIONS.md} §4 says so, because nothing else did.
     */
    public boolean delivered(long outboxId, String subscriptionId) {
        Boolean found = jdbc.sql("""
                        select exists (select 1 from webhook_delivery
                                        where outbox_id = :outboxId
                                          and subscription_id = :subscriptionId
                                          and status = 'DELIVERED')
                        """)
                .param("outboxId", outboxId)
                .param("subscriptionId", subscriptionId)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(found);
    }

    /** Delivery attempts for one outbox message. What answers "did it reach DenCRM". */
    public List<Delivery> deliveriesFor(long outboxId) {
        return jdbc.sql("""
                        select delivery_id, subscription_id, entity_id, subject_id, outbox_id,
                               attempt, status, response_code, error, delivered_at
                          from webhook_delivery
                         where outbox_id = :outboxId
                         order by delivered_at, delivery_id
                        """)
                .param("outboxId", outboxId)
                .query((rs, n) -> new Delivery(rs.getLong("delivery_id"),
                        rs.getString("subscription_id"), rs.getString("entity_id"),
                        rs.getString("subject_id"),
                        rs.getLong("outbox_id"), rs.getInt("attempt"), rs.getString("status"),
                        rs.getObject("response_code") == null ? null : rs.getInt("response_code"),
                        rs.getString("error"), rs.getTimestamp("delivered_at").toInstant()))
                .list();
    }

    /**
     * Delivery evidence for one subject, summarised per receiving system.
     *
     * <p>A summary rather than a log, and the reduction is also the better answer to the clause.
     * GDPR Art. 19 limb 2 entitles the principal to be told <em>the recipients</em>, not to a
     * per-message delivery journal — and the receipt already names active recipients per purpose,
     * proactively, above the statute's "if the data subject requests it" floor. What the receipt
     * cannot say is who was told about <em>this person's withdrawal</em>, which is this.
     *
     * <p>Grouped in the database rather than in Java, so the result is bounded by the number of
     * systems rather than by the number of messages. That is what lets the evidence bundle carry it
     * with no cap and no truncation entry — an uncapped section multiplied by the register size was
     * how an earlier draft of this would have re-created the defect the truncation notice exists to
     * close.
     */
    public List<SubjectDelivery> deliveriesForSubject(String entityId, String subjectId) {
        return jdbc.sql("""
                        select s.system_code, d.subscription_id,
                               count(*) filter (where d.status = 'DELIVERED') as delivered,
                               count(*) filter (where d.status = 'FAILED')    as failed,
                               min(d.delivered_at) filter (where d.status = 'DELIVERED') as first_at,
                               max(d.delivered_at) filter (where d.status = 'DELIVERED') as last_at
                          from webhook_delivery d
                          join webhook_subscription s
                            on s.subscription_id = d.subscription_id
                         where d.entity_id = :entityId and d.subject_id = :subjectId
                         group by s.system_code, d.subscription_id
                         order by s.system_code, d.subscription_id
                        """)
                .param("entityId", entityId)
                .param("subjectId", subjectId)
                .query((rs, n) -> new SubjectDelivery(
                        rs.getString("system_code"), rs.getString("subscription_id"),
                        rs.getLong("delivered"), rs.getLong("failed"),
                        rs.getTimestamp("first_at") == null
                                ? null : rs.getTimestamp("first_at").toInstant(),
                        rs.getTimestamp("last_at") == null
                                ? null : rs.getTimestamp("last_at").toInstant()))
                .list();
    }

    /**
     * What one downstream system was told about one principal.
     *
     * @param delivered attempts that arrived. <strong>Zero with a non-zero {@code failed} is a
     *                  system that was written to and never reached</strong> — the two are counted
     *                  apart because a failed attempt must never read as propagation
     * @param firstAt   null where nothing ever arrived
     */
    public record SubjectDelivery(String systemCode, String subscriptionId, long delivered,
                                  long failed, Instant firstAt, Instant lastAt) {
    }

    private static Subscription map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Subscription(rs.getString("subscription_id"), rs.getString("entity_id"),
                rs.getString("topic"), rs.getString("url"), rs.getString("secret"),
                rs.getBoolean("active"), rs.getString("description"));
    }

    public record Subscription(String subscriptionId, String entityId, String topic, String url,
                               String secret, boolean active, String description) {
    }

    public record Delivery(long deliveryId, String subscriptionId, String entityId,
                           String subjectId, long outboxId, int attempt, String status,
                           Integer responseCode, String error, Instant deliveredAt) {
    }
}
