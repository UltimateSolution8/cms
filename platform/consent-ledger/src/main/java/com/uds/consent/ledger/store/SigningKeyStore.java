package com.uds.consent.ledger.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;

/**
 * The public halves of the keys that sign offline consent snapshots.
 *
 * <p>Exists because the rotation procedure in {@code OPERATIONS.md} §2.2 had no mechanism behind
 * it. The runbook tells an operator to publish the retired verification key alongside the new one;
 * {@code SigningKeys} held exactly one key, from configuration, and {@code GET /v1/keys} returned
 * exactly one entry. Rotating meant every snapshot signed by the outgoing key stopped verifying the
 * moment the new one was configured — and a field device holding one is a device that silently
 * stops enforcing, in the exact window when nobody is watching for it.
 *
 * <p><strong>Private keys never come here.</strong> Only what a verifier needs: the id, the
 * algorithm, the public point, and the lifecycle it has to respect. The private half stays in the
 * process environment today and in a KMS when there is one, and that move touches
 * {@code SigningKeys} and nothing here.
 */
@Repository
public class SigningKeyStore {

    private final JdbcClient jdbc;

    public SigningKeyStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * Records the key this instance is holding, without disturbing any other.
     *
     * <p>Called at start-up. Idempotent on {@code key_id}, and it deliberately does <em>not</em>
     * retire whatever was active before: during a rolling deploy two instances legitimately hold
     * different keys, and an instance that retired "the other one" on the way up would revoke a key
     * its sibling is still signing with. Retirement is an administrative act with a person's name
     * against it.
     *
     * <p>A key already recorded as {@code COMPROMISED} is not resurrected by a restart. Somebody
     * decided that key must not verify; a process coming up holding it is a deployment mistake, and
     * quietly re-activating it would undo the one decision most worth being permanent.
     */
    public void register(String keyId, String algorithm, String publicKeyBase64) {
        jdbc.sql("""
                        insert into signing_key (key_id, algorithm, public_key_base64)
                        values (:keyId, :algorithm, :publicKey)
                        on conflict (key_id) do update
                           set public_key_base64 = excluded.public_key_base64,
                               algorithm = excluded.algorithm
                         where signing_key.state <> 'COMPROMISED'
                        """)
                .param("keyId", keyId)
                .param("algorithm", algorithm)
                .param("publicKey", publicKeyBase64)
                .update();
    }

    /**
     * Every key a device should still trust: active and retired, never compromised.
     *
     * <p>The distinction is the whole reason there are three states. A retired key's signatures
     * remain good evidence of what the platform asserted at the time, so a snapshot issued minutes
     * before a rotation must keep verifying until it expires. A compromised key's signatures prove
     * nothing, so publishing it would tell every device to trust exactly the assertions an attacker
     * can now manufacture.
     */
    public List<Key> trusted() {
        return jdbc.sql("""
                        select key_id, algorithm, public_key_base64, state, activated_at, retired_at
                          from signing_key
                         where state in ('ACTIVE', 'RETIRED')
                         order by state, activated_at desc
                        """)
                .query(SigningKeyStore::map)
                .list();
    }

    /** Every key including compromised ones. What the console shows an administrator. */
    public List<Key> all() {
        return jdbc.sql("""
                        select key_id, algorithm, public_key_base64, state, activated_at, retired_at
                          from signing_key
                         order by state, activated_at desc
                        """)
                .query(SigningKeyStore::map)
                .list();
    }

    /**
     * Moves a key out of signing service.
     *
     * @param state {@code RETIRED} to stop it signing while it still verifies, or
     *              {@code COMPROMISED} to stop it verifying at all
     * @return whether anything changed. False means the key does not exist or was already in a
     *         non-active state — worth returning rather than swallowing, because "I retired it"
     *         and "it was already retired" are different things to an operator mid-incident
     */
    public boolean changeState(String keyId, String state, String actor, String notes,
                               Instant at) {
        return jdbc.sql("""
                        update signing_key
                           set state = :state, retired_at = :at, retired_by = :actor,
                               notes = :notes
                         where key_id = :keyId and state = 'ACTIVE'
                        """)
                .param("keyId", keyId)
                .param("state", state)
                .param("at", java.sql.Timestamp.from(at))
                .param("actor", actor)
                .param("notes", notes)
                .update() > 0;
    }

    /**
     * The oldest key still signing, for the age check.
     *
     * <p>Oldest rather than newest: a fleet where one instance was restarted with a fresh key and
     * the rest were not is exactly the case an age check should catch, and asking about the newest
     * would report the healthy one.
     */
    public Instant oldestActiveActivation() {
        return jdbc.sql("select min(activated_at) from signing_key where state = 'ACTIVE'")
                .query(Instant.class)
                .optional()
                .orElse(null);
    }

    private static Key map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Key(
                rs.getString("key_id"),
                rs.getString("algorithm"),
                rs.getString("public_key_base64"),
                rs.getString("state"),
                rs.getTimestamp("activated_at").toInstant(),
                rs.getTimestamp("retired_at") == null
                        ? null
                        : rs.getTimestamp("retired_at").toInstant());
    }

    public record Key(String keyId, String algorithm, String publicKeyBase64, String state,
                      Instant activatedAt, Instant retiredAt) {
    }
}
