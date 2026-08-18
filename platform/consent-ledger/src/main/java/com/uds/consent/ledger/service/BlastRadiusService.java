package com.uds.consent.ledger.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * Works out who is affected when legal changes a purpose or a notice.
 *
 * <p>Consent given against notice v3 and purpose v5 is not consent to v7 and v9. When something
 * changes, someone has to answer: which subjects need to be asked again, which need only to be
 * told, and which are unaffected. Most consent platforms leave that to a spreadsheet and a
 * nervous afternoon. Computing it is the difference between a version chain that documents a
 * problem and one that resolves it.
 *
 * <p>The classification turns on whether the change was material — whether it altered what the
 * subject was agreeing to. That judgement is made by a human when publishing and recorded on the
 * version; this service applies it, it does not guess it.
 */
@Service
public class BlastRadiusService {

    private final JdbcClient jdbc;

    public BlastRadiusService(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * Impact of moving a purpose to {@code newVersion}, for a version that has been published.
     *
     * <p>Reads the material-change flag from the stored version. Only correct <em>after</em>
     * publishing — see {@link #forPurposeChange(String, int, boolean)} for why that distinction
     * matters and which method to call before.
     *
     * @param purposeCode purpose being changed
     * @param newVersion  the version that was published
     */
    @Transactional(readOnly = true)
    public Impact forPurposeChange(String purposeCode, int newVersion) {
        // Selected as a row rather than aggregated. bool_or over no rows yields a row containing
        // NULL rather than no rows at all, so an aggregate cannot distinguish "this version is not
        // material" from "this version does not exist" — and that distinction is the whole point.
        Boolean material = jdbc.sql("""
                        select material_change from purpose_version
                         where purpose_code = :code and version = :version
                        """)
                .param("code", purposeCode)
                .param("version", newVersion)
                .query(Boolean.class)
                .optional()
                .orElse(null);

        if (material == null) {
            // No such version. Previously this coalesced to false and produced a confident
            // NOTICE_UPDATE_ONLY — the safest-sounding answer to a question that was never
            // actually asked of the data. Refusing is the only honest option: the caller either
            // mistyped a version or is asking before publishing, and both deserve to be told.
            throw new IllegalArgumentException("purpose " + purposeCode + " has no version "
                    + newVersion + "; to assess a version before publishing it, pass the "
                    + "material-change judgement explicitly");
        }

        return impactForPurpose(purposeCode, newVersion, material);
    }

    /**
     * Impact of a purpose change that has <em>not</em> been published yet.
     *
     * <p>This is the one to call before publishing, which is what the operations notes instruct and
     * what the number is actually for — knowing that eleven thousand subjects need re-consent is
     * only useful while there is still a choice about whether to make the change.
     *
     * <p>The material-change flag is a parameter rather than a lookup because before publication
     * there is no row to look it up from. It is a human judgement about whether the change alters
     * what the subject agreed to, and this service applies that judgement rather than forming one.
     */
    @Transactional(readOnly = true)
    public Impact forPurposeChange(String purposeCode, int newVersion, boolean materialChange) {
        return impactForPurpose(purposeCode, newVersion, materialChange);
    }

    private Impact impactForPurpose(String purposeCode, int newVersion, boolean material) {
        List<Bucket> buckets = jdbc.sql("""
                        select entity_id, status, count(*) as n from consent_artefact
                         where purpose_code = :code and purpose_version < :version
                         group by entity_id, status
                         order by entity_id, status
                        """)
                .param("code", purposeCode)
                .param("version", newVersion)
                .query((rs, n) -> new Bucket(rs.getString("entity_id"), rs.getString("status"),
                        rs.getLong("n")))
                .list();

        long standingConsent = buckets.stream()
                .filter(b -> "GRANTED".equals(b.status()))
                .mapToLong(Bucket::count)
                .sum();

        long total = buckets.stream().mapToLong(Bucket::count).sum();

        Action action;
        if (total == 0) {
            action = Action.NO_ACTION;
        } else if (material) {
            // A material change means the standing consent no longer covers what will now be
            // done. Continuing to rely on it would be relying on consent to something else.
            action = Action.RE_CONSENT_REQUIRED;
        } else {
            action = Action.NOTICE_UPDATE_ONLY;
        }

        return new Impact(purposeCode, newVersion, material, action, standingConsent, total, buckets);
    }

    /**
     * Impact of a notice version that has been published.
     *
     * <p>Scoped through the purposes that point at the notice, since a notice is not consented to
     * directly — it is the text a purpose's consent was given against.
     */
    @Transactional(readOnly = true)
    public List<Impact> forNoticeChange(String noticeId, int newNoticeVersion) {
        Boolean material = jdbc.sql("""
                        select material_change from notice_version
                         where notice_id = :noticeId and version = :version
                        """)
                .param("noticeId", noticeId)
                .param("version", newNoticeVersion)
                .query(Boolean.class)
                .optional()
                .orElse(null);

        if (material == null) {
            throw new IllegalArgumentException("notice " + noticeId + " has no version "
                    + newNoticeVersion + "; to assess a version before publishing it, pass the "
                    + "material-change judgement explicitly");
        }

        return forNoticeChange(noticeId, newNoticeVersion, material);
    }

    /**
     * Impact of a notice change that has not been published yet.
     *
     * <p>See {@link #forPurposeChange(String, int, boolean)}: before publication there is no row to
     * read the flag from, and a lookup that quietly returns false in that case turns the one
     * calculation whose job is to say "ask these people again" into one that says "carry on".
     */
    @Transactional(readOnly = true)
    public List<Impact> forNoticeChange(String noticeId, int newNoticeVersion,
                                        boolean materialChange) {
        boolean material = materialChange;
        List<String> purposeCodes = jdbc.sql("""
                        select distinct purpose_code from purpose_version where notice_id = :noticeId
                        """)
                .param("noticeId", noticeId)
                .query(String.class)
                .list();

        return purposeCodes.stream()
                .map(code -> impactForNotice(code, noticeId, newNoticeVersion, material))
                .toList();
    }

    private Impact impactForNotice(String purposeCode, String noticeId, int newNoticeVersion,
                                   boolean material) {
        List<Bucket> buckets = jdbc.sql("""
                        select entity_id, status, count(*) as n from consent_artefact
                         where purpose_code = :code and notice_id = :noticeId
                           and (notice_version is null or notice_version < :version)
                         group by entity_id, status
                        """)
                .param("code", purposeCode)
                .param("noticeId", noticeId)
                .param("version", newNoticeVersion)
                .query((rs, n) -> new Bucket(rs.getString("entity_id"), rs.getString("status"),
                        rs.getLong("n")))
                .list();

        long standing = buckets.stream()
                .filter(b -> "GRANTED".equals(b.status()))
                .mapToLong(Bucket::count)
                .sum();
        long total = buckets.stream().mapToLong(Bucket::count).sum();

        Action action = total == 0 ? Action.NO_ACTION
                : material ? Action.RE_CONSENT_REQUIRED : Action.NOTICE_UPDATE_ONLY;

        return new Impact(purposeCode, newNoticeVersion, material, action, standing, total, buckets);
    }

    /** What has to happen to the subjects holding consent against the superseded version. */
    public enum Action {
        /** Nobody holds consent against the old version. Publish and move on. */
        NO_ACTION,

        /** Tell the affected subjects; their existing consent still covers what will be done. */
        NOTICE_UPDATE_ONLY,

        /**
         * Ask again. Until they answer, the platform must stop relying on the old consent — which
         * is what the INVALIDATED event type exists for.
         */
        RE_CONSENT_REQUIRED
    }

    /**
     * @param standingConsentAffected subjects currently in GRANTED against a superseded version —
     *                                the number that matters commercially, because it is the size
     *                                of the re-permissioning campaign
     * @param totalAffected           every artefact against a superseded version, any status
     * @param byEntityAndStatus       breakdown, so each entity can see its own exposure
     */
    public record Impact(String purposeCode, int newVersion, boolean materialChange, Action action,
                         long standingConsentAffected, long totalAffected,
                         List<Bucket> byEntityAndStatus) {

        public Impact {
            byEntityAndStatus = List.copyOf(byEntityAndStatus);
        }

        /** Counts keyed by entity, for the group rollup view. */
        public Map<String, Long> byEntity() {
            return byEntityAndStatus.stream().collect(java.util.stream.Collectors.toMap(
                    Bucket::entityId, Bucket::count, Long::sum));
        }
    }

    public record Bucket(String entityId, String status, long count) {
    }
}
