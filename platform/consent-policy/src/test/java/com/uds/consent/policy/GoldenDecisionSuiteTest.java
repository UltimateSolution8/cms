package com.uds.consent.policy;

import com.uds.consent.core.decision.DecisionRequest;
import com.uds.consent.core.decision.DecisionResponse;
import com.uds.consent.core.decision.DenialReason;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.ConsentStatus;
import com.uds.consent.core.model.ExpiryPolicy;
import com.uds.consent.core.model.FailureBehavior;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.LegalBasis;
import com.uds.consent.core.model.PurposeDefinition;
import com.uds.consent.core.snapshot.ConsentSnapshot;
import com.uds.consent.core.snapshot.LocalConsentEvaluator;
import com.uds.consent.core.snapshot.PurposeState;
import com.uds.consent.policy.jurisdiction.JurisdictionModule;
import com.uds.consent.policy.port.PolicyPorts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.uds.consent.policy.Fixtures.ENTITY;
import static com.uds.consent.policy.Fixtures.NOW;
import static com.uds.consent.policy.Fixtures.SUBJECT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The golden decision suite.
 *
 * <p>This is the regression net for the one thing in the platform that fails silently. A wrongly
 * restrictive decision produces a complaint from a colleague within a day; a wrongly permissive one
 * produces a call to a person who asked not to be called, and nobody notices until a regulator or
 * that person does. Every case below is a rule someone can point at in a statute — the citation is
 * in the test name or the comment above it, so that when a rule changes the test that encodes it
 * can be found and changed with it.
 *
 * <p>Cases are organised by regime rather than by code path, because that is how they will be
 * argued about: when TRAI amends the transactional window, the person changing it should be able to
 * find every affected assertion in one place.
 */
class GoldenDecisionSuiteTest {

    // -------------------------------------------------------------------------------------------
    // India — TRAI TCCCPR 2018, as amended February 2025
    // -------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("TRAI TCCCPR — expiry semantics a boolean consent flag cannot express")
    class Trai {

        @Test
        @DisplayName("transactional consent permits on day six")
        void transactionalConsentIsLiveInsideTheWindow() {
            DecisionResponse decision = engineWithGrant("TXN_SERVICE_SMS",
                    NOW.plus(7, ChronoUnit.DAYS))
                    .evaluate(request("TXN_SERVICE_SMS", Channel.SMS, Jurisdiction.IN,
                            NOW.plus(6, ChronoUnit.DAYS)));

            assertThat(decision.isAllowed()).isTrue();
            assertThat(decision.obligations())
                    .contains("transactional-consent-expires-seven-days-from-grant");
        }

        @Test
        @DisplayName("transactional consent auto-denies on day eight without any sweeper having run")
        void transactionalConsentLapsesAtSevenDays() {
            // The sweeper writes a durable EXPIRED event, but the decision must not wait for it.
            // A consent that ran out thirty seconds ago is already gone.
            DecisionResponse decision = engineWithGrant("TXN_SERVICE_SMS",
                    NOW.plus(7, ChronoUnit.DAYS))
                    .evaluate(request("TXN_SERVICE_SMS", Channel.SMS, Jurisdiction.IN,
                            NOW.plus(8, ChronoUnit.DAYS)));

            assertThat(decision.isAllowed()).isFalse();
            assertThat(decision.reason()).isEqualTo(DenialReason.CONSENT_EXPIRED);
        }

        @Test
        @DisplayName("inferred consent denies once the contract it rests on has ended")
        void inferredConsentDiesWithTheRelationship() {
            Instant contractEnd = NOW.plus(30, ChronoUnit.DAYS);
            DecisionResponse decision = engineWithGrant("SALES_RELATIONSHIP", contractEnd,
                    LegalBasis.INFERRED_CONSENT)
                    .evaluate(request("SALES_RELATIONSHIP", Channel.VOICE_CALL, Jurisdiction.IN,
                            contractEnd.plus(1, ChronoUnit.DAYS)));

            assertThat(decision.reason()).isEqualTo(DenialReason.CONSENT_EXPIRED);
        }

