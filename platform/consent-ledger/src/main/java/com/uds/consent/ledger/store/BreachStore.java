package com.uds.consent.ledger.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Personal data breaches and the notifications they oblige.
 *
 * <p>The method that justifies holding any of this in the consent platform is
 * {@link #affectedAsAt(String, Instant, List)}. Rule 7's 72-hour report has to carry a summary of
 * the intimations given to data principals, which means somebody must be able to say which
 * consents were live for each affected subject <em>at the moment of the breach</em> — not now,
 * after three days of withdrawals prompted by the notification itself. The ledger is the only
 * system in the group that can answer that.
 */
@Repository
public class BreachStore {

    private final JdbcClient jdbc;

    public BreachStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public void create(Breach breach) {
        jdbc.sql("""
                        insert into personal_data_breach (breach_id, entity_id, jurisdiction,
                                                          occurred_at, detected_at, aware_at,
                                                          description, data_categories,
                                                          purpose_codes, affected_subjects,
                                                          risk_assessment, severity, status,
                                                          reported_by)
                        values (:breachId, :entityId, :jurisdiction, :occurredAt, :detectedAt,
                                :awareAt, :description, cast(:categories as jsonb),
                                cast(:purposes as jsonb), :affected, :risk, :severity, :status,
                                :reportedBy)
                        """)
                .param("breachId", breach.breachId())
                .param("entityId", breach.entityId())
                .param("jurisdiction", breach.jurisdiction())
                .param("occurredAt", java.sql.Timestamp.from(breach.occurredAt()))
                .param("detectedAt", breach.detectedAt() == null ? null
                        : java.sql.Timestamp.from(breach.detectedAt()))
                .param("awareAt", java.sql.Timestamp.from(breach.awareAt()))
                .param("description", breach.description())
                // Bound as they arrive. The record's javadoc says these fields are JSON as stored,
                // and serialising them again here would wrap an array in a string — which reads
                // back as one purpose code with quotes in its name and silently empties the
                // affected population.
                .param("categories", breach.dataCategories())
                .param("purposes", breach.purposeCodes())
                .param("affected", breach.affectedSubjects())
                .param("risk", breach.riskAssessment())
                .param("severity", breach.severity())
                .param("status", breach.status())
                .param("reportedBy", breach.reportedBy())
                .update();
    }

    public Optional<Breach> find(String breachId) {
        return jdbc.sql(SELECT + " where breach_id = :breachId")
                .param("breachId", breachId)
                .query(BreachStore::map)
                .optional();
    }

    public List<Breach> findForEntity(String entityId, int limit) {
        return jdbc.sql(SELECT + " where entity_id = :entityId order by reported_at desc "
                        + "limit :limit")
                .param("entityId", entityId)
                .param("limit", limit)
                .query(BreachStore::map)
                .list();
    }

    /** Revises the assessment. Breach records are working documents — see the migration. */
    public void updateAssessment(String breachId, String severity, String riskAssessment,
                                 Integer affectedSubjects, String status) {
        jdbc.sql("""
                        update personal_data_breach
                           set severity = coalesce(:severity, severity),
                               risk_assessment = coalesce(:risk, risk_assessment),
                               affected_subjects = coalesce(:affected, affected_subjects),
                               status = coalesce(:status, status)
                         where breach_id = :breachId
                        """)
                .param("breachId", breachId)
                .param("severity", severity)
                .param("risk", riskAssessment)
                .param("affected", affectedSubjects)
                .param("status", status)
                .update();
    }

    public void close(String breachId, String closureNote, Instant closedAt) {
        jdbc.sql("""
                        update personal_data_breach
                           set status = 'CLOSED', closure_note = :note, closed_at = :closedAt
                         where breach_id = :breachId
                        """)
                .param("breachId", breachId)
                .param("note", closureNote)
                .param("closedAt", java.sql.Timestamp.from(closedAt))
                .update();
    }

    // -------------------------------------------------------------------------------------
    // Notifications
    // -------------------------------------------------------------------------------------

    /**
     * Records an obligation. Idempotent, so re-deriving the clock does not duplicate rows.
     *
     * <p>{@code entity_id} is selected from the parent breach rather than taken as an argument.
     * V22 gave this table an entity so the isolation policy has something to bind, and a caller
     * that could supply the value is a caller that could supply the wrong one — a notification
     * filed under a fiduciary other than the one whose breach it discharges would be invisible
     * to the entity that owes it and visible to one that does not. Deriving it here makes that
     * unrepresentable, and the insert writes nothing at all if the breach does not exist, which
     * is the correct answer to an obligation against no incident.
     */
    public void addObligation(String breachId, String party, Instant dueAt, boolean immediate,
                              String basis) {
        jdbc.sql("""
                        insert into breach_notification
                            (breach_id, entity_id, party, due_at, immediate, basis)
                        select :breachId, b.entity_id, :party, :dueAt, :immediate, :basis
                          from personal_data_breach b
                         where b.breach_id = :breachId
                        on conflict (breach_id, party, basis) do nothing
                        """)
                .param("breachId", breachId)
                .param("party", party)
                .param("dueAt", dueAt == null ? null : java.sql.Timestamp.from(dueAt))
                .param("immediate", immediate)
                .param("basis", basis)
                .update();
    }

    /**
     * Marks an obligation discharged.
     *
     * @return how many rows were affected; zero means the obligation did not exist, which the
     *         caller must treat as an error rather than as success — recording a notification
     *         against nothing is exactly the kind of quiet failure a breach report must not have
     */
    public int markNotified(long notificationId, Instant notifiedAt, String notifiedBy,
                            String method, String reference, Integer recipientCount, String note) {
        return jdbc.sql("""
                        update breach_notification
                           set notified_at = :notifiedAt, notified_by = :notifiedBy,
                               method = :method, reference = :reference,
                               recipient_count = :recipients, note = :note
                         where id = :id and notified_at is null
                        """)
                .param("id", notificationId)
                .param("notifiedAt", java.sql.Timestamp.from(notifiedAt))
                .param("notifiedBy", notifiedBy)
                .param("method", method)
                .param("reference", reference)
                .param("recipients", recipientCount)
                .param("note", note)
                .update();
    }

    public List<Notification> notifications(String breachId) {
        return jdbc.sql("""
                        select id, breach_id, party, due_at, immediate, basis, notified_at,
                               notified_by, method, reference, recipient_count, note
                          from breach_notification
                         where breach_id = :breachId
                         order by immediate desc, due_at, id
                        """)
                .param("breachId", breachId)
                .query(BreachStore::mapNotification)
                .list();
    }

    /** Obligations not yet discharged, across every open breach. What the sweeper pages on. */
    public List<Notification> outstanding(int limit) {
        return jdbc.sql("""
                        select n.id, n.breach_id, n.party, n.due_at, n.immediate, n.basis,
                               n.notified_at, n.notified_by, n.method, n.reference,
                               n.recipient_count, n.note
                          from breach_notification n
                          join personal_data_breach b on b.breach_id = n.breach_id
                         where n.notified_at is null
                           and b.status not in ('CLOSED', 'NOT_NOTIFIABLE')
                         order by n.immediate desc, n.due_at
                         limit :limit
                        """)
                .param("limit", limit)
                .query(BreachStore::mapNotification)
                .list();
    }

    // -------------------------------------------------------------------------------------
    // The reason this lives in the consent platform
    // -------------------------------------------------------------------------------------

    /**
     * Subjects whose consent was live for these purposes at the breach instant.
     *
     * <p>Computed from the event ledger rather than the artefact projection, and that is the whole
     * point. The projection holds current state; a subject who withdrew the day after the breach
     * reads as WITHDRAWN today and was nonetheless affected. Replaying events up to
     * {@code occurredAt} gives the population as it actually stood, which is what the Rule 7
     * report has to describe.
     *
     * <p>An empty {@code purposeCodes} means every purpose — a breach of the database as a whole
     * rather than of one processing activity.
     */
    public List<AffectedSubject> affectedAsAt(String entityId, Instant occurredAt,
                                              List<String> purposeCodes) {
        return jdbc.sql("""
                        with state_at_breach as (
                            select distinct on (subject_id, purpose_code)
                                   subject_id, purpose_code, purpose_version, event_type,
                                   occurred_at
                              from consent_event
                             where entity_id = :entityId
                               and occurred_at <= :at
                               and (:allPurposes or purpose_code in (:purposes))
                             order by subject_id, purpose_code, occurred_at desc,
                                      sequence_number desc
                        )
                        select subject_id, purpose_code, purpose_version, event_type,
                               occurred_at
                          from state_at_breach
                        -- The event types that leave a subject in GRANTED, mirroring
                        -- ConsentEventType.resultingStatus. Kept as a literal list rather than a
                        -- join to a lookup table so that adding an event type breaks this query
                        -- loudly at review rather than silently shrinking a breach's scope.
                         where event_type in ('GRANTED', 'MODIFIED')
                         order by subject_id, purpose_code
                        """)
                .param("entityId", entityId)
                .param("at", java.sql.Timestamp.from(occurredAt))
                .param("allPurposes", purposeCodes.isEmpty())
                // Never an empty list: PostgreSQL rejects `in ()`. The allPurposes flag short-
                // circuits the predicate, and this placeholder is then never evaluated.
                .param("purposes", purposeCodes.isEmpty() ? List.of("") : purposeCodes)
                .query((rs, n) -> new AffectedSubject(rs.getString("subject_id"),
                        rs.getString("purpose_code"), rs.getInt("purpose_version"),
                        rs.getString("event_type"),
                        rs.getTimestamp("occurred_at").toInstant()))
                .list();
    }

    private static final String SELECT = """
            select breach_id, entity_id, jurisdiction, occurred_at, detected_at, aware_at,
                   description, data_categories, purpose_codes, affected_subjects, risk_assessment,
                   severity, status, reported_by, reported_at, closed_at, closure_note
              from personal_data_breach
            """;

    private static Breach map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Breach(
                rs.getString("breach_id"), rs.getString("entity_id"), rs.getString("jurisdiction"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getTimestamp("detected_at") == null ? null
                        : rs.getTimestamp("detected_at").toInstant(),
                rs.getTimestamp("aware_at").toInstant(), rs.getString("description"),
                rs.getString("data_categories"), rs.getString("purpose_codes"),
                (Integer) rs.getObject("affected_subjects"), rs.getString("risk_assessment"),
                rs.getString("severity"), rs.getString("status"), rs.getString("reported_by"),
                rs.getTimestamp("reported_at").toInstant(),
                rs.getTimestamp("closed_at") == null ? null
                        : rs.getTimestamp("closed_at").toInstant(),
                rs.getString("closure_note"));
    }

    private static Notification mapNotification(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new Notification(rs.getLong("id"), rs.getString("breach_id"), rs.getString("party"),
                rs.getTimestamp("due_at") == null ? null : rs.getTimestamp("due_at").toInstant(),
                rs.getBoolean("immediate"), rs.getString("basis"),
                rs.getTimestamp("notified_at") == null ? null
                        : rs.getTimestamp("notified_at").toInstant(),
                rs.getString("notified_by"), rs.getString("method"), rs.getString("reference"),
                (Integer) rs.getObject("recipient_count"), rs.getString("note"));
    }

    /**
     * @param dataCategories JSON as stored. Rendered by the console and never branched on in Java,
     *                       so parsing it here would buy nothing and cost a mapper dependency
     */
    public record Breach(String breachId, String entityId, String jurisdiction, Instant occurredAt,
                         Instant detectedAt, Instant awareAt, String description,
                         String dataCategories, String purposeCodes, Integer affectedSubjects,
                         String riskAssessment, String severity, String status, String reportedBy,
                         Instant reportedAt, Instant closedAt, String closureNote) {

        public boolean open() {
            return !"CLOSED".equals(status) && !"NOT_NOTIFIABLE".equals(status);
        }
    }

    public record Notification(long id, String breachId, String party, Instant dueAt,
                               boolean immediate, String basis, Instant notifiedAt,
                               String notifiedBy, String method, String reference,
                               Integer recipientCount, String note) {

        public boolean discharged() {
            return notifiedAt != null;
        }

        /** An immediate obligation is late from the moment it exists until it is discharged. */
        public boolean overdueAt(Instant asOf) {
            if (discharged()) {
                return false;
            }
            return immediate || (dueAt != null && asOf.isAfter(dueAt));
        }
    }

    /**
     * @param lastEventType the event that left this subject in GRANTED as at the breach instant.
     *                      Carried rather than reduced to a boolean because a report reads better
     *                      for saying "granted on the web form in March" than "affected: true"
     */
    public record AffectedSubject(String subjectId, String purposeCode, int purposeVersion,
                                  String lastEventType, Instant asOf) {
    }
}
