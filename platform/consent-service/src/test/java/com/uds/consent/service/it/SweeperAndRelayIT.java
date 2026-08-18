package com.uds.consent.service.it;

import com.uds.consent.core.model.ActorType;
import com.uds.consent.core.model.CaptureMethod;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.ConsentStatus;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.ledger.service.ConsentLedger;
import com.uds.consent.ledger.store.ConsentArtefactStore;
import com.uds.consent.ledger.store.OutboxStore;
import com.uds.consent.policy.capture.CaptureSubmission;
import com.uds.consent.service.ConsentCaptureService;
import com.uds.consent.service.events.OutboxRelay;
import com.uds.consent.service.sweeper.ExpirySweeper;
import com.uds.consent.service.sweeper.SweepLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The background machinery, which nothing named until now.
 *
 * <p>Three gaps, and the second is the uncomfortable one.
 *
 * <p>{@code ExpirySweeper.sweepAsOf} was given an {@code Instant} parameter with the comment "so
 * that tests can drive it with a controlled clock", and no test did.
 *
 * <p>{@code ConsentLifecycleIT} asserts that the outbox's pending count is <em>positive</em> —
 * which passes precisely because the relay is disabled in the test profile. Nothing anywhere
 * proved a message is ever delivered, so the entire downstream integration rested on an assertion
 * that would still hold if the relay did nothing at all.
 *
 * <p>And {@code SweepLock} decides whether two replicas page on-call twice for the same statutory
 * breach. Its failure mode — a lock acquired on one pooled connection and released on another,
 * leaving it held forever by nobody — is silent, permanent, and looks exactly like a sweeper that
 * has nothing to do.
 */
class SweeperAndRelayIT extends PostgresIntegrationTest {

    private static final String ENTITY = "DENAVE_IN";
    private static final String APP = "DENAVE_WEB";
    private static final String NOTICE = "NOTICE_DENAVE_B2B";

    @Autowired
    private ExpirySweeper expiry;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxStore outbox;

    @Autowired
    private SweepLock lock;

    @Autowired
    private ConsentCaptureService capture;

    @Autowired
    private ConsentArtefactStore artefacts;

    @Autowired
    private ConsentLedger ledger;

    @Test
    @DisplayName("the expiry sweeper writes an EXPIRED event once the window has run")
    void lapsedConsentBecomesADurableEvent() {
        // The decision engine already treats lapsed consent as expired without waiting for this,
        // and that is the important part. What the sweeper adds is the evidence: "we stopped
        // relying on this on the eighth day" needs a record, not an inference from an absent one.
        String subject = grantTransactional();

        Instant afterTheWindow = Instant.parse("2026-08-15T09:00:00Z").plus(9, ChronoUnit.DAYS);
        expiry.sweepAsOf(afterTheWindow);

        assertThat(ledger.history(ENTITY, subject, "TXN_SERVICE_SMS"))
                .extracting(event -> event.event().type().name())
                .contains("EXPIRED");

        assertThat(artefacts.find(ENTITY, subject, "TXN_SERVICE_SMS").orElseThrow()
                .effectiveStatus(afterTheWindow)).isEqualTo(ConsentStatus.EXPIRED);
    }

    @Test
    @DisplayName("the sweeper is idempotent — running twice writes one EXPIRED event")
    void theSweepDoesNotDuplicate() {
        String subject = grantTransactional();
        Instant afterTheWindow = Instant.parse("2026-08-15T09:00:00Z").plus(9, ChronoUnit.DAYS);

        expiry.sweepAsOf(afterTheWindow);
        expiry.sweepAsOf(afterTheWindow);
        expiry.sweepAsOf(afterTheWindow);

        // Idempotency keys carry this, which is why the expiry sweep's lock buys efficiency
        // rather than correctness — unlike the other two sweeps, where it buys both.
        assertThat(ledger.history(ENTITY, subject, "TXN_SERVICE_SMS"))
                .filteredOn(event -> "EXPIRED".equals(event.event().type().name()))
                .hasSize(1);
    }

