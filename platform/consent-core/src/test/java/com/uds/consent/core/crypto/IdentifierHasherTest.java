package com.uds.consent.core.crypto;

import com.uds.consent.core.model.IdentifierType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Normalisation matters as much as hashing.
 *
 * <p>If {@code +91 98765 43210} and {@code 09876543210} hash differently, a withdrawal recorded
 * against one will silently fail to suppress outreach to the other — and the failure is invisible
 * until someone complains that they opted out and were called anyway.
 */
class IdentifierHasherTest {

    private final IdentifierHasher hasher = new IdentifierHasher("test-pepper", "91");

    @ParameterizedTest
    @ValueSource(strings = {
            "+919876543210",
            "+91 98765 43210",
            "+91-98765-43210",
            "0091 9876543210",
            "09876543210",
            "9876543210",
            "(+91) 98765 43210"})
    @DisplayName("the forms a real prospect list actually contains all normalise to one number")
    void phoneFormsConverge(String raw) {
        assertThat(hasher.normalise(IdentifierType.PHONE, raw)).isEqualTo("+919876543210");
    }

    @Test
    @DisplayName("the same number in different forms hashes identically")
    void equivalentNumbersHashAlike() {
        String fromInternational = hasher.hash(IdentifierType.PHONE, "+91 98765 43210");
        String fromNational = hasher.hash(IdentifierType.PHONE, "09876543210");

        assertThat(fromInternational).isEqualTo(fromNational);
    }

    @Test
    @DisplayName("an Indian mobile that happens to begin 91 is not mistaken for a prefixed number")
    void nationalNumberBeginningWithCountryCodeIsNotMisread() {
        // 9176543210 is a perfectly ordinary ten-digit Indian mobile. A prefix-based check would
        // read the leading 91 as a country code and produce a different, wrong subject.
        assertThat(hasher.normalise(IdentifierType.PHONE, "9176543210"))
                .isEqualTo("+919176543210");
    }

    @Test
    @DisplayName("a number pasted with the country code but no plus is still recognised")
    void twelveDigitNumberIsTreatedAsAlreadyPrefixed() {
        assertThat(hasher.normalise(IdentifierType.PHONE, "919876543210"))
                .isEqualTo("+919876543210");
    }

    @Test
    @DisplayName("a UK number keeps its own country code rather than acquiring India's")
    void internationalNumbersKeepTheirCode() {
        assertThat(hasher.normalise(IdentifierType.PHONE, "+44 20 7946 0958"))
                .isEqualTo("+442079460958");
    }

    @Test
    @DisplayName("email case and surrounding whitespace do not create a second person")
    void emailIsCaseInsensitive() {
        assertThat(hasher.hash(IdentifierType.EMAIL, "  Priya.Sharma@Example.COM "))
                .isEqualTo(hasher.hash(IdentifierType.EMAIL, "priya.sharma@example.com"));
    }

    @Test
    @DisplayName("different identifier types never collide even on the same string")
    void typesAreDomainSeparated() {
        assertThat(hasher.hash(IdentifierType.EXTERNAL_ID, "ABC123"))
                .isNotEqualTo(hasher.hash(IdentifierType.EMPLOYEE_ID, "ABC123"));
    }

    @Test
    @DisplayName("a value with no digits is rejected rather than hashed into nonsense")
    void unusableNumberIsRejected() {
        assertThatThrownBy(() -> hasher.hash(IdentifierType.PHONE, "not-a-number"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no digits");
    }

    @Test
    @DisplayName("the hash reveals nothing recognisable about the input")
    void hashIsOpaque() {
        String hash = hasher.hash(IdentifierType.PHONE, "+919876543210");

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}").doesNotContain("9876543210");
    }
}
