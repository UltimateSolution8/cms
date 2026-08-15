package com.uds.consent.core.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Hashing primitives for the evidence plane.
 *
 * <p>The ledger's tamper-evidence is a per-subject SHA-256 hash chain. That is deliberate and
 * sufficient: it detects any alteration of history, which is what the burden of proof requires. A
 * public blockchain would add cost, latency and a data-residency problem while proving nothing
 * further to an Indian regulator.
 */
public final class Hashes {

    private static final HexFormat HEX = HexFormat.of();

    /**
     * Domain separator mixed between the previous hash and the payload. A unit-separator
     * byte is used because it cannot occur in a hex digest or in canonical JSON, which stops
     * an attacker shifting bytes across the boundary to forge a colliding pair. Written as an
     * escape rather than a literal so the source survives any encoding round-trip.
     */
    private static final char SEPARATOR = '\u001f';

    private Hashes() {
    }

    /** SHA-256 of a UTF-8 string, lower-case hex. */
    public static String sha256Hex(String input) {
        return HEX.formatHex(sha256(input.getBytes(StandardCharsets.UTF_8)));
    }

    /** SHA-256 of raw bytes. */
    public static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Links one event to its predecessor.
     *
     * <p>{@code hash = SHA-256(previousHash || 0x1f || canonicalPayload)}. The separator is a
     * unit-separator byte that cannot occur in either operand, which stops an attacker shifting
     * bytes between the two fields to produce a colliding pair.
     *
     * @param previousHash    hex hash of the prior event, or the genesis value for the first
     * @param canonicalPayload canonical JSON of the event body
     */
    public static String chain(String previousHash, String canonicalPayload) {
        return sha256Hex(previousHash + SEPARATOR + canonicalPayload);
    }

    /** HMAC-SHA-256, lower-case hex. Used for peppered identifier hashing. */
    public static String hmacSha256Hex(String key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HEX.formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA-256 failed", e);
        }
    }

    /**
     * Constant-time comparison of two hex digests. Ordinary string equality leaks position of the
     * first differing byte through timing.
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return a == b;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
