package com.uds.consent.service.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The role boundary, over HTTP, on every route the platform serves.
 *
 * <p>{@code EntityIsolationIT} proves a Denave credential cannot reach a Matrix row. It says
 * nothing about what a <em>dialer</em> credential can reach, and that is the other half of the same
 * control: the decision API is called from Athena's dialer on every outbound attempt, so its
 * credential is the most widely distributed one the platform issues and the one most likely to end
 * up in a configuration file somebody can read. What it can do if stolen is therefore the question,
 * and until now nothing anywhere answered it.
 *
 * <p><strong>The sweep is the test.</strong> Written as a table over every route rather than as a
 * handful of representative cases, because the failure mode of this control is not a wrong
 * annotation — it is a route added later with no annotation at all, which inherits whatever the
 * class-level rule happens to be and is invisible in review. A test that checks three endpoints
 * would pass forever while the surface grew around it.
 *
 * <p><strong>The table is itself a hand-written list, which is the failure it exists to catch.</strong>
 * It missed {@code PATCH /v1/rights/{requestId}} for three releases — the only ADMIN write on the
 * statutory rights path — for the same reason {@code V13}'s policy array missed five tables: the
 * list lives somewhere other than the thing it covers. Deriving it from Spring's
 * {@code RequestMappingHandlerMapping} is possible and is not cheap: the mapping gives patterns
 * rather than callable paths, so every {@code {pathVariable}} would need a plausible value invented
 * for it, and a route the sweep could not call would have to be skipped — which is the same hole,
 * relocated and harder to see. It is therefore reviewed by hand, and the review is: <em>every new
 * route gets a row here in the same change that adds it.</em>
 *
 * <p><strong>Reading the assertions.</strong> A refusal is asserted exactly: 403. A permission is
 * asserted as "not 401 and not 403", because what is being tested here is the security layer and
 * not the handler behind it — a malformed body reaching a handler and coming back 400 has already
 * proved the point, and pinning the handler's own response would make this suite fail every time
 * somebody changed a validation message.
 */
class AdminApiIT extends PostgresIntegrationTest {

    private static final String CAPTURE = "CAPTURE";
    private static final String DECISION = "DECISION";
    private static final String ADMIN = "ADMIN";

    private static final Map<String, String[]> CREDENTIALS = Map.of(
            CAPTURE, new String[]{"denave-web", "capture-secret"},
            DECISION, new String[]{"athena-dialer", "decision-secret"},
            ADMIN, new String[]{"compliance-console", "admin-secret"});

    @Autowired
    private TestRestTemplate rest;

    /**
     * One route, the roles that may reach it, and whether it is safe to call.
     *
     * @param exercisePositive whether the allowed roles are actually called. False for the three
     *                         sweeps, which do real work against a database shared with every other
     *                         suite in the module — a sweep firing here would be a cross-suite
     *                         failure appearing at random, which is the specific thing the
     *                         integration-test profile disables the schedulers to avoid. Their
     *                         refusal direction is still asserted, and that is the direction with
     *                         the security consequence.
     */
    private record Route(HttpMethod method, String path, Set<String> allowed,
                         boolean exercisePositive, String body) {

        Route(HttpMethod method, String path, Set<String> allowed, boolean exercisePositive) {
            this(method, path, allowed, exercisePositive, "{}");
        }

        /**
         * The same, with a body that passes bean validation.
         *
         * <p>Needed because {@code @Valid} on a {@code @RequestBody} is resolved <em>before</em>
         * method security runs, so a route whose DTO has {@code @NotNull} or {@code @NotBlank}
         * answers 400 to an unauthorised caller and the role check is never reached. The sweep's
         * "an empty object reaches the handler and comes back 400" holds only where the body is
         * permissive; here it measured the validator instead of the boundary. Found when
         * {@code POST /v1/rights/…/verification} joined the table and reported CAPTURE as able to
         * reach it — it cannot; it was getting a 400 for sending {@code {}}.
         */
        static Route postWithBody(String path, String body, String... allowed) {
            return new Route(HttpMethod.POST, path, Set.of(allowed), true, body);
        }

        static Route get(String path, String... allowed) {
            return new Route(HttpMethod.GET, path, Set.of(allowed), true);
        }

        static Route post(String path, String... allowed) {
            return new Route(HttpMethod.POST, path, Set.of(allowed), true);
        }

        static Route put(String path, String... allowed) {
            return new Route(HttpMethod.PUT, path, Set.of(allowed), true);
        }

        static Route patch(String path, String... allowed) {
            return new Route(HttpMethod.PATCH, path, Set.of(allowed), true);
        }

        /** A route whose positive direction is not exercised. See {@link #exercisePositive}. */
        static Route sweep(String path, String... allowed) {
            return new Route(HttpMethod.POST, path, Set.of(allowed), false);
        }

        /** The same, for a PUT. */
        static Route putSweep(String path, String... allowed) {
            return new Route(HttpMethod.PUT, path, Set.of(allowed), false);
        }
    }

