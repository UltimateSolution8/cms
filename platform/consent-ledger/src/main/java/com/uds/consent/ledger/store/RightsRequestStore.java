package com.uds.consent.ledger.store;

import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.RightsRequestStatus;
import com.uds.consent.core.model.RightsRequestType;
import com.uds.consent.core.model.RightsVerificationMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Rights requests and their statutory clocks.
 *
 * <p>The deadline is stored, not derived on read. That is a deliberate choice and the schema
 * comment in V1 says so: a computed deadline changes retroactively when someone edits the rule
 * table, so a request that was answered in time can silently become a breach — or, worse, a breach
 * can silently become compliant. A stored deadline is a fact about what the group committed to on
 * the day the request arrived, which is the only version an auditor can use.
 */
@Repository
public class RightsRequestStore {

    private final JdbcClient jdbc;

    public RightsRequestStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * @param verification how identity was established before the clock started. Never inferred —
     *                     {@link RightsVerificationMethod#UNVERIFIED} is a legitimate answer and
     *                     the common one on the administrative route
     * @param verifiedAt   when it was established. Must be null exactly when the method is
     *                     {@code UNVERIFIED}; V30's check constraint holds the two in step
     */
    public void create(String requestId, String entityId, String subjectId,
                       RightsRequestType type, Jurisdiction jurisdiction, Instant receivedAt,
                       Instant dueAt, String dueAtBasis, String details,
                       RightsVerificationMethod verification, Instant verifiedAt,
                       String verificationDetail) {
        jdbc.sql("""
                        insert into rights_request (request_id, entity_id, subject_id, request_type,
                                                    jurisdiction, status, received_at, due_at,
                                                    due_at_basis, details, verification_method,
                                                    verified_at, verification_detail)
                        values (:requestId, :entityId, :subjectId, :type, :jurisdiction, 'RECEIVED',
                                :receivedAt, :dueAt, :basis, :details, :verification, :verifiedAt,
                                :verificationDetail)
                        """)
                .param("requestId", requestId)
                .param("entityId", entityId)
                .param("subjectId", subjectId)
                .param("type", type.name())
                .param("jurisdiction", jurisdiction.name())
                .param("receivedAt", Timestamp.from(receivedAt))
                .param("dueAt", Timestamp.from(dueAt))
                .param("basis", dueAtBasis)
                .param("details", details)
                .param("verification", verification.name())
                .param("verifiedAt", verifiedAt == null ? null : Timestamp.from(verifiedAt))
                .param("verificationDetail", verificationDetail)
                .update();
    }

    public Optional<Request> find(String requestId) {
        return jdbc.sql(SELECT + " where request_id = :requestId")
                .param("requestId", requestId)
                .query(RightsRequestStore::map)
                .optional();
    }

    /** Everything filed by one person, newest first. What a repeat complainant's file looks like. */
    public List<Request> findForSubject(String entityId, String subjectId) {
        return jdbc.sql(SELECT + " where entity_id = :entityId and subject_id = :subjectId "
                        + "order by received_at desc")
                .param("entityId", entityId)
                .param("subjectId", subjectId)
                .query(RightsRequestStore::map)
                .list();
    }

    /**
     * Open requests already past their deadline.
     *
     * <p>Each one is a statutory breach that has already happened. The sweep that reads this is
     * the difference between finding out from a monitor and finding out from the Board.
     */
    public List<Request> findOverdue(Instant asOf, int limit) {
        return jdbc.sql(SELECT + " where closed_at is null and due_at < :asOf "
                        + "order by due_at asc limit :limit")
                .param("asOf", Timestamp.from(asOf))
                .param("limit", limit)
                .query(RightsRequestStore::map)
                .list();
    }

    /** Open requests falling due inside a window — the queue that still has time to be saved. */
    public List<Request> findDueWithin(Instant asOf, Instant until, int limit) {
        return jdbc.sql(SELECT + " where closed_at is null and due_at >= :asOf and due_at < :until "
                        + "order by due_at asc limit :limit")
                .param("asOf", Timestamp.from(asOf))
                .param("until", Timestamp.from(until))
                .param("limit", limit)
                .query(RightsRequestStore::map)
                .list();
    }

    public List<Request> findOpen(String entityId, int limit, int offset) {
        return jdbc.sql(SELECT + " where entity_id = :entityId and closed_at is null "
                        + "order by due_at asc limit :limit offset :offset")
                .param("entityId", entityId)
                .param("limit", limit)
                .param("offset", offset)
                .query(RightsRequestStore::map)
                .list();
    }

    /**
     * Moves a request along.
     *
     * <p>{@code closedAt} is set here rather than by a trigger so that the closure timestamp is
     * the moment the resolution was recorded, not the moment some later UPDATE touched the row.
     * The V6 check constraint holds the two in step: a closed status without a closure time, or
     * the reverse, is rejected by the database.
     */
    public void updateStatus(String requestId, RightsRequestStatus status, String assignedTo,
                             String resolution, Instant closedAt) {
        jdbc.sql("""
                        update rights_request
                           set status = :status,
                               assigned_to = coalesce(:assignedTo, assigned_to),
                               resolution = coalesce(:resolution, resolution),
                               closed_at = :closedAt
                         where request_id = :requestId
                        """)
                .param("requestId", requestId)
                .param("status", status.name())
                .param("assignedTo", assignedTo)
                .param("resolution", resolution)
                .param("closedAt", closedAt == null ? null : Timestamp.from(closedAt))
                .update();
    }

    public void acknowledge(String requestId, Instant at) {
        jdbc.sql("update rights_request set acknowledged_at = :at where request_id = :requestId "
                        + "and acknowledged_at is null")
                .param("requestId", requestId)
                .param("at", Timestamp.from(at))
                .update();
    }

    /** Open, overdue and closed counts per type, for the compliance dashboard. */
    public List<TypeSummary> summarise(String entityId, Instant asOf) {
        return jdbc.sql("""
                        select request_type,
                               count(*) filter (where closed_at is null)                 as open,
                               count(*) filter (where closed_at is null and due_at < :asOf) as overdue,
                               count(*) filter (where closed_at is not null)             as closed,
                               count(*) filter (where closed_at is not null and closed_at > due_at)
                                                                                          as closed_late,
                               count(*)                                                   as total
                          from rights_request
                         where entity_id = :entityId
                         group by request_type
                         order by overdue desc, open desc
                        """)
                .param("entityId", entityId)
                .param("asOf", Timestamp.from(asOf))
                .query((rs, n) -> new TypeSummary(
                        RightsRequestType.valueOf(rs.getString("request_type")),
                        rs.getLong("open"), rs.getLong("overdue"), rs.getLong("closed"),
                        rs.getLong("closed_late"), rs.getLong("total")))
                .list();
    }

    private static final String SELECT = """
            select request_id, entity_id, subject_id, request_type, jurisdiction, status,
                   received_at, due_at, due_at_basis, closed_at, acknowledged_at, assigned_to,
                   resolution, details, verification_method, verified_at, verification_detail
              from rights_request
            """;

    private static Request map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Timestamp closedAt = rs.getTimestamp("closed_at");
        Timestamp acknowledgedAt = rs.getTimestamp("acknowledged_at");
        Timestamp verifiedAt = rs.getTimestamp("verified_at");
        return new Request(
                rs.getString("request_id"),
                rs.getString("entity_id"),
                rs.getString("subject_id"),
                RightsRequestType.valueOf(rs.getString("request_type")),
                Jurisdiction.valueOf(rs.getString("jurisdiction")),
                RightsRequestStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("received_at").toInstant(),
                rs.getTimestamp("due_at").toInstant(),
                rs.getString("due_at_basis"),
                closedAt == null ? null : closedAt.toInstant(),
                acknowledgedAt == null ? null : acknowledgedAt.toInstant(),
                rs.getString("assigned_to"),
                rs.getString("resolution"),
                rs.getString("details"),
                RightsVerificationMethod.valueOf(rs.getString("verification_method")),
                verifiedAt == null ? null : verifiedAt.toInstant(),
                rs.getString("verification_detail"));
    }

