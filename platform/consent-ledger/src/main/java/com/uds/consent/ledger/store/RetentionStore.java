package com.uds.consent.ledger.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;

/**
 * What is due for erasure, and whether it happened.
 *
 * <p>The gap between the two is the point. DPDP s.8(7) obliges erasure once the purpose is no
 * longer served, and the personal data lives in DenCRM, the HRMS and the BGV workflow rather than
 * here — so what this platform can do, and the only thing it should do, is say what is due, record
 * that somebody acted, and keep the difference visible.
 */
@Repository
public class RetentionStore {

    private final JdbcClient jdbc;

    public RetentionStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * Subjects whose last consent interaction for a purpose is older than the retention period.
     *
     * <p>Measured from the most recent event rather than the first. A subject who re-consented last
     * month has not been dormant for three years however old their earliest row is, and measuring
     * from creation would propose erasing the records of the group's most engaged contacts.
     *
     * <p>Excludes anyone who already has an open action, so a sweeper on a five-minute timer does
     * not raise the same proposal two hundred and eighty-eight times a day.
     */
    public List<DueSubject> findDue(String entityId, String purposeCode, Instant olderThan,
                                    int limit) {
        return jdbc.sql("""
                        with last_activity as (
                            select subject_id, max(occurred_at) as last_at
                              from consent_event
                             where entity_id = :entityId and purpose_code = :purposeCode
                             group by subject_id
                        )
                        select la.subject_id, la.last_at
                          from last_activity la
                         where la.last_at < :olderThan
                           and not exists (
                               select 1 from retention_action ra
                                where ra.entity_id = :entityId
                                  and ra.subject_id = la.subject_id
                                  and ra.purpose_code = :purposeCode
                                  and ra.status in ('DUE', 'NOTICE_SENT'))
                         order by la.last_at
                         limit :limit
                        """)
                .param("entityId", entityId)
                .param("purposeCode", purposeCode)
                .param("olderThan", java.sql.Timestamp.from(olderThan))
                .param("limit", limit)
                .query((rs, n) -> new DueSubject(rs.getString("subject_id"),
                        rs.getTimestamp("last_at").toInstant()))
                .list();
    }

    /**
     * Raises a proposed erasure.
     *
     * <p>Idempotent on the natural key, so a sweep that overlaps with the previous one — or a
     * second replica that briefly held the lock — produces one action rather than two.
     */
    public void raise(Action action) {
        jdbc.sql("""
                        insert into retention_action (entity_id, activity_id, purpose_code,
                                                      subject_id, last_activity_at, notice_due_at,
                                                      erase_due_at, status, system_name)
                        values (:entityId, :activityId, :purposeCode, :subjectId, :lastActivityAt,
                                :noticeDueAt, :eraseDueAt, 'DUE', :systemName)
                        on conflict (entity_id, subject_id, purpose_code, erase_due_at)
                            do nothing
                        """)
                .param("entityId", action.entityId())
                .param("activityId", action.activityId())
                .param("purposeCode", action.purposeCode())
                .param("subjectId", action.subjectId())
                .param("lastActivityAt", java.sql.Timestamp.from(action.lastActivityAt()))
                .param("noticeDueAt", java.sql.Timestamp.from(action.noticeDueAt()))
                .param("eraseDueAt", java.sql.Timestamp.from(action.eraseDueAt()))
                .param("systemName", action.systemName())
                .update();
    }

    public List<Action> open(String entityId, int limit) {
        return jdbc.sql(SELECT + " where entity_id = :entityId "
                        + "and status in ('DUE', 'NOTICE_SENT') order by notice_due_at limit :limit")
                .param("entityId", entityId)
                .param("limit", limit)
                .query(RetentionStore::map)
                .list();
    }

    /**
     * Every retention action ever raised against one person, open and closed alike.
     *
     * <p>For the evidence bundle. "Why do you still have my data" and "did you actually delete it"
     * are among the commonest things a principal asks, and this table is where the answer is: what
     * was due, when the Rule 8 notice went, whether the owning system confirmed the erasure and who
     * confirmed it. The closed rows are the exculpatory ones, so this must not filter by status the
     * way {@link #open} does.
     */
    public List<Action> forSubject(String entityId, String subjectId) {
        return jdbc.sql(SELECT + " where entity_id = :entityId and subject_id = :subjectId "
                        + "order by erase_due_at")
                .param("entityId", entityId)
                .param("subjectId", subjectId)
                .query(RetentionStore::map)
                .list();
    }

