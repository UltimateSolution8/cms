package com.uds.consent.ledger.store;

import com.uds.consent.core.crypto.CanonicalJson;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Evidence that enforcement happened.
 *
 * <p>The evidence plane could already prove what somebody consented to. What it could not prove
 * was that anybody asked before acting — which is the question both TRAI and DPDP Rule 6 put, and
 * the one a consent record cannot answer on its own.
 *
 * <p>Insert and read only. The tables are under the same triggers and revocations as the ledger,
 * for the same reason: a log the application can edit proves whatever the application last decided
 * it should prove.
 */
@Repository
public class EnforcementEvidenceStore {

    private final JdbcClient jdbc;

    public EnforcementEvidenceStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * Records a denied decision.
     *
     * <p>Only denials reach here. See the migration for the argument — the evidentiary question is
     * "why was this person contacted despite X", and enumerating every allowance would eventually
     * force somebody to turn the logging off under load.
     */
    public long recordDenial(Denial denial) {
        return jdbc.sql("""
                        insert into enforcement_decision (entity_id, subject_id, purpose_code,
                                                          purpose_version, channel, jurisdiction,
                                                          outcome, reason, explanation,
                                                          application_id, vendor_id, client_id,
                                                          campaign_id, policy_version, decided_at)
                        values (:entityId, :subjectId, :purposeCode, :purposeVersion, :channel,
                                :jurisdiction, :outcome, :reason, :explanation, :applicationId,
                                :vendorId, :clientId, :campaignId, :policyVersion, :decidedAt)
                        returning id
                        """)
                .param("entityId", denial.entityId())
                .param("subjectId", denial.subjectId())
                .param("purposeCode", denial.purposeCode())
                .param("purposeVersion", denial.purposeVersion())
                .param("channel", denial.channel())
                .param("jurisdiction", denial.jurisdiction())
                .param("outcome", denial.outcome())
                .param("reason", denial.reason())
                .param("explanation", denial.explanation())
                .param("applicationId", denial.applicationId())
                .param("vendorId", denial.vendorId())
                .param("clientId", denial.clientId())
                .param("campaignId", denial.campaignId())
                .param("policyVersion", denial.policyVersion())
                .param("decidedAt", java.sql.Timestamp.from(denial.decidedAt()))
                .query(Long.class)
                .single();
    }

    /** Records one campaign scrub — the artefact a TRAI investigation asks for. */
    public long recordScrubRun(ScrubRun run) {
        return jdbc.sql("""
                        insert into scrub_run (entity_id, channel, client_id, campaign_id, actor_id,
                                               submitted_count, permitted_count, excluded_count,
                                               reason_counts)
                        values (:entityId, :channel, :clientId, :campaignId, :actorId,
                                :submitted, :permitted, :excluded, cast(:reasons as jsonb))
                        returning id
                        """)
                .param("entityId", run.entityId())
                .param("channel", run.channel())
                .param("clientId", run.clientId())
                .param("campaignId", run.campaignId())
                .param("actorId", run.actorId())
                .param("submitted", run.submittedCount())
                .param("permitted", run.permittedCount())
                .param("excluded", run.excludedCount())
                .param("reasons", CanonicalJson.serialize(run.reasonCounts()))
                .query(Long.class)
                .single();
    }

