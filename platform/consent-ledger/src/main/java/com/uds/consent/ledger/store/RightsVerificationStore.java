package com.uds.consent.ledger.store;

import com.uds.consent.core.model.IdentifierType;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.RightsRequestType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * Pending rights requests, from the moment a principal submits one to the moment they prove it was
 * them.
 *
 * <p>See {@code V29__principal_rights_portal.sql} for why the gap exists at all: an unverified
 * submission must not start a statutory clock, because anyone could then burn the group's whole
 * response window on somebody else's behalf.
 */
@Repository
public class RightsVerificationStore {

    private final JdbcClient jdbc;

    public RightsVerificationStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public void create(String reference, String entityId, IdentifierType identifierType,
                       String identifierHash, RightsRequestType requestType,
                       Jurisdiction jurisdiction, String tokenHash, Instant expiresAt) {
        jdbc.sql("""
                        insert into rights_request_verification (
                            reference, entity_id, identifier_type, identifier_hash, request_type,
                            jurisdiction, token_hash, expires_at)
                        values (:reference, :entityId, :identifierType, :identifierHash, :type,
                                :jurisdiction, :tokenHash, :expiresAt)
                        """)
                .param("reference", reference)
                .param("entityId", entityId)
                .param("identifierType", identifierType.name())
                .param("identifierHash", identifierHash)
                .param("type", requestType.name())
                .param("jurisdiction", jurisdiction.name())
                .param("tokenHash", tokenHash)
                .param("expiresAt", Timestamp.from(expiresAt))
                .update();
    }

    public Optional<Pending> find(String reference) {
        return jdbc.sql("""
                        select reference, entity_id, identifier_type, identifier_hash, request_type,
                               jurisdiction, token_hash, request_id, created_at, expires_at,
                               verified_at, attempts
                          from rights_request_verification
                         where reference = :reference
                        """)
                .param("reference", reference)
                .query(RightsVerificationStore::map)
                .optional();
    }

    /**
     * Counts a failed attempt.
     *
     * <p>Incremented in the database rather than read-modify-written in Java, so that two guesses
     * arriving together both count. A cap enforced against a stale read is a cap an attacker
     * defeats with concurrency, which is the one condition under which it matters.
     */
    public int recordFailedAttempt(String reference) {
        Integer attempts = jdbc.sql("""
                        update rights_request_verification
                           set attempts = attempts + 1
                         where reference = :reference
                        returning attempts
                        """)
                .param("reference", reference)
                .query(Integer.class)
                .optional()
                .orElse(0);
        return attempts;
    }

    /** Binds the verified submission to the rights request it produced. Once. */
    public boolean consume(String reference, String requestId, Instant verifiedAt) {
        // The `verified_at is null` predicate is the single-use guarantee, and it is here rather
        // than in a service-layer check because two simultaneous verifications of the same token
        // would otherwise both pass the check and create two rights requests for one person.
        return jdbc.sql("""
                        update rights_request_verification
                           set request_id = :requestId, verified_at = :verifiedAt
                         where reference = :reference
                           and verified_at is null
                        """)
                .param("reference", reference)
                .param("requestId", requestId)
                .param("verifiedAt", Timestamp.from(verifiedAt))
                .update() == 1;
    }

    /**
     * Discards submissions nobody verified.
     *
     * <p>These are the ones that either never reached a real person or reached one who decided not
     * to continue. Keeping them indefinitely would accumulate a table of identifier hashes for
     * people who never completed a request — data collected for a purpose that did not happen,
     * which is the thing DPDP's storage limitation is about.
     */
    public int purgeExpired(Instant asOf, int limit) {
        return jdbc.sql("""
                        delete from rights_request_verification
                         where reference in (
                             select reference from rights_request_verification
                              where verified_at is null and expires_at < :asOf
                              limit :limit)
                        """)
                .param("asOf", Timestamp.from(asOf))
                .param("limit", limit)
                .update();
    }

    private static Pending map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Pending(
                rs.getString("reference"),
                rs.getString("entity_id"),
                IdentifierType.valueOf(rs.getString("identifier_type")),
                rs.getString("identifier_hash"),
                RightsRequestType.valueOf(rs.getString("request_type")),
                Jurisdiction.valueOf(rs.getString("jurisdiction")),
                rs.getString("token_hash"),
                rs.getString("request_id"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("verified_at") == null
                        ? null : rs.getTimestamp("verified_at").toInstant(),
                rs.getInt("attempts"));
    }

    public record Pending(String reference, String entityId, IdentifierType identifierType,
                          String identifierHash, RightsRequestType requestType,
                          Jurisdiction jurisdiction, String tokenHash, String requestId,
                          Instant createdAt, Instant expiresAt, Instant verifiedAt, int attempts) {

        public boolean verified() {
            return verifiedAt != null;
        }

        public boolean expired(Instant asOf) {
            return expiresAt.isBefore(asOf);
        }
    }
}
