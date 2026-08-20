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
 * Refusing a stranger must cost the platform less than it costs them.
 *
 * <p>Phase 12 measured the property this suite exists to prove is gone: 500 requests carrying an
 * invalid credential produced 500 × 401 and <strong>zero</strong> 429s, because
 * {@code RateLimitFilter} is ordered behind Spring Security. Each of those 401s cost one BCrypt
 * verification at ~113 ms — the same price as a successful decision, for a request that touches no
 * database and evaluates no policy. One instance serves ~50 rps on that path and the ceiling is
 * CPU, so an attacker holding no credential at all could saturate it while every refusal cost the
 * defender more than the attempt cost them. {@code CAPACITY.md} §7 and {@code OPERATIONS.md} §12.2.
 *
 * <p><strong>The assertion that matters is about order, not about 429.</strong> A test that floods
 * a route and finds a 429 would pass with the filter still behind authentication — it would simply
 * be the old filter answering. What cannot happen behind authentication is a request carrying a
 * <em>deliberately wrong password</em> being answered 429 instead of 401: reaching the credential
 * check at all produces the 401. So that is what is asserted.
 *
 * <p><strong>Its own context, with a ceiling in single digits.</strong> Production is 400/s because
 * the filter runs before authentication and therefore keys on the client address alone — behind a
 * corporate NAT that is one bucket for a building. Every test in this file shares one address, so
 * the ceiling has to be low enough to reach deterministically in a loop; {@link RateLimitIT} keeps
 * the per-credential classes and would fail if this ceiling were lowered there.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "uds.consent.rate-limit.enabled=true",
                // Burst equal to the rate so the bucket cannot absorb the loop below.
                "uds.consent.rate-limit.pre-auth.permits-per-second=3",
                "uds.consent.rate-limit.pre-auth.burst=3"
        })
class PreAuthRateLimitIT extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    /** The actuator's own port; health is no longer served on the traffic port. */
    @LocalManagementPort
    private int managementPort;

    @Test
    @DisplayName("an invalid credential over the ceiling is answered 429, not 401")
    void theRefusalPrecedesAuthentication() throws InterruptedException {
        // The whole phase, in one assertion. A wrong password that reaches the authentication
        // filter produces 401 — always, unconditionally, at the cost of a BCrypt verification. A
        // 429 for a wrong password can therefore only mean the request was refused before the
        // credential was ever checked, which is the property being bought.
        //
        // Waited out first, because every test in this class shares one bucket keyed on one
        // address and this one needs to start with tokens in it. Without the wait the assertion
        // below would depend on which test ran before it, and a test whose meaning depends on
        // execution order is one that will eventually be deleted for flapping.
        Thread.sleep(1_500);

        TestRestTemplate wrong = rest.withBasicAuth("compliance-console", "not-the-password");

        ResponseEntity<String> refusal = null;
        boolean sawUnauthorized = false;
        for (int i = 0; i < 25 && refusal == null; i++) {
            ResponseEntity<String> response =
                    wrong.getForEntity("/v1/admin/purposes", String.class);
            if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                refusal = response;
            } else if (response.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                sawUnauthorized = true;
            }
        }

        assertThat(sawUnauthorized)
                .withFailMessage("nothing was authenticated at all, so this proves nothing about "
                        + "ordering — the first requests must still reach the credential check")
                .isTrue();
        assertThat(refusal)
                .withFailMessage("twenty-five bad-password requests at a pre-authentication limit "
                        + "of three per second were every one of them answered 401; the limiter is "
                        + "still behind the security chain and each refusal still costs a BCrypt "
                        + "verification")
                .isNotNull();

        // RFC 7807 like every other refusal, and written by hand because at this point in the
        // chain there is neither a DispatcherServlet nor a security exception handler to fall back
        // on. An integrator with one error-handling path should not find it works everywhere but
        // here.
        assertThat(refusal.getHeaders().getContentType().toString())
                .startsWith("application/problem+json");
        assertThat(refusal.getBody()).contains("Rate limit exceeded");
        assertThat(refusal.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    }

    @Test
    @DisplayName("a valid credential is refused too, because this ceiling cannot tell callers apart")
    void theCeilingIsBlindToWhoIsCalling() {
        // Stated as a test rather than left in a comment, because it is the cost of the control
        // and somebody will otherwise discover it in production. Running before authentication
        // means keying on the client address alone: behind a corporate NAT or an ingress without
        // server.forward-headers-strategy configured, that is one bucket for an entire building.
        //
        // Which is exactly why the production default is 400/s rather than something that feels
        // more protective. This is a flood ceiling, not a fairness limit — fairness between
        // callers is RateLimitIT's per-credential, per-route-class subject, behind authentication
        // where the credential is known. Tightening this number is how a legitimate integrator
        // gets refused.
        TestRestTemplate valid = rest.withBasicAuth("compliance-console", "admin-secret");

        boolean refused = false;
        for (int i = 0; i < 25 && !refused; i++) {
            refused = valid.getForEntity("/v1/admin/purposes", String.class)
                    .getStatusCode() == HttpStatus.TOO_MANY_REQUESTS;
        }

        assertThat(refused)
                .withFailMessage("the pre-authentication ceiling appears to exempt authenticated "
                        + "callers, which it cannot do — it runs before there is a credential to "
                        + "read, so either it is not running or it is reading one it should not")
                .isTrue();
    }

    @Test
    @DisplayName("the health probes are never refused, even under the flood")
    void probesSurviveTheFlood() {
        // A limiter that refused a readiness probe during an attack would drain a healthy instance
        // out of the load balancer at the moment the fleet most needs it — turning a defence
        // against overload into an amplifier of one.
        //
        // THIS TEST PROVES ONE OF THE TWO DEFENCES, AND NOT THE ONE ITS NAME SUGGESTS. It floods
        // the traffic port and probes the MANAGEMENT port, where actuator endpoints live in a
        // child context that never registers the parent's @Component filters — so
        // PreAuthRateLimitFilter.shouldNotFilter is not on this path at all, and this test passes
        // unchanged with the /actuator exemption deleted. What it establishes is that the
        // management port is not the port the limiter defends, which is worth keeping and is a
        // narrower statement.
        //
        // The exemption itself is proven by SinglePortProbeIT, on a single-port configuration —
        // the deployment the exemption exists for. Carried as F9 from Phase 16 to Phase 20, named
        // as "recorded" in three delivery records before it actually was.
        TestRestTemplate flooder = rest.withBasicAuth("compliance-console", "not-the-password");
        for (int i = 0; i < 20; i++) {
            flooder.getForEntity("/v1/admin/purposes", String.class);
        }

        org.springframework.web.client.RestTemplate probe =
                new org.springframework.web.client.RestTemplate();
        for (int i = 0; i < 10; i++) {
            assertThat(probe.getForEntity(
                    "http://localhost:" + managementPort + "/actuator/health/liveness",
                    String.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
    }
}
