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

    // -------------------------------------------------------------------------------------------

    private RightsRequestStore.Request file(RightsRequestType type, Jurisdiction jurisdiction,
                                            Instant receivedAt) {
        return rights.intake(new RightsService.Intake(ENTITY, "it-rights-" + UUID.randomUUID(),
                null, null, type, jurisdiction, receivedAt, "filed by integration test",
                "compliance-console", RightsVerificationMethod.UNVERIFIED, null));
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