        @Test
        @DisplayName("inferred consent permits while the contract subsists, and says so")
        void inferredConsentHoldsWhileTheRelationshipDoes() {
            DecisionResponse decision = engineWithGrant("SALES_RELATIONSHIP",
                    NOW.plus(30, ChronoUnit.DAYS), LegalBasis.INFERRED_CONSENT)
                    .evaluate(request("SALES_RELATIONSHIP", Channel.VOICE_CALL, Jurisdiction.IN,
                            NOW.plus(1, ChronoUnit.DAYS)));

            assertThat(decision.isAllowed()).isTrue();
            assertThat(decision.obligations())
                    .contains("valid-only-while-contractual-relationship-subsists");
        }

        @Test
        @DisplayName("a valid consent record does not exempt an SMS from DLT registration")
        void dltObligationsRideAlongWithEverySms() {
            DecisionResponse decision = engineWithGrant("TXN_SERVICE_SMS",
                    NOW.plus(7, ChronoUnit.DAYS))
                    .evaluate(request("TXN_SERVICE_SMS", Channel.SMS, Jurisdiction.IN, NOW));

            assertThat(decision.obligations()).contains(
                    "scrub-against-ncpr-before-send",
                    "use-dlt-registered-header",
                    "use-dlt-registered-template");
        }

        @Test
        @DisplayName("a national preference register entry outranks a valid consent record")
        void statutorySuppressionBeatsConsent() {
            // This is the ordering that matters most in the whole engine. Someone on the NCPR is
            // not contactable on a promotional purpose even holding a signed, current consent.
            PolicyEngine engine = Fixtures.engine(Fixtures.fullCatalog(),
                    new Fixtures.Artefacts().with("MKT_OUTBOUND_CALL", ConsentStatus.GRANTED, null),
                    new Fixtures.Suppressions().statutory(Channel.VOICE_CALL), true, false);

            DecisionResponse decision = engine.evaluate(
                    request("MKT_OUTBOUND_CALL", Channel.VOICE_CALL, Jurisdiction.IN, NOW));

            assertThat(decision.isAllowed()).isFalse();
            assertThat(decision.reason()).isEqualTo(DenialReason.SUPPRESSED_STATUTORY);
        }
    }

    // -------------------------------------------------------------------------------------------
    // India — DPDP Act 2023 and DPDP Rules 2025
    // -------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("DPDP Act 2023 — consent, legitimate uses, and children")
    class Dpdp {

        @Test
        @DisplayName("a live grant permits, carrying the Rule 3 withdrawal and grievance links")
        void grantPermitsWithRuleThreeObligations() {
            DecisionResponse decision = engineWithGrant("MKT_OUTBOUND_CALL", null)
                    .evaluate(request("MKT_OUTBOUND_CALL", Channel.VOICE_CALL, Jurisdiction.IN, NOW));

            assertThat(decision.isAllowed()).isTrue();
            assertThat(decision.legalBasis()).isEqualTo(LegalBasis.CONSENT);
            assertThat(decision.obligations())
                    .contains("provide-withdrawal-link", "provide-grievance-link");
        }

        @Test
        @DisplayName("withdrawal takes effect on the next decision, not on the next batch run")
        void withdrawalDeniesImmediately() {
            DecisionResponse decision = engineWithStatus("MKT_OUTBOUND_CALL",
                    ConsentStatus.WITHDRAWN)
                    .evaluate(request("MKT_OUTBOUND_CALL", Channel.VOICE_CALL, Jurisdiction.IN, NOW));

            assertThat(decision.reason()).isEqualTo(DenialReason.CONSENT_WITHDRAWN);
        }

