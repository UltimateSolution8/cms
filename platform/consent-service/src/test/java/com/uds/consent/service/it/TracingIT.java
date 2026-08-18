package com.uds.consent.service.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tracing off — which is the configuration that will actually be deployed, and the one nobody
 * tests. {@link TracingEnabledIT} is the other half.
 *
 * <p>Phase 11 delivered {@code micrometer-tracing-bridge-otel}, the OTLP exporter, a sampling
 * probability and a paragraph in {@code application.yml} explaining that {@code traceId} and
 * {@code spanId} would join the caller's {@code correlationId} rather than replace it. Nothing
 * asserted any of it — it is the one deliverable of that phase that shipped without a test. This
 * pair closes it.
 *
 * <h2>The defect this found</h2>
 *
 * <p>{@link #theExporterEndpointBindsToAKeySpringBootReads()} asserts the <strong>bound value</strong>
 * of the OTLP endpoint rather than the presence of a property, and that is the entire point of it.
 * The configuration that shipped set {@code otel.exporter.otlp.endpoint} — the OpenTelemetry SDK's
 * own environment-variable name, which Spring Boot does not read. It bound to nothing. Setting
 * {@code OTLP_ENDPOINT} looked like pointing the exporter at a collector and did not, so the first
 * time anybody enabled tracing the spans would have gone to Boot's default localhost and been
 * dropped — presenting as an empty collector with tracing apparently switched on, which is a day
 * spent looking at the collector.
 *
 * <p>A test asserting the property <em>existed</em> would have passed.
 *
 * <p>The second defect is documented on {@link #theIdsArePresentEvenWithExportOff()}: this flag
 * turns off <em>export</em>, not instrumentation, and {@code application.yml} claimed otherwise.
 */
class TracingIT extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    /** Bound, not merely declared. See the class comment. */
    @Value("${management.otlp.tracing.endpoint:}")
    private String otlpEndpoint;

    @Test
    @DisplayName("the OTLP endpoint binds to a key Spring Boot actually reads")
    void theExporterEndpointBindsToAKeySpringBootReads() {
        assertThat(otlpEndpoint)
                .withFailMessage("management.otlp.tracing.endpoint is empty, which means the OTLP "
                        + "endpoint is configured under a key Spring Boot does not read: "
                        + "OTLP_ENDPOINT would appear to point the exporter somewhere and would "
                        + "not, and the symptom is a collector that stays empty")
                .isNotBlank()
                .contains("4318");
    }

    /**
     * The second defect. This test was written to assert an empty log context and failed, which is
     * how the difference between "tracing is off" and "export is off" was found.
     *
     * <p>{@code ConditionalOnEnabledTracing} gates the <strong>exporters</strong>. Span creation
     * follows the classpath: with {@code micrometer-tracing-bridge-otel} present, every request gets
     * a span and the ids reach MDC regardless of {@code management.tracing.enabled}. The comment in
     * {@code application.yml} said otherwise and argued the default on CPU-cost grounds — an
     * argument the measured 1.3 ms engine p50 in {@code CAPACITY.md} §7 settles, since that number
     * was produced on a build with the flag false and spans being created throughout.
     *
     * <p>The behaviour is right and the document was wrong, so the document was corrected: ids in
     * logs are useful with no collector at all, and the flag's real job is to stop an exporter
     * retrying into a void. What must not happen is a present-but-blank id, which would make a log
     * search for a trace match every line ever written — hence the emptiness assertions below.
     */
    @Test
    @DisplayName("with export off the ids are still in the log context, non-blank, beside the correlation id")
    void theIdsArePresentEvenWithExportOff() {
        String correlationId = UUID.randomUUID().toString();

        Map<String, String> context = LogCapture
                .aroundPortalSubmission(rest, correlationId, "+919812340098")
                .getMDCPropertyMap();

        assertThat(context)
                .withFailMessage("no traceId in the log context, so this build's log lines cannot be "
                        + "joined to a trace even after a collector is pointed at it")
                .containsKey("traceId");
        assertThat(context.get("traceId"))
                .withFailMessage("traceId is present but blank, which is worse than absent: a log "
                        + "search for a trace id matches every line ever written")
                .isNotBlank();
        assertThat(context.get("spanId")).isNotBlank();

        // Untouched by any of it. This is the identifier the platform has always had, the one that
        // survives outside the trace system, and the one an integrator can actually produce.
        assertThat(context).containsEntry("correlationId", correlationId);
    }
}