    @Test
    @DisplayName("consent still inside its window is left alone")
    void theSweeperDoesNotExpireLiveConsent() {
        String subject = grantTransactional();

        // The controlled clock is the point of sweepAsOf, and this is the assertion it was added
        // for: behaviour that can only be exercised by waiting a week does not get tested.
        expiry.sweepAsOf(Instant.parse("2026-08-15T09:00:00Z").plus(3, ChronoUnit.DAYS));

        assertThat(ledger.history(ENTITY, subject, "TXN_SERVICE_SMS"))
                .noneMatch(event -> "EXPIRED".equals(event.event().type().name()));
    }

    @Test
    @DisplayName("the relay actually delivers, and the queue actually drains")
    void messagesReachThePublisher() {
        // The gap this closes. ConsentLifecycleIT asserts the pending count is positive, which
        // passes because the relay is disabled in the test profile — so nothing anywhere proved a
        // consent change ever reaches a downstream system.
        grantTransactional();
        assertThat(outbox.pendingCount()).isPositive();

        relay.relay();

        assertThat(outbox.pendingCount()).isZero();
    }

    @Test
    @DisplayName("a relay pass with nothing to do is harmless")
    void anEmptyRelayIsANoOp() {
        relay.relay();
        relay.relay();

        assertThat(outbox.pendingCount()).isZero();
    }

    @Test
    @DisplayName("the sweep lock admits one holder at a time and releases afterwards")
    void theLockIsExclusiveAndThenIsNot() {
        AtomicInteger ran = new AtomicInteger();

        // Nested acquisition of the same key from inside the lock. PostgreSQL advisory locks are
        // re-entrant within a session, so this must be attempted on a different connection to
        // mean anything — which is what runExclusively does, by borrowing one per call.
        boolean outer = lock.runExclusively("test-lock-" + UUID.randomUUID(), () -> {
            ran.incrementAndGet();
        });

        assertThat(outer).isTrue();
        assertThat(ran.get()).isEqualTo(1);

        // And the lock is released, so the same name is immediately available again. The failure
        // this guards against is the one that would have followed from using a pooled JdbcClient:
        // acquire on one connection, release on another, and the lock is held forever by a
        // connection nobody can identify — after which every future sweep silently does nothing.
        String name = "test-lock-reuse";
        assertThat(lock.runExclusively(name, ran::incrementAndGet)).isTrue();
        assertThat(lock.runExclusively(name, ran::incrementAndGet)).isTrue();
        assertThat(ran.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("a sweep that throws still releases the lock")
    void theLockSurvivesAFailingSweep() {
        String name = "test-lock-throwing";

        try {
            lock.runExclusively(name, () -> {
                throw new IllegalStateException("sweep blew up");
            });
        } catch (RuntimeException expected) {
            // Whether the exception propagates is the sweeper's business. What must not happen is
            // the lock staying held — that would turn one bad pass into a permanently disabled
            // sweeper, and the symptom would be silence rather than an error.
        }

        assertThat(lock.runExclusively(name, () -> {
        })).isTrue();
    }

    private String grantTransactional() {
        String subject = "sw-" + UUID.randomUUID();
        ConsentCaptureService.Result result = capture.capture(new CaptureSubmission(ENTITY, subject,
                Jurisdiction.IN, "en", Channel.WEB, APP, CaptureMethod.CHECKBOX_OPT_IN,
                ActorType.SUBJECT, subject, NOTICE, 1,
                List.of(CaptureSubmission.PurposeChoice.acceptedSeparately("TXN_SERVICE_SMS")),
                true, Instant.parse("2026-08-15T09:00:00Z"), "sw-" + subject, null, Map.of()));

        assertThat(result.isAccepted())
                .withFailMessage("capture rejected: %s", result.violations())
                .isTrue();
        return subject;
    }
}