        @Test
        @DisplayName("s.7(i) employment processing permits with no consent record at all")
        void legitimateUseNeedsNoConsentRecord() {
            // Roughly 76,000 workforce records. Treating these as a consent problem would be both
            // wrong in law and a very large piece of unnecessary work: what s.7(i) removes is the
            // consent, not the notice, the retention schedule or the rights machinery.
            PolicyEngine engine = Fixtures.engine(Fixtures.fullCatalog(), new Fixtures.Artefacts());

            DecisionResponse decision = engine.evaluate(
                    request("HR_EMPLOYMENT_ADMIN", null, Jurisdiction.IN, NOW));

            assertThat(decision.isAllowed()).isTrue();
            assertThat(decision.legalBasis()).isEqualTo(LegalBasis.LEGITIMATE_USE_EMPLOYMENT);
            assertThat(decision.obligations())
                    .contains("serve-notice-no-consent-required", "enforce-retention-schedule");
        }

        @Test
        @DisplayName("s.9 closes advertising to a subject under eighteen however consent was given")
        void childrenCannotBeAdvertisedTo() {
            PolicyEngine engine = Fixtures.engine(Fixtures.fullCatalog(),
                    new Fixtures.Artefacts().with("WEB_ADVERTISING", ConsentStatus.GRANTED, null),
                    new Fixtures.Suppressions(), true, true);

            DecisionResponse decision = engine.evaluate(
                    request("WEB_ADVERTISING", Channel.WEB, Jurisdiction.IN, NOW));

            assertThat(decision.isAllowed()).isFalse();
            assertThat(decision.reason()).isEqualTo(DenialReason.CHILD_SUBJECT_RESTRICTED);
        }

        @Test
        @DisplayName("a child declared on the request alone is enough to trigger the restriction")
        void childFlagOnRequestIsHonoured() {
            // The subject store may not know an age; the capture surface often does. Either source
            // has to be sufficient, or the restriction is only as good as the weaker one.
            PolicyEngine engine = Fixtures.engine(Fixtures.fullCatalog(),
                    new Fixtures.Artefacts().with("WEB_ADVERTISING", ConsentStatus.GRANTED, null));

            DecisionResponse decision = engine.evaluate(new DecisionRequest(ENTITY, SUBJECT,
                    "WEB_ADVERTISING", Channel.WEB, Jurisdiction.IN, "APP", NOW, null, null, null,
                    Map.of("subject.isChild", "true")));

            assertThat(decision.reason()).isEqualTo(DenialReason.CHILD_SUBJECT_RESTRICTED);
        }

        @Test
        @DisplayName("a purpose marked safe for children still permits, with the s.9 obligations")
        void permittedChildPurposeStillAllows() {
            PolicyEngine engine = Fixtures.engine(Fixtures.fullCatalog(), new Fixtures.Artefacts(),
                    new Fixtures.Suppressions(), true, true);

            DecisionResponse decision = engine.evaluate(new DecisionRequest(ENTITY, SUBJECT,
                    "WEB_STRICTLY_NECESSARY", Channel.WEB, Jurisdiction.IN, "APP", NOW, null, null,
                    null, Map.of("subject.isChild", "true")));

            assertThat(decision.isAllowed()).isTrue();
            assertThat(decision.obligations()).contains(
                    "no-behavioural-tracking-of-children", "no-targeted-advertising-to-children");
        }
    }

    // -------------------------------------------------------------------------------------------
    // Provenance — the question no commercial CMP asks
    // -------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Provenance — data the group did not collect itself")
    class Provenance {

        @Test
        @DisplayName("a quarantined record denies even holding what looks like a consent row")
        void quarantineOutranksTheConsentRecord() {
            // Denave's prospect database is the commercial risk in this programme. A record whose
            // origin cannot be substantiated is quarantined, never grandfathered — and the consent
            // row attached to it, having come from the same unverifiable import, proves nothing.
            PolicyEngine engine = Fixtures.engine(Fixtures.fullCatalog(),
                    new Fixtures.Artefacts().with("MKT_OUTBOUND_CALL", ConsentStatus.GRANTED, null),
                    new Fixtures.Suppressions(), false, false);

            DecisionResponse decision = engine.evaluate(
                    request("MKT_OUTBOUND_CALL", Channel.VOICE_CALL, Jurisdiction.IN, NOW));

            assertThat(decision.isAllowed()).isFalse();
            assertThat(decision.reason()).isEqualTo(DenialReason.NO_PROVENANCE);
        }

