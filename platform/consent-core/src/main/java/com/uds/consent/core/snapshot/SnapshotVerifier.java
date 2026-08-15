package com.uds.consent.core.snapshot;

import com.uds.consent.core.crypto.CanonicalJson;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;

/**
 * Verifies a signed snapshot and returns its payload.
 *
 * <p>This runs on the device, inside the SDK, before any local decision is taken. A snapshot that
 * fails verification is discarded and the purpose falls back to its configured failure behaviour —
 * it is never treated as merely absent, because an attacker who can tamper with a snapshot would
 * otherwise be able to downgrade enforcement simply by corrupting the file.
 */
public final class SnapshotVerifier {

    private static final Base64.Decoder B64 = Base64.getUrlDecoder();

    private final Map<String, PublicKey> keysByKeyId;

    /**
     * @param keysByKeyId trusted verification keys, refreshed from the control plane. Keyed by id
     *                    so that a rotation does not invalidate snapshots signed moments earlier.
     */
    public SnapshotVerifier(Map<String, PublicKey> keysByKeyId) {
        this.keysByKeyId = Map.copyOf(keysByKeyId);
    }

    /**
     * Verifies the signature and returns the snapshot.
     *
     * @throws SnapshotVerificationException if the token is malformed, signed by an unknown key,
     *                                       or the signature does not match
     */
    public ConsentSnapshot verify(SignedSnapshot signed) {
        String[] parts = signed.segments();
        String header = new String(B64.decode(parts[0]), StandardCharsets.UTF_8);
        Map<?, ?> headerMap = CanonicalJson.parse(header, Map.class);

        Object kid = headerMap.get("kid");
        PublicKey key = kid == null ? null : keysByKeyId.get(kid.toString());
        if (key == null) {
            throw new SnapshotVerificationException("snapshot signed by unknown key: " + kid);
        }
        if (!"EdDSA".equals(headerMap.get("alg"))) {
            // Refusing anything but the expected algorithm closes the classic JWS algorithm-
            // confusion hole, where a token declaring "none" or a symmetric alg is accepted.
            throw new SnapshotVerificationException("unexpected algorithm: " + headerMap.get("alg"));
        }

        String signingInput = parts[0] + '.' + parts[1];
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(key);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            if (!signature.verify(B64.decode(parts[2]))) {
                throw new SnapshotVerificationException("snapshot signature did not verify");
            }
        } catch (SnapshotVerificationException e) {
            throw e;
        } catch (Exception e) {
            throw new SnapshotVerificationException("snapshot verification failed: " + e.getMessage());
        }

        String payload = new String(B64.decode(parts[1]), StandardCharsets.UTF_8);
        return CanonicalJson.parse(payload, ConsentSnapshot.class);
    }

    /** Raised when a snapshot cannot be trusted. Never swallowed silently by the SDK. */
    public static class SnapshotVerificationException extends RuntimeException {
        public SnapshotVerificationException(String message) {
            super(message);
        }
    }
}
