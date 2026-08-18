package com.uds.consent.service.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scrape endpoint, and the port boundary that keeps it off the internet.
 *
 * <p>{@code PlatformMetrics} has registered decision, capture and scrub timers with percentiles
 * since the instrumentation work, and every one of them was visible only at {@code
 * /actuator/metrics} — a JSON endpoint a human reads one meter at a time. Nothing scraped it, so
 * the 30 ms p95 in {@code OPERATIONS.md} §6 had no series behind it and no alert could reference
 * it. This adds the endpoint Prometheus actually reads.
 *
 * <p><strong>Why the port matters more than the endpoint.</strong> The series published here name
 * denial reasons, capture volumes and rights-queue depth — an accurate operational picture of a
 * regulated system, served without a credential because that is what a scraper expects. On the
 * traffic port that sits one ingress rule away from the public internet, and the mistake would look
 * like nothing at all until somebody found it. So it lives on the management port and the second
 * assertion below is the one that keeps it there.
 */
// Spring Boot switches metrics export off inside @SpringBootTest — sensible by default, since most
// suites have no interest in an exporter and would pay for one. It also means that without this
// annotation the Prometheus registry is absent, /actuator/prometheus 404s, and the failure looks
// exactly like a missing dependency or a mis-set exposure list.
@AutoConfigureObservability
class MetricsEndpointIT extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @LocalManagementPort
    private int managementPort;

    @LocalServerPort
    private int trafficPort;

    @Test
    @DisplayName("the scrape endpoint serves the meters the platform actually registers")
    void prometheusServesThePlatformMeters() {
        // Take a decision first, so the timer has a sample. An endpoint that returns a valid but
        // empty exposition would pass a mere 200 assertion while telling a scraper nothing.
        rest.withBasicAuth("athena-dialer", "decision-secret")
                .postForEntity("/v1/evaluate", decisionRequest(), String.class);

        ResponseEntity<String> response = new RestTemplate().getForEntity(
                "http://localhost:" + managementPort + "/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .withFailMessage("the decision timer is absent from the exposition, so the SLO in "
                        + "OPERATIONS.md still has no series behind it")
                .contains("uds_consent_decision_seconds");
        assertThat(response.getBody()).contains("uds_consent_decision_outcome_total");
    }

    @Test
    @DisplayName("the scrape endpoint is not mapped on the traffic port at all")
    void prometheusIsNotOnTheTrafficPort() {
        // The assertion that makes the port split real rather than decorative. If this starts
        // returning 200, the actuator has drifted back onto the port the ingress terminates on and
        // the group's operational metrics are published to whoever asks.
        //
        // Asked as an authenticated administrator on purpose. Anonymously this path returns 401 —
        // the main chain refuses before routing — and a 401 would pass a weaker assertion while
        // telling us nothing about whether the endpoint is there. 404 for a caller who would be
        // allowed to see it is proof that it is not mapped on this port.
        // Belt and braces: if the two ports were ever the same, the 404 below would be trivially
        // true and this test would be asserting nothing at all.
        assertThat(trafficPort)
                .withFailMessage("the management port is the traffic port, so the split that keeps "
                        + "the scrape endpoint off the ingress does not exist")
                .isNotEqualTo(managementPort);

        ResponseEntity<String> response = rest.withBasicAuth("compliance-console", "admin-secret")
                .getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode())
                .withFailMessage("actuator is answering on the traffic port; check "
                        + "management.server.port")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static Map<String, Object> decisionRequest() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("entityId", "DENAVE_IN");
        request.put("subjectId", "metrics-suite-subject");
        request.put("purposeCode", "MKT_OUTBOUND_CALL");
        request.put("channel", "VOICE_CALL");
        request.put("jurisdiction", "IN");
        return request;
    }
}
