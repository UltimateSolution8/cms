package com.uds.consent.service;

import com.uds.consent.core.snapshot.SigningKeyProvider;
import com.uds.consent.core.snapshot.SnapshotSigner;
import com.uds.consent.ledger.store.SigningKeyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.PublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publishes the verification keys an SDK should trust, and signs with whichever key custody model
 * is configured.
 *
 * <p>The custody question — where the private half lives — is {@link SigningKeyProvider}'s, not
 * this class's. This one owns what surrounds it: recording the public half so other instances and
 * devices can verify what this one signs, and assembling the set of keys still worth trusting.
 * Separating the two is what lets a KMS arrive as a bean rather than as a rewrite of the snapshot
 * path.
 */
@Component
public class SigningKeys {

    private static final Logger log = LoggerFactory.getLogger(SigningKeys.class);

    private final SnapshotSigner signer;
    private final SigningKeyProvider keys;
    private final SigningKeyStore registry;

    public SigningKeys(SigningKeyProvider keys, SigningKeyStore registry) {
        this.keys = keys;
        this.registry = registry;
        this.signer = new SnapshotSigner(keys);
        publish();
    }

    /**
     * Records this instance's public key so that other instances, and devices, can verify what it
     * signs.
     *
     * <p>Best-effort by design. A registry write failing must not stop the platform starting: the
     * key still works for this instance, and refusing to serve decisions because a metadata insert
     * failed would trade a publication problem for an outage. The WARN is the signal, and the
     * consequence — a device that cannot fetch this key from /v1/keys — is visible where it hurts
     * rather than hidden behind a healthy-looking process.
     */
    private void publish() {
        try {
            registry.register(keys.keyId(), "Ed25519", publicKeyBase64());
        } catch (RuntimeException e) {
            log.warn("could not record snapshot signing key '{}' in the registry: {}. Snapshots "
                    + "will still be signed and this instance will still verify them, but the key "
                    + "may not appear at GET /v1/keys for devices or for other instances.",
                    keys.keyId(), e.getMessage());
        }
    }

    public SnapshotSigner signer() {
        return signer;
    }

    public String keyId() {
        return keys.keyId();
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
        // This instance's own key first and unconditionally, so verification never depends on the
        // database being reachable — the offline enforcement story cannot rest on a query.
        Map<String, PublicKey> trusted = new LinkedHashMap<>();
        trusted.put(keys.keyId(), keys.publicKey());

        // Then everything else still trusted: keys held by sibling instances, and keys retired
        // within the last snapshot lifetime. Without these, a snapshot signed by another replica
        // is rejected by this one — which looks exactly like tampering and is not.
        try {
            for (SigningKeyStore.Key key : registry.trusted()) {
                if (!trusted.containsKey(key.keyId())) {
                    trusted.put(key.keyId(),
                            EnvironmentSigningKeyProvider.loadPublicKey(key.publicKeyBase64()));
                }
            }
        } catch (RuntimeException e) {
            log.warn("could not read the signing key registry; verifying against this instance's "
                    + "key alone: {}", e.getMessage());
        }
        return Map.copyOf(trusted);
    }

    /** The public key in base64 X.509 form, for the key-publication endpoint. */
    public String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(keys.publicKey().getEncoded());
    }
}