    /**
     * Denials for an entity, newest first.
     *
     * <p>{@code subjectId} and {@code campaignId} are both optional filters, because an
     * investigation starts from one or the other and never from a scan of everything.
     */
    public List<Denial> denials(String entityId, String subjectId, String campaignId, int limit,
                                int offset) {
        return jdbc.sql("""
                        select entity_id, subject_id, purpose_code, purpose_version, channel,
                               jurisdiction, outcome, reason, explanation, application_id,
                               vendor_id, client_id, campaign_id, policy_version, decided_at,
                               recorded_at
                          from enforcement_decision
                         where entity_id = :entityId
                           and (cast(:subjectId as varchar) is null
                                or subject_id = cast(:subjectId as varchar))
                           and (cast(:campaignId as varchar) is null
                                or campaign_id = cast(:campaignId as varchar))
                         order by decided_at desc, id desc
                         limit :limit offset :offset
                        """)
                .param("entityId", entityId)
                .param("subjectId", subjectId)
                .param("campaignId", campaignId)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, n) -> new Denial(
                        rs.getString("entity_id"), rs.getString("subject_id"),
                        rs.getString("purpose_code"), (Integer) rs.getObject("purpose_version"),
                        rs.getString("channel"), rs.getString("jurisdiction"),
                        rs.getString("outcome"), rs.getString("reason"),
                        rs.getString("explanation"), rs.getString("application_id"),
                        rs.getString("vendor_id"), rs.getString("client_id"),
                        rs.getString("campaign_id"), rs.getString("policy_version"),
                        rs.getTimestamp("decided_at").toInstant(),
                        rs.getTimestamp("recorded_at").toInstant()))
                .list();
    }

    public List<ScrubRun> scrubRuns(String entityId, String campaignId, int limit, int offset) {
        return jdbc.sql("""
                        select entity_id, channel, client_id, campaign_id, actor_id,
                               submitted_count, permitted_count, excluded_count, reason_counts,
                               run_at
                          from scrub_run
                         where entity_id = :entityId
                           and (cast(:campaignId as varchar) is null
                                or campaign_id = cast(:campaignId as varchar))
                         order by run_at desc, id desc
                         limit :limit offset :offset
                        """)
                .param("entityId", entityId)
                .param("campaignId", campaignId)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, n) -> new ScrubRun(
                        rs.getString("entity_id"), rs.getString("channel"),
                        rs.getString("client_id"), rs.getString("campaign_id"),
                        rs.getString("actor_id"), rs.getInt("submitted_count"),
                        rs.getInt("permitted_count"), rs.getInt("excluded_count"),
                        Map.of(), rs.getTimestamp("run_at").toInstant(),
                        rs.getString("reason_counts")))
                .list();
    }

    /** How many denials are on record, for the health indicator and the metric gauge. */
    public long denialCount(String entityId) {
        return jdbc.sql("select count(*) from enforcement_decision where entity_id = :entityId")
                .param("entityId", entityId)
                .query(Long.class)
                .single();
    }

    /**
     * A refused decision, as recorded.
     *
     * @param decidedAt  the instant the decision was taken, which callers may pin for
     *                   reproducibility; distinct from {@code recordedAt}, which is when this row
     *                   landed. A caller replaying an audit question backdates the first and
     *                   cannot touch the second
     */
    public record Denial(String entityId, String subjectId, String purposeCode,
                         Integer purposeVersion, String channel, String jurisdiction,
                         String outcome, String reason, String explanation, String applicationId,
                         String vendorId, String clientId, String campaignId, String policyVersion,
                         Instant decidedAt, Instant recordedAt) {
    }

    /**
     * @param reasonCounts  exclusions by reason, supplied on write
     * @param reasonCountsJson the same thing as stored, returned on read rather than reparsed —
     *                      the console renders it and nothing in Java branches on it
     */
    public record ScrubRun(String entityId, String channel, String clientId, String campaignId,
                           String actorId, int submittedCount, int permittedCount,
                           int excludedCount, Map<String, Integer> reasonCounts, Instant runAt,
                           String reasonCountsJson) {

        public ScrubRun(String entityId, String channel, String clientId, String campaignId,
                        String actorId, int submittedCount, int permittedCount, int excludedCount,
                        Map<String, Integer> reasonCounts) {
            this(entityId, channel, clientId, campaignId, actorId, submittedCount, permittedCount,
                    excludedCount, reasonCounts, null, null);
        }

        public ScrubRun {
            reasonCounts = reasonCounts == null ? Map.of() : Map.copyOf(reasonCounts);
        }
    }
}
