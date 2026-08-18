package com.uds.consent.service.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tracing on: the configuration nobody has deployed and the one that has to work the day somebody
 * does. {@link TracingIT} is the off half, and the pair is what makes either meaningful.
 *
 * <p>No collector, and none needed. The exporter will fail to ship these spans to
 * {@code localhost:4318} and that is irrelevant to what is being asserted — the question is whether
 * the ids reach the log context, which happens at span scope, long before anything is exported. A
 * Testcontainers OTLP collector would add start-up time to every run to test the collector.
 */
@TestPropertySource(properties = {
        "management.tracing.enabled=true",
        // Every request sampled. At the shipped 10% a test making one request would fail nine times
        // in ten, be marked flaky, and be deleted — which is how a suite ends up asserting nothing
        // about the feature it is named after.
        "management.tracing.sampling.probability=1.0",
})
class TracingEnabledIT extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("traceId and spanId join the log context without displacing the correlation id")
    void theIdsJoinTheLogContext() {
        String correlationId = UUID.randomUUID().toString();

        Map<String, String> context = LogCapture
                .aroundPortalSubmission(rest, correlationId, "+919812340099")
                .getMDCPropertyMap();

        assertThat(context)
                .withFailMessage("no traceId in the log context with tracing enabled, so a log "
                        + "line cannot be joined to the trace it belongs to — which is the entire "
                        + "reason for shipping the tracer")
                .containsKey("traceId");
        assertThat(context).containsKey("spanId");
        assertThat(context.get("traceId")).isNotBlank();
        assertThat(context.get("spanId")).isNotBlank();

        // The assertion that matters operationally, and the one application.yml promises in prose.
        // traceId is ours and dies with the trace system; correlationId is the caller's, survives
        // outside it, and is what a support conversation with Denave actually quotes. A tracer
        // that evicted it would be a downgrade dressed as an upgrade.
        assertThat(context)
                .withFailMessage("the caller's correlationId was displaced by the tracer, so the "
                        + "identifier an integrator quotes in a support ticket is gone")
                .containsEntry("correlationId", correlationId);
    }
}
