package com.uds.consent.core.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class HashesTest {

    @Test
    @DisplayName("canonical serialisation does not depend on the order keys were inserted in")
    void canonicalSerialisationIsOrderIndependent() {
        Map<String, Object> inserted = new LinkedHashMap<>();
        inserted.put("subjectId", "s-1");
        inserted.put("entityId", "DENAVE_IN");
        inserted.put("purposeCode", "MKT_OUTBOUND_CALL");

        Map<String, Object> sorted = new TreeMap<>(inserted);

        // If this ever fails, every hash the platform has written becomes unverifiable — the
        // chain is only as trustworthy as the determinism of the bytes it covers.
        assertThat(CanonicalJson.serialize(inserted)).isEqualTo(CanonicalJson.serialize(sorted));
    }

    @Test
    @DisplayName("chaining is deterministic for the same inputs")
    void chainIsDeterministic() {
        String payload = CanonicalJson.serialize(Map.of("a", 1, "b", "two"));
        String first = Hashes.chain("0".repeat(64), payload);
        String second = Hashes.chain("0".repeat(64), payload);

        assertThat(first).isEqualTo(second).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("a different previous hash produces a different event hash")
    void chainDependsOnPredecessor() {
        String payload = CanonicalJson.serialize(Map.of("event", "GRANTED"));

        assertThat(Hashes.chain("0".repeat(64), payload))
                .isNotEqualTo(Hashes.chain("1".repeat(64), payload));
    }

    @Test
    @DisplayName("bytes cannot be shifted across the separator to forge a colliding pair")
    void separatorPreventsBoundaryShifting() {
        // Without a separator that cannot occur in either operand, "ab" + "c" and "a" + "bc"
        // would hash identically, letting an attacker who controls part of the payload
        // manufacture a chain link that verifies against a different predecessor.
        String hashA = Hashes.chain("ab", "c");
        String hashB = Hashes.chain("a", "bc");

        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    @DisplayName("peppering changes the digest, so a stolen ledger is not a phone book")
    void hmacDependsOnPepper() {
        String withOnePepper = Hashes.hmacSha256Hex("pepper-one", "PHONE:+919876543210");
        String withAnother = Hashes.hmacSha256Hex("pepper-two", "PHONE:+919876543210");

        assertThat(withOnePepper).isNotEqualTo(withAnother);
    }
}
