package com.uds.consent.service.it;

import com.uds.consent.core.model.ActorType;
import com.uds.consent.core.model.CaptureMethod;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.IdentifierType;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.service.api.dto.ConsentApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The API as an integrator meets it: over HTTP, with credentials, through the real security chain.
 *
 * <p>The role model is the substance here. A dialer that can record consent is a dialer that can
 * manufacture it — so {@code DECISION} may ask questions and may not write answers, and that
 * separation is worth testing rather than trusting, because it is expressed in annotations that
 * nothing else would notice the absence of.
 */
class ConsentApiIT extends PostgresIntegrationTest {

    private static final String ENTITY = "DENAVE_IN";
    private static final String NOTICE = "NOTICE_DENAVE_B2B";

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("a capture client records consent and the decision client sees it immediately")
    void captureThenEvaluateOverHttp() {
        String subject = "it-" + UUID.randomUUID();

        ResponseEntity<ConsentApi.CaptureResponse> captured = asCapture()
                .postForEntity("/v1/consent", captureRequest(subject),
                        ConsentApi.CaptureResponse.class);

        assertThat(captured.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(captured.getBody()).isNotNull();
        assertThat(captured.getBody().accepted()).isTrue();
        assertThat(captured.getBody().events()).singleElement()
                .satisfies(event -> assertThat(event.sequenceNumber()).isEqualTo(1L));

        ConsentApi.EvaluateResponse decision = asDecision().postForObject("/v1/evaluate",
                new ConsentApi.EvaluateRequest(ENTITY, subject, "MKT_OUTBOUND_CALL",
                        Channel.VOICE_CALL, Jurisdiction.IN, "ATHENA_DIALER", null, null, null,
                        null, Map.of()),
                ConsentApi.EvaluateResponse.class);

        assertThat(decision.outcome()).isEqualTo("ALLOW");
        assertThat(decision.obligations()).contains("scrub-against-ncpr-before-send");
    }

    @Test
    @DisplayName("a pre-ticked box comes back as 422 with the violation named")
    void invalidCaptureIsRefusedWithReasons() {
        String subject = "it-" + UUID.randomUUID();
        ConsentApi.CaptureRequest request = new ConsentApi.CaptureRequest(ENTITY, subject, null, null,
                Jurisdiction.IN, "en", Channel.WEB, "DENAVE_WEB", CaptureMethod.CHECKBOX_OPT_IN,
                ActorType.SUBJECT, subject, NOTICE, 1,
                List.of(new ConsentApi.PurposeChoiceDto("MKT_OUTBOUND_CALL", true, true, true)),
                true, Instant.now(), "http-" + subject, null, Map.of(), null);

        ResponseEntity<ConsentApi.CaptureResponse> response = asCapture()
                .postForEntity("/v1/consent", request, ConsentApi.CaptureResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().violations()).extracting(ConsentApi.ViolationDto::code)
                .contains("PRE_SELECTED_OPTION");
    }

    @Test
    @DisplayName("the dialer's credential cannot write consent")
    void decisionRoleMayNotCapture() {
        ResponseEntity<String> response = asDecision().postForEntity("/v1/consent",
                captureRequest("it-" + UUID.randomUUID()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("an unauthenticated caller gets nothing")
    void anonymousIsRejected() {
        assertThat(rest.getForEntity("/v1/consent/" + ENTITY + "/nobody", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("verification keys are public, so a device that lost its credential can still verify")
    void verificationKeysNeedNoCredential() {
        // Requiring a credential to fetch a public key would mean a device whose credential has
        // expired also loses the ability to check snapshots it already holds — turning a routine
        // credential problem into a fleet-wide enforcement failure.
        ResponseEntity<ConsentApi.VerificationKey[]> response =
                rest.getForEntity("/v1/keys", ConsentApi.VerificationKey[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEmpty();
        assertThat(response.getBody()[0].algorithm()).isEqualTo("Ed25519");
    }

    @Test
    @DisplayName("history is admin-only; a capture client cannot read the evidence trail")
    void historyIsRestrictedToAdministrators() {
        String subject = "it-" + UUID.randomUUID();
        asCapture().postForEntity("/v1/consent", captureRequest(subject),
                ConsentApi.CaptureResponse.class);

        assertThat(asCapture().getForEntity(
                "/v1/consent/" + ENTITY + '/' + subject + "/history", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<ConsentApi.HistoryEntry[]> asAdmin = asAdmin().getForEntity(
                "/v1/consent/" + ENTITY + '/' + subject + "/history",
                ConsentApi.HistoryEntry[].class);

        assertThat(asAdmin.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(asAdmin.getBody()).hasSize(1);
    }

    @Test
    @DisplayName("a subject given only as a phone number is resolved without the number being stored")
    void subjectMayBeIdentifiedByAHashedIdentifier() {
        String phone = "+9198765" + (10000 + (int) (Math.random() * 89999));
        ConsentApi.CaptureRequest request = new ConsentApi.CaptureRequest(ENTITY, null,
                new ConsentApi.SubjectRef(IdentifierType.PHONE, phone), null, Jurisdiction.IN, "en",
                Channel.WEB, "DENAVE_WEB", CaptureMethod.CHECKBOX_OPT_IN, ActorType.SUBJECT,
                "agent-1", NOTICE, 1,
                List.of(new ConsentApi.PurposeChoiceDto("MKT_OUTBOUND_CALL", true, false, true)),
                true, Instant.now(), "phone-" + phone, null, Map.of(), null);

        ConsentApi.CaptureResponse response = asCapture()
                .postForObject("/v1/consent", request, ConsentApi.CaptureResponse.class);

        assertThat(response.accepted()).isTrue();
        assertThat(response.subjectId()).isNotBlank().doesNotContain(phone.substring(3));
    }

    @Test
    @DisplayName("a signed snapshot is issued over HTTP with the key id that signed it")
    void snapshotIsIssuedOverHttp() {
        String subject = "it-" + UUID.randomUUID();
        asCapture().postForEntity("/v1/consent", captureRequest(subject),
                ConsentApi.CaptureResponse.class);

        ConsentApi.SnapshotResponse snapshot = asDecision().getForObject(
                "/v1/snapshot/" + ENTITY + '/' + subject, ConsentApi.SnapshotResponse.class);

        assertThat(snapshot.snapshot()).contains(".");
        assertThat(snapshot.keyId()).isNotBlank();
        assertThat(snapshot.expiresAt()).isAfter(snapshot.issuedAt());
    }

    // -------------------------------------------------------------------------------------------

    private static ConsentApi.CaptureRequest captureRequest(String subjectId) {
        return new ConsentApi.CaptureRequest(ENTITY, subjectId, null, null, Jurisdiction.IN, "en",
                Channel.WEB, "DENAVE_WEB", CaptureMethod.CHECKBOX_OPT_IN, ActorType.SUBJECT,
                subjectId, NOTICE, 1,
                List.of(new ConsentApi.PurposeChoiceDto("MKT_OUTBOUND_CALL", true, false, true)),
                true, Instant.now(), "http-" + subjectId, "evidence://form/1", Map.of(), null);
    }

    private TestRestTemplate asCapture() {
        return rest.withBasicAuth("denave-web", "capture-secret");
    }

    private TestRestTemplate asDecision() {
        return rest.withBasicAuth("athena-dialer", "decision-secret");
    }

    private TestRestTemplate asAdmin() {
        return rest.withBasicAuth("compliance-console", "admin-secret");
    }
}
