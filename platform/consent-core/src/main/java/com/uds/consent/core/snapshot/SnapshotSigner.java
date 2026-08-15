package com.uds.consent.core.snapshot;

import com.uds.consent.core.crypto.CanonicalJson;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;

/**
 * Signs consent snapshots with Ed25519.
 *
 * <p>Ed25519 rather than RSA because signatures are 64 bytes and verification is fast enough to
 * sit on a field device's hot path without a measurable battery cost. It is available in the JDK
 * itself from 15 onwards, so no third-party crypto library enters the trust boundary.
 *
 * <p>In production the private key lives in the KMS or HSM and this class holds only a handle to
 * it; the in-memory key pair generator here exists for local development and tests.
 */
public final class SnapshotSigner {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final PrivateKey privateKey;
    private final String keyId;

    public SnapshotSigner(PrivateKey privateKey, String keyId) {
        this.privateKey = privateKey;
        this.keyId = keyId;
    }

    /** Generates an ephemeral Ed25519 key pair. Development and test use only. */
    public static KeyPair generateKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 unavailable on this JVM", e);
        }
    }

    /** Serialises and signs a snapshot into its compact wire form. */
    public SignedSnapshot sign(ConsentSnapshot snapshot) {
        String header = B64.encodeToString(CanonicalJson.serialize(
                        Map.of("alg", "EdDSA", "kid", keyId, "typ", "UDS-CONSENT-SNAPSHOT+JWT"))
                .getBytes(StandardCharsets.UTF_8));
        String payload = B64.encodeToString(
                CanonicalJson.serialize(snapshot).getBytes(StandardCharsets.UTF_8));
        String signingInput = header + '.' + payload;

        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            String encoded = B64.encodeToString(signature.sign());
            return new SignedSnapshot(signingInput + '.' + encoded, keyId);
        } catch (Exception e) {
            throw new IllegalStateException("snapshot signing failed", e);
        }
    }

    public String keyId() {
        return keyId;
    }
}
