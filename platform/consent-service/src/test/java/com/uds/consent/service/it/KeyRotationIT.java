package com.uds.consent.service.it;

import com.uds.consent.ledger.store.SigningKeyStore;
import com.uds.consent.service.api.dto.ConsentApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rotation is a non-event, because a retired key still verifies.
 *
 * <p>{@code OPERATIONS.md} §2.2 told an operator to publish the retired verification key alongside
 * the new one. {@code SigningKeys.verificationKeys()} agreed in its own javadoc — "publishing the
 * retired key alongside the new one for one snapshot lifetime is what makes rotation a non-event" —
 * and returned {@code Map.of(keyId, publicKey)}: one entry, from configuration, with nowhere for a
 * second to come from. {@code GET /v1/keys} returned one key.
 *
 * <p>So the runbook described something the platform could not do, and the failure it would have
 * produced is the quiet kind. Rotating meant every snapshot signed by the outgoing key stopped
 * verifying the moment the new one was configured — and a field device working offline from a
 * snapshot is a device that silently stops enforcing, mid-shift, with nobody watching. The
 * platform would have looked entirely healthy throughout.
 *
 * <p>Three states now, and the distinction between two of them is the whole design. {@code ACTIVE}
 * signs and verifies; {@code RETIRED} verifies only, which is the overlap that makes rotation
 * survivable; {@code COMPROMISED} does neither, because a key whose private half may be in someone
 * else's hands has signed nothing that proves anything.
 */
class KeyRotationIT extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private SigningKeyStore keys;

    @Test
    @DisplayName("the running instance's key is registered and published without being asked")
    void theActiveKeyIsPublished() {
        // Registered at start-up rather than by an operator remembering. A key that signs and is
        // not published is a key no device can verify against, which is an enforcement outage that
        // looks like tampering.
        assertThat(keys.all())
                .withFailMessage("no signing key was registered at start-up, so GET /v1/keys can "
                        + "only ever serve whatever this one instance happens to hold")
                .isNotEmpty();

        assertThat(rest.getForEntity("/v1/keys", ConsentApi.VerificationKey[].class).getBody())
                .isNotEmpty();
    }

    @Test
    @DisplayName("a retired key is still published, so snapshots signed before rotation verify")
    void aRetiredKeyStillVerifies() {
        String outgoing = registerKey("ACTIVE");

        retire(outgoing, "RETIRED", "scheduled rotation");

        ConsentApi.VerificationKey[] published =
                rest.getForEntity("/v1/keys", ConsentApi.VerificationKey[].class).getBody();

        assertThat(published)
                .withFailMessage("the retired key vanished from /v1/keys; every device holding a "
                        + "snapshot signed before the rotation has just stopped enforcing, and "
                        + "nothing on this platform would report it")
                .extracting(ConsentApi.VerificationKey::keyId)
                .contains(outgoing);
    }

    @Test
    @DisplayName("a compromised key is withdrawn from publication entirely")
    void aCompromisedKeyIsNotPublished() {
        // The case that separates RETIRED from COMPROMISED, and it is not a tidier retirement. A
        // retired key's signatures remain good evidence of what the platform asserted. A
        // compromised key's are evidence of nothing — publishing it would tell every device in the
        // field to trust exactly the assertions an attacker can now manufacture.
        String leaked = registerKey("ACTIVE");

        retire(leaked, "COMPROMISED", "private half found in an unencrypted backup");

        assertThat(rest.getForEntity("/v1/keys", ConsentApi.VerificationKey[].class).getBody())
                .extracting(ConsentApi.VerificationKey::keyId)
                .withFailMessage("a compromised key is still being published to devices")
                .doesNotContain(leaked);

        // Still visible to an administrator, though. Somebody investigating why a snapshot stopped
        // verifying needs to see that the key exists and when it was pulled.
        assertThat(admin().getForEntity("/v1/admin/signing-keys", String.class).getBody())
                .contains(leaked).contains("COMPROMISED");
    }

    @Test
    @DisplayName("retiring a key twice is refused rather than silently accepted")
    void retirementIsNotIdempotentlySilent() {
        // "I retired it" and "it was already retired" are different things to an operator working
        // an incident, and a 200 to both teaches them the call means nothing.
        String keyId = registerKey("ACTIVE");
        retire(keyId, "RETIRED", "scheduled rotation");

        assertThat(admin().postForEntity("/v1/admin/signing-keys/" + keyId + "/retire",
                Map.of("state", "RETIRED", "reason", "again"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a retirement is recorded against the person who ordered it")
    void retirementIsAttributed() {
        String keyId = registerKey("ACTIVE");
        retire(keyId, "COMPROMISED", "key material in a leaked backup");

        // "We rotated on schedule" and "the key was in a leaked backup" lead to entirely different
        // incident responses, and six months later the state column alone cannot tell them apart.
        assertThat(admin().getForEntity("/v1/admin/audit?limit=50", String.class).getBody())
                .contains("SIGNING_KEY_COMPROMISED")
                .contains("leaked backup");
    }

    private String registerKey(String state) {
        String keyId = "rotation-test-" + UUID.randomUUID();
        // A syntactically valid X.509 Ed25519 public key: 12 bytes of prefix and 32 of key. It is
        // never used to verify anything here — these tests are about lifecycle and publication —
        // but it has to parse, because SigningKeys decodes every key it publishes.
        keys.register(keyId, "Ed25519",
                "MCowBQYDK2VwAyEA" + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        if (!"ACTIVE".equals(state)) {
            keys.changeState(keyId, state, "fixture", "fixture", Instant.now());
        }
        return keyId;
    }

    private void retire(String keyId, String state, String reason) {
        assertThat(admin().postForEntity("/v1/admin/signing-keys/" + keyId + "/retire",
                Map.of("state", state, "reason", reason), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private TestRestTemplate admin() {
        return rest.withBasicAuth("compliance-console", "admin-secret");
    }
}
