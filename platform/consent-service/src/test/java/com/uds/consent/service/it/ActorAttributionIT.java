package com.uds.consent.service.it;

import com.uds.consent.ledger.store.AdminAuditStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audit trail names a person, and refuses the action when it cannot.
 *
 * <p>`admin_audit_event` recorded one identifier and it was the API client. `compliance-console` is
 * a single credential held by a compliance team, so a row saying it retired a purpose, invalidated
 * a consent, or assembled an evidence bundle for a named data principal identifies a service
 * account and nobody else. Because the table is append-only, that ambiguity could never afterwards
 * be corrected — the record is permanently and unfixably vague about the one question an audit
 * trail exists to answer.
 *
 * <p>Two facts now, in two columns. `client_id` is the credential, which the platform verifies.
 * `actor_id` is the human, which the caller asserts in `X-UDS-Actor` and without which a mutation
 * is refused.
 *
 * <p><strong>What this is and is not worth, asserted rather than assumed.</strong> A header the
 * console sets is weaker than a signed OIDC claim, and the tests below do not pretend otherwise —
 * they prove the header is required, is recorded, is bounded, and cannot be used to forge a log
 * line. They do not prove it is authentic, because it is not: it is trustworthy exactly as far as
 * the console is.
 *
 * <p>That path still exists and is still the one every current integrator uses, which is why this
 * suite still runs. {@code JwtAuthenticationIT} covers the stronger one that now sits beside it:
 * under a bearer token the human comes from a claim the provider signed and this header is ignored
 * outright. Both suites assert the same two columns, because the schema did not change — only the
 * question of who is entitled to fill them.
 */
class ActorAttributionIT extends PostgresIntegrationTest {

    /**
     * Suppresses the base class's actor header for one request.
     *
     * <p>The interceptor on the shared template behaves like a correctly-built console and always
     * sends an actor, which is what lets ten unrelated suites stay about what they are about. This
     * suite has to be able to act like a caller that does not, and it cannot remove an interceptor
     * it shares with every other test in the same context — so it says so instead.
     */
    private static final String SUPPRESS = IntegrationTestClient.SUPPRESS_ACTOR;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AdminAuditStore audit;

    @Test
    @DisplayName("an administrative change without an actor is refused, and says which header")
    void mutationsRequireAnActor() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(SUPPRESS, "true");

