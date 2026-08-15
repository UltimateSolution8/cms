package com.uds.consent.service.it;

import com.uds.consent.core.decision.DecisionRequest;
import com.uds.consent.core.decision.DecisionResponse;
import com.uds.consent.core.decision.DenialReason;
import com.uds.consent.core.model.ActorType;
import com.uds.consent.core.model.CaptureMethod;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.ConsentArtefact;
import com.uds.consent.core.model.ConsentEvent;
import com.uds.consent.core.model.ConsentStatus;
import com.uds.consent.core.model.IdentifierType;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.SuppressionScope;
import com.uds.consent.core.model.SuppressionSource;
import com.uds.consent.core.snapshot.ConsentSnapshot;
import com.uds.consent.core.snapshot.LocalConsentEvaluator;
import com.uds.consent.core.snapshot.SignedSnapshot;
import com.uds.consent.core.snapshot.SnapshotVerifier;
import com.uds.consent.ledger.service.ConsentLedger;
import com.uds.consent.ledger.store.OutboxStore;
import com.uds.consent.ledger.store.SubjectStore;
import com.uds.consent.policy.PolicyEngine;
import com.uds.consent.policy.capture.CaptureSubmission;
import com.uds.consent.policy.capture.CaptureViolation;
import com.uds.consent.service.ConsentCaptureService;
import com.uds.consent.service.SigningKeys;
import com.uds.consent.service.SnapshotService;
import com.uds.consent.service.SuppressionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End to end, against a real database: capture, decide, withdraw, sync, expire.
 *
 * <p>The golden decision suite already proves the rules in isolation. What this proves is that the
 * pieces are wired to each other — that a withdrawal recorded through the ingestion API actually
 * reaches the projection the dialer reads, that an offline device's queued event lands in the right
 * order, and that the snapshot a field app verifies offline says what the server would have said.
 * Those are the joins where an integration goes wrong, and no unit test can see them.
 */
class ConsentLifecycleIT extends PostgresIntegrationTest {

    private static final String ENTITY = "DENAVE_IN";
    private static final String APP = "DENAVE_WEB";
    private static final String NOTICE = "NOTICE_DENAVE_B2B";
    private static final Instant NOW = Instant.parse("2026-08-15T09:00:00Z");

    @Autowired
    private ConsentCaptureService capture;

    @Autowired
    private ConsentLedger ledger;

    @Autowired
    private PolicyEngine policy;

    @Autowired
    private SnapshotService snapshots;

    @Autowired
    private SigningKeys keys;

    @Autowired
    private SuppressionService suppression;

    @Autowired
    private SubjectStore subjects;

    @Autowired
    private OutboxStore outbox;

    @Autowired
    private JdbcTemplate jdbc;

    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("consent on the website flips the dialer's answer, and withdrawal flips it back")
    void captureThenWithdrawIsVisibleToTheDecisionApi() {
        // The single most important path in the platform: a person changes their mind and every
        // system that would contact them stops, without anyone running a script.
        String subject = newSubject();

        assertThat(decide(subject, "MKT_OUTBOUND_CALL", Channel.VOICE_CALL).reason())
                .isEqualTo(DenialReason.NO_CONSENT_RECORD);

        ConsentCaptureService.Result accepted = capture.capture(submission(subject,
                CaptureSubmission.PurposeChoice.acceptedSeparately("MKT_OUTBOUND_CALL")));

        assertThat(accepted.isAccepted()).isTrue();
        assertThat(decide(subject, "MKT_OUTBOUND_CALL", Channel.VOICE_CALL).isAllowed()).isTrue();

        capture.withdraw(ENTITY, subject, List.of("MKT_OUTBOUND_CALL"), Channel.WEB, APP,
                ActorType.SUBJECT, subject, Jurisdiction.IN, NOW.plus(1, ChronoUnit.HOURS),
                "withdraw-" + subject, "subject used the preference centre");

        DecisionResponse afterWithdrawal = decide(subject, "MKT_OUTBOUND_CALL", Channel.VOICE_CALL);
        assertThat(afterWithdrawal.isAllowed()).isFalse();
        assertThat(afterWithdrawal.reason()).isEqualTo(DenialReason.CONSENT_WITHDRAWN);

        // Both facts survive. The ledger is the evidence that the person once agreed and later did
        // not, which is exactly what a complaint turns on.
        assertThat(ledger.history(ENTITY, subject, "MKT_OUTBOUND_CALL")).hasSize(2);
    }

