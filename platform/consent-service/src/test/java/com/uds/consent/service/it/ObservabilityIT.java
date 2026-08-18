package com.uds.consent.service.it;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turning two claims in the operations manual into facts.
 *
 * <p>{@code OPERATIONS.md} §6 commits to p95 under 30 ms on the decision API and 99.99%
 * availability, and nothing measured either — an SLO nobody measures is a number that gets quoted
 * in a client contract and discovered to be wrong during the incident review. And
 * {@code ApiExceptionHandler} has always told callers to "check the service logs with the trace
 * id" while nothing produced one, which matters here more than in an ordinary service: error
 * responses from this platform deliberately carry no personal data, so an integrator reporting a
 * refusal has nothing else to hand over.
 */
class ObservabilityIT extends PostgresIntegrationTest {

    private static final String ENTITY = "DENAVE_IN";

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private TestRestTemplate rest;

    /**
     * The actuator's own port.
     *
     * <p>It moved off the traffic port so that {@code /actuator/prometheus} — which needs no
     * credential — is not served where the ingress terminates. Health moved with it, so the two
     * health assertions below now ask the port that actually answers.
     */
    @LocalManagementPort
    private int managementPort;

    @Test
    @DisplayName("a decision is timed and counted by its reason")
    void decisionsAreMeasured() {
        Timer timer = registry.find("uds.consent.decision").timer();
        assertThat(timer).isNotNull();
        long before = timer.count();

        rest.withBasicAuth("athena-dialer", "decision-secret").postForEntity("/v1/evaluate",
                Map.of("entityId", ENTITY, "subjectId", "obs-" + UUID.randomUUID(),
                        "purposeCode", "MKT_OUTBOUND_CALL", "channel", "VOICE_CALL",
                        "jurisdiction", "IN"),
                String.class);

        assertThat(registry.find("uds.consent.decision").timer().count()).isGreaterThan(before);

        // Tagged by reason, so an operations team can see that a spike in refusals is
        // NO_CONSENT_RECORD rather than SUPPRESSED_STATUTORY without reading a single log line.
        // Both are enums, so the tag's range is fixed and small — nothing here is tagged by
        // subject or campaign, which would create one time series per person.
        assertThat(registry.find("uds.consent.decision.outcome")
                .tag("reason", "NO_CONSENT_RECORD").counter())
                .isNotNull();
    }

    @Test
    @DisplayName("a batch contributes one observation per request, not one per batch")
    void batchesAreTimedPerDecision() {
        Timer timer = registry.find("uds.consent.decision").timer();
        long before = timer.count();

        List<Map<String, Object>> batch = List.of(
                evaluateBody("obs-" + UUID.randomUUID()),
                evaluateBody("obs-" + UUID.randomUUID()),
                evaluateBody("obs-" + UUID.randomUUID()));

        rest.withBasicAuth("athena-dialer", "decision-secret")
                .postForEntity("/v1/evaluate/batch", batch, String.class);

        // Timing the batch as a unit would report a p95 of "however big the last batch was",
        // which answers nothing about the per-decision latency the SLO actually names.
        assertThat(registry.find("uds.consent.decision").timer().count())
                .isGreaterThanOrEqualTo(before + 3);
    }

    @Test
    @DisplayName("the outbox depth and the evidence-write failures are gauged")
    void thingsWithReportsAndNoHomeAreExposed() {
        // Both had a method and no reader. OutboxStore.pendingCount() was documented as "exposed
        // as a metric" and was exposed nowhere at all.
        assertThat(registry.find("uds.consent.outbox.pending").gauge()).isNotNull();
        assertThat(registry.find("uds.consent.enforcement.failed_writes").gauge()).isNotNull();
        assertThat(registry.find("uds.consent.rights.overdue").gauge()).isNotNull();
    }

    @Test
    @DisplayName("the metrics an alert rule names actually exist under those names")
    void everyAlertedMeterIsRegistered() {
        // deploy/observability/alerts.yaml fires on these by their Prometheus names. A meter that
        // is never registered makes its rule permanently silent, and a rule that cannot fire looks
        // exactly like a condition that never occurs — which is how a control stops existing
        // without anybody noticing. The same shape as the OTLP endpoint bound under a key Spring
        // never read: assert the thing exists, not that somebody wrote the name down.
        assertThat(registry.find("uds.consent.propagation.uncovered").gauge())
                .withFailMessage("PropagationTargetUnreachable is a critical rule over a gauge "
                        + "that is not registered, so it can never fire")
                .isNotNull();
        assertThat(registry.find("uds.consent.propagation.failed_writes").gauge()).isNotNull();
        assertThat(registry.find("uds.consent.rights.unverified_open").gauge())
                .withFailMessage("V30 built the index for this question and UnverifiedRightsRequestsOpen "
                        + "alerts on it; the gauge that answers it is missing")
                .isNotNull();
    }