        @Test
        @DisplayName("quarantine does not block employment processing")
        void quarantineDoesNotReachEmploymentProcessing() {
            // The engagement is its own provenance. Applying the prospect-database rule to payroll
            // would stop the business for no compliance gain.
            PolicyEngine engine = Fixtures.engine(Fixtures.fullCatalog(), new Fixtures.Artefacts(),
                    new Fixtures.Suppressions(), false, false);

            assertThat(engine.evaluate(request("HR_EMPLOYMENT_ADMIN", null, Jurisdiction.IN, NOW))
                    .isAllowed()).isTrue();
        }
    }

    // -------------------------------------------------------------------------------------------
    // United Kingdom and European Union — GDPR, PECR / ePrivacy
    // -------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("UK and EU GDPR — legitimate interest holds until the subject objects")
    class Gdpr {

        @Test
        @DisplayName("legitimate interest permits B2B outreach with no consent record")
        void legitimateInterestPermitsWithoutConsent() {
            PolicyEngine engine = Fixtures.engine(Fixtures.fullCatalog(), new Fixtures.Artefacts());

            DecisionResponse decision = engine.evaluate(
                    request("MKT_OUTBOUND_CALL", Channel.VOICE_CALL, Jurisdiction.UK, NOW));

            assertThat(decision.isAllowed()).isTrue();
            assertThat(decision.legalBasis()).isEqualTo(LegalBasis.LEGITIMATE_INTEREST);
            assertThat(decision.obligations()).contains(
                    "honour-objection-immediately", "include-opt-out-mechanism",
                    "identify-sender-clearly", "scrub-against-tps-and-ctps");
        }

        @Test
        @DisplayName("an objection ends legitimate interest, with no consent record ever involved")
        void objectionDefeatsLegitimateInterest() {
            // Art.6(1)(f) is conditional in a way consent is not. The opt-out is the whole
            // condition, so it has to defeat the basis directly rather than by flipping a row that
            // does not exist.
            PolicyEngine engine = Fixtures.engine(Fixtures.fullCatalog(), new Fixtures.Artefacts(),
                    new Fixtures.Suppressions().optOut(Channel.VOICE_CALL), true, false);

            DecisionResponse decision = engine.evaluate(
                    request("MKT_OUTBOUND_CALL", Channel.VOICE_CALL, Jurisdiction.UK, NOW));

            assertThat(decision.isAllowed()).isFalse();
            assertThat(decision.reason()).isEqualTo(DenialReason.SUPPRESSED_OPT_OUT);
        }

        @Test
        @DisplayName("a lawful basis for the processing does not answer the cookie question")
        void ePrivacyIsASeparateQuestionFromTheLawfulBasis() {
            // The most commonly missed distinction in the whole regime, and a live exposure on
            // Denave's UK-facing site today: legitimate interest can support the processing and
            // does nothing at all for storing something on the device.
            PolicyEngine engine = Fixtures.engine(Fixtures.fullCatalog(), new Fixtures.Artefacts());

            DecisionResponse decision = engine.evaluate(
                    request("WEB_STRICTLY_NECESSARY", Channel.WEB, Jurisdiction.UK, NOW));

            assertThat(decision.isAllowed()).isTrue();
            assertThat(decision.obligations())
                    .contains("consent-required-for-non-essential-storage");
        }
    }

    // -------------------------------------------------------------------------------------------
    // The rest of Denave's footprint
    // -------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Korea, Singapore, Malaysia, California")
    class OtherJurisdictions {

        @Test
        @DisplayName("Korea requires itemised consent and a Korean-language notice")
        void pipaObligationsAttachToEveryConsentDecision() {
            DecisionResponse decision = engineWithGrant("MKT_OUTBOUND_CALL", null)
                    .evaluate(request("MKT_OUTBOUND_CALL", Channel.VOICE_CALL, Jurisdiction.KR,
                            NOW));

            assertThat(decision.isAllowed()).isTrue();
            assertThat(decision.obligations()).contains(
                    "consent-must-be-itemised-per-purpose", "provide-korean-language-notice");
        }

