package com.uds.consent.ledger.store;

import com.uds.consent.core.model.CaptureMethod;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.ConsentArtefact;
import com.uds.consent.core.model.ConsentStatus;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.LegalBasis;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Current-state projection over the event chain — the table the enforcement plane reads.
 *
 * <p>Everything here is derived and rebuildable. If it were lost entirely it could be
 * reconstructed by replaying {@link ConsentEventStore}; nothing in it is evidence of anything.
 */
@Repository
public class ConsentArtefactStore {

    private static final String COLUMNS = """
            entity_id, subject_id, purpose_code, purpose_version, status, legal_basis, notice_id,
            notice_version, language_tag, capture_method, channel, application_id, jurisdiction,
            granted_at, expires_at, withdrawn_at, last_event_at, sequence_number, last_event_hash,
            conflict_count
            """;

    private final JdbcClient jdbc;

    public ConsentArtefactStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public Optional<ConsentArtefact> find(String entityId, String subjectId, String purposeCode) {
        return jdbc.sql("select " + COLUMNS + """
                         from consent_artefact
                        where entity_id = :entityId and subject_id = :subjectId
                          and purpose_code = :purposeCode
                        """)
                .param("entityId", entityId)
                .param("subjectId", subjectId)
                .param("purposeCode", purposeCode)
                .query(ConsentArtefactStore::map)
                .optional();
    }

    /** All purposes on record for a subject. Used to build snapshots and receipts. */
    public List<ConsentArtefact> findAllForSubject(String entityId, String subjectId) {
        return jdbc.sql("select " + COLUMNS + """
                         from consent_artefact
                        where entity_id = :entityId and subject_id = :subjectId
                        order by purpose_code
                        """)
                .param("entityId", entityId)
                .param("subjectId", subjectId)
                .query(ConsentArtefactStore::map)
                .list();
    }

    /**
     * Artefacts with no event behind them at all.
     *
     * <p><strong>The forged grant.</strong> The reconciliation sweep walks chains and compares each
     * to its artefact, which catches an artefact that was <em>edited</em> — and cannot see one that
     * was <em>inserted</em>, because there is no chain to start from. That is the wrong way round:
     * a fabricated {@code WITHDRAWN} refuses lawful processing, while a fabricated {@code GRANTED}
     * authorises unlawful processing on a consent that never existed, and the same owner role can
     * write either.
     *
     * <p>Anti-join rather than a per-subject query, because it answers a question about the whole
     * table and runs once per sweep.
     */
    public List<String[]> findWithoutChain(int limit) {
        return jdbc.sql("""
                        select a.entity_id, a.subject_id, a.purpose_code, a.status
                          from consent_artefact a
                         where not exists (
                               select 1 from consent_event e
                                where e.entity_id = a.entity_id
                                  and e.subject_id = a.subject_id
                                  and e.purpose_code = a.purpose_code)
                         order by a.entity_id, a.subject_id, a.purpose_code
                         limit :limit
                        """)
                .param("limit", limit)
                .query((rs, n) -> new String[]{rs.getString("entity_id"), rs.getString("subject_id"),
                        rs.getString("purpose_code"), rs.getString("status")})
                .list();
    }

    /**
     * How many artefacts have no event behind them, counted rather than sampled.
     *
     * <p>{@link #findWithoutChain(int)} takes a limit, so the size of what it returns is the size of
     * the <em>page</em> and not of the finding. Reporting that as the count understates exactly the
     * case that matters most: a bulk insert into {@code consent_artefact} produces thousands of
     * forged rows and a limited query would report the page size, every time, looking like a small
     * and stable problem.
     *
     * <p><strong>{@code except} rather than {@code not exists}, and it is a measured 76× not a
     * preference.</strong> Under RLS the {@code not exists} form degrades to a nested loop: the
     * policy predicate calls {@code current_setting()}, which the planner cannot estimate, so it
     * assumes 0.5% selectivity — 250 rows against 50,002 — and chooses a plan that is correct and
     * quadratic. Measured as the application role on 50,002 artefacts: <strong>3,438 ms</strong>
     * against 45 ms for the form below, with 24,974,751 rows discarded by the join filter, and
     * unchanged by {@code ANALYZE} because the estimate is structural rather than stale.
     * {@code HashSetOp Except} cannot nested-loop, so the shape is stable whatever the policy does
     * to the estimate. The set operation is safe here because {@code (entity_id, subject_id,
     * purpose_code)} is {@code consent_artefact}'s primary key, so the rows are already distinct.
     * {@code CAPACITY.md} §8.
     */
    public long countWithoutChain() {
        return jdbc.sql("""
                        select count(*) from (
                            select entity_id, subject_id, purpose_code from consent_artefact
                            except
                            select entity_id, subject_id, purpose_code from consent_event
                        ) missing
                        """)
                .query(Long.class)
                .single();
    }