        ResponseEntity<String> response = admin().exchange(
                "/v1/admin/applications/actor-test-" + UUID.randomUUID(), HttpMethod.PUT,
                new HttpEntity<>(application(), headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .withFailMessage("the refusal does not name the header, so an integrator meeting "
                        + "it has to guess or read the source")
                .contains("X-UDS-Actor");
    }

    @Test
    @DisplayName("the audit row records the human and the credential as separate facts")
    void bothIdentitiesAreRecorded() {
        String applicationId = "actor-test-" + UUID.randomUUID();
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-UDS-Actor", "priya.sharma@uds.example");

        assertThat(admin().exchange("/v1/admin/applications/" + applicationId, HttpMethod.PUT,
                new HttpEntity<>(application(), headers), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        AdminAuditStore.Entry entry = latestFor(applicationId);

        // The point of the whole exercise. Before this, actorId held "compliance-console" and
        // there was nowhere to put the person — so the row could say a registration happened and
        // not who authorised it.
        assertThat(entry.actorId()).isEqualTo("priya.sharma@uds.example");
        assertThat(entry.clientId()).isEqualTo("compliance-console");
    }

    @Test
    @DisplayName("an OPERATOR_ASSERTED filing must name the operator, and is refused when it does not")
    void anAssertedVerificationNamesAPerson() {
        // Rules §5: a credential is not a person. OPERATOR_ASSERTED says somebody established
        // identity, and POST /v1/rights recorded authentication.getName() for it — one password
        // held by a compliance team, so the assurance was attributable to fifteen people at once
        // while three artefacts, including V30's own column comment, claimed it named somebody.
        //
        // Fixed in the code rather than in the prose, because the prose is right about what the
        // field should mean and correcting a schema comment would have spent a migration on it.
        HttpHeaders noActor = new HttpHeaders();
        noActor.add(SUPPRESS, "true");

        ResponseEntity<String> refused = admin().exchange("/v1/rights", HttpMethod.POST,
                new HttpEntity<>(rightsFiling("Called the mobile on file and confirmed the PAN"),
                        noActor), String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("X-UDS-Actor");
    }

    @Test
    @DisplayName("a machine filing no assertion is still accepted without an actor")
    void anUnassertedFilingIsUnaffected() {
        // The other half, and the one that would be easy to break. A dialer or a web form filing a
        // rights request is a system, not a person, and it asserts nothing about identity — so the
        // header stays deliberately unrequired there, exactly as rules §5 says for machine routes.
        // Requiring it would let any capture surface write a human name into evidence about a
        // check no human made, which is the failure the split exists to avoid.
        HttpHeaders noActor = new HttpHeaders();
        noActor.add(SUPPRESS, "true");

        Map<String, Object> body = rightsFiling(null);

        assertThat(admin().exchange("/v1/rights", HttpMethod.POST,
                new HttpEntity<>(body, noActor), String.class).getStatusCode())
                .withFailMessage("requiring the header on an unasserted filing would break every "
                        + "machine caller and claim a person behind a system's act")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("the operator who asserted a verification is the name in the audit trail")
    void theAssertingOperatorIsRecorded() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-UDS-Actor", "arjun.mehta@uds.example");

        ResponseEntity<Map> filed = admin().exchange("/v1/rights", HttpMethod.POST,
                new HttpEntity<>(rightsFiling("Employee ID checked at the service desk"), headers),
                Map.class);

        assertThat(filed.getStatusCode()).isEqualTo(HttpStatus.OK);
        String requestId = (String) filed.getBody().get("requestId");

        AdminAuditStore.Entry entry = latestFor(requestId);
        assertThat(entry.actorId())
                .withFailMessage("the assurance was attributed to the credential, so the audit "
                        + "trail cannot say which person made the claim it records")
                .isEqualTo("arjun.mehta@uds.example");
        assertThat(entry.clientId()).isEqualTo("compliance-console");
        assertThat(entry.detailJson()).contains("OPERATOR_ASSERTED");
    }

    @Test
    @DisplayName("an over-long actor is truncated rather than written whole into evidence")
    void theAssertedActorIsBounded() {
        // The value lands in an append-only table, so an unbounded header is a way to put a
        // megabyte of anything into evidence that can never afterwards be deleted.
        //
        // The other half of the same defence — stripping control characters, so a header carrying
        // a newline and a plausible timestamp cannot forge lines in the log an incident is
        // reconstructed from — cannot be exercised from here: the JDK HTTP client refuses to send
        // such a header at all, which is a second useful barrier and a useless test. It is
        // asserted directly in ActorTest instead.
        String applicationId = "actor-test-" + UUID.randomUUID();
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-UDS-Actor", "mallory" + "x".repeat(500));

        assertThat(admin().exchange("/v1/admin/applications/" + applicationId, HttpMethod.PUT,
                new HttpEntity<>(application(), headers), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        AdminAuditStore.Entry entry = latestFor(applicationId);

        assertThat(entry.actorId().length()).isLessThanOrEqualTo(128);
        assertThat(entry.actorId()).startsWith("mallory");
    }

    @Test
    @DisplayName("reads still work without an actor — the requirement is on changes")
    void readsDoNotRequireAnActor() {
        // Deliberate, and worth an assertion so nobody tightens it later without noticing the
        // cost. A query attributed to the credential that ran it is accurate, and demanding the
        // header on every GET would break every integration for no attribution gained.
        HttpHeaders headers = new HttpHeaders();
        headers.add(SUPPRESS, "true");

        assertThat(admin().exchange("/v1/admin/purposes", HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /**
     * A minimal valid application registration; the body is not what these tests are about.
     *
     * <p>The name is unique per call because {@code uq_application} is
     * {@code (entity_id, name, platform, environment)} — a second fixture with the same name comes
     * back 409 and looks, very convincingly, like an attribution failure.
     */
    private static Map<String, Object> application() {
        return Map.of(
                "entityId", "DENAVE_IN",
                "name", "Attribution fixture " + UUID.randomUUID(),
                "platform", "WEB",
                "environment", "TEST",
                "active", true);
    }

    /**
     * A rights filing, optionally carrying an operator's verification claim.
     *
     * @param verifiedAs the claim, or null for a filing that asserts nothing about identity
     */
    private static Map<String, Object> rightsFiling(String verifiedAs) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("entityId", "DENAVE_IN");
        body.put("identifierType", "EMAIL");
        body.put("identifierValue", "attribution-" + UUID.randomUUID() + "@example.test");
        body.put("type", "ACCESS");
        body.put("jurisdiction", "IN");
        body.put("details", "filed by the attribution suite");
        if (verifiedAs != null) {
            body.put("verifiedAs", verifiedAs);
        }
        return body;
    }

    private TestRestTemplate admin() {
        return rest.withBasicAuth("compliance-console", "admin-secret");
    }

    private AdminAuditStore.Entry latestFor(String targetId) {
        List<AdminAuditStore.Entry> entries = audit.recent("DENAVE_IN", 200);
        return entries.stream()
                .filter(entry -> targetId.equals(entry.targetId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no audit row was written for " + targetId + "; the action succeeded and "
                                + "left no trace, which is worse than it failing"));
    }
}
