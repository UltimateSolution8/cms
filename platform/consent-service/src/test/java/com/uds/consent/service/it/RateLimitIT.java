package com.uds.consent.service.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One caller cannot hold the platform open.
 *
 * <p>There was no rate limiting anywhere, and the two worst places to have none were the two routes
 * that need no credential at all: {@code GET /v1/notices/*} and {@code GET /v1/keys}. Both are
 * public deliberately and correctly — a person deciding whether to consent has to be able to read
 * the notice, and a device that lost its credential still has to verify snapshots it holds — which
 * is exactly what makes them the routes anyone at all can hold open.
 *
 * <p>The authenticated case is sharper. {@code POST /v1/evaluate/batch} caps at a thousand
 * identifiers per call and had no cap per second, so a dialer in a retry loop was an outage — and
 * the outage takes the decision API with it, at which point every downstream system either stops
 * calling or starts guessing, and guessing is how somebody who withdrew gets phoned.
 *
 * <p><strong>Its own context, with limits in single digits.</strong> The other suites run with the
 * limiter off, because they fire hundreds of requests as fast as the JVM will send them — which is
 * precisely the traffic the limiter refuses, and leaving it on would make unrelated assertions fail
 * depending on how fast the machine is. Proving the behaviour needs limits low enough to be reached
 * deterministically in a loop, not limits that a fast laptop happens to trip.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "uds.consent.rate-limit.enabled=true",
                // Deliberately tiny, and with burst equal to the rate so the bucket cannot absorb
                // the loop below. Production values are 20-200/s; the mechanism is the same.
                "uds.consent.rate-limit.public-routes.permits-per-second=2",
                "uds.consent.rate-limit.public-routes.burst=2",
                "uds.consent.rate-limit.batch.permits-per-second=2",
                "uds.consent.rate-limit.batch.burst=2",
                "uds.consent.rate-limit.admin.permits-per-second=1000",
                "uds.consent.rate-limit.admin.burst=1000"
        })
class RateLimitIT extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    /** The actuator's own port; health is no longer served on the traffic port. */
    @LocalManagementPort
    private int managementPort;

    @Test
    @DisplayName("an unauthenticated caller is limited on the public routes")
    void thePublicRoutesAreBounded() {
        ResponseEntity<String> refusal = null;
        for (int i = 0; i < 25 && refusal == null; i++) {
            ResponseEntity<String> response = rest.getForEntity("/v1/keys", String.class);
            if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                refusal = response;
            }
        }

        assertThat(refusal)
                .withFailMessage("twenty-five unauthenticated requests in a row at a limit of two "
                        + "per second were all served; the public routes are still unbounded")
                .isNotNull();

        // RFC 7807, like every other refusal on this platform. A filter's exception never reaches
        // @RestControllerAdvice, so this is serialised by hand — and an integrator writing one
        // error-handling path should not find it works everywhere except here.
        assertThat(refusal.getHeaders().getContentType().toString())
                .startsWith("application/problem+json");
        assertThat(refusal.getBody()).contains("Rate limit exceeded");

        // Without this a well-behaved client has nothing to back off against, and the polite
        // thing it does instead is retry immediately.
        assertThat(refusal.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    }

    @Test
    @DisplayName("batch evaluation is limited far harder than single decisions")
    void batchHasItsOwnCeiling() {
        // The class split is the point. /v1/evaluate/batch is also /v1/evaluate by prefix, so a
        // limiter checking in the other order would hand batch the decision path's far higher
        // ceiling — which would leave the one route that does a thousand times the work per call
        // effectively unlimited.
        TestRestTemplate dialer = rest.withBasicAuth("athena-dialer", "decision-secret");

        boolean refused = false;
        for (int i = 0; i < 25 && !refused; i++) {
            refused = dialer.postForEntity("/v1/evaluate/batch", """
                            {"entityId":"DENAVE_IN","purposeCode":"MARKETING_CALL",
                             "channel":"VOICE_CALL","identifiers":[]}""", String.class)
                    .getStatusCode() == HttpStatus.TOO_MANY_REQUESTS;
        }

        assertThat(refused)
                .withFailMessage("batch evaluation served twenty-five requests at a limit of two "
                        + "per second; a dialer in a retry loop is still an outage")
                .isTrue();
    }

    @Test
    @DisplayName("the admin ceiling is separate, so one noisy caller does not throttle another")
    void routeClassesAreLimitedIndependently() {
        // Buckets are keyed by (route class, caller). Without the route class in the key, a dialer
        // exhausting the batch ceiling would take the compliance console down with it — which
        // would turn a defence against one misbehaving client into an outage for everyone else.
        TestRestTemplate dialer = rest.withBasicAuth("athena-dialer", "decision-secret");
        for (int i = 0; i < 10; i++) {
            dialer.postForEntity("/v1/evaluate/batch", """
                    {"entityId":"DENAVE_IN","purposeCode":"MARKETING_CALL",
                     "channel":"VOICE_CALL","identifiers":[]}""", String.class);
        }

        assertThat(rest.withBasicAuth("compliance-console", "admin-secret")
                .getForEntity("/v1/admin/purposes", String.class).getStatusCode())
                .withFailMessage("the console was refused because a dialer exhausted a different "
                        + "route's budget")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("the health probes are never rate limited")
    void actuatorIsExempt() {
        // A limiter that refused a readiness probe would drain an instance that was working
        // perfectly — turning the defence against overload into a cause of one. The orchestrator
        // polls every few seconds from a handful of addresses and must always get an answer.
        //
        // Asked on the management port, because that is where the actuator now lives. There are
        // two independent defences here and they are worth keeping distinct: the filter's own
        // exemption for /actuator paths, and the fact that probe traffic no longer arrives on the
        // port the limiter is defending at all. This asserts the outcome both produce.
        org.springframework.web.client.RestTemplate probe =
                new org.springframework.web.client.RestTemplate();
        for (int i = 0; i < 30; i++) {
            assertThat(probe.getForEntity(
                    "http://localhost:" + managementPort + "/actuator/health/liveness",
                    String.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
    }
}