    /** Actions whose Rule 8 notice is now due and has not been sent. */
    public List<Action> noticeDue(Instant asOf, int limit) {
        return jdbc.sql(SELECT + " where status = 'DUE' and notice_due_at <= :asOf "
                        + "order by notice_due_at limit :limit")
                .param("asOf", java.sql.Timestamp.from(asOf))
                .param("limit", limit)
                .query(RetentionStore::map)
                .list();
    }

    /** Actions past their erasure date that nobody has confirmed. The compliance position. */
    public List<Action> overdue(Instant asOf, int limit) {
        return jdbc.sql(SELECT + " where status in ('DUE', 'NOTICE_SENT') "
                        + "and erase_due_at <= :asOf order by erase_due_at limit :limit")
                .param("asOf", java.sql.Timestamp.from(asOf))
                .param("limit", limit)
                .query(RetentionStore::map)
                .list();
    }

    public void markNoticeSent(long id, Instant sentAt) {
        jdbc.sql("update retention_action set status = 'NOTICE_SENT', notified_at = :at "
                        + "where id = :id and status = 'DUE'")
                .param("id", id)
                .param("at", java.sql.Timestamp.from(sentAt))
                .update();
    }

    /**
     * Records that the owning system acted.
     *
     * @param status ERASED, RETAINED or CANCELLED. RETAINED is a documented decision to keep the
     *               data on some other basis and is not a failure; what it must not be is
     *               indistinguishable from a DUE nobody looked at
     */
    public int complete(long id, String status, String confirmedBy, String note, Instant at) {
        return jdbc.sql("""
                        update retention_action
                           set status = :status, erased_at = :at, confirmed_by = :by, note = :note
                         where id = :id and status in ('DUE', 'NOTICE_SENT')
                        """)
                .param("id", id)
                .param("status", status)
                .param("by", confirmedBy)
                .param("note", note)
                .param("at", java.sql.Timestamp.from(at))
                .update();
    }

    private static final String SELECT = """
            select id, entity_id, activity_id, purpose_code, subject_id, last_activity_at,
                   notice_due_at, erase_due_at, status, system_name, notified_at, erased_at,
                   confirmed_by, note, raised_at
              from retention_action
            """;

    private static Action map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Action(rs.getLong("id"), rs.getString("entity_id"),
                (Long) rs.getObject("activity_id"), rs.getString("purpose_code"),
                rs.getString("subject_id"), rs.getTimestamp("last_activity_at").toInstant(),
                rs.getTimestamp("notice_due_at").toInstant(),
                rs.getTimestamp("erase_due_at").toInstant(), rs.getString("status"),
                rs.getString("system_name"),
                rs.getTimestamp("notified_at") == null ? null
                        : rs.getTimestamp("notified_at").toInstant(),
                rs.getTimestamp("erased_at") == null ? null
                        : rs.getTimestamp("erased_at").toInstant(),
                rs.getString("confirmed_by"), rs.getString("note"),
                rs.getTimestamp("raised_at").toInstant());
    }

    /**
     * @param noticeDueAt Rule 8's pre-erasure intimation. Earlier than {@code eraseDueAt} by the
     *                    configured lead time, because telling somebody after erasing their data
     *                    is not telling them
     */
    public record Action(Long id, String entityId, Long activityId, String purposeCode,
                         String subjectId, Instant lastActivityAt, Instant noticeDueAt,
                         Instant eraseDueAt, String status, String systemName, Instant notifiedAt,
                         Instant erasedAt, String confirmedBy, String note, Instant raisedAt) {

        public Action(String entityId, Long activityId, String purposeCode, String subjectId,
                      Instant lastActivityAt, Instant noticeDueAt, Instant eraseDueAt,
                      String systemName) {
            this(null, entityId, activityId, purposeCode, subjectId, lastActivityAt, noticeDueAt,
                    eraseDueAt, "DUE", systemName, null, null, null, null, null);
        }

        public boolean open() {
            return "DUE".equals(status) || "NOTICE_SENT".equals(status);
        }
    }

    public record DueSubject(String subjectId, Instant lastActivityAt) {
    }
}
