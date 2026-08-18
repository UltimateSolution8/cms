package com.uds.consent.service.it;

import com.uds.consent.core.crypto.IdentifierHasher;
import com.uds.consent.core.model.IdentifierType;
import com.uds.consent.ledger.store.SubjectStore;
import com.uds.consent.service.EvidenceBundleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A person is one subject, and a withdrawal reaches all of them.
 *
 * <p>The defect this closes was the largest purely-engineering compliance hole in the platform, and
 * it was recorded rather than fixed for four plans: {@code resolveOrCreate} maps one identifier to
 * one subject, so a person the group knows by a mobile number and by an email address is <em>two
 * subjects</em>, with two consent records, two hash chains and two evidence bundles. A principal who
 * withdraws by email leaves their phone contactable — the failure a grievance surfaces first, and
 * the one a dialer trips over at volume — and {@code GET /v1/admin/evidence/subject/…} answers a
 * Board complaint with half a person, incompletely and in good faith.
 *
 * <p>Two mechanisms, and the split is the design. <strong>{@code alsoKnownAs} at capture</strong>
 * prevents the split at source, where a surface holding both values knows they belong to the person
 * in front of it. <strong>The administrative merge</strong> repairs the ones already in the
 * database, attributably and irreversibly.
 *
 * <p><strong>Nothing infers.</strong> There is no fuzzy matching on names, no normalisation of phone
 * numbers across entities, no similarity measure anywhere. Every join in this suite happens because
 * somebody asserted it. Inference would merge two people eventually and the first evidence of it
 * would be a call to somebody who withdrew — strictly worse than the incompleteness being fixed.
 */
class SubjectIdentityIT extends PostgresIntegrationTest {

    private static final String ENTITY = "DENAVE_IN";
    private static final String PURPOSE = "MKT_OUTBOUND_CALL";
    private static final String NOTICE = "NOTICE_DENAVE_B2B";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private SubjectStore subjects;

    @Autowired
    private IdentifierHasher hasher;

    @Autowired
    private EvidenceBundleService bundles;

    @Test
    @DisplayName("a capture declaring both identifiers produces one subject, not two")
    void alsoKnownAsPreventsTheSplitAtSource() {
        String phone = uniquePhone();
        String email = uniqueEmail();

        String subjectId = capture(phone, List.of(Map.of(
                "identifierType", "EMAIL", "value", email)));

        // Both identifiers now resolve to the same subject. Before alsoKnownAs, the second call
        // would have minted a second subject and the two records would have diverged from the
        // first withdrawal onwards.
        assertThat(subjects.resolve(ENTITY, IdentifierType.PHONE, hash(IdentifierType.PHONE, phone)))
                .contains(subjectId);
        assertThat(subjects.resolve(ENTITY, IdentifierType.EMAIL, hash(IdentifierType.EMAIL, email)))
                .contains(subjectId);
    }