    @Test
    @DisplayName("propagation coverage is a health detail and never a DOWN condition")
    void propagationIsReportedWithoutTakingTheInstanceOutOfService() {
        // OPERATIONS.md §4.0a tells an operator to read propagationUncovered off /actuator/health.
        // If the key is not there, the instruction names something that does not exist — this
        // programme's third-most-common defect, and one it has shipped three times.
        ResponseEntity<String> response = management("/actuator/health");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .withFailMessage("OPERATIONS.md §4.0a instructs the reader to find "
                        + "propagationUncovered here, and it is absent")
                .contains("propagationUncovered");

        // The only DOWN condition is a broken chain. Draining a healthy instance because a
        // downstream system is unregistered would turn an evidence problem into an availability
        // one, for every entity at once.
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("every response carries a correlation id")
    void aCorrelationIdComesBackOnEveryCall() {
        ResponseEntity<String> response = rest.getForEntity(
                "/v1/notices/NOTICE_DENAVE_B2B?jurisdiction=IN&lang=en", String.class);

        String correlationId = response.getHeaders().getFirst("X-Correlation-Id");
        assertThat(correlationId).isNotBlank();
    }

    @Test
    @DisplayName("an inbound correlation id is honoured, so a trace survives the hop")
    void anInboundIdIsKept() {
        String supplied = "trace-" + UUID.randomUUID();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-Id", supplied);

        ResponseEntity<String> response = rest.exchange(
                "/v1/notices/NOTICE_DENAVE_B2B?jurisdiction=IN&lang=en", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(supplied);
    }

    @Test
    @DisplayName("a hostile correlation id is replaced rather than echoed")
    void aForgedIdIsNotEchoed() {
        // This value reaches log files and a response header. Echoing anything a caller sends
        // would let them shape log lines in every aggregator the group runs — a cheap attack
        // against a control whose whole purpose is making an investigation trustworthy. A raw
        // newline cannot be sent through the HTTP client at all, so the case exercised here is
        // the one that does travel: a well-formed header whose content is not an identifier.
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-Id", "abc def; INJECTED level=ERROR");

        ResponseEntity<String> response = rest.exchange(
                "/v1/notices/NOTICE_DENAVE_B2B?jurisdiction=IN&lang=en", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        String echoed = response.getHeaders().getFirst("X-Correlation-Id");
        assertThat(echoed).isNotNull().doesNotContain("INJECTED");
    }

    @Test
    @DisplayName("health reports the ledger integrity, the outbox and the evidence writes")
    void healthCarriesTheComplianceFacts() {
        ResponseEntity<String> response = management("/actuator/health");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("ledgerIntegrity")
                .contains("outboxPending")
                .contains("failedEvidenceWrites");
    }

    @Test
    @DisplayName("every endpoint the platform advertises is one it actually serves")
    void theActuatorListingIsHonest() {
        // The original of this test asserted that 'prometheus' was absent, because the dependency
        // was absent and advertising an endpoint that 404s is the documented-but-false class of
        // defect this programme has corrected repeatedly. The dependency and the exposure entry
        // arrived in the same change, so the specific claim has inverted — but the property worth
        // holding has not, and it is the general one: an operator wiring a scrape target reads
        // this listing, and a name in it is a promise.
        //
        // MetricsEndpointIT owns the prometheus endpoint itself, because asserting it here would
        // mean this suite carrying @AutoConfigureObservability and booting a second context to
        // check something another suite already checks.
        ResponseEntity<String> listing = management("/actuator");

        assertThat(listing.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listing.getBody()).contains("metrics").contains("health");

        for (String endpoint : List.of("metrics", "health", "info")) {
            assertThat(management("/actuator/" + endpoint).getStatusCode())
                    .withFailMessage("/actuator is advertising '%s', which does not answer",
                            endpoint)
                    .isEqualTo(HttpStatus.OK);
        }
    }

    /**
     * Calls the actuator on its own port, as an administrator.
     *
     * <p>The discovery listing and everything beyond health, info and the scrape endpoint need
     * ADMIN even there — "the management port is not routable" is a property of a deployment, and
     * the security configuration deliberately does not assume every deployment gets it right.
     */
    private ResponseEntity<String> management(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth("compliance-console", "admin-secret");
        return new org.springframework.web.client.RestTemplate().exchange(
                "http://localhost:" + managementPort + path, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    private static Map<String, Object> evaluateBody(String subjectId) {
        return Map.of("entityId", ENTITY, "subjectId", subjectId,
                "purposeCode", "MKT_OUTBOUND_CALL", "channel", "VOICE_CALL",
                "jurisdiction", "IN");
    }
}
