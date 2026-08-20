package com.uds.consent.service.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-origin access, which the platform had none of until a browser needed to reach it.
 *
 * <p>Two properties, and only the second is about CORS headers.
 *
 * <p><strong>One: a preflight is answered before anything can refuse it.</strong>
 * {@code CorsConfiguration} registers the filter at {@code DEFAULT_FILTER_ORDER - 20}, ahead of
 * {@link com.uds.consent.service.config.PreAuthRateLimitFilter} at {@code - 10} and of the security
 * chain. Had it gone in the chain — which {@code .cors(withDefaults())} would have done — a
 * preflight would be counted against the flood bucket, and a refusal from that bucket carries no
 * {@code Access-Control-Allow-Origin}, so the browser would report a CORS failure where the truth
 * was a 429. A client cannot act on a diagnosis that names the wrong subsystem.
 *
 * <p><strong>Two: an origin that is not on the list gets no header</strong>, which is what makes
 * the allowlist an allowlist. Asserted on the <em>absence</em> of the header rather than on a
 * status code, because a CORS refusal is enforced by the browser and the server answers 200 either
 * way — a test asserting a status would pass with the allowlist ignored entirely.
 *
 * <p>The pre-auth ceiling is set very low here so the ordering assertion is reachable in a test
 * rather than in theory.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "uds.consent.security.cors.allowed-origins=https://console.uds.example",
        // enabled=true is not decoration. The integration-test profile ships the limiter OFF
        // (application-integrationtest.yml), so without this line every ordering assertion below
        // passes against a filter that never runs — which is exactly what happened when this suite
        // was first written, and it is why preflightsOutrunTheFloodCeiling proved nothing for a
        // build. Assert the property, and make sure the mechanism is switched on first.
        "uds.consent.rate-limit.enabled=true",
        "uds.consent.rate-limit.pre-auth.permits-per-second=2",
        "uds.consent.rate-limit.pre-auth.burst=2"
})
class CorsIT extends PostgresIntegrationTest {

