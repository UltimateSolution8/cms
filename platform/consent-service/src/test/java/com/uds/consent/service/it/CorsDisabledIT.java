package com.uds.consent.service.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The default configuration — no allowlist — which is what every deployment runs today.
 *
 * <p>{@link CorsIT} sets an allowlist for its whole class, and it is the only suite in the
 * repository that sends an {@code Origin} header at all. So "every other suite still passes" proves
 * nothing about the default: a CORS filter is a no-op on a request that is not a CORS request,
 * whatever its configuration says.
 *
 * <p>What this pins is the failure mode nobody would otherwise see. {@code allowed-origins} binds
 * from {@code ${CORS_ALLOWED_ORIGINS:}}, and a list bound from an empty placeholder could plausibly
 * arrive as {@code [""]} rather than {@code []}. The emptiness guard would then fail open, a
 * {@code /**} configuration with an unmatchable origin would be registered, and — because Spring's
 * {@code CorsFilter} short-circuits any request whose origin fails the check — every request
 * carrying an {@code Origin} header would be refused on every route, in the shipped default.
 */
class CorsDisabledIT extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("with no allowlist configured, a preflight carries no CORS headers")
    void noAllowlistMeansNoHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, "https://console.uds.example");
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");

        ResponseEntity<String> response = rest.exchange("/v1/evaluate", HttpMethod.OPTIONS,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    @DisplayName("with no allowlist configured, an ordinary request with an Origin still works")
    void anOriginHeaderDoesNotBreakAnOrdinaryRequest() {
        // The fail-open case this suite exists for. If the emptiness guard ever stopped holding,
        // a registered-but-unmatchable configuration would refuse this with 403 and every machine
        // caller that happens to send an Origin would break in the default deployment.
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, "https://anything.example");

        ResponseEntity<String> response = rest.exchange("/v1/keys", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("a request carrying an Origin was refused with no allowlist configured; the "
                        + "empty-list guard in CorsConfiguration has stopped holding")
                .isTrue();
    }
}