        @Test
        @DisplayName("Singapore requires the Do Not Call check regardless of consent")
        void singaporeDncIsIndependentOfConsent() {
            DecisionResponse decision = engineWithGrant("MKT_OUTBOUND_CALL", null)
                    .evaluate(request("MKT_OUTBOUND_CALL", Channel.VOICE_CALL, Jurisdiction.SG,
                            NOW));

            assertThat(decision.obligations()).contains("check-dnc-registry-before-send");
        }

        @Test
        @DisplayName("Malaysia treats a fingerprint template as sensitive personal data")
        void malaysiaFlagsBiometricsAsSensitive() {
            DecisionResponse decision = engineWithGrant("HR_ATTENDANCE_BIOMETRIC", null)
                    .evaluate(request("HR_ATTENDANCE_BIOMETRIC", Channel.KIOSK, Jurisdiction.MY,
                            NOW));

            assertThat(decision.isAllowed()).isTrue();
            assertThat(decision.obligations()).contains(
                    "biometric-data-is-sensitive-personal-data",
                    "explicit-consent-required",
                    "processor-carries-direct-statutory-liability");
        }

        @Test
        @DisplayName("the same attendance kiosk needs no consent in India")
        void indiaTreatsAttendanceAsALegitimateUse() {
            PolicyEngine engine = Fixtures.engine(Fixtures.fullCatalog(), new Fixtures.Artefacts());

            DecisionResponse decision = engine.evaluate(
                    request("HR_ATTENDANCE_BIOMETRIC", Channel.KIOSK, Jurisdiction.IN, NOW));

            assertThat(decision.isAllowed()).isTrue();
            assertThat(decision.legalBasis()).isEqualTo(LegalBasis.LEGITIMATE_USE_EMPLOYMENT);
        }

        @Test
        @DisplayName("California is an opt-out regime, and says so on every allowance")
        void ccpaAttachesOptOutObligations() {
            PurposeDefinition californian = Fixtures.purpose("MKT_US_OUTREACH",
                    Map.of(Jurisdiction.US_CA, LegalBasis.LEGITIMATE_INTEREST),
                    Set.of(Channel.EMAIL), ExpiryPolicy.NONE, null, FailureBehavior.FAIL_CLOSED,
                    Set.of("CONTACT_BUSINESS"), false, false, false);
            PolicyEngine engine = Fixtures.engine(Fixtures.fullCatalog().with(californian),
                    new Fixtures.Artefacts());

            DecisionResponse decision = engine.evaluate(
                    request("MKT_US_OUTREACH", Channel.EMAIL, Jurisdiction.US_CA, NOW));

            assertThat(decision.obligations()).contains(
                    "provide-do-not-sell-or-share-link", "honour-global-privacy-control-signal");
        }

        @Test
        @DisplayName("a purpose with no basis in the jurisdiction denies rather than asking")
        void absentBasisDeniesOutright() {
            // Falling back to consent here would be the wrong instinct. If legal has not
            // established a basis in a jurisdiction, asking the subject does not create one.
            PolicyEngine engine = Fixtures.engine(Fixtures.fullCatalog(),
                    new Fixtures.Artefacts().with("MKT_OUTBOUND_CALL", ConsentStatus.GRANTED, null));

            DecisionResponse decision = engine.evaluate(
                    request("MKT_OUTBOUND_CALL", Channel.VOICE_CALL, Jurisdiction.MY, NOW));

            assertThat(decision.reason())
                    .isEqualTo(DenialReason.PURPOSE_NOT_PERMITTED_IN_JURISDICTION);
        }
    }

    // -------------------------------------------------------------------------------------------
    // Negative cases and the engine's own failure behaviour
    // -------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Negative cases — what happens when the answer is not clean")
    class Negatives {

        @Test
        @DisplayName("an unknown purpose code denies; free text never becomes a purpose")
        void unknownPurposeDenies() {
            DecisionResponse decision = Fixtures.engine(Fixtures.fullCatalog(),
                            new Fixtures.Artefacts())
                    .evaluate(request("SOMETHING_A_DEVELOPER_INVENTED", Channel.EMAIL,
                            Jurisdiction.IN, NOW));

            assertThat(decision.reason()).isEqualTo(DenialReason.PURPOSE_UNKNOWN);
        }

