package com.uds.consent.ledger.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * What a Significant Data Fiduciary owes under DPDP Rule 13, and whether it has been done.
 *
 * <p>Three obligation types, on two different rhythms. The Data Protection Impact Assessment and
 * the independent data audit are entity-level and annual. Algorithmic due diligence is per system:
 * Rule 13 asks for verification that the algorithmic systems processing personal data do not risk
 * data principals' rights, and a group running three scored-ranking systems owes three answers.
 *
 * <p>The register is empty for every entity the Government has not notified. That is the right
 * answer rather than a hidden one — a platform that manufactured obligations for entities that do
 * not have them would produce an overdue count nobody could act on, which is how a real overdue
 * count stops being read.
 */
@Repository
public class SdfObligationStore {

    private final JdbcClient jdbc;

    public SdfObligationStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * Puts an obligation on the register.
     *
     * @return the new row's id, or {@code 0} where this obligation was already on the register.
     *         The distinction is the caller's idempotency signal: {@code raiseDue} runs from a
     *         scheduler, an admin endpoint and a test, and counting a row that already existed as
     *         newly raised would report an entity acquiring two DPIAs a day
     */
    public long raise(Obligation obligation) {
        return jdbc.sql("""
                        insert into sdf_obligation (entity_id, obligation_type, period_start,
                                                    period_end, due_at, algorithmic_system_id)
                        values (:entityId, :type, :periodStart, :periodEnd, :dueAt, :systemId)
                        on conflict (entity_id, obligation_type, period_start,
                                     algorithmic_system_id) do nothing
                        returning id
                        """)
                .param("entityId", obligation.entityId())
                .param("type", obligation.obligationType())
                .param("periodStart", Date.valueOf(obligation.periodStart()))
                .param("periodEnd", Date.valueOf(obligation.periodEnd()))
                .param("dueAt", Timestamp.from(obligation.dueAt()))
                .param("systemId", obligation.algorithmicSystemId())
                .query(Long.class)
                // ON CONFLICT DO NOTHING returns no row, so an absent result means it was already
                // there rather than that anything failed.
                .optional()
                .orElse(0L);
    }

    /**
     * Records that the assessment or audit was carried out.
     *
     * <p>The artefact hash is not optional and the database agrees — {@code ck_sdf_completion_
     * evidenced} refuses a completion without one. Rule 13 requires the evidence to be available
     * on audit, and a row pointing at a filename that can be replaced without the row changing is
     * a record of a claim rather than of a report.
     */
    public int complete(long id, String conductedBy, String artefactRef, String artefactSha256,
                        String findings, Instant completedAt) {
        if (conductedBy == null || conductedBy.isBlank()
                || artefactRef == null || artefactRef.isBlank()
                || artefactSha256 == null || artefactSha256.isBlank()) {
            throw new IllegalArgumentException(
                    "Rule 13 requires the assessment to be evidenced: who conducted it, where the "
                            + "report is, and a hash proving it is that report");
        }
        return jdbc.sql("""
                        update sdf_obligation
                           set completed_at = :at, conducted_by = :by, artefact_ref = :ref,
                               artefact_sha256 = :hash, findings = :findings
                         where id = :id and completed_at is null
                        """)
                .param("id", id)
                .param("at", Timestamp.from(completedAt))
                .param("by", conductedBy)
                .param("ref", artefactRef)
                .param("hash", artefactSha256)
                .param("findings", findings)
                .update();
    }

    /** Records that the observations reached the Board, which Rule 13 asks for separately. */
    public int markReportedToBoard(long id, Instant reportedAt) {
        return jdbc.sql("""
                        update sdf_obligation set board_reported_at = :at
                         where id = :id and completed_at is not null
                        """)
                .param("id", id)
                .param("at", Timestamp.from(reportedAt))
                .update();
    }

    public Optional<Obligation> find(long id) {
        return jdbc.sql(SELECT + " where id = :id")
                .param("id", id)
                .query(SdfObligationStore::map)
                .optional();
    }

    public List<Obligation> forEntity(String entityId) {
        return jdbc.sql(SELECT + " where entity_id = :entityId order by due_at desc")
                .param("entityId", entityId)
                .query(SdfObligationStore::map)
                .list();
    }

