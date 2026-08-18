package com.uds.consent.ledger.store;

import com.uds.consent.core.audit.CallerContext;
import com.uds.consent.core.crypto.CanonicalJson;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What administrators did.
 *
 * <p>Administrators never silently edit consent — a correction is an appended event with
 * {@code actorType = ADMIN} — but they do publish notices, retire purposes, release records from
 * quarantine and load suppression registries. Each of those changes what the platform will decide,
 * so each is recorded here, and this table is append-only for the same reason the ledger is: an
 * administrator who can delete the record of their own action leaves the ledger technically
 * intact and practically worthless.
 */
@Repository
public class AdminAuditStore {

    private final JdbcClient jdbc;

    public AdminAuditStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * Records an administrative action against the person who took it and the credential they
     * took it with.
     *
     * <p>Two identifiers rather than one, because they answer different questions and a single
     * column has to lie about one of them. {@code clientId} is the credential — always known,
     * never sufficient, because the compliance console is one credential held by a team.
     * {@code actorId} is the human, asserted by the calling console and required on every
     * mutation. An audit table that can only say "compliance-console retired this purpose" cannot
     * answer the one question it exists for.
     *
     * <p>{@code clientId} is not a parameter. It comes from {@link CallerContext}, bound per
     * request by a filter, so that every one of the forty-odd call sites across seven services
     * gained the second identifier without gaining an argument. Off a request — a sweeper, the
     * outbox relay — it is null, which is the honest answer: scheduled work has no credential
     * behind it, and writing "system" there would make a job indistinguishable from a caller.
     *
     * @param actorId the human, from {@code X-UDS-Actor}; never blank on a mutation
     */
    public void record(String actorId, String action, String entityId,
                       String targetType, String targetId, Map<String, ?> detail) {
        String clientId = CallerContext.clientId();
        jdbc.sql("""
                        insert into admin_audit_event (actor_id, client_id, action, entity_id,
                                                       target_type, target_id, detail)
                        values (:actorId, :clientId, :action, :entityId, :targetType, :targetId,
                                cast(:detail as jsonb))
                        """)
                .param("actorId", actorId)
                .param("clientId", clientId)
                .param("action", action)
                .param("entityId", entityId)
                .param("targetType", targetType)
                .param("targetId", targetId)
                .param("detail", CanonicalJson.serialize(detail == null ? Map.of() : detail))
                .update();
    }

    public List<Entry> recent(String entityId, int limit) {
        return jdbc.sql("""
                        select actor_id, client_id, action, entity_id, target_type, target_id,
                               detail, occurred_at
                          from admin_audit_event
                        -- Cast explicitly. A null bound to a bare parameter reaches PostgreSQL
                        -- untyped, and it will refuse the statement rather than guess — so the
                        -- unfiltered call, which is the one that finds group-level actions
                        -- belonging to no entity, is exactly the one that would fail.
                         where (cast(:entityId as varchar) is null
                                or entity_id = cast(:entityId as varchar))
                         order by occurred_at desc
                         limit :limit
                        """)
                .param("entityId", entityId)
                .param("limit", limit)
                .query((rs, n) -> new Entry(rs.getString("actor_id"), rs.getString("client_id"),
                        rs.getString("action"), rs.getString("entity_id"),
                        rs.getString("target_type"), rs.getString("target_id"),
                        rs.getString("detail"), rs.getTimestamp("occurred_at").toInstant()))
                .list();
    }

    /**
     * @param actorId  the human who acted. On rows written before {@code V23} this holds a
     *                 credential name instead — history is not rewritten to look better than it
     *                 was, so a reader comparing it against {@code clientId} can tell which era a
     *                 row belongs to
     * @param clientId the API credential the request authenticated with
     */
    public record Entry(String actorId, String clientId, String action, String entityId,
                        String targetType, String targetId, String detailJson,
                        Instant occurredAt) {
    }
}
