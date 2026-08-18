package com.uds.consent.service.it;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;

/**
 * Makes every suite's HTTP client behave like a correctly-built console.
 *
 * <p>Administrative mutations now require {@code X-UDS-Actor} — the human behind a shared
 * credential — and refuse with 400 without it. Ten unrelated suites started failing on that, which
 * is the change working; adding header plumbing to each would have buried what those tests are
 * about (breach clocks, publishing, reconciliation) under boilerplate about attribution.
 *
 * <p><strong>Why a {@code RestTemplateBuilder} bean and not an interceptor added at
 * {@code @BeforeEach}.</strong> The obvious approach — reach into the autowired
 * {@code TestRestTemplate} and add an interceptor to its underlying {@code RestTemplate} — silently
 * does nothing where it matters. {@code TestRestTemplate.withBasicAuth} does not copy the current
 * template's interceptors; it builds a fresh one from the {@code RestTemplateBuilder} it was
 * constructed with. Since every suite authenticates through {@code withBasicAuth}, the interceptor
 * would be dropped by exactly the call under test. Supplying the builder puts the header on the
 * path {@code withBasicAuth} actually takes.
 *
 * <p>The header is added only when the caller has not set one, and is skipped entirely when the
 * caller asks for it to be — see {@code ActorAttributionIT}, which has to act like a client that
 * sends no actor and cannot remove a builder it shares with every other suite in the context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class IntegrationTestClient {

    /** The identity every suite's HTTP calls act under, unless they say otherwise. */
    public static final String TEST_ACTOR = "integration-test-operator";

    /** Opt-out header: present means "send no actor", so the refusal itself can be tested. */
    public static final String SUPPRESS_ACTOR = "X-UDS-No-Actor";

    @Bean
    RestTemplateBuilder testRestTemplateBuilder() {
        return new RestTemplateBuilder().additionalInterceptors((request, body, execution) -> {
            if (!request.getHeaders().containsKey("X-UDS-Actor")
                    && !request.getHeaders().containsKey(SUPPRESS_ACTOR)) {
                request.getHeaders().add("X-UDS-Actor", TEST_ACTOR);
            }
            return execution.execute(request, body);
        });
    }
}