    @Test
    @DisplayName("an invalid submission is refused whole; nothing partial reaches the ledger")
    void rejectedSubmissionWritesNothing() {
        String subject = newSubject();

        ConsentCaptureService.Result rejected = capture.capture(new CaptureSubmission(ENTITY,
                subject, Jurisdiction.IN, "en", Channel.WEB, APP, CaptureMethod.CHECKBOX_OPT_IN,
                ActorType.SUBJECT, subject, NOTICE, 1,
                List.of(new CaptureSubmission.PurposeChoice("MKT_OUTBOUND_EMAIL", true, true, true)),
                true, NOW, "reject-" + subject, null, Map.of()));

        assertThat(rejected.isAccepted()).isFalse();
        assertThat(rejected.violations()).extracting(CaptureViolation::code)
                .contains(CaptureViolation.Code.PRE_SELECTED_OPTION);
        assertThat(ledger.history(ENTITY, subject)).isEmpty();
    }

    @Test
    @DisplayName("a field device replaying its queue writes one event, not two")
    void replayIsIdempotent() {
        // A device on a flaky connection retries. Without the idempotency key the subject would
        // appear to have consented twice, and worse, a retried withdrawal would be published twice
        // to every downstream consumer.
        String subject = newSubject();
        CaptureSubmission submission = submission(subject,
                CaptureSubmission.PurposeChoice.acceptedSeparately("MKT_OUTBOUND_CALL"));

        ConsentEvent first = capture.capture(submission).events().get(0);
        ConsentEvent replayed = capture.capture(submission).events().get(0);

        assertThat(replayed.eventId()).isEqualTo(first.eventId());
        assertThat(replayed.sequenceNumber()).isEqualTo(first.sequenceNumber());
        assertThat(ledger.history(ENTITY, subject)).hasSize(1);
    }

    @Test
    @DisplayName("a late-syncing device does not overwrite a withdrawal the subject made since")
    void lateGrantDoesNotResurrectAWithdrawnConsent() {
        // iSFA in a basement: consent captured at nine, synced at five. Meanwhile the subject
        // withdrew on the web at noon. Wall-clock order says the withdrawal is the later wish, and
        // the projection must not be talked out of that by the order of arrival.
        String subject = newSubject();

        capture.capture(submission(subject, NOW.minus(2, ChronoUnit.HOURS),
                CaptureSubmission.PurposeChoice.acceptedSeparately("MKT_OUTBOUND_CALL")));

        capture.withdraw(ENTITY, subject, List.of("MKT_OUTBOUND_CALL"), Channel.WEB, APP,
                ActorType.SUBJECT, subject, Jurisdiction.IN, NOW, "withdraw-" + subject, "opt-out");

        // The device finally reconnects and submits what happened three hours ago.
        capture.capture(submission(subject, NOW.minus(3, ChronoUnit.HOURS), "late-" + subject,
                CaptureSubmission.PurposeChoice.acceptedSeparately("MKT_OUTBOUND_CALL")));

        ConsentArtefact state = ledger.currentState(ENTITY, subject, "MKT_OUTBOUND_CALL");
        assertThat(state.status()).isEqualTo(ConsentStatus.WITHDRAWN);
        assertThat(decide(subject, "MKT_OUTBOUND_CALL", Channel.VOICE_CALL).reason())
                .isEqualTo(DenialReason.CONSENT_WITHDRAWN);

        // Nothing was discarded: all three events remain as evidence of what actually happened.
        assertThat(ledger.history(ENTITY, subject, "MKT_OUTBOUND_CALL")).hasSize(3);
    }

    @Test
    @DisplayName("two surfaces disagreeing inside the clock-skew window resolve to CONFLICTED")
    void ambiguousOrderingDeniesRatherThanGuessing() {
        // Within the window where device clocks cannot be trusted to order two events, the
        // platform must not quietly choose the permissive reading. CONFLICTED denies, both events
        // stay in the ledger, and a human decides.
        String subject = newSubject();

        capture.withdraw(ENTITY, subject, List.of("MKT_OUTBOUND_CALL"), Channel.WEB, APP,
                ActorType.SUBJECT, subject, Jurisdiction.IN, NOW, "withdraw-" + subject, "opt-out");

        capture.capture(submission(subject, NOW.minus(1, ChronoUnit.MINUTES), "close-" + subject,
                CaptureSubmission.PurposeChoice.acceptedSeparately("MKT_OUTBOUND_CALL")));

        assertThat(ledger.currentState(ENTITY, subject, "MKT_OUTBOUND_CALL").status())
                .isEqualTo(ConsentStatus.CONFLICTED);
        assertThat(decide(subject, "MKT_OUTBOUND_CALL", Channel.VOICE_CALL).reason())
                .isEqualTo(DenialReason.CONSENT_CONFLICTED);
    }

