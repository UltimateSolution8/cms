package com.uds.consent.service.it;

import com.uds.consent.core.model.IdentifierType;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.RightsRequestStatus;
import com.uds.consent.core.model.RightsRequestType;
import com.uds.consent.core.model.RightsVerificationMethod;
import com.uds.consent.ledger.store.AdminAuditStore;
import com.uds.consent.ledger.store.RightsRequestStore;
import com.uds.consent.service.RightsService;
import com.uds.consent.service.sweeper.RightsSlaSweeper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
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

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private com.uds.consent.service.config.PlatformProperties properties;

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
    @DisplayName("a late filing is accepted, and the audit row says it arrived overdue")
    void aLateFilingIsAcceptedAndRecordedAsBornOverdue() {
        // The case the backdate bound was documented as preventing and never did. Korea's period
        // is ten days; sixty days back is comfortably inside the ninety-day bound, so the platform
        // accepts it — correctly, because a letter found in a postbag is a real filing and
        // refusing it teaches an operator to file with today's date, destroying the provenance the
        // whole mechanism exists to preserve.
        //
        // What was missing is the distinction a Rule 14(3) dispute actually turns on: *the group
        // was late* and *the request arrived late* are different facts, and until now the record
        // could not tell them apart.
        Instant sixtyDaysAgo = Instant.now().minus(60, ChronoUnit.DAYS);

        RightsRequestStore.Request late = file(RightsRequestType.ACCESS, Jurisdiction.KR,
                sixtyDaysAgo);

        assertThat(late.dueAt())
                .withFailMessage("a ten-day Korean period sixty days back should already be past")
                .isBefore(Instant.now());

        assertThat(audit.recent(ENTITY, 200))
                .filteredOn(entry -> "RIGHTS_REQUEST_RECEIVED".equals(entry.action())
                        && late.requestId().equals(entry.targetId()))
                .singleElement()
                .satisfies(entry -> assertThat(detail(entry).get("bornOverdue"))
                        .withFailMessage("the record cannot distinguish a late filing from a "
                                + "missed deadline, which is the distinction a Rule 14(3) dispute "
                                + "turns on")
                        .isEqualTo("true"));

        // And the ordinary case says so too, rather than leaving the field absent and ambiguous.
        RightsRequestStore.Request timely =
                file(RightsRequestType.ACCESS, Jurisdiction.IN, Instant.now());
        assertThat(audit.recent(ENTITY, 200))
                .filteredOn(entry -> timely.requestId().equals(entry.targetId()))
                .singleElement()
                .satisfies(entry -> assertThat(detail(entry).get("bornOverdue"))
                        .isEqualTo("false"));
    }

    @Test
    @DisplayName("both start-instant refusals reach the caller as 400, not as a 500")
    void theRefusalsAreAnsweredOverHttp() {
        // Both bounds were asserted only against RightsService.intake directly. The 400 they are
        // documented as producing — in the controller javadoc, in TRACEABILITY and in the plan —
        // was inferred from ApiExceptionHandler and never once exercised, so nothing would have
        // caught the handler being reordered, an exception type changing, or the mapping being
        // lost. An integrator meeting a 500 here would be told the platform is broken rather than
        // that their value is out of bounds.
        assertThat(fileOverHttp(Instant.now().plus(1, ChronoUnit.DAYS)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(fileOverHttp(Instant.now().minus(200, ChronoUnit.DAYS)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // And the body names the bound, so the caller can act on it without reading the source.
        assertThat(fileOverHttp(Instant.now().minus(200, ChronoUnit.DAYS)).getBody())
                .contains("sanity bound");
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
        verify(request);
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

        verify(request);
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
                Jurisdiction.IN, Instant.now(), "Emailed the DPO", "compliance-console",
                RightsVerificationMethod.UNVERIFIED, null));

        assertThat(request.subjectId()).isNotBlank().doesNotContain(phone);
        assertThat(rights.forSubject(ENTITY, request.subjectId())).hasSize(1);
    }

    @Test
    @DisplayName("a receivedAt in the future is refused, because it would move the deadline outward")
    void aFutureStartInstantIsRefused() {
        // The whole point of the bound. received_at is the input to the statutory clock, and until
        // this refusal existed it was whatever the caller said it was — so a request answered late
        // could be made to look timely by a value typed into a form, and in any Rule 14(3) dispute
        // the group's own record would be evidence offered by the party the dispute is with.
        Instant tomorrow = Instant.now().plus(1, ChronoUnit.DAYS);

        assertThatThrownBy(() -> file(RightsRequestType.ACCESS, Jurisdiction.IN, tomorrow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("in the future");

        // Ordinary clock skew is still absorbed silently, which is why the bound is a window and
        // not a comparison against now(). A tablet a minute ahead is a wrong clock, not a claim.
        RightsRequestStore.Request skewed = file(RightsRequestType.ACCESS, Jurisdiction.IN,
                Instant.now().plusSeconds(60));
        assertThat(skewed.requestId()).isNotBlank();
    }

    @Test
    @DisplayName("a receivedAt beyond the backdate bound is refused, as a sanity check on the value")
    void aFarBackdatedStartInstantIsRefused() {
        // A sanity bound, not a deadline argument. A value this old is far more likely a typo or
        // a wrong clock than a filing, and beyond it the platform declines to guess which. The
        // property asserted is that no request exists afterwards, not merely that something was
        // thrown.
        long before = store.summarise(ENTITY, Instant.now()).stream()
                .mapToLong(RightsRequestStore.TypeSummary::total).sum();

        // The bound is the *bound value*, not the Java field default. This profile sets 120 days
        // where the shipped default is 90, so a mis-keyed property would leave 90 in place and
        // fail here — a test asserting only that the property exists would pass either way, which
        // is how otel.exporter.otlp.endpoint got shipped pointing at nothing for a whole phase.
        assertThat(properties.getRights().getMaxBackdate()).isEqualTo(Duration.ofDays(120));

        assertThatThrownBy(() -> file(RightsRequestType.ACCESS, Jurisdiction.IN,
                Instant.now().minus(200, ChronoUnit.DAYS)))
                .isInstanceOf(IllegalArgumentException.class)
                // The refusal used to say the request "would be filed already past its deadline",
                // and that reasoning was false: every period the platform computes is shorter than
                // this bound, so filings between the two are accepted and *are* overdue. See
                // aLateFilingIsAcceptedAndRecordedAsBornOverdue, which is that case.
                .hasMessageContaining("sanity bound");

        assertThat(store.summarise(ENTITY, Instant.now()).stream()
                .mapToLong(RightsRequestStore.TypeSummary::total).sum())
                .withFailMessage("a refused backdate still created a request")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("a request nobody verified says so, rather than looking like every other one")
    void anUnverifiedRequestIsLabelledAsOne() {
        // Silence is not read as diligence. The label refuses nothing — the request is filed, the
        // clock runs, it enters the queue — and what it changes is that a reviewer can tell the
        // difference between a start instant somebody established and one nobody did.
        RightsRequestStore.Request request =
                file(RightsRequestType.ACCESS, Jurisdiction.IN, Instant.now());

        assertThat(request.verification()).isEqualTo(RightsVerificationMethod.UNVERIFIED);
        assertThat(request.verifiedAt()).isNull();
        assertThat(request.status().isOpen()).isTrue();
        assertThat(request.dueAt()).isAfter(request.receivedAt());
    }

    @Test
    @DisplayName("an operator who established identity says how, and the audit row carries it")
    void anOperatorAttestationIsRecorded() {
        RightsRequestStore.Request request = rights.intake(new RightsService.Intake(
                ENTITY, "it-rights-" + UUID.randomUUID(), null, null, RightsRequestType.ERASURE,
                Jurisdiction.IN, Instant.now(), "filed at the service desk", "compliance-console",
                RightsVerificationMethod.OPERATOR_ASSERTED,
                "Called back the mobile already on file and confirmed the last four of the PAN"));

        assertThat(request.verification()).isEqualTo(RightsVerificationMethod.OPERATOR_ASSERTED);
        assertThat(request.verifiedAt()).isNotNull();
        assertThat(request.verificationDetail()).contains("Called back");

        // And on the append-only trail, not only on the mutable request row. "When did the clock
        // start and what did that rest on" is a question an audit asks years later, by which time
        // the request row has been transitioned several times.
        assertThat(audit.recent(ENTITY, 200))
                .filteredOn(entry -> "RIGHTS_REQUEST_RECEIVED".equals(entry.action())
                        && request.requestId().equals(entry.targetId()))
                .singleElement()
                .satisfies(entry -> assertThat(entry.detailJson())
                        .contains("OPERATOR_ASSERTED"));
    }

    @Test
    @DisplayName("a row written without the column reads UNVERIFIED, never as though it were checked")
    void theMigrationDefaultClaimsNothing() throws Exception {
        // Every request that existed before V30 was filed through the administrative route with
        // nobody recording a check. A default of PORTAL_TOKEN would claim a verification that never
        // happened and OPERATOR_ASSERTED would claim an assurance nobody gave — either would be a
        // false statement written into the evidence plane by a migration, which is the worst place
        // to put one. Exercised by writing a row the way the pre-V30 code did, as the owner, so
        // this fails if anybody later "tidies" the default.
        String requestId = "RR-pre-v30-" + UUID.randomUUID();
        try (Connection owner = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = owner.createStatement()) {

            statement.executeUpdate("""
                    insert into rights_request (request_id, entity_id, subject_id, request_type,
                                                jurisdiction, received_at, due_at)
                    values ('%s', '%s', 'it-pre-v30', 'ACCESS', 'IN', now(), now() + interval '30 days')
                    """.formatted(requestId, ENTITY));

            try (ResultSet rs = statement.executeQuery(
                    "select verification_method, verified_at from rights_request "
                            + "where request_id = '" + requestId + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("verification_method")).isEqualTo("UNVERIFIED");
                assertThat(rs.getTimestamp("verified_at")).isNull();
            }
        }
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

    @Test
    @DisplayName("a request cannot bounce backwards, and the refusal names the moves that are legal")
    void theStatusesFormAStateMachine() {
        // Before this, the only rules were "the request must be open" and "closing needs a
        // resolution". Everything else was legal: RECEIVED → AWAITING_SUBJECT → RECEIVED →
        // AWAITING_SUBJECT indefinitely, each bounce resetting nothing, the statutory clock
        // running throughout, and the queue reading as active work the whole time. That is not a
        // hypothetical shape of abuse — it is what a console with a status dropdown produces when
        // somebody is trying to make a hard request look like it is moving.
        RightsRequestStore.Request request =
                file(RightsRequestType.ERASURE, Jurisdiction.IN, Instant.now());

        rights.transition(request.requestId(), RightsRequestStatus.IN_PROGRESS, "priya", null,
                "compliance-console");

        assertThatThrownBy(() -> rights.transition(request.requestId(),
                RightsRequestStatus.RECEIVED, "priya", null, "compliance-console"))
                .isInstanceOf(IllegalArgumentException.class)
                // "Nobody has looked at this yet" stops being true the moment somebody has.
                .hasMessageContaining("cannot move to RECEIVED")
                // And the message says what the operator *can* do, because a console button that
                // sometimes fails for unexplained reasons gets worked around rather than fixed.
                .hasMessageContaining("AWAITING_SUBJECT");

        // Forwards and sideways stay legal: a request genuinely does go back and forth between
        // being worked and waiting on the principal for verification.
        rights.transition(request.requestId(), RightsRequestStatus.AWAITING_SUBJECT, "priya", null,
                "compliance-console");
        assertThat(rights.transition(request.requestId(), RightsRequestStatus.IN_PROGRESS, "priya",
                null, "compliance-console").status()).isEqualTo(RightsRequestStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("a request can be fulfilled on the call that reported it, deliberately")
    void receivedStraightToFulfilledIsAllowed() {
        // The transition a reader would expect to be barred, left legal on purpose. A principal
        // asks what is held about them and the agent reads it out: that is a real access request,
        // really fulfilled, in one interaction. Forbidding it would not produce more work — it
        // would teach operators to click through IN_PROGRESS on the way past, and a state machine
        // people route around records less than one that admits the shortcut.
        RightsRequestStore.Request request =
                file(RightsRequestType.ACCESS, Jurisdiction.IN, Instant.now());

        verify(request);
        assertThat(rights.transition(request.requestId(), RightsRequestStatus.FULFILLED, "priya",
                "Read out on the call; nothing further held", "compliance-console").status())
                .isEqualTo(RightsRequestStatus.FULFILLED);
    }

    @Test
    @DisplayName("the PATCH endpoint moves a request, and refuses the caller who may not")
    void theTransitionEndpointIsReachableOverHttp() {
        // Until this test, PATCH /v1/rights/{requestId} was the only route in the tree with no
        // test at all — the only ADMIN write on the statutory rights path, absent from AdminApiIT's
        // route sweep, and no test anywhere issued an HTTP PATCH. A service-level test of
        // transition() proves the rule and proves nothing about whether the rule is reachable: a
        // wrong @PreAuthorize, a body that does not bind, or a verb Spring does not route would all
        // pass every assertion above.
        RightsRequestStore.Request request =
                file(RightsRequestType.ERASURE, Jurisdiction.IN, Instant.now());
        String path = "/v1/rights/" + request.requestId();

        ResponseEntity<String> refused = rest.withBasicAuth("denave-web", "capture-secret")
                .exchange(path, HttpMethod.PATCH, new HttpEntity<>(Map.of(
                        "status", "IN_PROGRESS")), String.class);
        assertThat(refused.getStatusCode())
                .withFailMessage("a capture credential moved a rights request")
                .isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> moved = rest.withBasicAuth("compliance-console", "admin-secret")
                .exchange(path, HttpMethod.PATCH, new HttpEntity<>(Map.of(
                        "status", "IN_PROGRESS", "assignedTo", "priya")), String.class);
        assertThat(moved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(moved.getBody()).contains("IN_PROGRESS").contains("priya");

        // And the state machine is enforced through the endpoint too, as a 400 rather than a 500 —
        // the caller made a mistake, and ApiExceptionHandler maps IllegalArgumentException to a
        // ProblemDetail that says which one.
        ResponseEntity<String> backwards = rest.withBasicAuth("compliance-console", "admin-secret")
                .exchange(path, HttpMethod.PATCH, new HttpEntity<>(Map.of(
                        "status", "RECEIVED")), String.class);
        assertThat(backwards.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(backwards.getBody()).contains("cannot move to RECEIVED");

        // Closing without a resolution is still refused over HTTP, which is the rule that was
        // already tested at the service and never at the edge.
        ResponseEntity<String> unexplained = rest.withBasicAuth("compliance-console", "admin-secret")
                .exchange(path, HttpMethod.PATCH, new HttpEntity<>(Map.of(
                        "status", "REJECTED")), String.class);
        assertThat(unexplained.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unexplained.getBody()).contains("resolution");
    }

    @Test
    @DisplayName("a disclosing right cannot be closed as fulfilled on an identity nobody recorded")
    void anUnverifiedAccessRequestCannotBeFulfilled() {
        // The hole this closes: an ACCESS request filed by telephone defaults to UNVERIFIED —
        // correctly, because parking it outside the clock until a field is filled is what Rule
        // 14(3) penalises — and could then be closed as FULFILLED. The evidence plane would record
        // a person's whole file as handed over in discharge of a statutory right, holding not one
        // fact about who received it.
        RightsRequestStore.Request request =
                file(RightsRequestType.ACCESS, Jurisdiction.IN, Instant.now());
        assertThat(request.verification()).isEqualTo(RightsVerificationMethod.UNVERIFIED);

        assertThatThrownBy(() -> rights.transition(request.requestId(),
                RightsRequestStatus.FULFILLED, "priya", "Summary sent", "compliance-console"))
                .isInstanceOf(RightsService.VerificationMissingException.class);

        // Then the operator does the thing the gate exists to make them do, and it closes.
        verify(request);
        assertThat(rights.transition(request.requestId(), RightsRequestStatus.FULFILLED, "priya",
                "Summary sent", "compliance-console").status())
                .isEqualTo(RightsRequestStatus.FULFILLED);
    }

    @Test
    @DisplayName("a withdrawal is never gated on verification, however it was filed")
    void anUnverifiedWithdrawalStillCloses() {
        // The assertion that fails the day somebody applies the gate uniformly, which is the
        // likeliest wrong turn here. A withdrawal by an impostor STOPS processing, and DPDP s.6(4)
        // requires withdrawing to be as easy as consenting was — consent is given by a checkbox
        // with no identity check at all. GDPR Art. 7(3) says the same. Making an identity check a
        // toll gate on the one right the group most wants exercised freely inverts both.
        RightsRequestStore.Request request =
                file(RightsRequestType.CONSENT_WITHDRAWAL, Jurisdiction.IN, Instant.now());
        assertThat(request.verification()).isEqualTo(RightsVerificationMethod.UNVERIFIED);

        assertThat(rights.transition(request.requestId(), RightsRequestStatus.FULFILLED, "priya",
                "Withdrawal recorded in the ledger", "compliance-console").status())
                .isEqualTo(RightsRequestStatus.FULFILLED);
    }

    @Test
    @DisplayName("every request type is on a named side of the gate, so a tenth cannot default in")
    void everyTypeIsDeliberatelyGatedOrNot() {
        // The gated set is an EnumSet, so a tenth RightsRequestType would default to *ungated* —
        // the permissive direction — with nothing failing. The plan's stated purpose was that a
        // new type be "a decision somebody has to make rather than a default", and a constant
        // alone cannot force that. This does: the map must cover values() exactly, and each side
        // is then exercised rather than asserted.
        Map<RightsRequestType, Boolean> gated = Map.ofEntries(
                Map.entry(RightsRequestType.ACCESS, true),
                Map.entry(RightsRequestType.PORTABILITY, true),
                Map.entry(RightsRequestType.ERASURE, true),
                Map.entry(RightsRequestType.CORRECTION, true),
                Map.entry(RightsRequestType.COMPLETION, true),
                Map.entry(RightsRequestType.NOMINATION, true),
                Map.entry(RightsRequestType.CONSENT_WITHDRAWAL, false),
                Map.entry(RightsRequestType.OPT_OUT_OF_SALE, false),
                Map.entry(RightsRequestType.GRIEVANCE, false));

        assertThat(gated.keySet())
                .as("a new request type must be added here deliberately, on one side or the other")
                .containsExactlyInAnyOrder(RightsRequestType.values());

        gated.forEach((type, isGated) -> {
            RightsRequestStore.Request request =
                    file(type, Jurisdiction.IN, Instant.now());
            if (isGated) {
                assertThatThrownBy(() -> rights.transition(request.requestId(),
                        RightsRequestStatus.FULFILLED, "priya", "closed", "compliance-console"))
                        .as("%s discloses or irreversibly changes the file and must be gated", type)
                        .hasMessageContaining("UNVERIFIED");
            } else {
                assertThat(rights.transition(request.requestId(), RightsRequestStatus.FULFILLED,
                        "priya", "closed", "compliance-console").status())
                        .as("%s must never be gated on identity", type)
                        .isEqualTo(RightsRequestStatus.FULFILLED);
            }
        });
    }

    @Test
    @DisplayName("a grievance is not gated either, and that is a risk judgement rather than a clause")
    void anUnverifiedGrievanceStillCloses() {
        // No text forbids gating a grievance and none supports leaving it open. What decides it is
        // that a grievance is the intake of a complaint, not the disclosure of the complainant's
        // file back to them — so the misdirected-disclosure risk that motivates gating ACCESS does
        // not arise, and gating s.13's escalation path would turn a control into a reason not to
        // answer the thing a principal escalates to the Board.
        RightsRequestStore.Request request =
                file(RightsRequestType.GRIEVANCE, Jurisdiction.IN, Instant.now());

        assertThat(rights.transition(request.requestId(), RightsRequestStatus.FULFILLED, "priya",
                "Grievance answered in writing", "compliance-console").status())
                .isEqualTo(RightsRequestStatus.FULFILLED);
    }

    @Test
    @DisplayName("the two 409s are told apart, so an operator does not fix the wrong thing")
    void theVerificationRefusalNamesVerification() {
        // Both gates answer 409 on the same call. A test asserting only the status would pass with
        // the two swapped, and an operator told "fulfilment is outstanding" when the real problem
        // is verification goes and configures a register that was never the issue.
        RightsRequestStore.Request request =
                file(RightsRequestType.ACCESS, Jurisdiction.IN, Instant.now());

        ResponseEntity<String> refused = rest.withBasicAuth("compliance-console", "admin-secret")
                .exchange("/v1/rights/" + request.requestId(), HttpMethod.PATCH,
                        new HttpEntity<>(Map.of("status", "FULFILLED",
                                "resolution", "summary sent to the principal")), String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody())
                .contains("Identity not verified")
                .contains("UNVERIFIED")
                .contains("/verification");
    }

    @Test
    @DisplayName("a recorded verification says what was checked, by whom, and when")
    void aVerificationIsRecordedWithItsActor() {
        RightsRequestStore.Request request =
                file(RightsRequestType.ACCESS, Jurisdiction.IN, Instant.now());
        int before = audit.recent(ENTITY, 200).size();
        // Truncated to seconds, which is what a console sends. It lands fractionally before the
        // request's own receivedAt, and that must not be refused — the operator verifying on the
        // call that raised the request is the case this route exists for.
        Instant checkedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        // Over HTTP with the header, not through the service with the name already resolved.
        // The property is that the human in X-UDS-Actor reaches the append-only row beside the
        // credential; calling recordVerification directly asserts only that a store persists a
        // string it was handed, which is the mechanism. Found by qa-verifier.
        org.springframework.http.HttpHeaders headers =
                new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("X-UDS-Actor", "arjun@uds.example");

        ResponseEntity<Map> recorded = rest.withBasicAuth("compliance-console", "admin-secret")
                .exchange("/v1/rights/" + request.requestId() + "/verification", HttpMethod.POST,
                        new HttpEntity<>(Map.of("method", "OPERATOR_ASSERTED",
                                "verifiedAt", checkedAt.toString(),
                                "detail", "call-back to the mobile already on file"), headers),
                        Map.class);

        assertThat(recorded.getStatusCode()).isEqualTo(HttpStatus.OK);

        RightsRequestStore.Request verified = store.find(request.requestId()).orElseThrow();
        assertThat(verified.verification())
                .isEqualTo(RightsVerificationMethod.OPERATOR_ASSERTED);
        assertThat(verified.verifiedAt()).isEqualTo(checkedAt);
        assertThat(verified.verificationDetail()).contains("call-back");

        // rules 5: the person, recorded apart from the credential. "Who established this identity"
        // cannot be answered by a password a team shares, so the two are asserted separately.
        assertThat(audit.recent(ENTITY, 200)).hasSizeGreaterThan(before)
                .anySatisfy(entry -> {
                    assertThat(entry.action()).isEqualTo("RIGHTS_REQUEST_VERIFIED");
                    assertThat(entry.targetId()).isEqualTo(request.requestId());
                    assertThat(entry.actorId()).isEqualTo("arjun@uds.example");
                    assertThat(entry.clientId()).isEqualTo("compliance-console");
                });
    }

    @Test
    @DisplayName("another entity's session cannot verify a request it does not own")
    void verificationIsEntityScoped() {
        // Layer one has nothing to read here: the route carries a request id and no entityId, in
        // the path or the query. So isolation on this path is layer two alone — rights_request is
        // inside V13's protected set and the update runs under the session claim.
        //
        // The first draft of this test called the route as `matrix-console` and asserted a
        // non-2xx. It passed the wrong way round: the application connects as
        // `uds_consent_owner` under test, and a table's owner is not subject to its policies, so
        // the write succeeded and proved the opposite of what the name claimed. That is the same
        // trap LedgerAppendOnlyIT fell into this phase — a refusal only means something as
        // `uds_consent_app` — and it is why this runs the store's own statement as that role.
        RightsRequestStore.Request request =
                file(RightsRequestType.ACCESS, Jurisdiction.IN, Instant.now());

        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                POSTGRES.getJdbcUrl(), "uds_consent_app", "uds_consent_app", true);
        dataSource.setDriverClassName(org.postgresql.Driver.class.getName());
        JdbcTemplate asMatrix = new JdbcTemplate(dataSource);
        asMatrix.queryForObject("select set_config('uds.entity_id', ?, false)", String.class,
                "MATRIX");

        int updated = asMatrix.update("""
                update rights_request
                   set verification_method = 'OPERATOR_ASSERTED',
                       verified_at = now(),
                       verification_detail = 'I say I checked'
                 where request_id = ?
                   and verification_method = 'UNVERIFIED'
                """, request.requestId());

        assertThat(updated)
                .as("a MATRIX session must not reach a DENAVE_IN request")
                .isZero();
        assertThat(store.find(request.requestId()).orElseThrow().verification())
                .isEqualTo(RightsVerificationMethod.UNVERIFIED);
    }

    @Test
    @DisplayName("verification is written once, and a second attempt leaves the first intact")
    void verificationIsNotOverwritten() {
        RightsRequestStore.Request request =
                file(RightsRequestType.ACCESS, Jurisdiction.IN, Instant.now());
        rights.recordVerification(request.requestId(), RightsVerificationMethod.OPERATOR_ASSERTED,
                null, "employee id checked at the desk", "arjun@uds.example");

        assertThatThrownBy(() -> rights.recordVerification(request.requestId(),
                RightsVerificationMethod.OPERATOR_ASSERTED, null, "something else entirely",
                "mallory@uds.example"))
                .isInstanceOf(RightsService.VerificationAlreadyRecordedException.class);

        // The property, not the exception. A refusal thrown after the write would leave the second
        // operator's words in the evidence plane and the first operator's gone.
        assertThat(store.find(request.requestId()).orElseThrow().verificationDetail())
                .isEqualTo("employee id checked at the desk");
    }

    @Test
    @DisplayName("an operator cannot assert the label the platform reserves for itself")
    void portalTokenCannotBeAsserted() {
        // PORTAL_TOKEN means a principal redeemed a token the platform minted and checked. Letting
        // an operator type it would put the platform's own strongest claim behind a human's
        // say-so, which is the difference between evidence and a label.
        RightsRequestStore.Request request =
                file(RightsRequestType.ACCESS, Jurisdiction.IN, Instant.now());

        assertThatThrownBy(() -> rights.recordVerification(request.requestId(),
                RightsVerificationMethod.PORTAL_TOKEN, null, "I checked", "arjun@uds.example"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PORTAL_TOKEN");

        assertThat(store.find(request.requestId()).orElseThrow().verification())
                .isEqualTo(RightsVerificationMethod.UNVERIFIED);
    }

    @Test
    @DisplayName("a verification instant outside the request's own life is refused")
    void theVerificationInstantIsBounded() {
        Instant received = Instant.now().minus(2, ChronoUnit.DAYS);
        RightsRequestStore.Request request =
                file(RightsRequestType.ACCESS, Jurisdiction.IN, received);

        // Before the request existed: the platform cannot have checked the identity behind
        // something it had not received.
        assertThatThrownBy(() -> rights.recordVerification(request.requestId(),
                RightsVerificationMethod.OPERATOR_ASSERTED, received.minus(1, ChronoUnit.HOURS),
                "checked", "arjun@uds.example"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before the request was received");

        // And in the future, beyond the shared skew window: a claim about a check that has not
        // happened yet.
        assertThatThrownBy(() -> rights.recordVerification(request.requestId(),
                RightsVerificationMethod.OPERATOR_ASSERTED, Instant.now().plus(1, ChronoUnit.HOURS),
                "checked", "arjun@uds.example"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(store.find(request.requestId()).orElseThrow().verification())
                .isEqualTo(RightsVerificationMethod.UNVERIFIED);
    }

    @Test
    @DisplayName("recording a verification needs a named person, not just a credential")
    void recordingAVerificationNeedsTheActorHeader() {
        RightsRequestStore.Request request =
                file(RightsRequestType.ACCESS, Jurisdiction.IN, Instant.now());

        org.springframework.http.HttpHeaders noActor = new org.springframework.http.HttpHeaders();
        noActor.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        noActor.set(IntegrationTestClient.SUPPRESS_ACTOR, "true");

        ResponseEntity<String> refused = rest.withBasicAuth("compliance-console", "admin-secret")
                .exchange("/v1/rights/" + request.requestId() + "/verification", HttpMethod.POST,
                        new HttpEntity<>(Map.of("method", "OPERATOR_ASSERTED",
                                "detail", "call-back to the number on file"), noActor),
                        String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("X-UDS-Actor");
        // Nothing written. A refusal after the write would record a check attributed to a password.
        assertThat(store.find(request.requestId()).orElseThrow().verification())
                .isEqualTo(RightsVerificationMethod.UNVERIFIED);
    }

    @Test
    @DisplayName("a verification instant before the request itself is refused at the wire, 400")
    void theVerificationInstantIsBoundedOverHttp() {
        RightsRequestStore.Request request =
                file(RightsRequestType.ACCESS, Jurisdiction.IN, Instant.now());

        // theVerificationInstantIsBounded covers the same rule at the service layer. Phase 18
        // recorded the HTTP half as a deviation and did not pay it: the 400 that the API contract
        // and TRACEABILITY both imply had never crossed the wire, so a change to the exception
        // mapping would have left the documented status wrong with every test still green.
        ResponseEntity<String> refused = rest.withBasicAuth("compliance-console", "admin-secret")
                .postForEntity("/v1/rights/" + request.requestId() + "/verification",
                        Map.of("method", "OPERATOR_ASSERTED",
                                "verifiedAt", request.receivedAt().minus(2, ChronoUnit.DAYS)
                                        .toString(),
                                "detail", "call-back to the number on file"),
                        String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("before the request was received");
        assertThat(store.find(request.requestId()).orElseThrow().verification())
                .isEqualTo(RightsVerificationMethod.UNVERIFIED);
    }

    // -------------------------------------------------------------------------------------------

    private RightsRequestStore.Request file(RightsRequestType type, Jurisdiction jurisdiction,
                                            Instant receivedAt) {
        return rights.intake(new RightsService.Intake(ENTITY, "it-rights-" + UUID.randomUUID(),
                null, null, type, jurisdiction, receivedAt, "filed by integration test",
                "compliance-console", RightsVerificationMethod.UNVERIFIED, null));
    }

    /**
     * Clears the verification gate the way an operator would.
     *
     * <p>Every request {@link #file} creates reads {@code UNVERIFIED}, which is correct and is the
     * state of every request open in production today. Since Phase 18 a disclosing or destructive
     * right cannot be recorded as {@code FULFILLED} in that state, so a test that closes one has to
     * say who was checked first — which is the point of the gate rather than an obstacle to it.
     */
    private void verify(RightsRequestStore.Request request) {
        rights.recordVerification(request.requestId(), RightsVerificationMethod.OPERATOR_ASSERTED,
                null, "call-back to the number already on file", "priya@uds.example");
    }

    /**
     * Files a request over HTTP so the refusal's status code is exercised rather than assumed.
     *
     * <p>Deliberately not routed through {@code rights.intake}: the thing under test is the
     * translation of the refusal into an RFC 7807 400 at the edge, which a direct service call
     * cannot see.
     */
    private ResponseEntity<String> fileOverHttp(Instant receivedAt) {
        Map<String, Object> body = Map.of(
                "entityId", ENTITY,
                "identifierType", "EMAIL",
                "identifierValue", "http-bound-" + UUID.randomUUID() + "@example.test",
                "type", "ACCESS",
                "jurisdiction", "IN",
                "receivedAt", receivedAt.toString(),
                "details", "filed to exercise the bound over the wire");

        return rest.withBasicAuth("compliance-console", "admin-secret")
                .exchange("/v1/rights", HttpMethod.POST, new HttpEntity<>(body), String.class);
    }

    /**
     * The audit row's detail as data.
     *
     * <p>Parsed rather than string-matched: the column is {@code jsonb} and PostgreSQL normalises
     * it on read, so asserting against the serialised form tests the database's formatting instead
     * of the fact the platform recorded.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, String> detail(AdminAuditStore.Entry entry) {
        return com.uds.consent.core.crypto.CanonicalJson.parse(entry.detailJson(), Map.class);
    }
}
