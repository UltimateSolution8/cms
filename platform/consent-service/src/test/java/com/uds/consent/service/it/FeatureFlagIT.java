package com.uds.consent.service.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The speculative regulatory surfaces are genuinely dark at their production defaults.
 *
 * <p>Two features are built, tested and correct for regimes that do not yet reach UDS: Korea's
 * two-year re-confirmation queue (Network Act Enforcement Decree Art. 62-3) and the DPDP Rule 4
 * Consent Manager relay. Both used to run by default — a scheduled sweep and a write-capable relay
 * for obligations nobody in the group owes.
 *
 * <p>{@code application-integrationtest.yml} turns both <em>on</em>, because the suites that prove
 * they work must keep running. That is exactly why this suite is needed: with the flags on
 * everywhere the tests look, "off by default" would be an assertion about a YAML file nobody
 * executes. So this class boots a second context at the production defaults and asks the platform
 * what it actually serves.
 *
 * <p>The two features are gated differently on purpose, and the difference is asserted rather than
 * assumed. The relay is a whole controller and is conditional on the bean: with the flag off the
 * routes are not mapped at all. The Korean queue is four routes inside a sixty-route admin
 * controller, so it is gated at the handler and refuses with a {@code ProblemDetail} naming the
 * flag. Both answer 404 — which is the point. A 403 would tell an integrator the surface exists and
 * to go and ask for a permission nobody can grant, and an empty 200 from the queue would read as
 * "nothing is owed" when it means "nothing is being looked for".
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // The production defaults, restated here rather than inherited. Restating them is the
        // assertion: if somebody flips a default in application.yml, this suite keeps testing the
        // behaviour that was signed off rather than silently following them.
        properties = {
                "uds.consent.features.korea-reconfirmation=false",
                "uds.consent.features.consent-manager-relay=false"
        })
class FeatureFlagIT extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    /** The actuator's own port; health is no longer served on the traffic port. */
    @LocalManagementPort
    private int managementPort;

    @Test
    @DisplayName("the Consent Manager relay is not mapped when the feature is off")
    void theRelaySurfaceIsAbsent() {
        // As a credential that would be allowed through if the routes existed. Authorising first
        // and finding nothing there is the assertion that matters — a 401 or a 403 would prove
        // only that the security rules ran, which they would do over a mapped route too.
        TestRestTemplate cm = rest.withBasicAuth("cm-test-client", "cm-secret");

        assertThat(cm.postForEntity("/v1/consent-manager/CM-TEST-0001/grant", "{}", String.class)
                .getStatusCode())
                .withFailMessage("the Consent Manager grant relay is still reachable with the "
                        + "feature off; this is the widest write surface on the platform and it "
                        + "verifies no signature")
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(cm.postForEntity("/v1/consent-manager/CM-TEST-0001/withdraw", "{}", String.class)
                .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(cm.getForEntity("/v1/consent-manager/CM-TEST-0001/subjects/ref-1/record",
                String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("the Korean re-confirmation routes refuse, naming the flag that would open them")
    void theKoreanQueueRefusesAndSaysWhy() {
        TestRestTemplate admin = rest.withBasicAuth("compliance-console", "admin-secret");

        ResponseEntity<String> due = admin.getForEntity(
                "/v1/admin/reconfirmation/due?entityId=DENAVE_KR", String.class);

        // Not an empty list. An empty 200 here is the failure this test exists for: it reads to an
        // operator as "Korea owes nothing today" and means "nothing has been looking".
        assertThat(due.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(due.getBody())
                .withFailMessage("the refusal does not name the property that would enable it, "
                        + "which is the one thing the operator meeting this needs")
                .contains("uds.consent.features.korea-reconfirmation");

        assertThat(admin.postForEntity("/v1/admin/reconfirmation/sweep", null, String.class)
                .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("an unmapped path is a 404, not a 500 — the defect this suite turned up")
    void anUnknownRouteIsNotAServerError() {
        // Not about feature flags, and found while asserting one. The catch-all
        // @ExceptionHandler(Exception.class) in ApiExceptionHandler was swallowing Spring's
        // NoResourceFoundException, so every request to a path this platform does not serve came
        // back 500 with "check the service logs with the trace id" and wrote an ERROR line.
        //
        // Two costs, both real. An integrator with a typo was told the platform was broken and
        // would reasonably retry. And once the SLA ERROR logs are wired to on-call — which
        // OPERATIONS.md §4 assumes — a scanner walking for /wp-admin would page somebody, which is
        // how an alerting channel stops being read.
        ResponseEntity<String> response = rest.withBasicAuth("compliance-console", "admin-secret")
                .getForEntity("/v1/no-such-route", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("No such route");
    }

    @Test
    @DisplayName("an unparseable body is a 400 that names the problem, not a 500")
    void aMalformedBodyIsExplained() {
        // The second defect of the same family as the 404 above, and found the same accidental
        // way: a suite sent a plausible-looking captureMethod that is not one of the twelve the
        // platform accepts and got "Internal Server Error — check the service logs with the trace
        // id". Spring raises HttpMessageNotReadableException before the handler runs, and the
        // catch-all @ExceptionHandler(Exception.class) was swallowing it.
        //
        // Capture surfaces are built against this API by teams outside the platform group. Telling
        // them the platform is broken, when the truth is that their enum value is not in the list,
        // is paid for in their afternoons — and in ERROR lines about unhandled failures that are
        // nothing of the kind.
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.withBasicAuth("denave-web", "capture-secret")
                .exchange("/v1/consent", HttpMethod.POST, new HttpEntity<>("""
                        {"entityId":"DENAVE_IN","subjectId":"probe","jurisdiction":"IN",
                         "languageTag":"en","captureMethod":"NOT_A_REAL_METHOD",
                         "actorType":"SUBJECT","choices":[]}""", json), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .withFailMessage("the refusal does not name the field or the accepted values, so "
                        + "the caller learns only that something was wrong")
                .contains("captureMethod");
    }

    @Test
    @DisplayName("the rest of the platform is unaffected by the flags being off")
    void everythingElseStillWorks() {
        // The risk of dark-starting a feature is not that it stays dark. It is that switching it
        // off takes something else with it — a bean the relay controller happened to be holding
        // alive, a security rule that only applied because a route existed. The decision path and
        // the admin surface are what the pilot runs on; they are asserted here because a context
        // that boots is not the same as a context that works.
        assertThat(rest.withBasicAuth("compliance-console", "admin-secret")
                .getForEntity("/v1/admin/purposes", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // On the management port: the actuator moved there so the scrape endpoint is not served
        // where the ingress terminates, and health moved with it.
        assertThat(new org.springframework.web.client.RestTemplate().getForEntity(
                "http://localhost:" + managementPort + "/actuator/health", String.class)
                .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // The Consent Manager *register* stays administrable with the relay dark. An entity may
        // legitimately record registrations before the relay opens, and these routes are ADMIN-only
        // and write nothing to the ledger — so this is a deliberate asymmetry, asserted so that
        // nobody later "fixes" it by making the whole feature one switch.
        assertThat(rest.withBasicAuth("compliance-console", "admin-secret")
                .getForEntity("/v1/admin/consent-managers", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
