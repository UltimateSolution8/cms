package com.uds.consent.service.it;

import com.uds.consent.core.model.IdentifierType;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.RightsRequestStatus;
import com.uds.consent.core.model.RightsRequestType;
import com.uds.consent.ledger.store.AdminAuditStore;
import com.uds.consent.ledger.store.RightsRequestStore;
import com.uds.consent.service.RightsService;
import com.uds.consent.service.sweeper.RightsSlaSweeper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The statutory clock.
 *
 * <p>Fulfilment is the next phase; the clock is the part that cannot be added afterwards. A rights
 * request that arrives before anyone can fulfil it becomes a manual job, and manual jobs get done.
 * One that arrives before anyone is <em>counting the days</em> becomes a breach nobody notices
 * until the principal escalates to the Board.
 *
 * <p>The deadline is stored rather than derived, so these tests are also asserting that a request
 * answered in time cannot retroactively become a breach when someone edits a rule.
 */
class RightsRequestIT extends PostgresIntegrationTest {

    private static final String ENTITY = "DENAVE_IN";

    @Autowired
    private RightsService rights;

    @Autowired
    private RightsRequestStore store;

    @Autowired
    private RightsSlaSweeper sweeper;

    @Autowired
    private AdminAuditStore audit;

    @Test
    @DisplayName("the deadline differs by jurisdiction, and the working is recorded with it")
    void deadlineIsSetPerJurisdiction() {
        Instant received = Instant.parse("2026-08-01T09:00:00Z");

        RightsRequestStore.Request korea = file(RightsRequestType.ACCESS, Jurisdiction.KR, received);
        RightsRequestStore.Request uk = file(RightsRequestType.ACCESS, Jurisdiction.UK, received);
        RightsRequestStore.Request california =
                file(RightsRequestType.ACCESS, Jurisdiction.US_CA, received);

        // Korea's PIPA is the tightest regime the group operates under by a wide margin. A single
        // group-wide default would either breach Korea or answer everyone else far too slowly.
        assertThat(Duration.between(received, korea.dueAt()).toDays()).isEqualTo(10);
        assertThat(Duration.between(received, uk.dueAt()).toDays()).isEqualTo(30);
        assertThat(Duration.between(received, california.dueAt()).toDays()).isEqualTo(45);

        assertThat(korea.dueAtBasis()).contains("PIPA");
        assertThat(uk.dueAtBasis()).contains("UK GDPR");
        assertThat(california.dueAtBasis()).contains("CCPA/CPRA");
    }

    @Test
    @DisplayName("a withdrawal is not on a month-long clock like an access request")
    void withdrawalIsNotQueuedForAMonth() {
        // Treating a withdrawal like an access request would let it sit in a queue for a month
        // while the dialer kept calling — the precise failure the enforcement plane exists to
        // prevent.
        Instant received = Instant.parse("2026-08-01T09:00:00Z");
        RightsRequestStore.Request request =
                file(RightsRequestType.CONSENT_WITHDRAWAL, Jurisdiction.IN, received);

        assertThat(Duration.between(received, request.dueAt()).toDays()).isEqualTo(1);
        assertThat(request.dueAtBasis()).contains("as easily and as promptly");
    }

    @Test
    @DisplayName("the clock runs from when the principal acted, not from when it was keyed in")
    void clockRunsFromTheSubjectsAct() {
        // A form typed up three days late does not buy the group three extra days.
        Instant actedAt = Instant.now().minus(3, ChronoUnit.DAYS);
        RightsRequestStore.Request request = file(RightsRequestType.ACCESS, Jurisdiction.KR, actedAt);

        assertThat(Duration.between(request.receivedAt(), actedAt).abs())
                .isLessThan(Duration.ofSeconds(2));
        assertThat(Duration.between(actedAt, request.dueAt()).toDays()).isEqualTo(10);
    }

    @Test
    @DisplayName("the sweeper reports an overdue request and leaves a timely one alone")
    void sweeperFindsOnlyTheBreach() {
        Instant longAgo = Instant.now().minus(60, ChronoUnit.DAYS);
        RightsRequestStore.Request breached =
                file(RightsRequestType.ACCESS, Jurisdiction.KR, longAgo);
        RightsRequestStore.Request timely =
                file(RightsRequestType.ACCESS, Jurisdiction.US_CA, Instant.now());

        RightsSlaSweeper.Report report = sweeper.run(Instant.now());

        assertThat(report.clean()).isFalse();
        assertThat(report.breachedRequestIds())
                .contains(breached.requestId())
                .doesNotContain(timely.requestId());
    }

