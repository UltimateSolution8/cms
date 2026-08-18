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
