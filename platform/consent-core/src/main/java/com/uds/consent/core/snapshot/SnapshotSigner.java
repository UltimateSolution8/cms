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
 * it; the in-memory key pair generator here exists for local development and tests. That handle is
 * {@link SigningKeyProvider} — this class asks for a signature over bytes it has already prepared
 * and never sees key material, which is what lets the KMS implementation be a bean replacement.
 */
public final class SnapshotSigner {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final SigningKeyProvider keys;

    public SnapshotSigner(SigningKeyProvider keys) {
        this.keys = keys;
    }

    /**
     * Signs with a private key held in this process.
     *
     * <p>Retained because tests and the ephemeral development path hold a {@link KeyPair} directly
     * and expressing that as a provider at every call site would be ceremony. It delegates to the
     * same path as everything else, so there is one signing implementation rather than two.
     */
    public SnapshotSigner(PrivateKey privateKey, String keyId) {
        this(new InProcessKey(privateKey, keyId));
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
        String keyId = keys.keyId();
        String header = B64.encodeToString(CanonicalJson.serialize(
                        Map.of("alg", "EdDSA", "kid", keyId, "typ", "UDS-CONSENT-SNAPSHOT+JWT"))
                .getBytes(StandardCharsets.UTF_8));
        String payload = B64.encodeToString(
                CanonicalJson.serialize(snapshot).getBytes(StandardCharsets.UTF_8));
        String signingInput = header + '.' + payload;

        // The kid is read once and reused for the header and the wire form. Reading it twice would
        // let a provider that rotates mid-call stamp one id into the header and report another,
        // producing a snapshot that names a key it was not signed with.
        String encoded = B64.encodeToString(
                keys.sign(signingInput.getBytes(StandardCharsets.UTF_8)));
        return new SignedSnapshot(signingInput + '.' + encoded, keyId);
    }

    public String keyId() {
        return keys.keyId();
    }

    /**
     * A key held in this process, which is the development and test case and the case a plain
     * {@code SNAPSHOT_SIGNING_KEY} environment variable produces.
     *
     * <p>{@code publicKey()} is unsupported rather than guessed: the JDK's Ed25519 private key
     * encoding does not carry the public point, and recovering it would mean implementing scalar
     * multiplication by hand in the component every offline decision rests on. Callers that need
     * the public half configure it alongside the private one — {@code SigningKeys} enforces that
     * they arrive as a pair.
     */
    private record InProcessKey(PrivateKey privateKey, String keyId) implements SigningKeyProvider {

        @Override
        public java.security.PublicKey publicKey() {
            throw new UnsupportedOperationException(
                    "an in-process signing key does not carry its public half; configure "
                            + "uds.consent.snapshot.verification-key-base64 alongside it");
        }

        @Override
        public byte[] sign(byte[] signingInput) {
            try {
                Signature signature = Signature.getInstance("Ed25519");
                signature.initSign(privateKey);
                signature.update(signingInput);
                return signature.sign();
            } catch (Exception e) {
                throw new IllegalStateException("snapshot signing failed", e);
            }
        }
    }
}
