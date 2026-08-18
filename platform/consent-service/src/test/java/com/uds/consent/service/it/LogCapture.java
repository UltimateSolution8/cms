package com.uds.consent.service.it;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads the log context a request actually produced.
 *
 * <p>Shared by {@link TracingIT} and {@link TracingEnabledIT}, which assert the same thing about
 * two different configurations and must therefore make the same request in the same way — otherwise
 * the difference between them is not the one under test.
 *
 * <p><strong>Why the log context rather than a filter.</strong> The claim being tested is not "the
 * tracer produces ids", it is "a log line can be joined to a trace", which is the only reason
 * anybody wants either. Reading {@link ILoggingEvent#getMDCPropertyMap()} tests exactly that, and
 * it keeps working if the mechanism by which the ids reach MDC ever changes — which for a Boot
 * auto-configuration is a thing that happens on a minor version bump.
 */
final class LogCapture {

    /**
     * The route used to provoke a log line.
     *
     * <p>The rights portal, because it needs no credential — so the request under test is one
     * unauthenticated POST and nothing about authentication can affect the result — and because
     * {@code PrincipalPortalService} logs exactly one INFO line per submission, which makes "the
     * first event" unambiguous.
     */
    private static final String LOGGER = "com.uds.consent.service.PrincipalPortalService";

    private LogCapture() {
    }

    static ILoggingEvent aroundPortalSubmission(
            TestRestTemplate rest, String correlationId, String identifier) {

        Logger target = (Logger) LoggerFactory.getLogger(LOGGER);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        target.addAppender(captured);
        try {
            assertThat(submit(rest, correlationId, identifier).getStatusCode())
                    .withFailMessage("the portal refused the submission, so the log line this test "
                            + "inspects was never written")
                    .isEqualTo(HttpStatus.ACCEPTED);

            List<ILoggingEvent> events = captured.list;
            assertThat(events)
                    .withFailMessage("the portal logged nothing, so there is no log context to "
                            + "inspect and every assertion below would pass against an empty map")
                    .isNotEmpty();
            return events.get(0);
        } finally {
            target.detachAppender(captured);
            captured.stop();
        }
    }

    private static ResponseEntity<String> submit(
            TestRestTemplate rest, String correlationId, String identifier) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("entityId", "DENAVE_IN");
        body.put("identifierType", "PHONE");
        body.put("identifierValue", identifier);
        body.put("requestType", "ACCESS");
        body.put("jurisdiction", "IN");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-Id", correlationId);

        return rest.postForEntity("/v1/portal/requests", new HttpEntity<>(body, headers),
                String.class);
    }
}
