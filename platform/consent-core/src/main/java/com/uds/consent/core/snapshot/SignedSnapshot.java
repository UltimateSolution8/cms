package com.uds.consent.core.snapshot;

/**
 * A snapshot in its wire form: a JWS-style compact serialisation,
 * {@code base64url(header).base64url(payload).base64url(signature)}.
 *
 * <p>The compact form is used because every SDK the group needs — TypeScript, Kotlin, Swift,
 * Flutter, React Native — already has a JWS implementation, so verification on the device is a
 * library call rather than bespoke parsing that each platform gets subtly wrong.
 *
 * @param compact the serialised token
 * @param keyId   identifier of the signing key, so rotation does not invalidate issued snapshots
 */
public record SignedSnapshot(String compact, String keyId) {

    /** The three dot-separated segments. */
    public String[] segments() {
        String[] parts = compact.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("malformed snapshot: expected 3 segments");
        }
        return parts;
    }
}