        @Test
        @DisplayName("a retired purpose may still be reported on but never newly relied upon")
        void retiredPurposeDenies() {
            DecisionResponse decision = engineWithGrant("MKT_LEGACY_BLAST", null)
                    .evaluate(request("MKT_LEGACY_BLAST", Channel.EMAIL, Jurisdiction.IN, NOW));

            assertThat(decision.reason()).isEqualTo(DenialReason.PURPOSE_RETIRED);
        }

        @Test
        @DisplayName("a channel the purpose does not cover denies, consent or no consent")
        void channelOutsideThePurposeDenies() {
            DecisionResponse decision = engineWithGrant("MKT_OUTBOUND_CALL", null)
                    .evaluate(request("MKT_OUTBOUND_CALL", Channel.EMAIL, Jurisdiction.IN, NOW));

            assertThat(decision.reason()).isEqualTo(DenialReason.CHANNEL_NOT_PERMITTED);
        }

        @Test
        @DisplayName("an unknown subject denies for a fail-closed purpose")
        void unknownSubjectDeniesWhenFailingClosed() {
            DecisionResponse decision = Fixtures.engine(Fixtures.fullCatalog(),
                            new Fixtures.Artefacts())
                    .evaluate(request("MKT_OUTBOUND_CALL", Channel.VOICE_CALL, Jurisdiction.IN,
                            NOW));

            assertThat(decision.reason()).isEqualTo(DenialReason.NO_CONSENT_RECORD);
        }

        @Test
        @DisplayName("an unknown subject permits a fail-open purpose, and says the record is absent")
        void unknownSubjectPermitsWhenFailingOpen() {
            PurposeDefinition failOpen = Fixtures.purpose("SEC_FRAUD_SIGNAL",
                    Map.of(Jurisdiction.IN, LegalBasis.CONSENT), Set.of(Channel.WEB),
                    ExpiryPolicy.NONE, null, FailureBehavior.FAIL_OPEN, Set.of("DEVICE_TELEMETRY"),
                    false, false, false);
            PolicyEngine engine = Fixtures.engine(Fixtures.fullCatalog().with(failOpen),
                    new Fixtures.Artefacts());

            DecisionResponse decision = engine.evaluate(
                    request("SEC_FRAUD_SIGNAL", Channel.WEB, Jurisdiction.IN, NOW));

            assertThat(decision.isAllowed()).isTrue();
            assertThat(decision.obligations()).contains("no-consent-on-record");
        }

        @Test
        @DisplayName("an unresolved offline conflict denies rather than guessing")
        void conflictedStatusDenies() {
            DecisionResponse decision = engineWithStatus("MKT_OUTBOUND_CALL",
                    ConsentStatus.CONFLICTED)
                    .evaluate(request("MKT_OUTBOUND_CALL", Channel.VOICE_CALL, Jurisdiction.IN,
                            NOW));

            assertThat(decision.reason()).isEqualTo(DenialReason.CONSENT_CONFLICTED);
        }

        @Test
        @DisplayName("a consent invalidated by a material notice change denies until re-consent")
        void invalidatedStatusDenies() {
            DecisionResponse decision = engineWithStatus("MKT_OUTBOUND_CALL",
                    ConsentStatus.INVALIDATED)
                    .evaluate(request("MKT_OUTBOUND_CALL", Channel.VOICE_CALL, Jurisdiction.IN,
                            NOW));

            assertThat(decision.reason()).isEqualTo(DenialReason.CONSENT_INVALIDATED);
        }

