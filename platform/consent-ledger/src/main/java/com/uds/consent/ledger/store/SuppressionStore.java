package com.uds.consent.ledger.store;

import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.IdentifierType;
import com.uds.consent.core.model.SuppressionScope;
import com.uds.consent.core.model.SuppressionSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Do-not-contact state.
 *
 * <p>This is the part of the platform that protects against the group's nearest-term regulatory
 * risk. TRAI acts against telemarketers today, with financial penalties and disconnection of
 * telecom resources, while DPDP's substantive obligations do not bite until May 2027. A
 * suppression entry from a statutory registry overrides any consent record: a subject on the
 * national preference register is not contactable on a promotional purpose even if a perfectly
 * valid consent row exists for them.
 */
@Repository
public class SuppressionStore {

    /**
     * Ordered so that a statutory, group-wide suppression is found before a narrower one. The
     * caller acts on the first hit, and it should be the broadest and least defeasible.
     */
    private static final String SCOPE_ORDER = """
            order by case scope
                         when 'GLOBAL'   then 0
                         when 'ENTITY'   then 1
                         when 'CLIENT'   then 2
                         when 'CAMPAIGN' then 3
                     end
            limit 1
            """;

    private static final String SCOPE_PREDICATE = """
            and (scope = 'GLOBAL'
                 or (scope = 'ENTITY'   and entity_id = :entityId)
                 or (scope = 'CLIENT'   and entity_id = :entityId and client_id   = :clientId)
                 or (scope = 'CAMPAIGN' and entity_id = :entityId and campaign_id = :campaignId))
            """;

    private static final String WINDOW_PREDICATE = """
            and effective_from <= :at
            and (effective_to is null or effective_to > :at)
            """;

    private final JdbcClient jdbc;

    public SuppressionStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** The strongest active suppression against a known subject, if any. */
    public Optional<Hit> findForSubject(String entityId, String subjectId, Channel channel,
                                        String clientId, String campaignId, Instant at) {
        return jdbc.sql("""
                        select source, scope, reason from suppression_entry
                         where subject_id = :subjectId and channel = :channel
                        """ + WINDOW_PREDICATE + SCOPE_PREDICATE + SCOPE_ORDER)
                .param("subjectId", subjectId)
                .param("channel", channel.name())
                .param("entityId", entityId)
                .param("clientId", clientId)
                .param("campaignId", campaignId)
                .param("at", Timestamp.from(at))
                .query(SuppressionStore::mapHit)
                .optional();
    }

    /**
     * The strongest active suppression against a raw identifier.
     *
     * <p>Needed for campaign scrubbing, which runs over a list of numbers before any of them has
     * been resolved to a subject — and which must not create subjects as a side effect of being
     * scrubbed, or the act of checking would itself expand the database.
     */
    public Optional<Hit> findForIdentifier(String entityId, IdentifierType identifierType,
                                           String identifierHash, Channel channel, String clientId,
                                           String campaignId, Instant at) {
        return jdbc.sql("""
                        select source, scope, reason from suppression_entry
                         where identifier_hash = :hash and identifier_type = :type
                           and channel = :channel
                        """ + WINDOW_PREDICATE + SCOPE_PREDICATE + SCOPE_ORDER)
                .param("hash", identifierHash)
                .param("type", identifierType.name())
                .param("channel", channel.name())
                .param("entityId", entityId)
                .param("clientId", clientId)
                .param("campaignId", campaignId)
                .param("at", Timestamp.from(at))
                .query(SuppressionStore::mapHit)
                .optional();
    }

    public long add(String entityId, SuppressionScope scope, SuppressionSource source,
                    Channel channel, IdentifierType identifierType, String identifierHash,
                    String subjectId, String clientId, String campaignId, Instant effectiveFrom,
                    Instant effectiveTo, String reason, String createdBy) {
        return jdbc.sql("""
                        insert into suppression_entry (
                            entity_id, scope, source, channel, identifier_type, identifier_hash,
                            subject_id, client_id, campaign_id, effective_from, effective_to,
                            reason, created_by)
                        values (
                            :entityId, :scope, :source, :channel, :identifierType, :identifierHash,
                            :subjectId, :clientId, :campaignId, :effectiveFrom, :effectiveTo,
                            :reason, :createdBy)
                        returning id
                        """)
                .param("entityId", scope == SuppressionScope.GLOBAL ? null : entityId)
                .param("scope", scope.name())
                .param("source", source.name())
                .param("channel", channel.name())
                .param("identifierType", identifierType.name())
                .param("identifierHash", identifierHash)
                .param("subjectId", subjectId)
                .param("clientId", clientId)
                .param("campaignId", campaignId)
                .param("effectiveFrom", Timestamp.from(effectiveFrom))
                .param("effectiveTo", effectiveTo == null ? null : Timestamp.from(effectiveTo))
                .param("reason", reason)
                .param("createdBy", createdBy)
                .query(Long.class)
                .single();
    }

    /** Bulk load of a statutory registry export. Returns the number of entries written. */
    public int addStatutoryBatch(SuppressionSource source, Channel channel,
                                 IdentifierType identifierType, List<String> identifierHashes,
                                 Instant effectiveFrom, String createdBy) {
        if (!source.isStatutory()) {
            throw new IllegalArgumentException(
                    "addStatutoryBatch is for registry loads; " + source + " is not statutory");
        }
        int written = 0;
        for (String hash : identifierHashes) {
            add(null, SuppressionScope.GLOBAL, source, channel, identifierType, hash, null, null,
                    null, effectiveFrom, null, "statutory registry load", createdBy);
            written++;
        }
        return written;
    }

    private static Hit mapHit(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Hit(
                SuppressionSource.valueOf(rs.getString("source")),
                SuppressionScope.valueOf(rs.getString("scope")),
                rs.getString("reason"));
    }

    /**
     * A matched suppression.
     *
     * @param source where it came from; statutory sources cannot be overridden by consent
     * @param scope  how far it reaches
     * @param reason free text, present for manual entries
     */
    public record Hit(SuppressionSource source, SuppressionScope scope, String reason) {
    }
}
