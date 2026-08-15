package com.uds.consent.core.snapshot;

import com.uds.consent.core.decision.DecisionRequest;
import com.uds.consent.core.decision.DecisionResponse;
import com.uds.consent.core.decision.DenialReason;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.ConsentStatus;
import com.uds.consent.core.model.FailureBehavior;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.LegalBasis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The offline decision path.
 *
 * <p>These cases matter more than their size suggests. A field device reaching a different answer
 * from the server is the failure mode that would be hardest to notice in production — nothing
 * errors, a call simply gets made that should not have been.
 */
class LocalConsentEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-08-15T09:00:00Z");
    private static final String PURPOSE = "MKT_OUTBOUND_CALL";

    @Test
    @DisplayName("granted consent inside the snapshot's life permits processing")
    void grantedPermits() {
        DecisionResponse decision = evaluate(
                state(ConsentStatus.GRANTED, null, FailureBehavior.FAIL_CLOSED, false),
                NOW.plus(1, ChronoUnit.MINUTES));

        assertThat(decision.isAllowed()).isTrue();
        assertThat(decision.evaluatedLocally()).isTrue();
    }

    @Test
    @DisplayName("consent that lapsed while the device was offline denies without asking the server")
    void expiryIsEnforcedLocally() {
        // The TRAI seven-day window. A device that has been in a basement all week must not treat
        // a consent that ran out on Tuesday as still live because it has not spoken to the server.
        DecisionResponse decision = evaluate(
                state(ConsentStatus.GRANTED, NOW.plus(1, ChronoUnit.HOURS),
                        FailureBehavior.FAIL_CLOSED, false),
                NOW.plus(2, ChronoUnit.HOURS));

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(DenialReason.CONSENT_EXPIRED);
    }

    @Test
    @DisplayName("withdrawal in the snapshot denies")
    void withdrawnDenies() {
        DecisionResponse decision = evaluate(
                state(ConsentStatus.WITHDRAWN, null, FailureBehavior.FAIL_CLOSED, false), NOW);

        assertThat(decision.reason()).isEqualTo(DenialReason.CONSENT_WITHDRAWN);
    }

    @Test
    @DisplayName("a stale snapshot denies for a fail-closed purpose")
    void staleSnapshotFailsClosed() {
        DecisionResponse decision = evaluate(
                state(ConsentStatus.GRANTED, null, FailureBehavior.FAIL_CLOSED, false),
                NOW.plus(1, ChronoUnit.DAYS));

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(DenialReason.FAIL_CLOSED_DEFAULT);
    }

    @Test
    @DisplayName("a stale snapshot permits a fail-open purpose, and asks for a refresh")
    void staleSnapshotFailsOpenWhereConfigured() {
        DecisionResponse decision = evaluate(
                state(ConsentStatus.GRANTED, null, FailureBehavior.FAIL_OPEN, false),
                NOW.plus(1, ChronoUnit.DAYS));

        assertThat(decision.isAllowed()).isTrue();
        assertThat(decision.obligations()).contains("refresh-snapshot");
    }

    @Test
    @DisplayName("suppression baked into the snapshot denies on a commercial channel")
    void suppressionDenies() {
        // The device cannot query the national preference register, so the server resolves it at
        // issue time. Without this the offline answer would be systematically more permissive.
        DecisionResponse decision = evaluate(
                state(ConsentStatus.GRANTED, null, FailureBehavior.FAIL_CLOSED, true), NOW);

        assertThat(decision.reason()).isEqualTo(DenialReason.SUPPRESSED_STATUTORY);
    }

    @Test
    @DisplayName("a purpose absent from the snapshot denies rather than defaulting to permitted")
    void unknownPurposeDenies() {
        ConsentSnapshot snapshot = new ConsentSnapshot("snap-1", "DENAVE_IN", "subject-1", NOW,
                NOW.plus(15, ChronoUnit.MINUTES), "policy-2026.08.1", Map.of());

        DecisionResponse decision = new LocalConsentEvaluator(snapshot).evaluate(request(NOW));

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(DenialReason.NO_CONSENT_RECORD);
    }

    private static DecisionResponse evaluate(PurposeState state, Instant at) {
        ConsentSnapshot snapshot = new ConsentSnapshot("snap-1", "DENAVE_IN", "subject-1", NOW,
                NOW.plus(15, ChronoUnit.MINUTES), "policy-2026.08.1", Map.of(PURPOSE, state));
        return new LocalConsentEvaluator(snapshot).evaluate(request(at));
    }

    private static PurposeState state(ConsentStatus status, Instant expiresAt,
                                      FailureBehavior failure, boolean suppressed) {
        return new PurposeState(status, LegalBasis.CONSENT, 1, expiresAt, failure,
                Set.of("VOICE_CALL"), suppressed);
    }

    private static DecisionRequest request(Instant at) {
        return DecisionRequest.of("DENAVE_IN", "subject-1", PURPOSE, Channel.VOICE_CALL,
                Jurisdiction.IN, at);
    }
}
