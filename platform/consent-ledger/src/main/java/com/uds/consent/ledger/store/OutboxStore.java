package com.uds.consent.ledger.store;

import com.uds.consent.core.crypto.CanonicalJson;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.List;

/**
 * Transactional outbox for downstream propagation.
 *
 * <p>The outbox row is written in the same transaction as the consent event. Publishing directly
 * to the broker instead would create a window in which a withdrawal is durably recorded but never
 * announced — leaving a dialer, quite legitimately as far as it knows, still calling someone who
 * has opted out. That is the failure this table exists to make impossible.
 */
@Repository
public class OutboxStore {

    private final JdbcClient jdbc;

    public OutboxStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public void enqueue(String topic, String eventKey, Object payload) {
        jdbc.sql("""
                        insert into event_outbox (topic, event_key, payload)
                        values (:topic, :eventKey, cast(:payload as jsonb))
                        """)
                .param("topic", topic)
                .param("eventKey", eventKey)
                .param("payload", CanonicalJson.serialize(payload))
                .update();
    }


    /**
     * Claims a batch for publication, skipping rows another relay is already working on.
     *
     * <p><strong>Must be called inside a transaction</strong>, and the lock is held until it
     * commits — which is the point. {@code OutboxRelay} publishes and marks within that
     * transaction, so two relays draining concurrently take disjoint batches.
     *
     * <p>Until this existed the relay took no lock at all and, unlike the seven sweepers, no
     * {@code SweepLock} — while {@code deploy/k8s/deployment.yaml} ships three replicas. Three
     * relays therefore drained the same batch every two seconds: every subscriber received each
     * event up to three times, and {@code webhook_delivery} — the row that proves a withdrawal
     * arrived — carried up to three rows per attempt. An evidence table that over-counts is a poor
     * foundation for a proof.
     *
     * <p><strong>{@code skip locked} rather than a {@code SweepLock}</strong>, deliberately. An
     * advisory lock would serialise fan-out onto one instance, which is a throughput change; this
     * lets all three replicas work, on disjoint rows.
     *
     * <p>Historical duplicate {@code webhook_delivery} rows are not de-duplicated. They are
     * append-only evidence and three replicas really did make three attempts; the correction
     * belongs at the cause, which is here.
     */
    public List<PendingMessage> claimUnpublished(int limit) {
        return jdbc.sql("""
                        select id, topic, event_key, payload, attempts from event_outbox
                         where published_at is null
                         order by created_at asc
                         limit :limit
                           for update skip locked
                        """)
                .param("limit", limit)
                .query((rs, n) -> new PendingMessage(rs.getLong("id"), rs.getString("topic"),
                        rs.getString("event_key"), rs.getString("payload"), rs.getInt("attempts")))
                .list();
    }

    /**
     * Reads pending messages without claiming them.
     *
     * <p>For inspection and for tests. The relay uses {@link #claimUnpublished(int)}; a reader that
     * uses this one to drive publication reintroduces the triplication that method exists to close.
     */
    public List<PendingMessage> fetchUnpublished(int limit) {
        return jdbc.sql("""
                        select id, topic, event_key, payload, attempts from event_outbox
                         where published_at is null
                         order by created_at asc
                         limit :limit
                        """)
                .param("limit", limit)
                .query((rs, n) -> new PendingMessage(rs.getLong("id"), rs.getString("topic"),
                        rs.getString("event_key"), rs.getString("payload"), rs.getInt("attempts")))
                .list();
    }

    public void markPublished(long id) {
        jdbc.sql("update event_outbox set published_at = now() where id = :id")
                .param("id", id)
                .update();
    }

    public void markFailed(long id, String error) {
        jdbc.sql("""
                        update event_outbox
                           set attempts = attempts + 1,
                               last_error = :error
                         where id = :id
                        """)
                .param("id", id)
                .param("error", error == null ? "unknown" : error.substring(0, Math.min(error.length(), 2000)))
                .update();
    }

    /** Count of messages still waiting, exposed as a metric — a rising number means fan-out is stalled. */
    public long pendingCount() {
        return jdbc.sql("select count(*) from event_outbox where published_at is null")
                .query(Long.class)
                .single();
    }

    public record PendingMessage(long id, String topic, String eventKey, String payload, int attempts) {
    }
}