        @Test
        @DisplayName("a broken registry denies instead of throwing into the caller's catch block")
        void internalFailureDenies() {
            // A policy engine that throws is a policy engine that gets wrapped in a try/catch whose
            // catch block allows the operation, because the campaign has to go out tonight. Keeping
            // that decision here is the point.
            PolicyPorts.PurposeCatalog broken = new PolicyPorts.PurposeCatalog() {
                @Override
                public Optional<PurposeDefinition> find(String purposeCode) {
                    throw new IllegalStateException("registry unavailable");
                }

                @Override
                public List<PurposeDefinition> all() {
                    throw new IllegalStateException("registry unavailable");
                }
            };
            PolicyEngine engine = new PolicyEngine(broken, new Fixtures.Artefacts(),
                    new Fixtures.Suppressions(), (e, s) -> true, s -> false, Fixtures.allModules(),
                    Fixtures.POLICY_VERSION);

            DecisionResponse decision = engine.evaluate(
                    request("MKT_OUTBOUND_CALL", Channel.VOICE_CALL, Jurisdiction.IN, NOW));

            assertThat(decision.isAllowed()).isFalse();
            assertThat(decision.reason()).isEqualTo(DenialReason.POLICY_ERROR);
        }

        @Test
        @DisplayName("a module that tries to turn a denial into an allowance cannot")
        void moduleCannotUpgradeADenial() {
            // Modules exist to add local restrictions. Two govern India already, and where one has
            // denied, a later one must not reinstate the processing — that would be a defect worth
            // failing loudly for, and the failure has to land as a denial rather than as an
            // exception the caller might swallow.
            JurisdictionModule denies = module((request, purpose, basis, decision) ->
                    DecisionResponse.deny(purpose.code(), purpose.version(),
                            DenialReason.VENDOR_NOT_AUTHORISED, "local rule refuses",
                            decision.policyVersion(), request.at()));
            JurisdictionModule reinstates = module((request, purpose, basis, decision) ->
                    DecisionResponse.allow(purpose.code(), purpose.version(), basis,
                            decision.policyVersion(), request.at(), null, List.of()));

            PolicyEngine engine = new PolicyEngine(Fixtures.fullCatalog(),
                    new Fixtures.Artefacts().with("MKT_OUTBOUND_CALL", ConsentStatus.GRANTED, null),
                    new Fixtures.Suppressions(), (e, s) -> true, s -> false,
                    List.of(denies, reinstates), Fixtures.POLICY_VERSION);

            DecisionResponse decision = engine.evaluate(
                    request("MKT_OUTBOUND_CALL", Channel.VOICE_CALL, Jurisdiction.IN, NOW));

            assertThat(decision.isAllowed()).isFalse();
            assertThat(decision.reason()).isEqualTo(DenialReason.POLICY_ERROR);
        }

        /** A jurisdiction module for India with only its refinement behaviour supplied. */
        private JurisdictionModule module(Refinement refinement) {
            return new JurisdictionModule() {
                @Override
                public Jurisdiction jurisdiction() {
                    return Jurisdiction.IN;
                }

                @Override
                public DecisionResponse refine(DecisionRequest request, PurposeDefinition purpose,
                                               LegalBasis basis, DecisionResponse decision) {
                    return refinement.apply(request, purpose, basis, decision);
                }
            };
        }

        @FunctionalInterface
        private interface Refinement {
            DecisionResponse apply(DecisionRequest request, PurposeDefinition purpose,
                                   LegalBasis basis, DecisionResponse decision);
        }

        @Test
        @DisplayName("every decision carries the policy version that produced it")
        void everyDecisionIsReproducible() {
            // Without this an audit in 2031 can establish what was decided and never why.
            DecisionResponse allowed = engineWithGrant("MKT_OUTBOUND_CALL", null)
                    .evaluate(request("MKT_OUTBOUND_CALL", Channel.VOICE_CALL, Jurisdiction.IN,
                            NOW));
            DecisionResponse denied = Fixtures.engine(Fixtures.fullCatalog(),
                            new Fixtures.Artefacts())
                    .evaluate(request("NOPE", Channel.VOICE_CALL, Jurisdiction.IN, NOW));

            assertThat(allowed.policyVersion()).isEqualTo(Fixtures.POLICY_VERSION);
            assertThat(denied.policyVersion()).isEqualTo(Fixtures.POLICY_VERSION);
            assertThat(allowed.evaluatedAt()).isEqualTo(NOW);
        }
    }

