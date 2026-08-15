package com.uds.consent.core.crypto;

import com.uds.consent.core.model.IdentifierType;

import java.util.Locale;
import java.util.Objects;

/**
 * Turns a real-world identifier into the peppered hash the ledger stores.
 *
 * <p>The ledger's job is to answer "did subject X consent to purpose Y". It must not become a
 * second master customer database sitting beside the CRM, so it never holds a phone number or an
 * email address in the clear.
 *
 * <p>Hashing alone would not be enough — the space of Indian mobile numbers is small enough to
 * enumerate exhaustively, so a bare SHA-256 of a phone number is reversible in practice. The
 * pepper is held in the KMS, never in the database, so an attacker with a copy of the ledger
 * cannot mount that attack.
 *
 * <p>Normalisation happens before hashing and matters as much as the hash: {@code +91 98765 43210}
 * and {@code 09876543210} are one person, and if they hash differently a withdrawal on one will
 * not suppress outreach to the other.
 */
public final class IdentifierHasher {

    /** National subscriber number length for the default calling code. Ten for India. */
    private static final int DEFAULT_NATIONAL_NUMBER_LENGTH = 10;

    private final String pepper;
    private final String defaultCallingCode;
    private final int nationalNumberLength;

    /**
     * @param pepper             secret retrieved from the KMS at startup; rotating it requires a
     *                           planned re-hash, so it is versioned alongside the ledger
     * @param defaultCallingCode calling code applied to national-format numbers, e.g. {@code 91}
     */
    public IdentifierHasher(String pepper, String defaultCallingCode) {
        this(pepper, defaultCallingCode, DEFAULT_NATIONAL_NUMBER_LENGTH);
    }

    /**
     * @param nationalNumberLength digits in a national subscriber number for the default calling
     *                             code. Needed to tell a number that already carries the country
     *                             code from one that merely begins with the same digits — an
     *                             Indian mobile beginning 91 is a real and common case
     */
    public IdentifierHasher(String pepper, String defaultCallingCode, int nationalNumberLength) {
        this.pepper = Objects.requireNonNull(pepper, "pepper");
        this.defaultCallingCode = Objects.requireNonNull(defaultCallingCode, "defaultCallingCode");
        this.nationalNumberLength = nationalNumberLength;
    }

    /** Normalises and hashes an identifier of the given type. */
    public String hash(IdentifierType type, String rawValue) {
        String normalised = normalise(type, rawValue);
        return Hashes.hmacSha256Hex(pepper, type.name() + ':' + normalised);
    }

    /**
     * Normalises an identifier to its canonical form.
     *
     * <p>Exposed separately from {@link #hash} so that data-quality tooling can report on
     * normalisation without ever needing the pepper.
     */
    public String normalise(IdentifierType type, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new IllegalArgumentException("identifier value must not be blank");
        }
        String value = rawValue.trim();
        return switch (type) {
            case PHONE -> normalisePhone(value);
            case EMAIL -> value.toLowerCase(Locale.ROOT);
            case EMPLOYEE_ID, CANDIDATE_ID, DEVICE_ID, EXTERNAL_ID -> value.toUpperCase(Locale.ROOT);
        };
    }

    /**
     * Best-effort E.164 normalisation.
     *
     * <p>Handles the forms that actually turn up in Denave's and Athena's data: international
     * prefixes written as {@code +} or {@code 00}, national numbers with a trunk {@code 0}, and
     * numbers carrying spaces, hyphens or brackets.
     *
     * <p>This is intentionally not a full libphonenumber implementation. Before the prospect
     * database is migrated, swap this for libphonenumber with the subject's country as a hint —
     * getting normalisation wrong at scale means withdrawals that silently fail to suppress.
     */
    private String normalisePhone(String value) {
        boolean international = value.startsWith("+") || value.startsWith("00");
        String digits = value.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            throw new IllegalArgumentException("phone number contains no digits");
        }
        if (value.startsWith("00")) {
            digits = digits.substring(2);
        }
        if (international) {
            return "+" + digits;
        }
        // National format: drop a single trunk prefix, then apply the default calling code.
        if (digits.startsWith("0")) {
            digits = digits.replaceFirst("^0+", "");
        }

        // A number may already carry the country code without a plus — pasted from a spreadsheet,
        // typically. Distinguished by total length, not by prefix: plenty of Indian mobile numbers
        // legitimately begin 91, and treating "9176543210" as an already-prefixed number would
        // silently produce a different person.
        int withCountryCode = defaultCallingCode.length() + nationalNumberLength;
        if (digits.length() == withCountryCode && digits.startsWith(defaultCallingCode)) {
            return "+" + digits;
        }
        return "+" + defaultCallingCode + digits;
    }
}
