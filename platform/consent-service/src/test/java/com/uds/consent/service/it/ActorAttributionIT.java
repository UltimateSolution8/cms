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
