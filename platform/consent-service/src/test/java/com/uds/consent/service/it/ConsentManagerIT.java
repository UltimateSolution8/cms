package com.uds.consent.service.it;

import com.uds.consent.core.decision.DecisionRequest;
import com.uds.consent.core.decision.DecisionResponse;
import com.uds.consent.core.model.ActorType;
import com.uds.consent.core.model.CaptureMethod;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.ConsentStatus;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.ledger.service.ConsentLedger;
import com.uds.consent.ledger.store.AdminAuditStore;
import com.uds.consent.ledger.store.ConsentArtefactStore;
import com.uds.consent.ledger.store.ConsentManagerStore;
import com.uds.consent.ledger.store.EnforcementEvidenceStore;
import com.uds.consent.policy.PolicyEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consent relayed through a Consent Manager (DPDP Rules 2025, Rule 4).
 *
 * <p>Operational from 13 November 2026, and the first hard dated obligation in the DPDP rollout.
 * UDS does not register as a Consent Manager and structurally cannot — the First Schedule requires
 * independence from the fiduciaries it intermediates for. What it must do is transact with one.
 *
 * <p>Two properties are being proved here and they pull in opposite directions, which is the point
 * of testing both in one suite. A relayed consent must be <em>indistinguishable in effect</em>: the
 * decision API must honour it exactly as it honours a first-party one, and a relayed withdrawal
 * must stop the next call. And it must be <em>distinguishable in evidence</em>: an auditor must be
 * able to see that this consent came through an intermediary, and follow the registration number
 * back to the register entry that made it legitimate. A platform with only the first property
 * cannot answer an audit; one with only the second does not honour the statutory channel.
 */
class ConsentManagerIT extends PostgresIntegrationTest {

    private static final String ENTITY = "DENAVE_IN";
    private static final String APP = "DENAVE_WEB";
    private static final String NOTICE = "NOTICE_DENAVE_B2B";
    private static final String PURPOSE = "MKT_OUTBOUND_CALL";

    /** Seeded by V14. Named so nobody mistakes it for a Board registration. */
    private static final String REGISTERED = "CM-TEST-0001";
    private static final String DEREGISTERED = "CM-TEST-0002";
    /**
     * A second registration that is <em>also</em> active, held by a different credential.
     *
     * <p>Seeded by {@code V15} for the binding tests specifically. Testing the binding against
     * {@link #DEREGISTERED} would hold nothing constant: that relay is refused for its status
     * before the binding is ever consulted, so the assertion would pass with the check removed.
     */
    private static final String OTHER_REGISTERED = "CM-TEST-0003";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ConsentManagerStore managers;

    @Autowired
    private ConsentArtefactStore artefacts;

    @Autowired
    private ConsentLedger ledger;

    @Autowired
    private PolicyEngine engine;

    @Autowired
    private EnforcementEvidenceStore enforcement;

    @Autowired
    private AdminAuditStore audit;

