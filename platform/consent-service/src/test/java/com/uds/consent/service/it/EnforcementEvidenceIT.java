package com.uds.consent.service.it;

import com.uds.consent.core.decision.DecisionRequest;
import com.uds.consent.core.decision.DecisionResponse;
import com.uds.consent.core.decision.DenialReason;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.IdentifierType;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.SuppressionSource;
import com.uds.consent.ledger.store.EnforcementEvidenceStore;
import com.uds.consent.policy.PolicyEngine;
import com.uds.consent.service.EnforcementRecorder;
import com.uds.consent.service.SuppressionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proving that the platform asked before it acted.
 *
 * <p>The evidence plane could always prove what a subject consented to. Until now it could not
 * prove that anybody checked before dialling them — {@code PolicyEngine.evaluate} returned and
 * forgot, and {@code SuppressionService.scrub} returned counts and wrote nothing, while the
 * decision API's own javadoc called itself "one place a regulator can be shown how the answer was
 * reached".
 *
 * <p>Two regulators want different artefacts and both are asserted here. TRAI asks whether a
 * campaign list was screened at all, which is a property of the run. DPDP Rule 6 asks for logs of
 * processing decisions retained for at least a year, which is a property of the decision.
 */
class EnforcementEvidenceIT extends PostgresIntegrationTest {

    private static final String ENTITY = "DENAVE_IN";

    @Autowired
    private PolicyEngine policy;

    @Autowired
    private EnforcementRecorder recorder;

    @Autowired
    private EnforcementEvidenceStore evidence;

    @Autowired
    private SuppressionService suppression;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private javax.sql.DataSource dataSource;

    @Autowired
    private com.uds.consent.service.config.PlatformProperties properties;