    /** Writes the projected state. Called only by the projector, inside the append transaction. */
    public void upsert(ConsentArtefact artefact, int conflictCount) {
        jdbc.sql("""
                        insert into consent_artefact (
                            entity_id, subject_id, purpose_code, purpose_version, status, legal_basis,
                            notice_id, notice_version, language_tag, capture_method, channel,
                            application_id, jurisdiction, granted_at, expires_at, withdrawn_at,
                            last_event_at, sequence_number, last_event_hash, conflict_count, updated_at)
                        values (
                            :entityId, :subjectId, :purposeCode, :purposeVersion, :status, :legalBasis,
                            :noticeId, :noticeVersion, :languageTag, :captureMethod, :channel,
                            :applicationId, :jurisdiction, :grantedAt, :expiresAt, :withdrawnAt,
                            :lastEventAt, :sequenceNumber, :lastEventHash, :conflictCount, now())
                        on conflict (entity_id, subject_id, purpose_code) do update set
                            purpose_version = excluded.purpose_version,
                            status          = excluded.status,
                            legal_basis     = excluded.legal_basis,
                            notice_id       = excluded.notice_id,
                            notice_version  = excluded.notice_version,
                            language_tag    = excluded.language_tag,
                            capture_method  = excluded.capture_method,
                            channel         = excluded.channel,
                            application_id  = excluded.application_id,
                            jurisdiction    = excluded.jurisdiction,
                            granted_at      = excluded.granted_at,
                            expires_at      = excluded.expires_at,
                            withdrawn_at    = excluded.withdrawn_at,
                            last_event_at   = excluded.last_event_at,
                            sequence_number = excluded.sequence_number,
                            last_event_hash = excluded.last_event_hash,
                            conflict_count  = excluded.conflict_count,
                            updated_at      = now()
                        """)
                .param("entityId", artefact.entityId())
                .param("subjectId", artefact.subjectId())
                .param("purposeCode", artefact.purposeCode())
                .param("purposeVersion", artefact.purposeVersion())
                .param("status", artefact.status().name())
                .param("legalBasis", name(artefact.legalBasis()))
                .param("noticeId", artefact.noticeId())
                .param("noticeVersion", artefact.noticeVersion())
                .param("languageTag", artefact.languageTag())
                .param("captureMethod", name(artefact.captureMethod()))
                .param("channel", name(artefact.channel()))
                .param("applicationId", artefact.applicationId())
                .param("jurisdiction", name(artefact.jurisdiction()))
                .param("grantedAt", timestamp(artefact.grantedAt()))
                .param("expiresAt", timestamp(artefact.expiresAt()))
                .param("withdrawnAt", timestamp(artefact.withdrawnAt()))
                .param("lastEventAt", timestamp(artefact.lastEventAt()))
                .param("sequenceNumber", artefact.sequenceNumber())
                .param("lastEventHash", artefact.lastEventHash())
                .param("conflictCount", conflictCount)
                .update();
    }

    /** Conflict counter for a projected artefact, used by the projector and the console. */
    public int conflictCount(String entityId, String subjectId, String purposeCode) {
        return jdbc.sql("""
                        select coalesce(max(conflict_count), 0) from consent_artefact
                         where entity_id = :entityId and subject_id = :subjectId
                           and purpose_code = :purposeCode
                        """)
                .param("entityId", entityId)
                .param("subjectId", subjectId)
                .param("purposeCode", purposeCode)
                .query(Integer.class)
                .single();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static ConsentArtefact map(ResultSet rs, int rowNum) throws SQLException {
        return new ConsentArtefact(
                rs.getString("entity_id"),
                rs.getString("subject_id"),
                rs.getString("purpose_code"),
                rs.getInt("purpose_version"),
                ConsentStatus.valueOf(rs.getString("status")),
                enumOrNull(LegalBasis.class, rs.getString("legal_basis")),
                rs.getString("notice_id"),
                (Integer) rs.getObject("notice_version"),
                rs.getString("language_tag"),
                enumOrNull(CaptureMethod.class, rs.getString("capture_method")),
                enumOrNull(Channel.class, rs.getString("channel")),
                rs.getString("application_id"),
                enumOrNull(Jurisdiction.class, rs.getString("jurisdiction")),
                instant(rs.getTimestamp("granted_at")),
                instant(rs.getTimestamp("expires_at")),
                instant(rs.getTimestamp("withdrawn_at")),
                instant(rs.getTimestamp("last_event_at")),
                rs.getLong("sequence_number"),
                rs.getString("last_event_hash"));
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
