package com.uds.consent.service.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pre-authentication limiter's {@code /actuator} exemption, asserted on the deployment it
 * exists for.
 *
 * <p>{@code PreAuthRateLimitIT.probesSurviveTheFlood} floods the traffic port and then probes the
 * <strong>management</strong> port. Management endpoints live in a child application context, and
 * the parent's {@code @Component} filters are never registered there — so
 * {@code PreAuthRateLimitFilter.shouldNotFilter} is not on that path at all, and that test passes
 * unchanged with the exemption deleted. It proves that the management port is not the port the
 * limiter defends, which is true and is a different statement.
 *
 * <p>Carried as F9 since Phase 16, and named as recorded in three delivery records before it
 * actually was. The exemption is not decoration: with {@code management.server.port} unset the
 * actuator is served on the traffic port, which is the configuration {@code docker-compose.yml}
 * runs and the one a single-container deployment gets by default. A readiness probe refused during
 * a flood drains a healthy instance out of the load balancer at the moment the fleet most needs
 * it — the defence becoming an amplifier.
 *
 * <p>So: one context with the ports collapsed, a ceiling low enough to reach in a loop, and the
 * probe asserted <em>on the flooded port</em>. Falsified against the exemption removed before it
 * was trusted — which matters here, because the test it replaces passed either way.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "uds.consent.rate-limit.enabled=true",
                "uds.consent.rate-limit.pre-auth.permits-per-second=3",
                "uds.consent.rate-limit.pre-auth.burst=3",
                // The whole point of the suite: the actuator on the traffic port, so the flood and
                // the probe arrive at the same filter chain and the same address bucket.
                "management.server.port="
        })
class SinglePortProbeIT extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("a liveness probe on the flooded port is exempt from the pre-auth ceiling")
    void theProbeIsExemptOnASinglePortDeployment() {
        // Establish that the ceiling is actually being hit on this port, or the assertion below
        // would pass on an instance that was never under pressure — which is precisely how the
        // test this one replaces came to prove nothing.
        TestRestTemplate flooder = rest.withBasicAuth("compliance-console", "not-the-password");
        boolean refused = false;
        for (int i = 0; i < 30; i++) {
            if (flooder.getForEntity("/v1/admin/purposes", String.class)
                    .getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                refused = true;
            }
        }
        assertThat(refused)
                .withFailMessage("the flood never reached the ceiling, so the exemption below is "
                        + "asserted against an instance under no pressure at all")
                .isTrue();

        // The property. Same port, same address, same bucket — and the probe still answers.
        for (int i = 0; i < 10; i++) {
            assertThat(rest.getForEntity("/actuator/health/liveness", String.class).getStatusCode())
                    .withFailMessage("a liveness probe was refused by the pre-auth ceiling on a "
                            + "single-port deployment; shouldNotFilter's /actuator exemption is "
                            + "what prevents this")
                    .isEqualTo(HttpStatus.OK);
        }
    }
}
