package com.uds.consent.ledger.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Reads published notices.
 *
 * <p>Notice versions are immutable once published, enforced by trigger. The reason is narrow and
 * important: every consent event points at the exact notice version rendered, and the group must
 * be able to reproduce in 2031 precisely what a person read in 2026. A notice that can be edited
 * in place destroys that, quietly and irreversibly.
 */
@Repository
public class NoticeStore {

    private final JdbcClient jdbc;

    public NoticeStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** The current published version of a notice for a jurisdiction. */
    public Optional<NoticeVersion> findCurrent(String noticeId, String jurisdiction) {
        return jdbc.sql("""
                        select id, notice_id, version, jurisdiction, material_change,
                               withdrawal_uri, rights_uri, grievance_uri, published_at
                          from notice_version
                         where notice_id = :noticeId and jurisdiction = :jurisdiction
                         order by version desc
                         limit 1
                        """)
                .param("noticeId", noticeId)
                .param("jurisdiction", jurisdiction)
                .query(NoticeStore::mapVersion)
                .optional();
    }

    /** A specific historical version — what an audit or a receipt reproduction needs. */
    public Optional<NoticeVersion> findVersion(String noticeId, int version) {
        return jdbc.sql("""
                        select id, notice_id, version, jurisdiction, material_change,
                               withdrawal_uri, rights_uri, grievance_uri, published_at
                          from notice_version
                         where notice_id = :noticeId and version = :version
                        """)
                .param("noticeId", noticeId)
                .param("version", version)
                .query(NoticeStore::mapVersion)
                .optional();
    }

    /**
     * The rendered text for a language.
     *
     * <p>Returns empty rather than falling back to English when the requested language is
     * missing. A notice the subject cannot read is not an informed notice, and silently
     * substituting English would produce a consent record that looks valid and is not — the
     * worst of both outcomes. The caller must handle the gap explicitly.
     */
    public Optional<Translation> findTranslation(long noticeVersionId, String languageTag) {
        return jdbc.sql("""
                        select language_tag, title, body from notice_translation
                         where notice_version_id = :id and language_tag = :lang
                        """)
                .param("id", noticeVersionId)
                .param("lang", languageTag)
                .query((rs, n) -> new Translation(rs.getString("language_tag"),
                        rs.getString("title"), rs.getString("body")))
                .optional();
    }

    /** Languages a notice version is available in, for the language picker and coverage reports. */
    public List<String> availableLanguages(long noticeVersionId) {
        return jdbc.sql("select language_tag from notice_translation "
                        + "where notice_version_id = :id order by language_tag")
                .param("id", noticeVersionId)
                .query(String.class)
                .list();
    }

    private static NoticeVersion mapVersion(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new NoticeVersion(rs.getLong("id"), rs.getString("notice_id"), rs.getInt("version"),
                rs.getString("jurisdiction"), rs.getBoolean("material_change"),
                rs.getString("withdrawal_uri"), rs.getString("rights_uri"),
                rs.getString("grievance_uri"), rs.getTimestamp("published_at").toInstant());
    }

    /**
     * @param materialChange whether this version changed what the subject was agreeing to, as
     *                       opposed to correcting a typo. Drives the blast-radius calculation.
     */
    public record NoticeVersion(long id, String noticeId, int version, String jurisdiction,
                                boolean materialChange, String withdrawalUri, String rightsUri,
                                String grievanceUri, Instant publishedAt) {
    }

    public record Translation(String languageTag, String title, String body) {
    }
}