    @Test
    @DisplayName("a denial is recorded with its reason, its caller and its policy version")
    void denialsAreRecordedInFull() {
        String subject = newSubject();
        DecisionRequest request = new DecisionRequest(ENTITY, subject, "MKT_OUTBOUND_CALL",
                Channel.VOICE_CALL, Jurisdiction.IN, "DENAVE_WEB", Instant.now(), "CLIENT_ACME",
                "CAMPAIGN_" + subject, null, Map.of());

        DecisionResponse decision = policy.evaluate(request);
        assertThat(decision.reason()).isEqualTo(DenialReason.NO_CONSENT_RECORD);
        recorder.record(request, decision);

        List<EnforcementEvidenceStore.Denial> denials =
                evidence.denials(ENTITY, subject, null, 10, 0);

        assertThat(denials).singleElement().satisfies(denial -> {
            assertThat(denial.reason()).isEqualTo("NO_CONSENT_RECORD");
            assertThat(denial.purposeCode()).isEqualTo("MKT_OUTBOUND_CALL");
            assertThat(denial.applicationId()).isEqualTo("DENAVE_WEB");
            assertThat(denial.campaignId()).isEqualTo("CAMPAIGN_" + subject);
            // Without the policy version a decision from 2026 can be described in 2031 and not
            // reproduced, and a description is not evidence.
            assertThat(denial.policyVersion()).isNotBlank();
            assertThat(denial.recordedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("allowances are not enumerated, deliberately")
    void allowancesAreNotWrittenOnePerCall() {
        String subject = newSubject();
        DecisionRequest request = new DecisionRequest(ENTITY, subject, "HR_EMPLOYMENT_ADMIN", null,
                Jurisdiction.IN, "DENAVE_WEB", Instant.now(), null, null, null, Map.of());

        DecisionResponse decision = policy.evaluate(request);
        assertThat(decision.isAllowed()).isTrue();
        recorder.record(request, decision);

        // A dialer at a hundred thousand calls a day would otherwise write a hundred thousand rows
        // to prove nothing happened, and evidence that gets switched off under load leaves its gap
        // on exactly the busy days. Screening coverage is proved by the scrub run instead.
        assertThat(evidence.denials(ENTITY, subject, null, 10, 0)).isEmpty();
    }

    @Test
    @DisplayName("a scrub writes exactly one run row carrying the reason histogram")
    void aScrubWritesOneRunRow() {
        String campaign = "CAMPAIGN_" + UUID.randomUUID();
        String suppressed = "+919000000001";

        suppression.optOut(ENTITY, com.uds.consent.core.model.SuppressionScope.ENTITY,
                SuppressionSource.INBOUND_OPT_OUT, Channel.VOICE_CALL, IdentifierType.PHONE,
                suppressed, null, null, null, "asked on a call", "test");

        ResponseEntity<String> response = rest.withBasicAuth("athena-dialer", "decision-secret")
                .postForEntity("/v1/suppression/scrub", Map.of(
                        "entityId", ENTITY,
                        "channel", "VOICE_CALL",
                        "identifierType", "PHONE",
                        "identifiers", List.of(suppressed, "+919000000002", "not-a-number"),
                        "campaignId", campaign), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<EnforcementEvidenceStore.ScrubRun> runs = evidence.scrubRuns(ENTITY, campaign, 10, 0);
        assertThat(runs).singleElement().satisfies(run -> {
            assertThat(run.submittedCount()).isEqualTo(3);
            assertThat(run.excludedCount()).isEqualTo(2);
            assertThat(run.permittedCount()).isEqualTo(1);
            assertThat(run.actorId()).isEqualTo("athena-dialer");
            // The histogram, not the numbers. Storing the identifiers would rebuild the contact
            // list inside the evidence plane, which is what the hashing design exists to prevent.
            assertThat(run.reasonCountsJson()).contains("INBOUND_OPT_OUT")
                    .contains("UNPARSEABLE_IDENTIFIER");
        });
    }

    @Test
    @DisplayName("a failed evidence write does not fail the decision")
    void evidenceFailureNeverBreaksTheDecision() {
        // The load-bearing property of the whole item. If this inverted, a full tablespace on the
        // evidence table would stop the dialer, stop the CRM export and stop lawful processing
        // across the group — and whoever was paged would disable the recorder, losing the evidence
        // permanently rather than for an afternoon.
        long failuresBefore = recorder.failedWrites();
        String subject = newSubject();

        DecisionRequest request = new DecisionRequest(
                // An entity that does not exist violates the foreign key, which is the cheapest
                // way to make the insert fail for real rather than through a mock.
                "ENTITY_THAT_DOES_NOT_EXIST", subject, "MKT_OUTBOUND_CALL", Channel.VOICE_CALL,
                Jurisdiction.IN, null, Instant.now(), null, null, null, Map.of());

        DecisionResponse decision = policy.evaluate(request);
        assertThat(decision.isAllowed()).isFalse();

        // Does not throw.
        recorder.record(request, decision);

        // And the failure is counted rather than swallowed silently. Best-effort with no counter
        // is indistinguishable from a recorder nobody wired up.
        assertThat(recorder.failedWrites()).isEqualTo(failuresBefore + 1);
    }

    @Test
    @DisplayName("recorded evidence cannot be edited or deleted")
    void evidenceIsAppendOnly() {
        String subject = newSubject();
        DecisionRequest request = new DecisionRequest(ENTITY, subject, "MKT_OUTBOUND_CALL",
                Channel.VOICE_CALL, Jurisdiction.IN, null, Instant.now(), null, null, null,
                Map.of());
        recorder.record(request, policy.evaluate(request));

        // Evidence the application can quietly rewrite proves whatever it last decided it should
        // prove. Same trigger family as the ledger, for the same reason.
        assertThatThrownBy(() -> jdbc.update(
                "update enforcement_decision set reason = 'NONE' where subject_id = ?", subject))
                .rootCause().hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbc.update(
                "delete from enforcement_decision where subject_id = ?", subject))
                .rootCause().hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("the evidence log is readable by ADMIN over HTTP, and by nobody else")
    void evidenceIsAdminOnly() {
        String path = "/v1/admin/enforcement/denials?entityId=" + ENTITY;

        assertThat(rest.getForEntity(path, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.withBasicAuth("athena-dialer", "decision-secret")
                .getForEntity(path, String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rest.withBasicAuth("compliance-console", "admin-secret")
                .getForEntity(path, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // An immutable log nobody can read is a control in name and a cost in practice — the same
        // argument that put a reader on the administrative audit trail.
        assertThat(rest.withBasicAuth("compliance-console", "admin-secret")
                .getForEntity("/v1/admin/enforcement/scrub-runs?entityId=" + ENTITY, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("the decision API records as a side effect of answering")
    void theApiItselfRecords() {
        String subject = newSubject();
        String campaign = "CAMPAIGN_" + UUID.randomUUID();

        rest.withBasicAuth("athena-dialer", "decision-secret").postForEntity("/v1/evaluate",
                Map.of("entityId", ENTITY, "subjectId", subject,
                        "purposeCode", "MKT_OUTBOUND_CALL", "channel", "VOICE_CALL",
                        "jurisdiction", "IN", "campaignId", campaign),
                String.class);

        // The point of wiring it into the controller rather than leaving it to callers: a caller
        // who forgets is a caller with no evidence, and the ones most likely to forget are the
        // ones under the most load.
        assertThat(evidence.denials(ENTITY, subject, campaign, 10, 0)).hasSize(1);
    }

    @Test
    @DisplayName("a failing scrub write is swallowed and counted, like a failing denial")
    void aFailedScrubWriteIsAlsoCounted() {
        // The denial path is covered above by making the insert violate a foreign key. The scrub
        // path cannot be broken that way — its row has no entity foreign key to violate — so the
        // store is subclassed to throw. Both halves matter: a scrub run is the artefact TRAI asks
        // for, and losing it silently would mean the platform could not show a campaign list was
        // ever screened.
        EnforcementEvidenceStore broken = new EnforcementEvidenceStore(dataSource) {
            @Override
            public long recordScrubRun(ScrubRun run) {
                throw new IllegalStateException("evidence tablespace is full");
            }
        };
        EnforcementRecorder fragile = new EnforcementRecorder(broken, properties);

        fragile.recordScrub(ENTITY, "VOICE_CALL", null, "CAMPAIGN_" + UUID.randomUUID(), "tester",
                0, new SuppressionService.ScrubResult(List.of(), List.of()));

        assertThat(fragile.failedWrites()).isEqualTo(1);
    }

    private static String newSubject() {
        return "ev-" + UUID.randomUUID();
    }
}