    @Test
    @DisplayName("closing a request needs a resolution, including when it is refused")
    void closingRequiresAResolution() {
        RightsRequestStore.Request request =
                file(RightsRequestType.ERASURE, Jurisdiction.IN, Instant.now());

        // Refusing an erasure request against data held under a legal obligation is a legitimate
        // outcome. Refusing it without saying why is not, and the Board would ask to see exactly
        // this text.
        assertThatThrownBy(() -> rights.transition(request.requestId(),
                RightsRequestStatus.REJECTED, "priya", null, "compliance-console"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a resolution");

        RightsRequestStore.Request rejected = rights.transition(request.requestId(),
                RightsRequestStatus.REJECTED, "priya",
                "Retained under the Companies Act 2013 statutory retention period",
                "compliance-console");

        assertThat(rejected.status()).isEqualTo(RightsRequestStatus.REJECTED);
        assertThat(rejected.closedAt()).isNotNull();
        assertThat(rejected.resolution()).contains("Companies Act");
    }

    @Test
    @DisplayName("a closed request cannot be reopened by editing it")
    void closedRequestsAreNotEdited() {
        RightsRequestStore.Request request =
                file(RightsRequestType.ACCESS, Jurisdiction.IN, Instant.now());
        rights.transition(request.requestId(), RightsRequestStatus.FULFILLED, "priya",
                "Summary of processing sent to the principal", "compliance-console");

        assertThatThrownBy(() -> rights.transition(request.requestId(),
                RightsRequestStatus.IN_PROGRESS, "priya", null, "compliance-console"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already");
    }

    @Test
    @DisplayName("an intermediate status leaves the request open and the clock running")
    void inProgressKeepsTheClockRunning() {
        RightsRequestStore.Request request =
                file(RightsRequestType.ACCESS, Jurisdiction.IN, Instant.now());

        RightsRequestStore.Request awaiting = rights.transition(request.requestId(),
                RightsRequestStatus.AWAITING_SUBJECT, "priya", null, "compliance-console");

        // Blocked on the principal for identity verification. Whether the statute permits a pause
        // is a question for legal per jurisdiction, and the safe implementation is the one that
        // does not quietly grant an extension the law may not allow.
        assertThat(awaiting.status().isOpen()).isTrue();
        assertThat(awaiting.closedAt()).isNull();
        assertThat(awaiting.dueAt()).isEqualTo(request.dueAt());
    }

    @Test
    @DisplayName("a late answer is recorded as late, not merely as answered")
    void lateClosureIsVisibleAfterwards() {
        RightsRequestStore.Request request = file(RightsRequestType.ACCESS, Jurisdiction.KR,
                Instant.now().minus(30, ChronoUnit.DAYS));

        RightsRequestStore.Request closed = rights.transition(request.requestId(),
                RightsRequestStatus.FULFILLED, "priya", "Answered, twenty days late",
                "compliance-console");

        assertThat(closed.closedLate()).isTrue();
        assertThat(store.summarise(ENTITY, Instant.now()))
                .filteredOn(summary -> summary.type() == RightsRequestType.ACCESS)
                .singleElement()
                .satisfies(summary -> assertThat(summary.closedLate()).isPositive());
    }

    @Test
    @DisplayName("intake is audited with the deadline it committed to")
    void intakeIsAudited() {
        RightsRequestStore.Request request =
                file(RightsRequestType.GRIEVANCE, Jurisdiction.IN, Instant.now());

        assertThat(audit.recent(ENTITY, 200))
                .filteredOn(entry -> "RIGHTS_REQUEST_RECEIVED".equals(entry.action())
                        && request.requestId().equals(entry.targetId()))
                .singleElement()
                .satisfies(entry -> assertThat(entry.detailJson())
                        .contains("GRIEVANCE")
                        .contains(request.dueAt().toString()));
    }

    @Test
    @DisplayName("a request from someone the group holds no consent record for is still tracked")
    void unknownSubjectStillGetsARequest() {
        // These are the ones that matter most: a principal asking what is held about them, where
        // the honest answer might be "we bought your details".
        String phone = "+9199" + (10_000_000 + (int) (Math.random() * 89_999_999));

        RightsRequestStore.Request request = rights.intake(new RightsService.Intake(
                ENTITY, null, IdentifierType.PHONE, phone, RightsRequestType.ACCESS,
                Jurisdiction.IN, Instant.now(), "Emailed the DPO", "compliance-console"));

        assertThat(request.subjectId()).isNotBlank().doesNotContain(phone);
        assertThat(rights.forSubject(ENTITY, request.subjectId())).hasSize(1);
    }

    @Test
    @DisplayName("acknowledgement is recorded once and does not move the deadline")
    void acknowledgementIsIdempotent() {
        RightsRequestStore.Request request =
                file(RightsRequestType.ACCESS, Jurisdiction.IN, Instant.now());

        rights.acknowledge(request.requestId());
        RightsRequestStore.Request first = rights.find(request.requestId());
        rights.acknowledge(request.requestId());
        RightsRequestStore.Request second = rights.find(request.requestId());

        assertThat(first.acknowledgedAt()).isNotNull();
        assertThat(second.acknowledgedAt()).isEqualTo(first.acknowledgedAt());
        assertThat(second.dueAt()).isEqualTo(request.dueAt());
    }

    // -------------------------------------------------------------------------------------------

    private RightsRequestStore.Request file(RightsRequestType type, Jurisdiction jurisdiction,
                                            Instant receivedAt) {
        return rights.intake(new RightsService.Intake(ENTITY, "it-rights-" + UUID.randomUUID(),
                null, null, type, jurisdiction, receivedAt, "filed by integration test",
                "compliance-console"));
    }
}