    private static final String ALLOWED = "https://console.uds.example";
    private static final String STRANGER = "https://not-ours.example";

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("a preflight from an allowed origin is answered with the headers a browser needs")
    void anAllowedOriginIsAnswered() {
        ResponseEntity<String> response = preflight(ALLOWED, "POST");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin"))
                .isEqualTo(ALLOWED);

        // Spring echoes the headers the browser ASKED for, not the whole allowlist, so the
        // question has to be asked before it can be answered. X-UDS-Actor is required on every
        // administrative mutation (rules §5) — a console that cannot send it cannot administer
        // anything, and the failure would present as a browser error rather than as the 400 the
        // platform would have returned.
        assertThat(preflightAsking(ALLOWED, "authorization,content-type,x-uds-actor")
                .getHeaders().getFirst("Access-Control-Allow-Headers"))
                .containsIgnoringCase("X-UDS-Actor");

        // And the list is an allowlist rather than a mirror — a configuration that echoed whatever
        // was asked for would pass every assertion above. The refusal is stronger than an absent
        // header: Spring rejects the preflight outright, so the browser never issues the real
        // request at all.
        ResponseEntity<String> smuggled = preflightAsking(ALLOWED, "x-smuggled-header");
        assertThat(smuggled.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(smuggled.getHeaders().getFirst("Access-Control-Allow-Headers")).isNull();
    }

    @Test
    @DisplayName("an origin that is not on the list gets no allow-origin header")
    void anUnknownOriginGetsNoHeader() {
        // Asserted on the header's absence. A status assertion would pass with CORS switched off
        // entirely, because the server answers a preflight either way and it is the browser that
        // enforces the refusal.
        assertThat(preflight(STRANGER, "POST").getHeaders().getFirst("Access-Control-Allow-Origin"))
                .isNull();
    }

    @Test
    @DisplayName("a browser can read the correlation id and Retry-After back")
    void theExposedHeadersAreNamed() {
        String exposed = preflight(ALLOWED, "GET").getHeaders()
                .getFirst("Access-Control-Expose-Headers");

        // Without this list both are invisible to JavaScript however faithfully the server sends
        // them. The correlation id is the identifier OPERATIONS.md §11 assumes a caller can quote
        // in a support thread; Retry-After is what turns a 429 into a client that backs off.
        assertThat(exposed).contains("X-Correlation-Id").contains("Retry-After");
    }

    @Test
    @DisplayName("preflights are not counted against the pre-authentication flood ceiling")
    void preflightsOutrunTheFloodCeiling() {
        // The ordering assertion, and the only one here that can fail if the filter is registered
        // in the wrong place. The ceiling is 2/s for this context, so twelve preflights in a row
        // would exhaust it several times over if they reached it. Every one must still carry the
        // allow-origin header — a 429 from PreAuthRateLimitFilter carries none, so a single
        // missing header proves the filter is behind the limiter.
        for (int i = 0; i < 12; i++) {
            ResponseEntity<String> response = preflight(ALLOWED, "POST");
            assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin"))
                    .as("preflight %s was refused or unheadered; the CORS filter is behind the "
                            + "pre-auth rate limiter", i)
                    .isEqualTo(ALLOWED);
        }
    }

    @Test
    @DisplayName("an actual cross-origin call still has to authenticate")
    void corsIsNotAuthorisation() {
        // Worth pinning explicitly. CORS decides which origins a browser will let read a response;
        // it decides nothing about who may call. An allowed origin with no credential is still 401,
        // and anybody reading the allowlist as a security boundary has misread it.
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, ALLOWED);

        assertThat(rest.exchange("/v1/admin/entities", HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an actual cross-origin request is still counted against the flood ceiling")
    void anOriginHeaderIsNotAWayPastTheCeiling() {
        // The hole the second ordering attempt opened. Spring's CorsFilter short-circuits on ANY
        // request whose origin fails the check, not only on preflights — so with CORS registered
        // ahead of the limiter, a POST carrying a bogus Origin was answered by the CORS filter and
        // never reached the bucket: unmetered, and evadable with one header.
        //
        // Asserted on 429 appearing. The ceiling is 2/s for this context, so a dozen requests must
        // exhaust it whatever origin they claim.
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, "https://evil.example");

        boolean limited = false;
        for (int i = 0; i < 20 && !limited; i++) {
            limited = rest.exchange("/v1/evaluate", HttpMethod.POST,
                    new HttpEntity<>("{}", headers), String.class)
                    .getStatusCode() == HttpStatus.TOO_MANY_REQUESTS;
        }

        assertThat(limited)
                .as("a request with an unlisted Origin was never rate limited; the CORS filter is "
                        + "in front of PreAuthRateLimitFilter and is short-circuiting it")
                .isTrue();
    }

    @Test
    @DisplayName("a browser over the ceiling reads a 429, not a CORS error")
    void theRefusalCarriesTheOriginBack() {
        // The other half of the ordering. The limiter now runs ahead of the CORS filter, so when it
        // refuses, nothing downstream adds the header — unless the refusal carries it itself. A
        // browser that cannot read the response cannot tell "rate limited" from "your origin is
        // wrong", and backs off from neither.
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, ALLOWED);

        for (int i = 0; i < 20; i++) {
            ResponseEntity<String> response = rest.exchange("/v1/evaluate", HttpMethod.POST,
                    new HttpEntity<>("{}", headers), String.class);
            if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin"))
                        .isEqualTo(ALLOWED);
                assertThat(response.getHeaders().getFirst("Access-Control-Expose-Headers"))
                        .contains("Retry-After");
                return;
            }
        }
        throw new AssertionError("never reached the pre-auth ceiling, so the assertion never ran");
    }

    @Test
    @DisplayName("credentials are not allowed and the preflight is cacheable")
    void credentialsAreNotAllowedAndPreflightsAreCacheable() {
        // Both are claimed in DECISIONS.md, in OPERATIONS.md §12.8 and in the class javadoc, and
        // until now both were properties of the author's restraint rather than of the code.
        HttpHeaders headers = preflight(ALLOWED, "POST").getHeaders();

        assertThat(headers.getFirst("Access-Control-Allow-Credentials")).isNull();
        assertThat(headers.getFirst("Access-Control-Max-Age")).isEqualTo("1800");
    }

    private ResponseEntity<String> preflightAsking(String origin, String requestedHeaders) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, origin);
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, requestedHeaders);
        return rest.exchange("/v1/evaluate", HttpMethod.OPTIONS,
                new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> preflight(String origin, String method) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, origin);
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method);
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type");
        return rest.exchange("/v1/evaluate", HttpMethod.OPTIONS,
                new HttpEntity<>(headers), String.class);
    }
}
