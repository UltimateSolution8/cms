package com.uds.consent.service.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code FULFILLED} now has to be evidenced.
 *
 * <p>The largest compliance exposure in the platform, and the plainest. {@code RightsService}
 * let an operator move a request to {@code FULFILLED} with a sentence of resolution text. Nothing
 * in this platform erases, exports or corrects anything in DenCRM, the HRMS or the BGV workflow —
 * so a closure by somebody who had done the work and a closure by somebody who had not were
 * indistinguishable on the record, and because the audit trail is append-only, permanently so.
 *
 * <p>Intake and a clock are the easy half of DPDP ss.11–13. The platform could prove a request
 * arrived and was closed inside the statutory period; it could not prove anything was done, and a
 * Board asking "what did you actually erase" got prose.
 *
 * <p><strong>What this is not.</strong> No connector here erases anything. We have no access to
 * those systems, and a stub written against a system nobody on this side can call would be worse
 * than none, because it would look like fulfilment. What it converts is "an operator asserted" into
 * "an operator asserted, against a named system, with a reference a reviewer can follow" — and,
 * crucially, makes the systems that were *left out* enumerable rather than invisible.
 */
class RightsFulfilmentIT extends PostgresIntegrationTest {

    /**
     * A different fiduciary per test, because the register is per entity and outlives a test.
     *
     * <p>`fulfilment_target` is configuration, so it persists across a shared container — and a
     * mandatory ERASURE target added by one test blocks every ERASURE closure in every test after
     * it. Giving each test its own entity is not tidiness: without it, "an empty register blocks
     * nothing" would pass or fail depending on execution order, which is the kind of test that gets
     * deleted six months from now for being flaky when it was telling the truth all along.
     *
     * <p>These are real seeded group companies rather than fixtures, which also means the suite
     * exercises the per-entity scoping it depends on.
     */
    private static final String NO_REGISTER = "DENAVE_SG";
    private static final String BLOCKING = "DENAVE_UK";
    private static final String RETRY = "DENAVE_MY";
    private static final String OPTIONAL = "DENAVE_SG2";
    private static final String EVIDENCED = "ATHENA";

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("with no register configured, nothing is blocked — and that is the state today")
    void anEmptyRegisterBlocksNothing() {
        // Deliberate, and asserted so nobody "fixes" it into a hard refusal. A platform that
        // blocked every closure until somebody filled in a table would get the table filled with
        // placeholder rows on the first busy afternoon, and the control would be worth less than
        // nothing because it would look real.
        //
        // The register is a statement by UDS about which of its systems hold a principal's data.
        // Until it is made, the platform cannot know it and will not invent it — which is exactly
        // why the scope statement in REGULATORY_HANDOFF §8.5 needs a signature rather than code.
        String requestId = raiseRequest(NO_REGISTER, "ACCESS");

        assertThat(close(requestId).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a mandatory system that has not acted blocks the closure, and is named")
    void aMandatoryTargetBlocksClosure() {
        String system = "DENCRM-" + UUID.randomUUID();
        configureTarget(BLOCKING, "ERASURE", system, true);

        String requestId = raiseRequest(BLOCKING, "ERASURE");
        ResponseEntity<String> refused = close(requestId);

        // 409, not 400. Nothing about the request is malformed and the operator is not wrong to be
        // trying — the state of the world is simply not yet what the closure would assert. A 400
        // says "fix your call"; this says "finish the work", and on a statutory clock the
        // difference is not pedantry.
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody())
                .withFailMessage("the refusal does not name the system that has not acted, so the "
                        + "operator's next question — which one — needs another call to answer")
                .contains(system);
    }

    @Test
    @DisplayName("a failed attempt is recorded and does not satisfy the gate")
    void aFailedActionDoesNotCount() {
        // The sharpest case. Recording a failure is worth doing — it shows somebody tried, and when
        // — and it is precisely not evidence that anything was erased. A gate that counted any
        // recorded action would let a request be closed on the strength of a failed deletion job,
        // which is the exact outcome this table exists to make impossible.
        String system = "HRMS-" + UUID.randomUUID();
        configureTarget(RETRY, "ERASURE", system, true);
        String requestId = raiseRequest(RETRY, "ERASURE");

        recordAction(requestId, system, "ERASED", "FAILED", "JIRA-1234 — deletion job errored");

        assertThat(close(requestId).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        recordAction(requestId, system, "ERASED", "COMPLETED", "JIRA-1234 — rerun, 3 rows removed");

        assertThat(close(requestId).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a non-mandatory system is recorded and does not block")
    void anOptionalTargetIsRecordedNotEnforced() {
        // A reporting warehouse that refreshes nightly is worth listing and not worth holding a
        // statutory deadline open for. Making every target mandatory would push operators towards
        // recording actions that did not happen, which is how an evidence table becomes fiction.
        String system = "WAREHOUSE-" + UUID.randomUUID();
        configureTarget(OPTIONAL, "ERASURE", system, false);

        assertThat(close(raiseRequest(OPTIONAL, "ERASURE")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("the attestation is immutable and carries a reference to somewhere else")
    void theAttestationIsEvidence() {
        String system = "BGV-" + UUID.randomUUID();
        configureTarget(EVIDENCED, "ERASURE", system, true);
        String requestId = raiseRequest(EVIDENCED, "ERASURE");
        recordAction(requestId, system, "ERASED", "COMPLETED", "BGV-TICKET-8891");

        String actions = admin().getForEntity("/v1/rights/" + requestId + "/fulfilment",
                String.class).getBody();

        // The reference is what makes this evidence rather than a second assertion. "We erased it"
        // with nothing behind it is the same unevidenced claim the resolution field already was,
        // and the whole point of the endpoint is that it stops being sufficient.
        assertThat(actions).contains("BGV-TICKET-8891").contains(system);

        // And who said so. rights_fulfilment_action has UPDATE and DELETE revoked from the
        // application role, so an attestation cannot be withdrawn after the request closed — which
        // would let a record be tidied into looking clean, the exact shape of problem this exists
        // to prevent.
        assertThat(actions).contains(IntegrationTestClient.TEST_ACTOR);
    }

    // -----------------------------------------------------------------------------------

    private void configureTarget(String entityId, String requestType, String systemCode,
                                 boolean mandatory) {
        assertThat(admin().exchange("/v1/admin/fulfilment-targets", HttpMethod.PUT,
                new HttpEntity<>(Map.of(
                        "entityId", entityId,
                        "requestType", requestType,
                        "systemCode", systemCode,
                        "mandatory", mandatory,
                        "active", true,
                        "description", "fulfilment suite fixture")), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String raiseRequest(String entityId, String type) {
        ResponseEntity<Map> response = rest.withBasicAuth("denave-web", "capture-secret")
                .postForEntity("/v1/rights", Map.of(
                        "entityId", entityId,
                        "type", type,
                        "jurisdiction", "IN",
                        "identifierType", "EMAIL",
                        "identifierValue", "fulfilment-" + UUID.randomUUID() + "@example.test",
                        "details", "fulfilment suite"), Map.class);

        assertThat(response.getStatusCode())
                .withFailMessage("rights intake fixture failed: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return (String) response.getBody().get("requestId");
    }

    private void recordAction(String requestId, String systemCode, String actionType,
                              String status, String evidenceRef) {
        assertThat(admin().postForEntity("/v1/rights/" + requestId + "/fulfilment", Map.of(
                        "systemCode", systemCode,
                        "actionType", actionType,
                        "status", status,
                        "evidenceRef", evidenceRef), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private ResponseEntity<String> close(String requestId) {
        return admin().exchange("/v1/rights/" + requestId, HttpMethod.PATCH,
                new HttpEntity<>(Map.of(
                        "status", "FULFILLED",
                        "resolution", "handled under the manual SOP")), String.class);
    }

    private TestRestTemplate admin() {
        return rest.withBasicAuth("compliance-console", "admin-secret");
    }
}
