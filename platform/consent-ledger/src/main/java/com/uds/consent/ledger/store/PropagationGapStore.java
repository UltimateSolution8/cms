package com.uds.consent.ledger.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Append-only evidence: which propagation obligations went unmet, on which day.
 *
 * <p>Separate from {@link PropagationCoverageStore} because they answer different questions. That
 * one is current state an operator can fix and which returns to zero; this one is history nobody
 * can edit away. It is the same split the platform already uses for
 * {@code rights_request_verification} against {@code rights_request}.
 *
 * <p><strong>Every row records what was observed. None records a conclusion.</strong> That is what
 * {@code reason} is for, and it is not a technicality: {@code webhook_delivery} is written
 * <em>only</em> by {@code WebhookEventPublisher}. The logging and Kafka publishers never write one —
 * {@code EventPublisher} discards {@code outboxId} deliberately — and the default publisher is
 * {@code log}, which is what the Denave pilot runs. Writing {@code NOT_DELIVERED} there would assert
 * that a system was not told when the truth is that the platform has no way to know, and under
 * {@code kafka} with DENCRM consuming normally it would simply be false.
 *
 * <p>That is the same false statement as answering "no recipients" on a receipt where the truth is
 * "nobody wrote down who the recipients are" — the defect
 * {@code .claude/rules/consent-management.md} §1 spends a paragraph refusing. Record the absence of
 * a channel; never infer non-delivery.
 *
 * <p><strong>One row per target per day.</strong> The table is genuinely partitionable where
 * {@code consent_event} is not — but it needs a unique key to be idempotent under three concurrent
 * relays, and a partitioned table's unique constraints must include the partition key, at which
 * point a retry crossing a month boundary is accepted twice. That is {@code V28}'s trap exactly, so
 * the rows are bounded instead of partitioned. Growth is targets × days rather than targets ×
 * events, and the evidential property survives: <em>this obligation was unmet on that day</em> is
 * the fact a regulator asks about.
 */
@Repository
public class PropagationGapStore {

    private final JdbcClient jdbc;

    public PropagationGapStore(javax.sql.DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * Records one unmet obligation for today, or does nothing if it is already recorded.
     *
     * <p>{@code on conflict do nothing} rather than {@code do update}, and the choice is forced
     * rather than stylistic: the migration revokes {@code update} and {@code delete} on this table
     * from {@code uds_consent_app}, so {@code do update} would be refused. A plain insert is all an
     * append-only evidence table should ever need, and the daily unique key makes it idempotent
     * under however many relay replicas are running — which today is three, because
     * {@code OutboxStore.fetchUnpublished} takes no lock and {@code OutboxRelay}, unlike all seven
     * sweepers, takes no {@code SweepLock}.
     */
    public void record(String entityId, String subjectId, String topic, String systemCode,
                       String eventType, Reason reason) {
        jdbc.sql("""
                        insert into propagation_gap (entity_id, subject_id, topic, system_code,
                                                     event_type, reason)
                        values (:entityId, :subjectId, :topic, :systemCode, :eventType, :reason)
                        on conflict (entity_id, topic, system_code, detected_on) do nothing
                        """)
                .param("entityId", entityId)
                .param("subjectId", subjectId == null || subjectId.isBlank() ? null : subjectId)
                .param("topic", topic)
                .param("systemCode", systemCode)
                .param("eventType", eventType)
                .param("reason", reason.name())
                .update();
    }

    /** Gaps for one entity, most recent first. The operational read. */
    public List<Gap> forEntity(String entityId, String topic, int limit, int offset) {
        return jdbc.sql("""
                        select gap_id, entity_id, subject_id, topic, system_code, event_type,
                               reason, detected_on, detected_at
                          from propagation_gap
                         where entity_id = :entityId
                           -- Cast required: PostgreSQL cannot infer the type of a bare null
                           -- parameter in `:topic is null`, and fails with a grammar error rather
                           -- than treating it as unfiltered.
                           and (cast(:topic as varchar) is null or topic = cast(:topic as varchar))
                         order by detected_on desc, gap_id desc
                         limit :limit offset :offset
                        """)
                .param("entityId", entityId)
                .param("topic", topic)
                .param("limit", limit)
                .param("offset", offset)
                .query(PropagationGapStore::map)
                .list();
    }

    /**
     * Gaps recorded against one subject, for the evidence bundle.
     *
     * <p>Reads {@code propagation_gap} directly and <strong>never joins {@code event_outbox}</strong>
     * — that table has no {@code entity_id}, no {@code subject_id}, no index on {@code event_key}
     * and no row-level-security policy, so reaching it under an entity-scoped path would scan the
     * largest table in the database through a layer that does not cover it. Rules §2 exists to
     * refuse exactly that, which is why the subject is carried onto this row instead.
     */
    public List<Gap> forSubject(String entityId, String subjectId) {
        return jdbc.sql("""
                        select gap_id, entity_id, subject_id, topic, system_code, event_type,
                               reason, detected_on, detected_at
                          from propagation_gap
                         where entity_id = :entityId and subject_id = :subjectId
                         order by detected_on desc, gap_id desc
                        """)
                .param("entityId", entityId)
                .param("subjectId", subjectId)
                .query(PropagationGapStore::map)
                .list();
    }

    private static Gap map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Gap(rs.getLong("gap_id"), rs.getString("entity_id"), rs.getString("subject_id"),
                rs.getString("topic"), rs.getString("system_code"), rs.getString("event_type"),
                Reason.valueOf(rs.getString("reason")),
                rs.getObject("detected_on", LocalDate.class),
                rs.getTimestamp("detected_at").toInstant());
    }

    /**
     * Why the platform could not show the obligation was met.
     *
     * <p>Three observations, never a conclusion. The critical alert keys on the first two; the third
     * is a statement about this deployment's configuration rather than about any downstream system.
     */
    public enum Reason {
        /** A mandatory target has no active subscription. The platform knows nobody was reachable. */
        NO_SUBSCRIPTION,
        /** A subscription exists and produced no {@code DELIVERED} row for this message. */
        NOT_DELIVERED,
        /**
         * The configured publisher writes no delivery evidence at all — {@code log} and
         * {@code kafka}. The platform does not know whether the system was told, and says so
         * rather than inferring that it was not.
         */
        NO_DELIVERY_CHANNEL
    }

    public record Gap(long gapId, String entityId, String subjectId, String topic,
                      String systemCode, String eventType, Reason reason, LocalDate detectedOn,
                      Instant detectedAt) {
    }
}
