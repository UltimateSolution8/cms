package com.uds.consent.service;

import com.uds.consent.core.snapshot.SnapshotSigner;
import com.uds.consent.service.config.PlatformProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

/**
 * Holds the Ed25519 key pair snapshots are signed with, and publishes the public half so that
 * SDKs can verify offline.
 *
 * <p>In production both halves come from the KMS. Both are required together, because the JDK's
 * Ed25519 private key encoding does not carry the public point and recovering it would mean
 * reimplementing scalar multiplication — exactly the kind of hand-rolled cryptography that has no
 * place in the component the whole offline enforcement story rests on.
 *
 * <p>Where neither is configured this generates an ephemeral pair and says so, loudly: every
 * snapshot issued before a restart stops verifying after it, which is tolerable on a laptop and
 * nowhere else.
 */
@Component
public class SigningKeys {

    private static final Logger log = LoggerFactory.getLogger(SigningKeys.class);

    private final SnapshotSigner signer;
    private final PublicKey publicKey;
    private final String keyId;

    public SigningKeys(PlatformProperties properties) {
        PlatformProperties.Snapshot config = properties.getSnapshot();
        this.keyId = config.getSigningKeyId();

        boolean hasPrivate = notBlank(config.getSigningKeyBase64());
        boolean hasPublic = notBlank(config.getVerificationKeyBase64());

        if (!hasPrivate && !hasPublic) {
            KeyPair pair = SnapshotSigner.generateKeyPair();
            this.signer = new SnapshotSigner(pair.getPrivate(), keyId);
            this.publicKey = pair.getPublic();
            log.warn("no snapshot signing key configured; generated an ephemeral Ed25519 key with "
                    + "id '{}'. Snapshots signed with it stop verifying when this process "
                    + "restarts, and other instances will reject them. Configure "
                    + "uds.consent.snapshot.signing-key-base64 and .verification-key-base64 from "
                    + "the KMS for any shared environment.", keyId);
            return;
        }

        if (hasPrivate != hasPublic) {
            throw new IllegalStateException(
                    "snapshot signing keys must be configured as a pair: set both "
                            + "uds.consent.snapshot.signing-key-base64 (PKCS#8) and "
                            + "uds.consent.snapshot.verification-key-base64 (X.509), or neither.");
        }

        this.signer = new SnapshotSigner(loadPrivateKey(config.getSigningKeyBase64()), keyId);
        this.publicKey = loadPublicKey(config.getVerificationKeyBase64());
        log.info("snapshot signing key '{}' loaded", keyId);
    }

    public SnapshotSigner signer() {
        return signer;
    }

    public String keyId() {
        return keyId;
    }

    /**
     * The verification keys an SDK should trust, keyed by id.
     *
     * <p>A map rather than a single key because rotation must not invalidate snapshots signed
     * moments before it: a device holding one from the outgoing key has to keep working until it
     * expires. Publishing the retired key alongside the new one for one snapshot lifetime is what
     * makes rotation a non-event.
     */
    public Map<String, PublicKey> verificationKeys() {
        return Map.of(keyId, publicKey);
    }

    /** The public key in base64 X.509 form, for the key-publication endpoint. */
    public String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static PrivateKey loadPrivateKey(String base64Pkcs8) {
        try {
            byte[] decoded = Base64.getDecoder().decode(base64Pkcs8.trim());
            return KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new PKCS8EncodedKeySpec(decoded));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "could not load the snapshot signing key; expected base64 PKCS#8 Ed25519", e);
        }
    }

    private static PublicKey loadPublicKey(String base64X509) {
        try {
            byte[] decoded = Base64.getDecoder().decode(base64X509.trim());
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(decoded));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "could not load the snapshot verification key; expected base64 X.509 Ed25519", e);
        }
    }
}