    /**
     * @param dueAtBasis the rule the deadline came from, in words. Written once at intake and
     *                   never recomputed, so the working can still be shown years later
     * @param verification what the start of the clock rests on. {@code dueAtBasis} says which rule
     *                   produced the deadline; this says what produced the instant the rule was
     *                   applied to. A deadline is only as defensible as both
     * @param verifiedAt when identity was established, null exactly when it was not
     * @param verificationDetail how, in the operator's words. Null on the portal path, where the
     *                   method is the whole answer
     */
    public record Request(String requestId, String entityId, String subjectId,
                          RightsRequestType type, Jurisdiction jurisdiction,
                          RightsRequestStatus status, Instant receivedAt, Instant dueAt,
                          String dueAtBasis, Instant closedAt, Instant acknowledgedAt,
                          String assignedTo, String resolution, String details,
                          RightsVerificationMethod verification, Instant verifiedAt,
                          String verificationDetail) {

        public boolean overdueAt(Instant asOf) {
            return closedAt == null && dueAt.isBefore(asOf);
        }

        /** Whether it was answered late. Distinct from overdue: this one is already history. */
        public boolean closedLate() {
            return closedAt != null && closedAt.isAfter(dueAt);
        }
    }

    public record TypeSummary(RightsRequestType type, long open, long overdue, long closed,
                              long closedLate, long total) {
    }
}
