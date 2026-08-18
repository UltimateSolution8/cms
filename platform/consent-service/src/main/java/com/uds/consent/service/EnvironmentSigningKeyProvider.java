package com.uds.consent.service;

import com.uds.consent.core.snapshot.SigningKeyProvider;
import com.uds.consent.core.snapshot.SnapshotSigner;
import com.uds.consent.service.config.PlatformProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * The signing key as an environment variable — today's custody model, stated as one implementation
 * of {@link SigningKeyProvider} rather than as the only thing possible.
 *
 * <p>This is what {@code SNAPSHOT_SIGNING_KEY} and {@code SNAPSHOT_VERIFICATION_KEY} produce: both
 * halves decoded into this process's memory at start-up. It is adequate for the Denave pilot and it
 * is not where the group's signing key should live indefinitely — a pod's memory, whatever injected
 * the variable, and the shell history of whoever set it are three copies of a key whose whole value
 * is that only one party holds it.
 *
 * <p><strong>To move to a KMS</strong>, declare a {@code @Bean SigningKeyProvider} backed by it.
 * {@code PolicyConfiguration} declares this one {@code @ConditionalOnMissingBean}, so it steps
 * aside with no other change anywhere, and {@link SigningKeyProvider}'s Javadoc carries the
 * contract that implementation must meet. That was the point of extracting it, and it is the
 * difference between a decision UDS can take in an afternoon and one that needs the snapshot path
 * reworked.
 *
 * <p>Both halves are required together. The JDK's Ed25519 private key encoding does not carry the
 * public point, and recovering it would mean implementing scalar multiplication by hand in the one
 * component every offline enforcement decision rests on.
 */
public class EnvironmentSigningKeyProvider implements SigningKeyProvider {

    private static final Logger log =
            LoggerFactory.getLogger(EnvironmentSigningKeyProvider.class);

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String keyId;

    public EnvironmentSigningKeyProvider(PlatformProperties properties) {
        PlatformProperties.Snapshot config = properties.getSnapshot();
        this.keyId = config.getSigningKeyId();

        boolean hasPrivate = notBlank(config.getSigningKeyBase64());
        boolean hasPublic = notBlank(config.getVerificationKeyBase64());

        if (!hasPrivate && !hasPublic) {
            // Ephemeral, and loud about it. Every snapshot issued before a restart stops verifying
            // after it, and a sibling replica rejects them outright — tolerable on a laptop and
            // nowhere else.
            KeyPair pair = SnapshotSigner.generateKeyPair();
            this.privateKey = pair.getPrivate();
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

        this.privateKey = loadPrivateKey(config.getSigningKeyBase64());
        this.publicKey = loadPublicKey(config.getVerificationKeyBase64());
        log.info("snapshot signing key '{}' loaded from configuration", keyId);
    }

    @Override
    public String keyId() {
        return keyId;
    }

    @Override
    public PublicKey publicKey() {
        return publicKey;
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

    static PublicKey loadPublicKey(String base64X509) {
        try {
            byte[] decoded = Base64.getDecoder().decode(base64X509.trim());
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(decoded));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "could not load the snapshot verification key; expected base64 X.509 Ed25519",
                    e);
        }
    }
}
