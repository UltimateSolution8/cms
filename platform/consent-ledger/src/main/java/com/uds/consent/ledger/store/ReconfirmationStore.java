package com.uds.consent.ledger.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Korea's two-yearly confirmation that a recipient still consents to receive advertising.
 *
 * <p>Enforcement Decree of the Information and Communications Network Act, Article 62-3. Modelled
 * on {@link RetentionStore}: raise what is due, record what was done, and keep the gap between the
 * two visible to somebody. That gap is the compliance position, and it is the only thing a
 * platform can honestly offer for an obligation whose discharge happens in a mail server rather
 * than here.
 *
 * <p>Note what this store deliberately cannot do. There is no method to expire a consent whose
 * confirmation went unanswered, because the Decree does not say silence withdraws consent and
 * inventing that rule would suppress lawful contact on the platform's own authority. An overdue
 * row stays overdue until a person deals with it.
 */
@Repository
public class ReconfirmationStore {

    /** Art. 62-3(1): every two years from the date consent was obtained. */
    public static final int INTERVAL_YEARS = 2;

    private final JdbcClient jdbc;

    public ReconfirmationStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * When a consent given at {@code consentedAt} must next be confirmed.
     *
     * <p>Two calendar years, not 730 days. The two are the same number in three years out of four
     * and differ by a day across a leap year, and the Decree says "the same date" — so a duration
     * in days would drift by a day every four years in whichever direction happened to be
     * unfavourable. 29 February plus two years resolves to 28 February, which is what
     * {@link LocalDate#plusYears} does and what any reasonable reading of the Decree requires:
     * there is no 29 February to wait for.
     */
    public static Instant dueAfter(Instant consentedAt) {
        return consentedAt.atZone(ZoneOffset.UTC)
                .toLocalDate()
                .plusYears(INTERVAL_YEARS)
                .atTime(consentedAt.atZone(ZoneOffset.UTC).toLocalTime())
                .toInstant(ZoneOffset.UTC);
    }

    /**
     * Consents whose two years have elapsed and which have no confirmation row yet.
     *
     * <p>Reads the grant from the ledger rather than from the artefact projection, because the
     * clock runs from the date consent was <em>obtained</em> and the projection carries the
     * current state. A subject who withdrew and re-consented has two grants and owes a
     * confirmation on the second, which only the event history shows.
     */
    public List<DueConsent> findDue(String entityId, String purposeCode, Instant asOf, int limit) {
        return jdbc.sql("""
                        with granted as (
                            select subject_id, max(occurred_at) as consented_at
                              from consent_event
                             where entity_id = :entityId and purpose_code = :purposeCode
                               and event_type = 'GRANTED'
                             group by subject_id
                        ),
                        current_state as (
                            select subject_id, status
                              from consent_artefact
                             where entity_id = :entityId and purpose_code = :purposeCode
                        )
                        select g.subject_id, g.consented_at
                          from granted g
                          join current_state cs on cs.subject_id = g.subject_id
                         where cs.status = 'GRANTED'
                           and g.consented_at <= :cutoff
                           and not exists (
                               select 1 from consent_reconfirmation cr
                                where cr.entity_id = :entityId
                                  and cr.subject_id = g.subject_id
                                  and cr.purpose_code = :purposeCode
                                  and cr.consented_at = g.consented_at)
                         order by g.consented_at
                         limit :limit
                        """)
                .param("entityId", entityId)
                .param("purposeCode", purposeCode)
                // Consents given more than two years before asOf are the ones now due. Computing
                // the cutoff here rather than the due date per row keeps the whole test in the
                // index; dueAfter() then puts the exact date on the row that is raised.
                .param("cutoff", Timestamp.from(
                        asOf.atZone(ZoneOffset.UTC).toLocalDateTime()
                                .minusYears(INTERVAL_YEARS).toInstant(ZoneOffset.UTC)))
                .param("limit", limit)
                .query((rs, n) -> new DueConsent(rs.getString("subject_id"),
                        rs.getTimestamp("consented_at").toInstant()))
                .list();
    }

    /** Raises the obligation. Idempotent on the natural key, so an overlapping sweep raises one. */
    public void raise(String entityId, String subjectId, String purposeCode, Instant consentedAt) {
        jdbc.sql("""
                        insert into consent_reconfirmation (entity_id, subject_id, purpose_code,
                                                            consented_at, due_at, status)
                        values (:entityId, :subjectId, :purposeCode, :consentedAt, :dueAt, 'DUE')
                        on conflict (entity_id, subject_id, purpose_code, due_at) do nothing
                        """)
                .param("entityId", entityId)
                .param("subjectId", subjectId)
                .param("purposeCode", purposeCode)
                .param("consentedAt", Timestamp.from(consentedAt))
                .param("dueAt", Timestamp.from(dueAfter(consentedAt)))
                .update();
    }

    /**
     * Records that the confirmation was sent, with what it disclosed.
     *
     * <p>All three disclosures are required. Art. 62-3(2) specifies the content, so a call that
     * could record the act without the content would let the platform report an obligation
     * discharged by something that did not discharge it — the failure being a clean-looking
     * register and a finding on the first inspection.
     */
    public int markSent(long id, String senderName, Instant disclosedConsentDate,
                        String withdrawalMethod, String channel, Instant sentAt) {
        if (senderName == null || senderName.isBlank()
                || disclosedConsentDate == null
                || withdrawalMethod == null || withdrawalMethod.isBlank()) {
            throw new IllegalArgumentException(
                    "Art. 62-3(2) requires the confirmation to disclose the sender's name, the "
                            + "recipient's consent and its date, and how to maintain or withdraw "
                            + "it; a confirmation missing any of the three does not discharge the "
                            + "obligation and will not be recorded as though it did");
        }
        return jdbc.sql("""
                        update consent_reconfirmation
                           set status = 'SENT', sent_at = :sentAt, sender_name = :senderName,
                               disclosed_consent_date = :disclosedAt,
                               withdrawal_method = :withdrawalMethod, channel = :channel
                         where id = :id and status = 'DUE'
                        """)
                .param("id", id)
                .param("sentAt", Timestamp.from(sentAt))
                .param("senderName", senderName)
                .param("disclosedAt", Timestamp.from(disclosedConsentDate))
                .param("withdrawalMethod", withdrawalMethod)
                .param("channel", channel)
                .update();
    }

    /**
     * Records what the recipient answered.
     *
     * @param status MAINTAINED, WITHDRAWN or NOT_APPLICABLE. A withdrawal recorded here is the
     *               administrative closure of the queue row; the consent itself is withdrawn by
     *               appending a WITHDRAWN event, which is the caller's job and not this store's
     */
    public int complete(long id, String status, String completedBy, String note, Instant at) {
        return jdbc.sql("""
                        update consent_reconfirmation
                           set status = :status, responded_at = :at, completed_by = :by,
                               note = :note
                         where id = :id and status in ('DUE', 'SENT')
                        """)
                .param("id", id)
                .param("status", status)
                .param("by", completedBy)
                .param("note", note)
                .param("at", Timestamp.from(at))
                .update();
    }

    /** Everything outstanding for an entity, oldest obligation first. */
    public List<Reconfirmation> open(String entityId, int limit) {
        return jdbc.sql(SELECT + " where entity_id = :entityId and status in ('DUE', 'SENT') "
                        + "order by due_at limit :limit")
                .param("entityId", entityId)
                .param("limit", limit)
                .query(ReconfirmationStore::map)
                .list();
    }

    /**
     * Every confirmation ever raised for one person, whatever became of it.
     *
     * <p>For the evidence bundle, and unlike {@link #open} it deliberately includes the closed ones.
     * The complaint this answers is "you kept mailing me and never asked whether I still wanted it",
     * and the exculpatory facts are the confirmations that <em>were</em> sent and what was disclosed
     * in them — a list filtered to what is still outstanding would carry only the accusatory half.
     *
     * <p>Oldest first, because it reads as a history rather than a work queue.
     */
    public List<Reconfirmation> forSubject(String entityId, String subjectId) {
        return jdbc.sql(SELECT + " where entity_id = :entityId and subject_id = :subjectId "
                        + "order by due_at")
                .param("entityId", entityId)
                .param("subjectId", subjectId)
                .query(ReconfirmationStore::map)
                .list();
    }

    /**
     * Obligations whose date has passed and which nobody has sent.
     *
     * <p>The number that belongs on a health check. An obligation with a queue and no counter is
     * an obligation nobody notices going unmet.
     */
    public List<Reconfirmation> overdue(Instant asOf, int limit) {
        return jdbc.sql(SELECT + " where status = 'DUE' and due_at <= :asOf "
                        + "order by due_at limit :limit")
                .param("asOf", Timestamp.from(asOf))
                .param("limit", limit)
                .query(ReconfirmationStore::map)
                .list();
    }

    public int countOverdue(Instant asOf) {
        return jdbc.sql("select count(*) from consent_reconfirmation "
                        + "where status = 'DUE' and due_at <= :asOf")
                .param("asOf", Timestamp.from(asOf))
                .query(Integer.class)
                .single();
    }

    /**
     * Whether this subject owes a confirmation for this purpose as at {@code asOf}.
     *
     * <p>Read on the decision path so that a Korean marketing decision can carry the fact as an
     * obligation. It does not change the answer — see the class javadoc and V19's header.
     */
    public boolean isOverdue(String entityId, String subjectId, String purposeCode, Instant asOf) {
        return jdbc.sql("""
                        select count(*) from consent_reconfirmation
                         where entity_id = :entityId and subject_id = :subjectId
                           and purpose_code = :purposeCode
                           and status in ('DUE', 'SENT') and due_at <= :asOf
                        """)
                .param("entityId", entityId)
                .param("subjectId", subjectId)
                .param("purposeCode", purposeCode)
                .param("asOf", Timestamp.from(asOf))
                .query(Integer.class)
                .single() > 0;
    }

    private static final String SELECT = """
            select id, entity_id, subject_id, purpose_code, consented_at, due_at, status,
                   sender_name, disclosed_consent_date, withdrawal_method, channel, sent_at,
                   responded_at, completed_by, note, raised_at
              from consent_reconfirmation
            """;

    private static Reconfirmation map(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new Reconfirmation(rs.getLong("id"), rs.getString("entity_id"),
                rs.getString("subject_id"), rs.getString("purpose_code"),
                rs.getTimestamp("consented_at").toInstant(),
                rs.getTimestamp("due_at").toInstant(), rs.getString("status"),
                rs.getString("sender_name"),
                rs.getTimestamp("disclosed_consent_date") == null ? null
                        : rs.getTimestamp("disclosed_consent_date").toInstant(),
                rs.getString("withdrawal_method"), rs.getString("channel"),
                rs.getTimestamp("sent_at") == null ? null : rs.getTimestamp("sent_at").toInstant(),
                rs.getTimestamp("responded_at") == null ? null
                        : rs.getTimestamp("responded_at").toInstant(),
                rs.getString("completed_by"), rs.getString("note"),
                rs.getTimestamp("raised_at").toInstant());
    }

    /**
     * @param senderName           Art. 62-3(2), as disclosed
     * @param disclosedConsentDate the consent date the recipient was shown, which is the same
     *                             value as {@code consentedAt} unless somebody sent the wrong one
     *                             — and the two being separate columns is what makes that
     *                             detectable rather than assumed
     */
    public record Reconfirmation(Long id, String entityId, String subjectId, String purposeCode,
                                 Instant consentedAt, Instant dueAt, String status,
                                 String senderName, Instant disclosedConsentDate,
                                 String withdrawalMethod, String channel, Instant sentAt,
                                 Instant respondedAt, String completedBy, String note,
                                 Instant raisedAt) {

        public boolean open() {
            return "DUE".equals(status) || "SENT".equals(status);
        }
    }

    public record DueConsent(String subjectId, Instant consentedAt) {
    }
}