    private static final String E = "?entityId=DENAVE_IN";

    /**
     * Every authenticated route the platform exposes, with the roles the annotations grant.
     *
     * <p>Public routes — {@code GET /v1/notices/{id}}, {@code GET /v1/keys}, the actuator — are
     * absent by design and asserted separately below. Adding them here would mean asserting that a
     * role cannot reach something anyone can reach, which is not a boundary.
     */
    private static final List<Route> ROUTES = List.of(
            // --- AdminController: class-level hasRole('ADMIN') -----------------------------------
            Route.get("/v1/admin/purposes", ADMIN),
            Route.post("/v1/admin/purposes/refresh", ADMIN),
            Route.get("/v1/admin/applications" + E, ADMIN),
            Route.put("/v1/admin/applications/DENAVE_WEB", ADMIN),
            Route.get("/v1/admin/entities", ADMIN),
            Route.get("/v1/admin/blast-radius/purpose/MKT_OUTBOUND_CALL", ADMIN),
            Route.get("/v1/admin/blast-radius/notice/NOTICE_DENAVE_B2B", ADMIN),
            Route.post("/v1/admin/consent/invalidate", ADMIN),
            Route.get("/v1/admin/integrity/DENAVE_IN/api-it-nobody", ADMIN),
            Route.sweep("/v1/admin/integrity/sweep", ADMIN),
            Route.get("/v1/admin/integrity/last", ADMIN),
            Route.get("/v1/admin/provenance/quarantined" + E, ADMIN),
            Route.get("/v1/admin/provenance/summary" + E, ADMIN),
            Route.post("/v1/admin/provenance/00000000-0000-0000-0000-000000000000/substantiate",
                    ADMIN),
            Route.get("/v1/admin/ropa/DENAVE_IN", ADMIN),
            Route.post("/v1/admin/ropa/DENAVE_IN/export", ADMIN),
            Route.get("/v1/admin/processing-activities" + E, ADMIN),
            Route.post("/v1/admin/processing-activities", ADMIN),
            Route.put("/v1/admin/processing-activities/1", ADMIN),
            Route.get("/v1/admin/vendors" + E, ADMIN),
            Route.put("/v1/admin/vendors/api-it-vendor", ADMIN),
            Route.get("/v1/admin/enforcement/denials" + E, ADMIN),
            Route.get("/v1/admin/enforcement/scrub-runs" + E, ADMIN),
            Route.get("/v1/admin/enforcement/health", ADMIN),
            Route.get("/v1/admin/dlt/registrations" + E, ADMIN),
            Route.get("/v1/admin/dlt/headers" + E, ADMIN),
            Route.put("/v1/admin/dlt/headers/api-it-header", ADMIN),
            Route.put("/v1/admin/dlt/templates/api-it-template", ADMIN),
            Route.get("/v1/admin/retention/open" + E, ADMIN),
            Route.post("/v1/admin/retention/1/complete", ADMIN),
            Route.sweep("/v1/admin/retention/sweep", ADMIN),
            Route.get("/v1/admin/audit" + E, ADMIN),
            // Korea, Art. 62-3. The two write directions run the negative sweep only — the
            // positive direction needs a queue row this suite has no business creating, and
            // ReconfirmationIT drives it against one it raised itself.
            // DPDP Rule 13. The register reads for any entity, notified or not — a non-SDF gets
            // an empty one rather than a 404, which is why the positive direction is exercised.
            Route.get("/v1/admin/sdf/DENAVE_IN", ADMIN),
            Route.get("/v1/admin/sdf/DENAVE_IN/systems", ADMIN),
            Route.sweep("/v1/admin/sdf/DENAVE_IN/raise", ADMIN),
            Route.putSweep("/v1/admin/sdf/DENAVE_IN/systems", ADMIN),
            Route.sweep("/v1/admin/sdf/obligations/1/complete", ADMIN),
            Route.sweep("/v1/admin/sdf/obligations/1/reported", ADMIN),
            Route.get("/v1/admin/reconfirmation/due" + E, ADMIN),
            Route.sweep("/v1/admin/reconfirmation/1/sent", ADMIN),
            Route.sweep("/v1/admin/reconfirmation/1/completed", ADMIN),
            Route.sweep("/v1/admin/reconfirmation/sweep", ADMIN),
            Route.get("/v1/admin/consent-managers", ADMIN),
            // The three write directions on the register. The negative sweep runs on all of them —
            // that is the whole point of listing them here rather than only in ConsentManagerIT —
            // but the positive direction is exercised only on the reconciliation mark, because the
            // other two mutate the register the rest of this suite authenticates against.
            Route.sweep("/v1/admin/consent-managers", ADMIN),
            Route.putSweep("/v1/admin/consent-managers/CM-TEST-0001/status", ADMIN),
            Route.post("/v1/admin/consent-managers/CM-TEST-0001/reconciled", ADMIN),

            // --- Propagation register (V31). Added late to this table, which is the failure the
            // --- class javadoc above predicts: three routes shipped in Phase 17 and the sweep that
            // --- exists to catch a route with no annotation did not know they existed.
            Route.get("/v1/admin/propagation/targets" + E, ADMIN),
            Route.get("/v1/admin/propagation/gaps" + E, ADMIN),
            // The PUT's positive direction is exercised by PropagationAdminIT, which can assert the
            // audit row and the resolved subscription as well as the boundary. Here it would write
            // a register row into every other suite's entity.
            Route.putSweep("/v1/admin/propagation/targets", ADMIN),

            // --- BreachController: class-level hasRole('ADMIN') ----------------------------------
            Route.post("/v1/admin/breaches", ADMIN),
            Route.get("/v1/admin/breaches" + E, ADMIN),
            Route.get("/v1/admin/breaches/api-it-nobody", ADMIN),
            Route.get("/v1/admin/breaches/api-it-nobody/affected", ADMIN),
            Route.post("/v1/admin/breaches/api-it-nobody/assess", ADMIN),
            Route.post("/v1/admin/breaches/api-it-nobody/notifications/api-it-none", ADMIN),
            Route.post("/v1/admin/breaches/api-it-nobody/close", ADMIN),
            Route.sweep("/v1/admin/breaches/sla/sweep", ADMIN),

            // --- PublishingController: the most consequential writes the platform accepts --------
            Route.post("/v1/admin/notices/NOTICE_DENAVE_B2B/versions", ADMIN),
            Route.post("/v1/admin/notices/NOTICE_DENAVE_B2B/versions/1/translations", ADMIN),
            Route.post("/v1/admin/purposes/MKT_OUTBOUND_CALL/versions", ADMIN),

            // --- NoticeController: the drafting surface, not the served notice -------------------
            Route.get("/v1/notices/NOTICE_DENAVE_B2B/versions", ADMIN),
            Route.get("/v1/notices/NOTICE_DENAVE_B2B/versions/1", ADMIN),
            Route.get("/v1/notices/reports/coverage", ADMIN),

            // --- DecisionController -------------------------------------------------------------
            Route.post("/v1/evaluate", DECISION, CAPTURE, ADMIN),
            Route.post("/v1/evaluate/batch", DECISION, ADMIN),

            // --- ConsentController --------------------------------------------------------------
            Route.post("/v1/consent", CAPTURE, ADMIN),
            Route.post("/v1/consent/withdraw", CAPTURE, ADMIN),
            Route.post("/v1/consent/notice-served", CAPTURE, ADMIN),
            Route.get("/v1/consent/DENAVE_IN/api-it-nobody", CAPTURE, DECISION, ADMIN),
            Route.get("/v1/consent/DENAVE_IN/api-it-nobody/history", ADMIN),
            Route.get("/v1/consent/DENAVE_IN/api-it-nobody/receipt", CAPTURE, ADMIN),

            // --- ReceiptController --------------------------------------------------------------
            Route.get("/v1/receipts/api-it-nobody", CAPTURE, ADMIN),
            Route.get("/v1/receipts/api-it-nobody/verification", CAPTURE, ADMIN),
            Route.get("/v1/receipts" + E + "&subjectId=api-it-nobody", CAPTURE, ADMIN),

            // --- ProvenanceController -----------------------------------------------------------
            Route.post("/v1/provenance", CAPTURE, ADMIN),
            Route.post("/v1/provenance/bulk", ADMIN),
            Route.get("/v1/provenance/DENAVE_IN/api-it-nobody", DECISION, CAPTURE, ADMIN),
            Route.get("/v1/provenance/summary" + E, ADMIN),

            // --- SuppressionController ----------------------------------------------------------
            Route.post("/v1/suppression/opt-out", CAPTURE, ADMIN),
            Route.post("/v1/suppression/scrub", DECISION, ADMIN),
            Route.post("/v1/suppression/universal-opt-out", CAPTURE, ADMIN),
            Route.post("/v1/suppression/registry", ADMIN),

            // --- RightsController ---------------------------------------------------------------
            Route.post("/v1/rights", CAPTURE, ADMIN),
            Route.get("/v1/rights/api-it-nobody", CAPTURE, ADMIN),
            Route.post("/v1/rights/api-it-nobody/acknowledge", CAPTURE, ADMIN),
            Route.get("/v1/rights/subject/DENAVE_IN/api-it-nobody", ADMIN),
            Route.get("/v1/rights/queue" + E, ADMIN),
            Route.get("/v1/rights/overdue", ADMIN),
            Route.get("/v1/rights/summary" + E, ADMIN),
            // The only ADMIN write on the statutory rights path, and it was missing from this
            // table — which is this sweep's own failure mode, arriving on the sweep. It was the
            // only route in the tree with no test at all, and no test anywhere issued a PATCH, so
            // the verb itself had never been exercised against the security filter chain.
            Route.patch("/v1/rights/api-it-nobody", ADMIN),
            // Phase 18's two writes on the same path. The verification route is the one that
            // matters most here: nothing else asserts that `denave-web` — ROLE_CAPTURE, the most
            // widely distributed credential in the group — cannot write "identity verified" onto
            // somebody's ACCESS request and thereby clear the gate that exists to stop exactly
            // that. Found missing by qa-verifier, which is this table's own failure mode again.
            Route.postWithBody("/v1/rights/api-it-nobody/verification",
                    "{\"method\":\"OPERATOR_ASSERTED\",\"detail\":\"sweep\"}", ADMIN),
            Route.postWithBody("/v1/rights/api-it-nobody/fulfilment",
                    "{\"systemCode\":\"SWEEP\",\"actionType\":\"ATTESTED\","
                            + "\"status\":\"COMPLETED\",\"evidenceRef\":\"sweep\"}", ADMIN),
            Route.get("/v1/rights/api-it-nobody/fulfilment", ADMIN),

            // --- SnapshotController -------------------------------------------------------------
            Route.get("/v1/snapshot/DENAVE_IN/api-it-nobody", DECISION, CAPTURE, ADMIN),
            Route.get("/v1/snapshot/purposes", DECISION, CAPTURE, ADMIN));