    /** Obligations whose date has passed with nothing recorded against them. */
    public List<Obligation> overdue(String entityId, Instant asOf) {
        return jdbc.sql(SELECT + " where entity_id = :entityId and completed_at is null "
                        + "and due_at <= :asOf order by due_at")
                .param("entityId", entityId)
                .param("asOf", Timestamp.from(asOf))
                .query(SdfObligationStore::map)
                .list();
    }

    /**
     * Completed assessments whose observations have not reached the Board.
     *
     * <p>Its own query because it is its own failure. An entity that did the DPIA and never
     * reported it has done the expensive part and missed the part that is checked, and a register
     * that folded the two together would show it as compliant.
     */
    public List<Obligation> completedButUnreported(String entityId) {
        return jdbc.sql(SELECT + " where entity_id = :entityId and completed_at is not null "
                        + "and board_reported_at is null "
                        + "and obligation_type in ('DPIA', 'INDEPENDENT_AUDIT') "
                        + "order by completed_at")
                .param("entityId", entityId)
                .query(SdfObligationStore::map)
                .list();
    }

    /** The latest completed obligation of a type, which is what the next due date runs from. */
    public Optional<Obligation> lastCompleted(String entityId, String obligationType,
                                              Long algorithmicSystemId) {
        return jdbc.sql(SELECT + " where entity_id = :entityId and obligation_type = :type "
                        + "and algorithmic_system_id is not distinct from :systemId "
                        + "and completed_at is not null order by period_start desc limit 1")
                .param("entityId", entityId)
                .param("type", obligationType)
                .param("systemId", algorithmicSystemId)
                .query(SdfObligationStore::map)
                .optional();
    }

    /** Whether any obligation exists for this entity at all. Cheap enough for a health check. */
    public int countOverdue(String entityId, Instant asOf) {
        return jdbc.sql("select count(*) from sdf_obligation where entity_id = :entityId "
                        + "and completed_at is null and due_at <= :asOf")
                .param("entityId", entityId)
                .param("asOf", Timestamp.from(asOf))
                .query(Integer.class)
                .single();
    }

    private static final String SELECT = """
            select id, entity_id, obligation_type, period_start, period_end, due_at, completed_at,
                   conducted_by, artefact_ref, artefact_sha256, board_reported_at, findings,
                   algorithmic_system_id, created_at
              from sdf_obligation
            """;

    private static Obligation map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Obligation(rs.getLong("id"), rs.getString("entity_id"),
                rs.getString("obligation_type"), rs.getDate("period_start").toLocalDate(),
                rs.getDate("period_end").toLocalDate(),
                rs.getTimestamp("due_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null
                        : rs.getTimestamp("completed_at").toInstant(),
                rs.getString("conducted_by"), rs.getString("artefact_ref"),
                rs.getString("artefact_sha256"),
                rs.getTimestamp("board_reported_at") == null ? null
                        : rs.getTimestamp("board_reported_at").toInstant(),
                rs.getString("findings"), (Long) rs.getObject("algorithmic_system_id"),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * @param obligationType DPIA, INDEPENDENT_AUDIT or ALGORITHMIC_DUE_DILIGENCE
     * @param artefactSha256 hash of the report. What turns a reference into evidence
     */
    public record Obligation(Long id, String entityId, String obligationType,
                             LocalDate periodStart, LocalDate periodEnd, Instant dueAt,
                             Instant completedAt, String conductedBy, String artefactRef,
                             String artefactSha256, Instant boardReportedAt, String findings,
                             Long algorithmicSystemId, Instant createdAt) {

        public Obligation(String entityId, String obligationType, LocalDate periodStart,
                          LocalDate periodEnd, Instant dueAt, Long algorithmicSystemId) {
            this(null, entityId, obligationType, periodStart, periodEnd, dueAt, null, null, null,
                    null, null, null, algorithmicSystemId, null);
        }

        public boolean open() {
            return completedAt == null;
        }

        /** Done, and reported. Rule 13 asks for both and only the pair discharges it. */
        public boolean discharged() {
            return completedAt != null
                    && (boardReportedAt != null
                        || "ALGORITHMIC_DUE_DILIGENCE".equals(obligationType));
        }
    }
}
