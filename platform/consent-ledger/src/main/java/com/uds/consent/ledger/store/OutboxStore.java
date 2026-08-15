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

    /** Oldest unpublished messages, for the relay to drain. */
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
