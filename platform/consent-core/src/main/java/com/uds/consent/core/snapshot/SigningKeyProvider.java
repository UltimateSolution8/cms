package com.uds.consent.core.snapshot;

import java.security.PublicKey;

/**
 * Where the private half of the snapshot signing key lives, and how to get a signature out of it.
 *
 * <p>{@code V25__signing_key_registry.sql} made the <em>public</em> side of rotation survivable: a
 * snapshot signed under a key retired ten minutes ago still verifies, because {@code GET /v1/keys}
 * publishes ACTIVE and RETIRED together. The private side was still an environment variable read
 * into a {@link java.security.PrivateKey} field, which means the group's signing key exists in
 * plaintext in a pod's memory, in whatever injected it, and in whoever's terminal history set it.
 *
 * <p>This interface is what makes the other half swappable. It is deliberately shaped around
 * <strong>producing a signature</strong> rather than around <strong>handing back a key</strong>,
 * and that distinction is the entire point:
 *
 * <pre>{@code
 * PrivateKey privateKey();   // <-- cannot be implemented by a KMS. Never add this.
 * byte[] sign(byte[] input); // <-- can. The key stays where it is and the bytes travel.
 * }</pre>
 *
 * <p>In a KMS or HSM the private key never leaves the appliance — that is what the appliance is
 * for. An SPI with a {@code getPrivateKey()} on it therefore cannot be implemented by the very
 * thing it was extracted to allow, and the extraction would have bought nothing but an interface.
 *
 * <h2>Implementing this against a KMS</h2>
 *
 * <p>An implementation must satisfy four things, none of which the platform can check for you:
 *
 * <ul>
 *   <li><strong>{@link #sign} is a remote call and must be treated as one.</strong> It sits on the
 *       snapshot path, which is on the decision path's shoulder. Give it a timeout and a bounded
 *       retry, and fail loudly — a snapshot that cannot be signed must not be served unsigned.</li>
 *   <li><strong>{@link #keyId} must be stable for the lifetime of the key</strong> and must change
 *       when the key does. It is what a device uses to select a verification key, so a provider
 *       that silently rotates the material under a fixed id produces snapshots that fail to verify
 *       and look exactly like tampering.</li>
 *   <li><strong>{@link #publicKey} must be the public half of the key {@link #sign} uses.</strong>
 *       Obvious, and worth stating: these are two separate KMS calls and it is entirely possible to
 *       wire them to different keys, at which point every signature this platform issues is
 *       invalid and nothing here will notice.</li>
 *   <li><strong>Ed25519.</strong> {@code SnapshotSigner} stamps {@code "alg": "EdDSA"} into the
 *       header and every SDK verifies on that basis. A provider backed by an RSA key would produce
 *       a header that lies about its own signature.</li>
 * </ul>
 *
 * <p>Swapping is then a bean replacement — declare a {@code @Bean SigningKeyProvider} and the
 * environment-backed one steps aside — rather than a refactor of the component that the whole
 * offline enforcement story rests on.
 */
public interface SigningKeyProvider {

    /** The key identifier stamped into every snapshot header as {@code kid}. */
    String keyId();

    /** The public half, published at {@code GET /v1/keys} so devices can verify offline. */
    PublicKey publicKey();

    /**
     * Signs the snapshot's signing input.
     *
     * @param signingInput the exact bytes to sign — already canonicalised and base64url-joined by
     *                     {@link SnapshotSigner}. An implementation must sign them unaltered; any
     *                     re-encoding here produces a signature over something other than what the
     *                     verifier will reconstruct.
     * @return the raw Ed25519 signature, 64 bytes
     */
    byte[] sign(byte[] signingInput);
}