    @Test
    @DisplayName("every route refuses every role its annotation does not name")
    void theBoundaryHoldsOnEveryRoute() {
        for (Route route : ROUTES) {
            for (String role : List.of(CAPTURE, DECISION, ADMIN)) {
                if (route.allowed().contains(role)) {
                    continue;
                }
                assertThat(call(role, route).getStatusCode())
                        .withFailMessage("%s %s was reachable by %s", route.method(), route.path(),
                                role)
                        .isEqualTo(HttpStatus.FORBIDDEN);
            }
        }
    }

    @Test
    @DisplayName("every route admits every role its annotation does name")
    void theBoundaryIsNotSimplyClosed() {
        // The half that is easy to leave out, and the half that decides whether the control
        // survives contact with an operator. A rule that refuses everyone passes the test above and
        // is worse than no rule, because the first person to hit it at three in the morning will
        // widen it to permitAll rather than work out which annotation was wrong.
        for (Route route : ROUTES) {
            if (!route.exercisePositive()) {
                continue;
            }
            for (String role : route.allowed()) {
                HttpStatusCode status = call(role, route).getStatusCode();

                assertThat(status)
                        .withFailMessage("%s %s refused %s, which its annotation grants (status %s)",
                                route.method(), route.path(), role, status)
                        .isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
            }
        }
    }