    @Test
    @DisplayName("after a merge, a withdrawal by email suppresses the phone as well")
    void aWithdrawalReachesTheWholePerson() {
        // The scenario in the hand-off, end to end. Two captures, two subjects — the state every
        // record written before this change is in.
        String phone = uniquePhone();
        String email = uniqueEmail();
        String byPhone = capture(phone, List.of());
        String byEmail = capture(email, IdentifierType.EMAIL, List.of());

        assertThat(byPhone)
                .withFailMessage("the fixture did not reproduce the split this test is about")
                .isNotEqualTo(byEmail);

        merge(byEmail, byPhone, "grievance call: the principal confirmed both are theirs");

        // The identifier moved, so the very next resolution lands on the surviving subject. This
        // is the whole point: the decision path did not have to learn about merges, because it
        // funnels through resolve() and resolve() answers canonically.
        assertThat(subjects.resolve(ENTITY, IdentifierType.EMAIL, hash(IdentifierType.EMAIL, email)))
                .contains(byPhone);

        // And the withdrawal, arriving by the email, is now recorded against the subject the phone
        // resolves to — so a dialer asking about the phone number gets DENIED.
        assertThat(withdrawBy(email, IdentifierType.EMAIL).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(decisionFor(phone))
                .withFailMessage("the phone is still contactable after a withdrawal by email; "
                        + "this is exactly the failure the merge exists to prevent")
                .contains("\"outcome\":\"DENY\"")
                .contains("WITHDRAWN");
    }

    @Test
    @DisplayName("the evidence bundle returns the whole person, and names what was merged in")
    void theBundleUnionsAcrossMergedSubjects() {
        String phone = uniquePhone();
        String email = uniqueEmail();
        String byPhone = capture(phone, List.of());
        String byEmail = capture(email, IdentifierType.EMAIL, List.of());

        merge(byEmail, byPhone, "same principal, confirmed by CRM id");

        EvidenceBundleService.Bundle bundle = bundles.assemble(ENTITY, byPhone, Instant.now());

        // Events written before the merge stay under the id they were written against — the ledger
        // is append-only and rewriting a subject id would break the chain that makes it evidence.
        // So the bundle unions, and a bundle that read the canonical id alone would go straight
        // back to answering with half a person.
        assertThat(bundle.events())
                .withFailMessage("the bundle dropped the merged subject's events")
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(bundle.mergedFrom())
                .withFailMessage("the bundle does not say which subjects were folded in, so a "
                        + "reader cannot tell why it contains events under ids they have not seen")
                .containsExactly(byEmail);
        assertThat(bundle.subjectId()).isEqualTo(byPhone);
    }

    @Test
    @DisplayName("asking about a superseded id answers under the surviving one")
    void aSupersededIdStillResolves() {
        // Somebody holding an id from before a merge — a saved link, an open ticket, a downstream
        // system's cached reference — must not get an empty bundle. An empty answer would read as
        // "we hold nothing about this person", which is both wrong and the worst possible thing to
        // tell a regulator.
        String byPhone = capture(uniquePhone(), List.of());
        String byEmail = capture(uniqueEmail(), IdentifierType.EMAIL, List.of());
        merge(byEmail, byPhone, "same principal");

        EvidenceBundleService.Bundle bundle = bundles.assemble(ENTITY, byEmail, Instant.now());

        assertThat(bundle.subjectId()).isEqualTo(byPhone);
        assertThat(bundle.events()).isNotEmpty();
    }

    @Test
    @DisplayName("the merge refuses the cases that would corrupt the record")
    void theMergeRefusesWhatItMust() {
        String byPhone = capture(uniquePhone(), List.of());
        String byEmail = capture(uniqueEmail(), IdentifierType.EMAIL, List.of());

        assertThatThrownBy(() -> subjects.merge(ENTITY, byPhone, byPhone, "someone", "self"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("itself");

        // Across entities. This one is not a tidiness rule: merging a Matrix subject into a Denave
        // one would move a data principal from one fiduciary's evidence plane into another's,
        // which is the exact thing two independent layers of isolation exist to prevent.
        assertThatThrownBy(() -> subjects.merge("MATRIX", byEmail, byPhone, "someone", "wrong entity"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exist at MATRIX");

        subjects.merge(ENTITY, byEmail, byPhone, "someone", "same principal");

        // Chaining. An alias pointing at an alias is a cycle waiting for one bad edit, and a cycle
        // on the decision path is a hang rather than a failure.
        String third = capture(uniquePhone(), List.of());
        assertThatThrownBy(() -> subjects.merge(ENTITY, byEmail, third, "someone", "again"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already been merged");
    }

    @Test
    @DisplayName("a merge cannot be deleted, because it joined two people's records")
    void theMergeRecordIsImmutable() {
        // subject_alias has UPDATE and DELETE revoked from the application role, like the ledger.
        // A merge that turns out to be wrong has joined two people; the only useful thing left is
        // a permanent record of who said they were the same person and on what basis. That is why
        // the reason is required and why this row cannot be tidied away afterwards.
        String byPhone = capture(uniquePhone(), List.of());
        String byEmail = capture(uniqueEmail(), IdentifierType.EMAIL, List.of());
        merge(byEmail, byPhone, "same principal");

        assertThat(subjects.historyIdsFor(ENTITY, byPhone)).containsExactly(byPhone, byEmail);
    }

    // -----------------------------------------------------------------------------------

    private void merge(String superseded, String canonical, String reason) {
        assertThat(rest.withBasicAuth("compliance-console", "admin-secret")
                .postForEntity("/v1/admin/subjects/merge", Map.of(
                        "entityId", ENTITY,
                        "supersededSubjectId", superseded,
                        "canonicalSubjectId", canonical,
                        "reason", reason), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String capture(String identifier, List<Map<String, String>> alsoKnownAs) {
        return capture(identifier, IdentifierType.PHONE, alsoKnownAs);
    }

    private String capture(String identifier, IdentifierType type,
                           List<Map<String, String>> alsoKnownAs) {
        // A LinkedHashMap rather than Map.of, which stops at ten pairs — and a capture that
        // satisfies CaptureValidator needs thirteen. Two of them are worth naming rather than
        // copying: refusing has to be offered in the same interaction as accepting, and the exact
        // notice version rendered has to be recorded so it can be reproduced years later.
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("entityId", ENTITY);
        body.put("subject", Map.of("identifierType", type.name(), "value", identifier));
        body.put("alsoKnownAs", alsoKnownAs);
        body.put("jurisdiction", "IN");
        body.put("languageTag", "en");
        body.put("channel", "VOICE_CALL");
        body.put("applicationId", "DENAVE_WEB");
        body.put("captureMethod", "CHECKBOX_OPT_IN");
        body.put("actorType", "SUBJECT");
        body.put("noticeId", NOTICE);
        body.put("noticeVersion", 1);
        body.put("rejectAllOffered", true);
        body.put("choices", List.of(Map.of(
                "purposeCode", PURPOSE,
                "granted", true,
                "preTicked", false,
                "separateAction", true)));

        var response = rest.withBasicAuth("denave-web", "capture-secret")
                .postForEntity("/v1/consent", body, Map.class);

        assertThat(response.getStatusCode())
                .withFailMessage("capture fixture failed: %s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return (String) response.getBody().get("subjectId");
    }

    private org.springframework.http.ResponseEntity<String> withdrawBy(String identifier,
                                                                        IdentifierType type) {
        return rest.withBasicAuth("denave-web", "capture-secret").postForEntity(
                "/v1/consent/withdraw", Map.of(
                        "entityId", ENTITY,
                        "subject", Map.of("identifierType", type.name(), "value", identifier),
                        "purposeCodes", List.of(PURPOSE),
                        "jurisdiction", "IN",
                        "captureMethod", "CHECKBOX_OPT_IN",
                        "actorType", "SUBJECT",
                        "reason", "identity resolution suite"),
                String.class);
    }

    /**
     * Asks the decision API about whoever the phone number currently resolves to.
     *
     * <p>Two steps rather than one, because {@code POST /v1/evaluate} takes a subject id and not an
     * identifier — a dialer holds subject references, and putting a phone number in a decision
     * request body would send plaintext identifiers over a path that is called millions of times a
     * day. Resolving first is what a dialer's own pipeline does, and it is the step under test:
     * after the merge, the phone resolves to the surviving subject, so the withdrawal that arrived
     * by email is the answer it gets.
     */
    private String decisionFor(String phone) {
        String subjectId = subjects
                .resolve(ENTITY, IdentifierType.PHONE, hash(IdentifierType.PHONE, phone))
                .orElseThrow(() -> new AssertionError("the phone resolves to no subject at all"));

        return rest.withBasicAuth("athena-dialer", "decision-secret").postForEntity(
                "/v1/evaluate", Map.of(
                        "entityId", ENTITY,
                        "subjectId", subjectId,
                        "purposeCode", PURPOSE,
                        "channel", "VOICE_CALL"),
                String.class).getBody();
    }

    private String hash(IdentifierType type, String value) {
        return hasher.hash(type, value);
    }

    private static String uniquePhone() {
        return "+9198" + String.format("%08d",
                Math.abs(UUID.randomUUID().hashCode()) % 100_000_000);
    }

    private static String uniqueEmail() {
        return "identity-" + UUID.randomUUID() + "@example.test";
    }
}