    // -------------------------------------------------------------------------------------------
    // Server and device must agree
    // -------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("The offline evaluator reaches the server's answer")
    class ServerAndDeviceAgree {

        /**
         * A field device quietly disagreeing with the server is the failure mode that would be
         * hardest to detect in production: nothing errors, a call simply gets made. These cases run
         * the same state through both paths and compare outcome and reason, not merely outcome —
         * two logs that agree on the answer and disagree on the reason turn any later investigation
         * into guesswork.
         */
        @Test
        @DisplayName("granted, withdrawn, expired and suppressed all agree on outcome and reason")
        void bothPathsAgree() {
            assertAgreement(ConsentStatus.GRANTED, null, false, NOW);
            assertAgreement(ConsentStatus.WITHDRAWN, null, false, NOW);
            assertAgreement(ConsentStatus.GRANTED, NOW.plus(1, ChronoUnit.HOURS), false,
                    NOW.plus(2, ChronoUnit.HOURS));
            assertAgreement(ConsentStatus.GRANTED, null, true, NOW);
            assertAgreement(ConsentStatus.CONFLICTED, null, false, NOW);
            assertAgreement(ConsentStatus.DENIED, null, false, NOW);
        }

        private void assertAgreement(ConsentStatus status, Instant expiresAt, boolean suppressed,
                                     Instant at) {
            Fixtures.Suppressions suppressions = new Fixtures.Suppressions();
            if (suppressed) {
                suppressions.statutory(Channel.VOICE_CALL);
            }
            PolicyEngine server = Fixtures.engine(Fixtures.fullCatalog(),
                    new Fixtures.Artefacts().with("MKT_OUTBOUND_CALL", status, expiresAt),
                    suppressions, true, false);

            // The snapshot is issued for the same instant the server is asked about, and given a
            // life long enough that staleness is not what is under test here.
            ConsentSnapshot snapshot = new ConsentSnapshot("snap-1", ENTITY, SUBJECT, NOW,
                    at.plus(1, ChronoUnit.HOURS), Fixtures.POLICY_VERSION,
                    Map.of("MKT_OUTBOUND_CALL", new PurposeState(status, LegalBasis.CONSENT, 1,
                            expiresAt, FailureBehavior.FAIL_CLOSED, Set.of("VOICE_CALL"),
                            suppressed)));

            DecisionRequest question = request("MKT_OUTBOUND_CALL", Channel.VOICE_CALL,
                    Jurisdiction.IN, at);
            DecisionResponse fromServer = server.evaluate(question);
            DecisionResponse fromDevice = new LocalConsentEvaluator(snapshot).evaluate(question);

            assertThat(fromDevice.outcome())
                    .as("outcome for status %s suppressed=%s", status, suppressed)
                    .isEqualTo(fromServer.outcome());
            assertThat(fromDevice.reason())
                    .as("reason for status %s suppressed=%s", status, suppressed)
                    .isEqualTo(fromServer.reason());
        }
    }

    // -------------------------------------------------------------------------------------------

    private static DecisionRequest request(String purposeCode, Channel channel,
                                           Jurisdiction jurisdiction, Instant at) {
        return DecisionRequest.of(ENTITY, SUBJECT, purposeCode, channel, jurisdiction, at);
    }

    private static PolicyEngine engineWithGrant(String purposeCode, Instant expiresAt) {
        return engineWithGrant(purposeCode, expiresAt, LegalBasis.CONSENT);
    }

    private static PolicyEngine engineWithGrant(String purposeCode, Instant expiresAt,
                                                LegalBasis basis) {
        return Fixtures.engine(Fixtures.fullCatalog(),
                new Fixtures.Artefacts().with(purposeCode, ConsentStatus.GRANTED, expiresAt, basis));
    }

    private static PolicyEngine engineWithStatus(String purposeCode, ConsentStatus status) {
        return Fixtures.engine(Fixtures.fullCatalog(),
                new Fixtures.Artefacts().with(purposeCode, status, null));
    }
}