    @Test
    @DisplayName("a dialer credential reaches the decision path and the scrub, and nothing else")
    void theDecisionCredentialIsNarrow() {
        // Stated separately from the sweep because it is the claim that matters if this credential
        // leaks, and it should be findable by that question rather than by reading a table. The
        // dialer's credential sits in Athena's configuration on every host that places a call.
        Set<String> reachableByDecision = ROUTES.stream()
                .filter(route -> route.allowed().contains(DECISION))
                .map(route -> route.method() + " " + route.path())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(reachableByDecision).containsExactlyInAnyOrder(
                "POST /v1/evaluate",
                "POST /v1/evaluate/batch",
                "POST /v1/suppression/scrub",
                "GET /v1/consent/DENAVE_IN/api-it-nobody",
                "GET /v1/provenance/DENAVE_IN/api-it-nobody",
                "GET /v1/snapshot/DENAVE_IN/api-it-nobody",
                "GET /v1/snapshot/purposes");

        // And it writes nothing. Every route it reaches either asks a question or scrubs a list
        // against the answer — none of them appends to the ledger, which is the property that makes
        // a stolen dialer credential an information problem rather than an evidence problem.
        assertThat(call(DECISION, Route.post("/v1/consent", CAPTURE, ADMIN)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(DECISION, Route.post("/v1/consent/withdraw", CAPTURE, ADMIN))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a capture credential cannot read the audit trail or a subject's history")
    void theCaptureCredentialCannotRead() {
        // A capture credential belongs to a web form. It has to be able to write a consent and it
        // has no business reading who else consented, which administrator changed what, or one
        // person's whole event history — the three reads that turn a compromised landing page into
        // a disclosure.
        for (String path : List.of(
                "/v1/admin/audit" + E,
                "/v1/consent/DENAVE_IN/api-it-nobody/history",
                "/v1/admin/enforcement/denials" + E,
                "/v1/rights/subject/DENAVE_IN/api-it-nobody")) {
            assertThat(rest.withBasicAuth("denave-web", "capture-secret")
                    .getForEntity(path, String.class).getStatusCode())
                    .withFailMessage("%s was readable by a capture credential", path)
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    @DisplayName("nothing below ADMIN publishes a notice version")
    void publishingIsAdminOnly() {
        // The single most consequential write the platform accepts. A notice version is what every
        // consent captured afterwards is evidenced against, so publishing one with the wrong text
        // does not corrupt a row — it invalidates the basis of every consent taken under it, and
        // the ledger will faithfully record that the wrong thing was shown.
        for (String role : List.of(CAPTURE, DECISION)) {
            assertThat(call(role, Route.post("/v1/admin/notices/NOTICE_DENAVE_B2B/versions", ADMIN))
                    .getStatusCode())
                    .withFailMessage("%s could publish a notice version", role)
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    @DisplayName("an unauthenticated caller reaches the public routes and nothing else")
    void anonymousIsNotAdmin() {
        assertThat(rest.getForEntity("/v1/notices/NOTICE_DENAVE_B2B?jurisdiction=IN&lang=en",
                String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/v1/keys", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        for (String path : List.of("/v1/admin/audit" + E, "/v1/admin/purposes", "/v1/rights/queue" + E)) {
            assertThat(rest.getForEntity(path, String.class).getStatusCode())
                    .withFailMessage("%s was reachable unauthenticated", path)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    @DisplayName("a malformed write is a 400 naming the field, not a 500 naming a constraint")
    void validationFailuresAreLegible() {
        // What an integrator sees when they get it wrong is part of the interface. A 500 carrying a
        // database constraint name tells them nothing they can act on and tells them something
        // about the schema they should not have — and it puts a genuine client error in the alert
        // stream, where it competes with real ones.
        ResponseEntity<String> response = rest.withBasicAuth("compliance-console", "admin-secret")
                .postForEntity("/v1/consent", Map.of("entityId", "DENAVE_IN"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody())
                .withFailMessage("the 400 did not name the fields at fault: %s", response.getBody())
                .contains("Request validation failed")
                .contains("captureMethod")
                .contains("jurisdiction")
                .contains("choices");
        assertThat(response.getBody()).doesNotContain("SQLException", "Exception", "constraint");

        // And the same for a publish, where the body is larger and getting it wrong is likelier.
        ResponseEntity<String> publish = rest.withBasicAuth("compliance-console", "admin-secret")
                .postForEntity("/v1/admin/notices/NOTICE_DENAVE_B2B/versions",
                        Map.of("languageTag", "en"), String.class);

        assertThat(publish.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(publish.getBody()).doesNotContain("SQLException", "constraint");
    }

    /** Calls a route as a role, with an empty JSON body for the methods that take one. */
    private ResponseEntity<String> call(String role, Route route) {
        String[] credential = CREDENTIALS.get(role);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // "{}" rather than a valid body throughout. The point of the sweep is which layer answers,
        // and an empty object reaches the handler and comes back 400 — which proves the security
        // filter passed it without any route needing a fixture that would rot the moment a DTO
        // changed.
        HttpEntity<String> request = route.method() == HttpMethod.GET
                ? new HttpEntity<>(headers)
                : new HttpEntity<>(route.body(), headers);

        return rest.withBasicAuth(credential[0], credential[1])
                .exchange(route.path(), route.method(), request, String.class);
    }
}