    @Test
    @DisplayName("a relayed grant is honoured by the decision API exactly as a first-party one is")
    void aRelayedGrantIsRealConsent() {
        String ref = "cm-ref-" + UUID.randomUUID();

        ResponseEntity<String> response = asConsentManager()
                .postForEntity("/v1/consent-manager/" + REGISTERED + "/grant",
                        grantBody(ref), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String subjectId = subjectFor(ref);
        assertThat(artefacts.find(ENTITY, subjectId, PURPOSE).orElseThrow()
                .effectiveStatus(Instant.now())).isEqualTo(ConsentStatus.GRANTED);

        // The property that matters. A relayed consent that the decision path did not honour would
        // be a record of a permission the platform then declined to act on — which is worse than
        // not having accepted the relay, because the principal has been told they consented.
        DecisionResponse decision = engine.evaluate(request(subjectId));
        assertThat(decision.isAllowed())
                .withFailMessage("relayed consent was not honoured: %s", decision.explanation())
                .isTrue();
    }

    @Test
    @DisplayName("the relay is visible as a relay in the evidence, not folded into first-party consent")
    void aRelayedGrantIsDistinguishableInEvidence() {
        String ref = "cm-ref-" + UUID.randomUUID();
        asConsentManager().postForEntity("/v1/consent-manager/" + REGISTERED + "/grant",
                grantBody(ref), String.class);

        String subjectId = subjectFor(ref);

        assertThat(ledger.history(ENTITY, subjectId, PURPOSE))
                .anySatisfy(stored -> {
                    assertThat(stored.event().captureMethod())
                            .isEqualTo(CaptureMethod.RELAYED_BY_CONSENT_MANAGER);
                    assertThat(stored.event().actorType()).isEqualTo(ActorType.CONSENT_MANAGER);
                    // The registration number on the actor id is what lets an auditor go from an
                    // event to the register entry. Without it the event says only "an intermediary"
                    // and the question "which one, and were they registered at the time" has no
                    // answer.
                    assertThat(stored.event().actorId()).isEqualTo(REGISTERED);
                });
    }

    @Test
    @DisplayName("a relayed withdrawal denies the next decision")
    void aRelayedWithdrawalTakesEffectImmediately() {
        String ref = "cm-ref-" + UUID.randomUUID();
        asConsentManager().postForEntity("/v1/consent-manager/" + REGISTERED + "/grant",
                grantBody(ref), String.class);
        String subjectId = subjectFor(ref);
        assertThat(engine.evaluate(request(subjectId)).isAllowed()).isTrue();

        ResponseEntity<String> withdrawal = asConsentManager()
                .postForEntity("/v1/consent-manager/" + REGISTERED + "/withdraw", Map.of(
                        "cmSubjectRef", ref,
                        "entityId", ENTITY,
                        "jurisdiction", "IN",
                        "channel", "VOICE_CALL",
                        "applicationId", APP,
                        "purposeCodes", List.of(PURPOSE),
                        "idempotencyKey", "cm-wd-" + ref), String.class);

        assertThat(withdrawal.getStatusCode()).isEqualTo(HttpStatus.OK);

        // No queue, no review step. A withdrawal that had to be approved would not be as easy as
        // giving consent, and the fact that it arrived through an intermediary changes nothing
        // about whose decision it is.
        assertThat(engine.evaluate(request(subjectId)).isAllowed()).isFalse();
    }

    @Test
    @DisplayName("a deregistered Consent Manager is refused, and the refusal is evidence")
    void aDeregisteredManagerIsRefused() {
        String ref = "cm-ref-" + UUID.randomUUID();
        long before = enforcement.denialCount(ENTITY);

        ResponseEntity<String> response = rest
                .withBasicAuth("cm-deregistered-client", "cm-deregistered-secret")
                .postForEntity("/v1/consent-manager/" + DEREGISTERED + "/grant",
                        grantBody(ref), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Recorded rather than logged. An inbound channel that writes consent and authenticates on
        // a claim about who the caller is will be probed, and the question afterwards is how many
        // times and against which entity — which a log that rotates in a fortnight cannot answer.
        assertThat(enforcement.denialCount(ENTITY)).isGreaterThan(before);
        assertThat(enforcement.denials(ENTITY, null, null, 20, 0))
                .filteredOn(denial -> "CONSENT_MANAGER_NOT_REGISTERED".equals(denial.reason()))
                .isNotEmpty()
                // The claimed registration is on the row, in the column that carries "who was
                // asking" everywhere else in this table. A refusal that did not name the caller
                // would count attempts and identify none of them.
                .anySatisfy(denial -> assertThat(denial.clientId()).isEqualTo(DEREGISTERED));

        assertThat(managers.resolveSubject(ENTITY, DEREGISTERED, ref))
                .withFailMessage("a refused relay still created a link")
                .isEmpty();
    }

    @Test
    @DisplayName("a registration number nobody registered is refused the same way")
    void anUnknownRegistrationIsRefused() {
        // The same 403 as a deregistered one, deliberately. Telling an unregistered caller that the
        // number they guessed exists but is suspended would turn this endpoint into a way of
        // enumerating the Board's register.
        ResponseEntity<String> response = asConsentManager()
                .postForEntity("/v1/consent-manager/CM-NOT-A-REAL-ONE/grant",
                        grantBody("cm-ref-" + UUID.randomUUID()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).doesNotContain("DEREGISTERED", "SUSPENDED", "not on the");
    }

    @Test
    @DisplayName("an invalid relayed consent is refused on the same terms as a first-party one")
    void aRelayEarnsNoExemptionFromValidation() {
        // The temptation is to accept whatever a registered Consent Manager sends because it came
        // through the statutory channel. Validity under s.6 does not depend on how the consent
        // arrived, and a fiduciary that recorded an invalid consent because an intermediary
        // relayed it would be holding evidence against itself.
        Map<String, Object> body = grantBody("cm-ref-" + UUID.randomUUID());
        body.put("choices", List.of(Map.of("purposeCode", "NOT_A_REGISTERED_PURPOSE",
                "granted", true, "preTicked", false, "separateAction", true)));

        ResponseEntity<String> response = asConsentManager()
                .postForEntity("/v1/consent-manager/" + REGISTERED + "/grant", body, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("UNKNOWN_PURPOSE");
    }

    @Test
    @DisplayName("the outbound record is the same receipt the principal would be given")
    void theOutboundRecordIsTheCanonicalReceipt() {
        String ref = "cm-ref-" + UUID.randomUUID();
        asConsentManager().postForEntity("/v1/consent-manager/" + REGISTERED + "/grant",
                grantBody(ref), String.class);

        ResponseEntity<String> record = asConsentManager().getForEntity(
                "/v1/consent-manager/" + REGISTERED + "/subjects/" + ref + "/record?entityId="
                        + ENTITY, String.class);

        assertThat(record.getStatusCode()).isEqualTo(HttpStatus.OK);
        // One artefact, seen identically by the principal, the Consent Manager and the auditor. A
        // Consent-Manager-shaped projection would be a second thing to be wrong, and the
        // disagreement would surface as two documents about the same consent that do not match.
        assertThat(record.getBody())
                .contains("receiptId")
                .contains(PURPOSE)
                .contains("evidenceHash");
    }

    @Test
    @DisplayName("a Consent Manager reaches its own routes and nothing else")
    void theRelayCredentialIsNarrow() {
        // Narrower than CAPTURE rather than a variant of it. A registered Consent Manager writes
        // consent for the principals it manages; it has no business reading the audit trail, and a
        // capture surface has no business relaying as though it were an intermediary.
        for (String path : List.of("/v1/admin/audit?entityId=" + ENTITY,
                "/v1/admin/purposes",
                "/v1/consent/" + ENTITY + "/nobody/history")) {
            assertThat(asConsentManager().getForEntity(path, String.class).getStatusCode())
                    .withFailMessage("%s was reachable by a Consent Manager credential", path)
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        assertThat(rest.withBasicAuth("denave-web", "capture-secret")
                .postForEntity("/v1/consent-manager/" + REGISTERED + "/grant",
                        grantBody("cm-ref-" + UUID.randomUUID()), String.class)
                .getStatusCode())
                .withFailMessage("a capture surface could relay as a Consent Manager")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("unlinking ends the link and withdraws nothing")
    void unlinkingIsNotWithdrawing() {
        String ref = "cm-ref-" + UUID.randomUUID();
        asConsentManager().postForEntity("/v1/consent-manager/" + REGISTERED + "/grant",
                grantBody(ref), String.class);
        String subjectId = subjectFor(ref);

        assertThat(managers.unlink(ENTITY, subjectId, REGISTERED, Instant.now())).isEqualTo(1);

        // A principal who stops using a Consent Manager has said nothing about whether they still
        // want to hear from anyone. Conflating the two would silently revoke consents nobody
        // revoked, and the evidence plane would faithfully record a withdrawal that never happened.
        assertThat(engine.evaluate(request(subjectId)).isAllowed()).isTrue();
        assertThat(managers.resolveSubject(ENTITY, REGISTERED, ref)).isEmpty();
    }

    @Test
    @DisplayName("the register is readable by compliance and by nobody else")
    void theRegisterIsAdministrative() {
        ResponseEntity<String> registry = rest.withBasicAuth("compliance-console", "admin-secret")
                .getForEntity("/v1/consent-manager/registry", String.class);

        assertThat(registry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(registry.getBody()).contains(REGISTERED).contains("DEREGISTERED");

        // A fiduciary that could edit — or a Consent Manager that could read — the list of who is
        // registered is a step towards a relay authorising itself.
        assertThat(asConsentManager().getForEntity("/v1/consent-manager/registry", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a Consent Manager cannot relay under another Consent Manager's registration")
    void aRegistrationIsBoundToTheCredentialThatHoldsIt() {
        // The defect this test was written for. The registration number arrived as a path variable
        // and was checked only for being on the register and active — never against the credential
        // that authenticated. So any CONSENT_MANAGER credential could write consent into the ledger
        // under any other registration, and the ledger would faithfully record the *other* Consent
        // Manager as the actor. In an evidence plane, an actor id the caller can choose is not
        // evidence of anything.
        //
        // CM-TEST-0003 is registered and active, held by a different credential. Status is held
        // constant on purpose: relaying under the deregistered number would be refused for being
        // deregistered, and would prove nothing about whether anybody checks who is asking.
        String ref = "cm-ref-" + UUID.randomUUID();
        long before = enforcement.denialCount(ENTITY);

        ResponseEntity<String> response = asConsentManager()
                .postForEntity("/v1/consent-manager/" + OTHER_REGISTERED + "/grant",
                        grantBody(ref), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Nothing was written under the borrowed number.
        assertThat(managers.resolveSubject(ENTITY, OTHER_REGISTERED, ref))
                .withFailMessage("a relay under a borrowed registration created a link")
                .isEmpty();

        // And the refusal is evidence, under its own reason. A caller the platform has never heard
        // of and a caller it has heard of claiming somebody else's number are different incidents
        // with different responses, and this column is the only place that distinction survives.
        assertThat(enforcement.denialCount(ENTITY)).isGreaterThan(before);
        assertThat(enforcement.denials(ENTITY, null, null, 20, 0))
                .filteredOn(denial -> "CONSENT_MANAGER_NOT_BOUND".equals(denial.reason()))
                .anySatisfy(denial -> assertThat(denial.clientId()).isEqualTo(OTHER_REGISTERED));
    }

    @Test
    @DisplayName("the same credential relaying under its own registration is honoured")
    void theBindingIsNotSimplyClosed() {
        // The half that makes the test above mean something. A check that refused everything would
        // satisfy every assertion in it and break the statutory channel completely — and the
        // breakage would look like a Consent Manager integration problem rather than a platform
        // one, which is the kind of fault that takes a week to find.
        String ref = "cm-ref-" + UUID.randomUUID();

        ResponseEntity<String> response = rest.withBasicAuth("cm-other-client", "cm-other-secret")
                .postForEntity("/v1/consent-manager/" + OTHER_REGISTERED + "/grant",
                        grantBody(ref), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(managers.resolveSubject(ENTITY, OTHER_REGISTERED, ref)).isPresent();
    }

    @Test
    @DisplayName("the outbound record is bound too, so one Consent Manager cannot read another's")
    void theOutboundRecordIsBoundAsWell() {
        // The route that matters most for binding and the one easiest to forget, because it is a
        // read rather than a write. It discloses a named principal's entire consent record, so an
        // unbound registration number here would let one Consent Manager read another's principals
        // by quoting a registration and a reference.
        String ref = "cm-ref-" + UUID.randomUUID();
        rest.withBasicAuth("cm-other-client", "cm-other-secret")
                .postForEntity("/v1/consent-manager/" + OTHER_REGISTERED + "/grant",
                        grantBody(ref), String.class);

        ResponseEntity<String> stolen = asConsentManager().getForEntity(
                "/v1/consent-manager/" + OTHER_REGISTERED + "/subjects/" + ref + "/record?entityId="
                        + ENTITY, String.class);

        assertThat(stolen.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(stolen.getBody()).doesNotContain("receiptId");
    }

    @Test
    @DisplayName("all three refusals are indistinguishable from outside")
    void theRefusalsCannotBeToldApart() {
        // Deliberate, and the reason the reasons live in the evidence table rather than the
        // response. Told apart, this endpoint becomes a way of enumerating the Board's register:
        // a caller could learn which numbers exist, which are live, and which it does not hold —
        // which is most of what an attacker wants before they start.
        String unknown = asConsentManager().postForEntity(
                "/v1/consent-manager/CM-NOT-A-REAL-ONE/grant",
                grantBody("cm-ref-" + UUID.randomUUID()), String.class).getBody();

        String deregistered = rest.withBasicAuth("cm-deregistered-client", "cm-deregistered-secret")
                .postForEntity("/v1/consent-manager/" + DEREGISTERED + "/grant",
                        grantBody("cm-ref-" + UUID.randomUUID()), String.class).getBody();

        String notBound = asConsentManager().postForEntity(
                "/v1/consent-manager/" + OTHER_REGISTERED + "/grant",
                grantBody("cm-ref-" + UUID.randomUUID()), String.class).getBody();

        // Everything but "instance", which is the request URI and is different because the three
        // calls necessarily name different registrations — that is the caller's own input echoed
        // back, not something the platform disclosed about the register.
        assertThat(withoutInstance(notBound))
                .isEqualTo(withoutInstance(unknown))
                .isEqualTo(withoutInstance(deregistered));
        assertThat(notBound).doesNotContain("DEREGISTERED", "SUSPENDED", "credential", "register");
    }

    @Test
    @DisplayName("an administrator may relay under a registration it does not hold")
    void administratorsMayRelayOnBehalf() {
        // An explicit decision rather than a side effect of the endpoint accepting ADMIN at all.
        // Rehearsing the relay path before 13 November 2026, and reproducing a disputed relay
        // during an investigation, both require somebody to act as a Consent Manager they are not —
        // and both are already audited as administrative acts.
        String ref = "cm-ref-" + UUID.randomUUID();

        ResponseEntity<String> response = rest.withBasicAuth("compliance-console", "admin-secret")
                .postForEntity("/v1/consent-manager/" + OTHER_REGISTERED + "/grant",
                        grantBody(ref), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // The bypass is over the binding only. A deregistered registration is still refused, since
        // an administrator relaying through a Consent Manager the Board has removed would be
        // recording consent through a channel that no longer exists.
        assertThat(rest.withBasicAuth("compliance-console", "admin-secret")
                .postForEntity("/v1/consent-manager/" + DEREGISTERED + "/grant",
                        grantBody("cm-ref-" + UUID.randomUUID()), String.class)
                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a suspension made through the admin endpoint stops the next relay, and is audited")
    void aSuspensionTakesEffectOnTheNextRelay() {
        // The operation Rule 4 makes inevitable. The Board may suspend or cancel a registration
        // after a hearing, and when it does UDS has to stop honouring that Consent Manager's
        // relays that day. Until this endpoint existed that meant a DBA with a psql session at
        // whatever hour the notice arrived, leaving no audit trail and no way to rehearse — so
        // what this test is really pinning is that the control can be *operated*, not merely that
        // the column can hold the value.
        //
        // A registration created for this test rather than one of the fixtures, because suspending
        // CM-TEST-0001 would break every other case in this class depending on the order they run
        // in — and a test that passes or fails by ordering is not evidence of anything.
        String registration = "CM-IT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        TestRestTemplate admin = rest.withBasicAuth("compliance-console", "admin-secret");

        assertThat(admin.postForEntity("/v1/admin/consent-managers", Map.of(
                        "registrationId", registration,
                        "name", "Suspension rehearsal (not a Board registration)",
                        // No apiClientId. Not an omission — the column is unique, one credential to
                        // one registration, which is the constraint that makes D1's binding mean
                        // anything. So the relays below go through the administrator's on-behalf
                        // path, which bypasses the binding and deliberately does *not* bypass
                        // status. That is the exact pairing under test.
                        "contactEmail", "privacy@uds.example"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Live before the suspension, so the refusal afterwards is attributable to the suspension
        // and not to the registration never having worked.
        assertThat(admin.postForEntity("/v1/consent-manager/" + registration + "/grant",
                grantBody("cm-ref-" + UUID.randomUUID()), String.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> suspension = admin.exchange(
                "/v1/admin/consent-managers/" + registration + "/status", HttpMethod.PUT,
                new HttpEntity<>(Map.of("status", "SUSPENDED",
                        "reason", "Board notice 2026-08-17, pending hearing")), String.class);

        assertThat(suspension.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(suspension.getBody()).contains("SUSPENDED").contains("Board notice 2026-08-17");

        String ref = "cm-ref-" + UUID.randomUUID();
        assertThat(admin.postForEntity("/v1/consent-manager/" + registration + "/grant",
                grantBody(ref), String.class).getStatusCode())
                .withFailMessage("a suspended registration still relayed")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(managers.resolveSubject(ENTITY, registration, ref)).isEmpty();

        // And the trail. "Who suspended this one, when, and on what authority" is precisely the
        // question asked after a relay that should not have been honoured — or after one that was
        // refused and should not have been. The reason is in the audit detail because the status
        // column only holds the latest, and the sequence is what an investigation reads.
        assertThat(audit.recent(null, 50))
                .filteredOn(entry -> "CONSENT_MANAGER_STATUS_CHANGED".equals(entry.action())
                        && registration.equals(entry.targetId()))
                .singleElement()
                .satisfies(entry -> {
                    // The human, not the credential. Before X-UDS-Actor existed this said
                    // "compliance-console", which is a team rather than a person — and on an
                    // append-only table that ambiguity could never afterwards be corrected. The
                    // credential is still recorded, in clientId, because it is the half the
                    // platform can verify.
                    assertThat(entry.actorId()).isEqualTo(IntegrationTestClient.TEST_ACTOR);
                    assertThat(entry.clientId()).isEqualTo("compliance-console");
                    assertThat(entry.detailJson()).contains("SUSPENDED").contains("Board notice");
                    // Group-wide, not scoped to a fiduciary: the register is one register for the
                    // whole group, and filing this under DENAVE_IN would hide it from Matrix's
                    // administrators while binding them to its effects.
                    assertThat(entry.entityId()).isNull();
                });

        // Restoring is the same act in the other direction, and has to be, because a suspension
        // lifted after a hearing is the ordinary outcome of one.
        assertThat(admin.exchange("/v1/admin/consent-managers/" + registration + "/status",
                HttpMethod.PUT, new HttpEntity<>(Map.of("status", "REGISTERED",
                        "reason", "Board hearing concluded, registration restored")), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(admin.postForEntity("/v1/consent-manager/" + registration + "/grant",
                grantBody("cm-ref-" + UUID.randomUUID()), String.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("re-registering an entry does not restore a suspended one")
    void aReRegistrationCannotQuietlyRestore() {
        // The failure this shape was chosen to prevent. Transcribing the Board's published list is
        // a routine, repetitive job; suspending a registration is a consequential one. If the
        // create call also set status, the routine job would silently undo the consequential one,
        // and nobody would be able to name the moment it happened.
        String registration = "CM-IT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        TestRestTemplate admin = rest.withBasicAuth("compliance-console", "admin-secret");

        admin.postForEntity("/v1/admin/consent-managers", Map.of(
                "registrationId", registration, "name", "Re-registration rehearsal"), String.class);
        admin.exchange("/v1/admin/consent-managers/" + registration + "/status", HttpMethod.PUT,
                new HttpEntity<>(Map.of("status", "DEREGISTERED", "reason", "Board cancellation")),
                String.class);

        // The same entry recorded again, exactly as a re-transcription would send it.
        assertThat(admin.postForEntity("/v1/admin/consent-managers", Map.of(
                        "registrationId", registration, "name", "Re-registration rehearsal, again"),
                String.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(managers.find(registration).orElseThrow().status())
                .withFailMessage("a re-registration restored a cancelled registration")
                .isEqualTo(ConsentManagerStore.Status.DEREGISTERED);
        // The correctable fields did update, which is the point of allowing the call at all.
        assertThat(managers.find(registration).orElseThrow().name()).contains("again");
    }

    @Test
    @DisplayName("the register reports how stale UDS's copy of the Board's list is")
    void reconciliationIsRecordedAndSurfaced() {
        // There is no Board feed to poll, so this column can only ever record that a person
        // compared the two lists. That is a weaker control than a sync and it is the true one —
        // and the fixtures deliberately stay unreconciled so they keep appearing in the health
        // report until somebody retires them before go-live.
        String registration = "CM-IT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        TestRestTemplate admin = rest.withBasicAuth("compliance-console", "admin-secret");

        admin.postForEntity("/v1/admin/consent-managers", Map.of(
                "registrationId", registration, "name", "Reconciliation rehearsal"), String.class);

        assertThat(managers.neverReconciled()).contains(registration);

        assertThat(admin.postForEntity(
                "/v1/admin/consent-managers/" + registration + "/reconciled", null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(managers.find(registration).orElseThrow())
                .satisfies(entry -> {
                    assertThat(entry.lastReconciledAt()).isNotNull();
                    // Named, and now named after a person rather than after the shared console
                    // credential — which is what "named" was supposed to mean all along. A
                    // reconciliation nobody is named for is a reconciliation nobody did.
                    assertThat(entry.lastReconciledBy()).isEqualTo(IntegrationTestClient.TEST_ACTOR);
                });
        assertThat(managers.neverReconciled()).doesNotContain(registration);
    }

    /**
     * A ProblemDetail body with the {@code instance} member removed.
     *
     * <p>{@code instance} is the request URI, so it necessarily differs between refusals of
     * different registration numbers. It is the caller's own input reflected back and discloses
     * nothing — what must be identical is every member the platform chose: the title, the status
     * and the detail.
     */
    private static String withoutInstance(String problemDetail) {
        return problemDetail.replaceAll(",\"instance\":\"[^\"]*\"", "");
    }

    private TestRestTemplate asConsentManager() {
        return rest.withBasicAuth("cm-test-client", "cm-secret");
    }

    private Map<String, Object> grantBody(String cmSubjectRef) {
        // A mutable map rather than Map.of, which stops at ten pairs.
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("cmSubjectRef", cmSubjectRef);
        body.put("entityId", ENTITY);
        body.put("identifierType", "PHONE");
        // A distinct number per relay, so two tests never resolve to the same principal and
        // inherit each other's consent state.
        body.put("identifierValue", "+9190000" + Math.abs(cmSubjectRef.hashCode() % 100000));
        body.put("jurisdiction", "IN");
        body.put("languageTag", "en");
        body.put("channel", "VOICE_CALL");
        body.put("applicationId", APP);
        body.put("noticeId", NOTICE);
        body.put("noticeVersion", 1);
        body.put("choices", List.of(Map.of("purposeCode", PURPOSE, "granted", true,
                "preTicked", false, "separateAction", true)));
        body.put("rejectAllOffered", true);
        body.put("idempotencyKey", "cm-grant-" + cmSubjectRef);
        return body;
    }

    private String subjectFor(String cmSubjectRef) {
        return managers.resolveSubject(ENTITY, REGISTERED, cmSubjectRef)
                .orElseThrow(() -> new AssertionError(
                        "the relay did not link " + cmSubjectRef + " to a principal"));
    }

    private DecisionRequest request(String subjectId) {
        return new DecisionRequest(ENTITY, subjectId, PURPOSE, Channel.VOICE_CALL, Jurisdiction.IN,
                APP, Instant.now(), null, null, null, Map.of());
    }
}