    @Test
    @DisplayName("TRAI's seven-day transactional window is written from when the subject acted")
    void transactionalConsentExpiresSevenDaysAfterTheSubjectActed() {
        // Not seven days after the server heard about it. A consent given on a field device at
        // nine in the morning and synced at five expires seven days from nine in the morning.
        String subject = newSubject();
        Instant acted = NOW.minus(6, ChronoUnit.HOURS);

        capture.capture(submission(subject, acted, "txn-" + subject, Channel.SMS,
                CaptureSubmission.PurposeChoice.acceptedSeparately("TXN_SERVICE_SMS")));

        ConsentArtefact state = ledger.currentState(ENTITY, subject, "TXN_SERVICE_SMS");
        assertThat(state.expiresAt()).isEqualTo(acted.plus(7, ChronoUnit.DAYS));

        assertThat(decideAt(subject, "TXN_SERVICE_SMS", Channel.SMS,
                acted.plus(6, ChronoUnit.DAYS)).isAllowed()).isTrue();
        assertThat(decideAt(subject, "TXN_SERVICE_SMS", Channel.SMS,
                acted.plus(8, ChronoUnit.DAYS)).reason())
                .isEqualTo(DenialReason.CONSENT_EXPIRED);
    }

    @Test
    @DisplayName("a preference-register entry denies a subject who holds a valid consent record")
    void statutorySuppressionOverridesConsent() {
        String subject = newSubject();
        capture.capture(submission(subject,
                CaptureSubmission.PurposeChoice.acceptedSeparately("MKT_OUTBOUND_CALL")));
        assertThat(decide(subject, "MKT_OUTBOUND_CALL", Channel.VOICE_CALL).isAllowed()).isTrue();

        suppression.optOut(ENTITY, SuppressionScope.GLOBAL, SuppressionSource.NCPR_INDIA,
                Channel.VOICE_CALL, IdentifierType.PHONE, "+919876500001", subject, null, null,
                "national preference register", "ncpr-import");

        // Evaluated against the wall clock rather than this suite's fixed instant, because a
        // suppression entry carries a real effective_from and the whole point of the entry is that
        // it starts binding the moment it is loaded.
        DecisionResponse decision = decideAt(subject, "MKT_OUTBOUND_CALL", Channel.VOICE_CALL,
                Instant.now().plusSeconds(60));
        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(DenialReason.SUPPRESSED_STATUTORY);
    }

    @Test
    @DisplayName("the snapshot a device verifies offline gives the server's answer")
    void offlineSnapshotAgreesWithTheServer() {
        String subject = newSubject();
        capture.capture(submission(subject,
                CaptureSubmission.PurposeChoice.acceptedSeparately("MKT_OUTBOUND_CALL")));

        SignedSnapshot signed = snapshots.issue(ENTITY, subject, NOW);
        ConsentSnapshot verified = new SnapshotVerifier(keys.verificationKeys()).verify(signed);

        DecisionRequest question = DecisionRequest.of(ENTITY, subject, "MKT_OUTBOUND_CALL",
                Channel.VOICE_CALL, Jurisdiction.IN, NOW);

        DecisionResponse fromDevice = new LocalConsentEvaluator(verified).evaluate(question);
        DecisionResponse fromServer = policy.evaluate(question);

        assertThat(fromDevice.outcome()).isEqualTo(fromServer.outcome());
        assertThat(fromDevice.reason()).isEqualTo(fromServer.reason());
        assertThat(fromDevice.evaluatedLocally()).isTrue();
    }

    @Test
    @DisplayName("a snapshot issued before a withdrawal denies once the device refreshes")
    void snapshotReflectsWithdrawalOnceReissued() {
        String subject = newSubject();
        capture.capture(submission(subject,
                CaptureSubmission.PurposeChoice.acceptedSeparately("MKT_OUTBOUND_CALL")));

        SnapshotVerifier verifier = new SnapshotVerifier(keys.verificationKeys());
        ConsentSnapshot before = verifier.verify(snapshots.issue(ENTITY, subject, NOW));

        capture.withdraw(ENTITY, subject, List.of("MKT_OUTBOUND_CALL"), Channel.WEB, APP,
                ActorType.SUBJECT, subject, Jurisdiction.IN, NOW.plus(1, ChronoUnit.MINUTES),
                "withdraw-" + subject, "opt-out");

        ConsentSnapshot after = verifier.verify(
                snapshots.issue(ENTITY, subject, NOW.plus(2, ChronoUnit.MINUTES)));

        assertThat(before.purpose("MKT_OUTBOUND_CALL").status()).isEqualTo(ConsentStatus.GRANTED);
        assertThat(after.purpose("MKT_OUTBOUND_CALL").status()).isEqualTo(ConsentStatus.WITHDRAWN);

        // The old snapshot is still a valid signed object. Its life is what bounds the exposure,
        // which is why snapshot validity is a configured trade-off rather than an afterthought.
        assertThat(before.isStaleAt(NOW.plus(1, ChronoUnit.HOURS))).isTrue();
    }

