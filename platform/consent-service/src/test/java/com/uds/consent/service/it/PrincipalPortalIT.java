package com.uds.consent.service.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uds.consent.core.model.RightsVerificationMethod;
import com.uds.consent.ledger.store.OutboxStore;
import com.uds.consent.ledger.store.RightsRequestStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The data principal's way in, and the two properties that make it safe to leave unauthenticated.
 *
 * <p>Every route on the platform required a credential, so the group's ability to receive a rights
 * request was its ability to answer the phone. DPDP <strong>Rule 14(1)</strong> requires the means
 * of exercising a right to be published, and {@code NoticeStore.rightsUri} — reproduced on every
 * consent receipt ever issued — pointed at a page that did not exist.
 *
 * <p>Opening an unauthenticated write path into a regulated system is the kind of change that is
 * either done carefully or should not be done. Two of the tests below are the reason it is safe:
 * {@link #aKnownAndAnUnknownIdentifierAreIndistinguishable} and
 * {@link #theStatutoryClockStartsAtVerification}. The rest are the ordinary mechanics.
 */
class PrincipalPortalIT extends PostgresIntegrationTest {

    private static final String ENTITY = "DENAVE_IN";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private OutboxStore outbox;

    @Autowired
    private RightsRequestStore requests;

    @Test
    @DisplayName("a known and an unknown identifier produce byte-identical responses")
    void aKnownAndAnUnknownIdentifierAreIndistinguishable() throws Exception {
        // The property that makes this route safe to leave open. If the response differed, anyone
        // with a list of phone numbers could learn which of them UDS holds a file on — a
        // disclosure about every person on that list, produced by the feature built to serve them.
        //
        // It holds because the submission path never looks the identifier up, not because two
        // branches were carefully matched. There is no branch.
        String known = "+919812345001";
        captureConsentFor(known);

        JsonNode forKnown = submit(known, "ACCESS");
        JsonNode forUnknown = submit("+919899999999", "ACCESS");

        // Everything except the reference and its expiry, which are random and time-based by
        // construction. Compared field by field so a future addition to the response has to be
        // considered rather than silently letting the two diverge.
        assertThat(forKnown.get("message").asText())
                .withFailMessage("the message differs between a known and an unknown identifier, "
                        + "which turns this route into an oracle over the group's contact list")
                .isEqualTo(forUnknown.get("message").asText());
        assertThat(forKnown.fieldNames()).toIterable()
                .containsExactlyElementsOf(() -> forUnknown.fieldNames());
        assertThat(forKnown.get("reference").asText()).startsWith("PR-");
        assertThat(forUnknown.get("reference").asText()).startsWith("PR-");
    }

    @Test
    @DisplayName("the statutory clock starts at verification, not at submission")
    void theStatutoryClockStartsAtVerification() throws Exception {
        // The second reason this is safe to leave open. StatutoryClock derives a deadline that
        // Rule 14(3) caps at ninety days. If an anonymous submission started it, anyone could burn
        // the group's whole response window on somebody else's behalf — repeatedly, and without
        // ever proving they were that person.
        String identifier = "+919812345002";
        JsonNode submission = submit(identifier, "ERASURE");
        String reference = submission.get("reference").asText();

        // Nothing exists yet. A rights_request created at submission would already be counting.
        assertThat(requests.findOpen(ENTITY, 500, 0))
                .withFailMessage("a rights request was created before the principal proved the "
                        + "identifier was theirs")
                .noneMatch(request -> reference.equals(request.details()));

        Instant beforeVerification = Instant.now();
        JsonNode verified = verify(reference, tokenFor(reference));

        assertThat(verified.get("requestId").asText()).startsWith("RR-");
        Instant receivedAt = Instant.parse(verified.get("receivedAt").asText());
        assertThat(receivedAt)
                .withFailMessage("received_at predates verification, so the clock was started by "
                        + "an unauthenticated submission")
                .isAfterOrEqualTo(beforeVerification.minusSeconds(2));
        assertThat(Instant.parse(verified.get("dueAt").asText())).isAfter(receivedAt);
    }

    @Test
    @DisplayName("a verified request is an ordinary rights request with an ordinary clock")
    void aVerifiedRequestJoinsTheQueue() throws Exception {
        String reference = submit("+919812345003", "ACCESS").get("reference").asText();
        String requestId = verify(reference, tokenFor(reference)).get("requestId").asText();

        // Not a parallel world. It appears in the same queue the compliance console works, subject
        // to the same SLA sweep and the same fulfilment gate — the portal is a front door, not a
        // second system.
        RightsRequestStore.Request filed = requests.find(requestId).orElseThrow();
        assertThat(filed.entityId()).isEqualTo(ENTITY);
        assertThat(filed.status().isOpen()).isTrue();
        assertThat(filed.details())
                .withFailMessage("the request does not record that it came from the principal "
                        + "themselves, which is exactly the provenance an auditor asks about")
                .contains(reference);

        // And the provenance is a typed field rather than only prose in a details string, because
        // "how many of our open requests started on an instant nobody checked" is a question that
        // has to be answerable by a query. This is the only path on the platform that establishes
        // identity for itself; everything filed at the console is an operator's claim or nothing.
        assertThat(filed.verification()).isEqualTo(RightsVerificationMethod.PORTAL_TOKEN);
        assertThat(filed.verifiedAt())
                .withFailMessage("the clock's start instant and the verification instant have "
                        + "drifted apart on the one path where they are the same act")
                .isEqualTo(filed.receivedAt());
    }

    @Test
    @DisplayName("a wrong code is refused and burns an attempt, and the cap closes the reference")
    void guessingIsBounded() throws Exception {
        // Ten characters from a 32-symbol alphabet is a trillion possibilities, which is only
        // safe against an online attack if the attempts are bounded. Five, then the reference is
        // dead — including for the real holder, which is the correct trade: they can submit again.
        String reference = submit("+919812345004", "ACCESS").get("reference").asText();

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(verifyRaw(reference, "WRONGCODE1").getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        assertThat(verifyRaw(reference, tokenFor(reference)).getStatusCode())
                .withFailMessage("the correct code still worked after five failures; the attempt "
                        + "cap is not being enforced")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a code is single-use")
    void aCodeCannotBeReplayed() throws Exception {
        String reference = submit("+919812345005", "ACCESS").get("reference").asText();
        String token = tokenFor(reference);

        assertThat(verifyRaw(reference, token).getStatusCode()).isEqualTo(HttpStatus.OK);
        // Otherwise a code in a forwarded email files a second request every time somebody clicks
        // it, and each one carries its own statutory deadline.
        assertThat(verifyRaw(reference, token).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("an unknown reference fails exactly like a wrong code")
    void refusalsAreUndifferentiated() {
        ResponseEntity<String> unknownReference = verifyRaw("PR-DOESNOTEXIST", "WRONGCODE1");

        assertThat(unknownReference.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // Distinguishing these would confirm which references exist, which is the one fact a
        // caller guessing at references is trying to establish.
        assertThat(unknownReference.getBody()).contains("Reference or code not recognised");
    }

    @Test
    @DisplayName("status returns the clock and nothing else about the person")
    void statusIsNarrow() throws Exception {
        String reference = submit("+919812345006", "CORRECTION").get("reference").asText();
        String token = tokenFor(reference);
        verify(reference, token);

        ResponseEntity<String> status = rest.getForEntity(
                "/v1/portal/requests/" + reference + "?code=" + token, String.class);

        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status.getBody()).contains("dueAt").contains("CORRECTION");
        // A code delivered to an email address is not the authentication standard on which to hand
        // over a person's complete file. The evidence bundle stays behind ADMIN.
        assertThat(status.getBody())
                .withFailMessage("the status response leaks the subject id, which is the join key "
                        + "to every other record about this person")
                .doesNotContain("subjectId");
    }

    @Test
    @DisplayName("the code leaves the platform through the outbox and is never stored in the clear")
    void theTokenLeavesOnlyThroughTheOutbox() throws Exception {
        // The platform sends nothing — it never has. It mints the code and hands it to whichever
        // system sends messages, which is also why identifierHash rather than an address travels
        // with it: this platform holds no contact details.
        String reference = submit("+919812345007", "ACCESS").get("reference").asText();

        JsonNode payload = outboxPayload(reference);
        assertThat(payload.get("token").asText()).hasSize(10);
        assertThat(payload.get("identifierHash").asText()).hasSize(64);
        assertThat(payload.has("identifierValue"))
                .withFailMessage("the raw identifier is on the outbox message; the platform is "
                        + "forwarding contact details it deliberately does not store")
                .isFalse();
    }

    // ---------------------------------------------------------------------------------------

    private JsonNode submit(String identifier, String requestType) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("entityId", ENTITY);
        body.put("identifierType", "PHONE");
        body.put("identifierValue", identifier);
        body.put("requestType", requestType);
        body.put("jurisdiction", "IN");

        ResponseEntity<String> response =
                rest.postForEntity("/v1/portal/requests", body, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return JSON.readTree(response.getBody());
    }

    private JsonNode verify(String reference, String token) throws Exception {
        ResponseEntity<String> response = verifyRaw(reference, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return JSON.readTree(response.getBody());
    }

    private ResponseEntity<String> verifyRaw(String reference, String token) {
        return rest.postForEntity("/v1/portal/requests/" + reference + "/verify",
                Map.of("code", token), String.class);
    }

    /**
     * Reads the code out of the outbox, which is where a real sending system would read it.
     *
     * <p>There is no other way to get it, and that is the design working: the platform stores only
     * a peppered hash, so a test that could recover the token from the database would mean a
     * leaked backup could too.
     */
    private String tokenFor(String reference) throws Exception {
        return outboxPayload(reference).get("token").asText();
    }

    private JsonNode outboxPayload(String reference) throws Exception {
        for (OutboxStore.PendingMessage message : outbox.fetchUnpublished(500)) {
            if ("rights.verification.requested".equals(message.topic())
                    && reference.equals(message.eventKey())) {
                return JSON.readTree(message.payload());
            }
        }
        throw new AssertionError("no verification message was enqueued for " + reference
                + "; the principal would never receive a code and the request would expire");
    }

    private void captureConsentFor(String identifier) {
        Map<String, Object> capture = new LinkedHashMap<>();
        capture.put("entityId", ENTITY);
        capture.put("identifierType", "PHONE");
        capture.put("identifierValue", identifier);
        capture.put("purposeCode", "MKT_OUTBOUND_CALL");
        capture.put("purposeVersion", 1);
        capture.put("jurisdiction", "IN");
        capture.put("captureMethod", "WEB_FORM");
        capture.put("noticeId", "NOTICE-DENAVE-MKT-EN");
        capture.put("noticeVersion", 1);
        capture.put("noticeLanguage", "en");
        capture.put("channels", java.util.List.of("VOICE_CALL"));
        capture.put("applicationId", "denave-web");
        capture.put("idempotencyKey", UUID.randomUUID().toString());

        rest.withBasicAuth("denave-web", "capture-secret")
                .postForEntity("/v1/consent", capture, String.class);
    }
}
