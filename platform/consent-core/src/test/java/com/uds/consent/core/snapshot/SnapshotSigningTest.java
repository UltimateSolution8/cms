package com.uds.consent.core.snapshot;

import com.uds.consent.core.model.ConsentStatus;
import com.uds.consent.core.model.FailureBehavior;
import com.uds.consent.core.model.LegalBasis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnapshotSigningTest {

    private static final Instant NOW = Instant.parse("2026-08-15T09:00:00Z");

    private final KeyPair keyPair = SnapshotSigner.generateKeyPair();
    private final SnapshotSigner signer = new SnapshotSigner(keyPair.getPrivate(), "test-key-1");
    private final SnapshotVerifier verifier =
            new SnapshotVerifier(Map.of("test-key-1", keyPair.getPublic()));

    @Test
    @DisplayName("a signed snapshot round-trips with its purpose states intact")
    void roundTrip() {
        ConsentSnapshot original = snapshot(ConsentStatus.GRANTED, NOW.plus(7, ChronoUnit.DAYS));

        ConsentSnapshot verified = verifier.verify(signer.sign(original));

        assertThat(verified.subjectId()).isEqualTo(original.subjectId());
        assertThat(verified.policyVersion()).isEqualTo(original.policyVersion());
        assertThat(verified.purpose("MKT_OUTBOUND_CALL").status()).isEqualTo(ConsentStatus.GRANTED);
        assertThat(verified.purpose("MKT_OUTBOUND_CALL").expiresAt())
                .isEqualTo(NOW.plus(7, ChronoUnit.DAYS));
    }

    @Test
    @DisplayName("a tampered payload fails verification")
    void tamperingIsDetected() {
        SignedSnapshot signed = signer.sign(snapshot(ConsentStatus.WITHDRAWN, null));
        String[] parts = signed.segments();

        // Substituting a payload that says GRANTED for one that said WITHDRAWN is exactly the
        // attack a device-local enforcement decision has to withstand.
        ConsentSnapshot forged = snapshot(ConsentStatus.GRANTED, null);
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(com.uds.consent.core.crypto.CanonicalJson.serialize(forged)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        SignedSnapshot tampered =
                new SignedSnapshot(parts[0] + '.' + forgedPayload + '.' + parts[2], signed.keyId());

        assertThatThrownBy(() -> verifier.verify(tampered))
                .isInstanceOf(SnapshotVerifier.SnapshotVerificationException.class)
                .hasMessageContaining("did not verify");
    }

    @Test
    @DisplayName("a snapshot signed by an unknown key is rejected")
    void unknownKeyIsRejected() {
        KeyPair other = SnapshotSigner.generateKeyPair();
        SnapshotSigner rogue = new SnapshotSigner(other.getPrivate(), "rogue-key");

        assertThatThrownBy(() -> verifier.verify(rogue.sign(snapshot(ConsentStatus.GRANTED, null))))
                .isInstanceOf(SnapshotVerifier.SnapshotVerificationException.class)
                .hasMessageContaining("unknown key");
    }

    @Test
    @DisplayName("a token declaring a different algorithm is rejected")
    void algorithmConfusionIsRejected() {
        // The classic JWS failure: a token that declares "none" or a symmetric algorithm and is
        // accepted because the verifier trusted the header.
        String forgedHeader = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"kid\":\"test-key-1\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        SignedSnapshot signed = signer.sign(snapshot(ConsentStatus.GRANTED, null));
        String[] parts = signed.segments();

        assertThatThrownBy(() -> verifier.verify(
                new SignedSnapshot(forgedHeader + '.' + parts[1] + '.' + parts[2], "test-key-1")))
                .isInstanceOf(SnapshotVerifier.SnapshotVerificationException.class)
                .hasMessageContaining("unexpected algorithm");
    }

    @Test
    @DisplayName("a provider that never surrenders its private key can still sign")
    void aRemoteKeyCustodianCanImplementTheSpi() {
        // The assertion that justifies SigningKeyProvider existing. This stands in for a KMS: it
        // holds the key behind a method call and exposes no way to obtain it, which is exactly the
        // shape an HSM-backed implementation has. If the SPI ever grows a privateKey() accessor
        // this test still compiles — so it asserts the behaviour instead, by holding the key in a
        // closure the caller has no reference to.
        KeyPair custodied = SnapshotSigner.generateKeyPair();
        SigningKeyProvider remote = new SigningKeyProvider() {
            @Override
            public String keyId() {
                return "kms-key-1";
            }

            @Override
            public java.security.PublicKey publicKey() {
                return custodied.getPublic();
            }

            @Override
            public byte[] sign(byte[] signingInput) {
                try {
                    java.security.Signature signature =
                            java.security.Signature.getInstance("Ed25519");
                    signature.initSign(custodied.getPrivate());
                    signature.update(signingInput);
                    return signature.sign();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };

        SignedSnapshot signed = new SnapshotSigner(remote).sign(snapshot(ConsentStatus.GRANTED, null));

        assertThat(signed.keyId()).isEqualTo("kms-key-1");
        assertThat(new SnapshotVerifier(Map.of("kms-key-1", custodied.getPublic()))
                .verify(signed).subjectId()).isEqualTo("subject-1");
    }

    @Test
    @DisplayName("the kid in the header is the kid on the wire form")
    void theHeaderNamesTheKeyThatSignedIt() {
        // A snapshot naming one key in its header and another in its envelope would be rejected by
        // whichever the verifier believed, and the failure reads as tampering rather than as a
        // wiring mistake. Cheap to assert, and it pins the single read of keyId() in sign().
        SignedSnapshot signed = signer.sign(snapshot(ConsentStatus.GRANTED, null));
        String header = new String(java.util.Base64.getUrlDecoder().decode(signed.segments()[0]),
                java.nio.charset.StandardCharsets.UTF_8);

        assertThat(header).contains("\"kid\":\"test-key-1\"");
        assertThat(signed.keyId()).isEqualTo("test-key-1");
    }

    private static ConsentSnapshot snapshot(ConsentStatus status, Instant expiresAt) {
        return new ConsentSnapshot(
                "snap-1", "DENAVE_IN", "subject-1", NOW, NOW.plus(15, ChronoUnit.MINUTES),
                "policy-2026.08.1",
                Map.of("MKT_OUTBOUND_CALL", new PurposeState(status, LegalBasis.CONSENT, 1,
                        expiresAt, FailureBehavior.FAIL_CLOSED, Set.of("VOICE_CALL"), false)));
    }
}