    @Test
    @DisplayName("every recorded event queues exactly one outbox message in the same transaction")
    void eventAndOutboxRowCommitTogether() {
        // The failure this prevents: a withdrawal recorded in the ledger but never fanned out,
        // leaving a dialer calling someone the platform believes has opted out.
        String subject = newSubject();
        capture.capture(submission(subject,
                CaptureSubmission.PurposeChoice.acceptedSeparately("MKT_OUTBOUND_CALL"),
                CaptureSubmission.PurposeChoice.declined("MKT_OUTBOUND_EMAIL")));

        Integer queued = jdbc.queryForObject(
                "select count(*) from event_outbox where event_key = ?", Integer.class,
                ENTITY + '|' + subject);

        assertThat(queued).isEqualTo(2);
        assertThat(outbox.pendingCount()).isPositive();
    }

    @Test
    @DisplayName("a purpose resting on s.7(i) permits with no consent record for the subject")
    void legitimateUsePermitsWithoutAnyCaptureAtAll() {
        String subject = newSubject();

        DecisionResponse decision = policy.evaluate(DecisionRequest.of(ENTITY, subject,
                "HR_EMPLOYMENT_ADMIN", null, Jurisdiction.IN, NOW));

        assertThat(decision.isAllowed()).isTrue();
        assertThat(decision.obligations()).contains("serve-notice-no-consent-required");
        assertThat(ledger.history(ENTITY, subject)).isEmpty();
    }

    @Test
    @DisplayName("a subject known only by a hashed identifier is resolved, never by raw number")
    void subjectsAreResolvedFromHashesOnly() {
        // The ledger must not become a second master customer database. What it holds is an opaque
        // subject id and a peppered hash — enough to answer "did this person consent", and not
        // enough to reconstitute a contact list from a stolen backup.
        String hash = "hash-" + UUID.randomUUID();

        String subject = subjects.resolveOrCreate(ENTITY, IdentifierType.PHONE, hash);
        String again = subjects.resolveOrCreate(ENTITY, IdentifierType.PHONE, hash);

        assertThat(again).isEqualTo(subject);
        assertThat(jdbc.queryForObject(
                "select count(*) from subject_identifier where identifier_hash = ?", Integer.class,
                hash)).isEqualTo(1);
    }

    // -------------------------------------------------------------------------------------------

    private DecisionResponse decide(String subjectId, String purposeCode, Channel channel) {
        return decideAt(subjectId, purposeCode, channel, NOW.plus(2, ChronoUnit.HOURS));
    }

    private DecisionResponse decideAt(String subjectId, String purposeCode, Channel channel,
                                      Instant at) {
        return policy.evaluate(DecisionRequest.of(ENTITY, subjectId, purposeCode, channel,
                Jurisdiction.IN, at));
    }

    private static String newSubject() {
        return "it-" + UUID.randomUUID();
    }

    private static CaptureSubmission submission(String subjectId,
                                                CaptureSubmission.PurposeChoice... choices) {
        return submission(subjectId, NOW, "capture-" + subjectId, Channel.WEB, choices);
    }

    private static CaptureSubmission submission(String subjectId, Instant occurredAt,
                                                CaptureSubmission.PurposeChoice... choices) {
        return submission(subjectId, occurredAt, "capture-" + subjectId, Channel.WEB, choices);
    }

    private static CaptureSubmission submission(String subjectId, Instant occurredAt, String key,
                                                CaptureSubmission.PurposeChoice... choices) {
        return submission(subjectId, occurredAt, key, Channel.WEB, choices);
    }

    private static CaptureSubmission submission(String subjectId, Instant occurredAt, String key,
                                                Channel channel,
                                                CaptureSubmission.PurposeChoice... choices) {
        return new CaptureSubmission(ENTITY, subjectId, Jurisdiction.IN, "en", channel, APP,
                CaptureMethod.CHECKBOX_OPT_IN, ActorType.SUBJECT, subjectId, NOTICE, 1,
                List.of(choices), true, occurredAt, key, "evidence://form/" + subjectId, Map.of());
    }
}
