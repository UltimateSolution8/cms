package com.uds.consent.policy.capture;

import com.uds.consent.core.model.ActorType;
import com.uds.consent.core.model.CaptureMethod;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.GuardianVerification;
import com.uds.consent.core.model.GuardianVerificationMethod;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.policy.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The door the ledger sits behind.
 *
 * <p>A consent record that was invalid the moment it was created is worse than no record at all. It
 * looks like evidence, every downstream system relies on it, and its invalidity surfaces only when
 * a complainant or a regulator goes looking — by which time the group has been acting on it for
 * months and the remedy is a re-permissioning campaign rather than a bug fix.
 *
 * <p>So these cases are about failing loudly and early: during the pilot, in front of the engineer
 * integrating the surface, rather than in 2029 in front of the Board.
 */
class CaptureValidatorTest {

    private static final Instant NOW = Fixtures.NOW;

    private final CaptureValidator validator =
            new CaptureValidator(Fixtures.fullCatalog(), Fixtures.applications(),
                    Fixtures.notices(), Fixtures.allModules());

    // -------------------------------------------------------------------------------------------
    // DPDP Rule 8 — dark patterns
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a clean, itemised submission is accepted")
    void aValidSubmissionPasses() {
        assertThat(validator.validate(submission(Jurisdiction.IN, Channel.WEB,
                CaptureMethod.CHECKBOX_OPT_IN,
                CaptureSubmission.PurposeChoice.acceptedSeparately("WEB_ADVERTISING")))).isEmpty();
    }

    @Test
    @DisplayName("a pre-ticked box is rejected outright, not recorded with a caveat")
    void preTickedBoxIsRejected() {
        // Rule 8 prohibits pre-selection. The field exists on the submission precisely so a surface
        // that does it is refused rather than producing a record that looks indistinguishable from
        // a real one.
        List<CaptureViolation> violations = validator.validate(submission(Jurisdiction.IN,
                Channel.WEB, CaptureMethod.CHECKBOX_OPT_IN,
                new CaptureSubmission.PurposeChoice("WEB_ADVERTISING", true, true, true)));

        assertThat(codes(violations)).contains(CaptureViolation.Code.PRE_SELECTED_OPTION);
    }

    @Test
    @DisplayName("accepting without an equally available way to refuse is rejected")
    void refusalMustBeOfferedAsPlainlyAsAcceptance() {
        CaptureSubmission noRejectAll = new CaptureSubmission("DENAVE_IN", "subject-1",
                Jurisdiction.IN, "en", Channel.WEB, Fixtures.APP, CaptureMethod.CHECKBOX_OPT_IN,
                ActorType.SUBJECT, "subject-1", "NOTICE_TEST", 1,
                List.of(CaptureSubmission.PurposeChoice.acceptedSeparately("WEB_ADVERTISING")),
                false, NOW, "idem-1", "evidence://form/1", Map.of());

        assertThat(codes(validator.validate(noRejectAll)))
                .contains(CaptureViolation.Code.REFUSAL_NOT_EQUALLY_AVAILABLE);
    }

    @Test
    @DisplayName("declining everything needs no reject-all button to be valid")
    void aPureRefusalIsAlwaysRecordable() {
        // The check is about how acceptance was obtained. A subject who said no to everything has
        // demonstrably found a way to refuse, and blocking that record would lose the very
        // interaction most worth keeping.
        CaptureSubmission allDeclined = new CaptureSubmission("DENAVE_IN", "subject-1",
                Jurisdiction.IN, "en", Channel.WEB, Fixtures.APP, CaptureMethod.CHECKBOX_OPT_IN,
                ActorType.SUBJECT, "subject-1", "NOTICE_TEST", 1,
                List.of(CaptureSubmission.PurposeChoice.declined("WEB_ADVERTISING")),
                false, NOW, "idem-2", null, Map.of());

        assertThat(validator.validate(allDeclined)).isEmpty();
    }

    // -------------------------------------------------------------------------------------------
    // DPDP s.6 — a clear affirmative action
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("consent cannot be manufactured from a purchased list")
    void importedRecordsAreNotConsent() {
        // This is the rule that decides how much of Denave's prospect database survives triage.
        // An import carrying provenance is a lawful thing to hold; it is not a clear affirmative
        // action, and writing it as a grant would fabricate evidence.
        List<CaptureViolation> violations = validator.validate(submission(Jurisdiction.IN,
                null, CaptureMethod.IMPORTED_WITH_PROVENANCE,
                CaptureSubmission.PurposeChoice.acceptedSeparately("MKT_OUTBOUND_CALL")));

        assertThat(codes(violations)).containsExactly(
                CaptureViolation.Code.NOT_AN_AFFIRMATIVE_ACTION);
    }

    @Test
    @DisplayName("consent inferred from a relationship is not an affirmative action either")
    void inferenceIsNotAffirmativeAction() {
        List<CaptureViolation> violations = validator.validate(submission(Jurisdiction.IN,
                Channel.WEB, CaptureMethod.INFERRED_FROM_RELATIONSHIP,
                CaptureSubmission.PurposeChoice.acceptedSeparately("WEB_ADVERTISING")));

        assertThat(codes(violations)).contains(CaptureViolation.Code.NOT_AN_AFFIRMATIVE_ACTION);
    }

    // -------------------------------------------------------------------------------------------
    // Rule 3 — what must be recorded for the record to be reproducible later
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a submission that records neither language nor notice version is rejected")
    void whatTheSubjectSawMustBeReproducible() {
        // Without both, the group can say a person consented and can never show what they read.
        // In 2031 that distinction is the whole case.
        CaptureSubmission incomplete = new CaptureSubmission("DENAVE_IN", "subject-1",
                Jurisdiction.IN, null, Channel.WEB, Fixtures.APP, CaptureMethod.CHECKBOX_OPT_IN,
                ActorType.SUBJECT, "subject-1", "NOTICE_TEST", null,
                List.of(CaptureSubmission.PurposeChoice.acceptedSeparately("WEB_ADVERTISING")),
                true, NOW, "idem-3", null, Map.of());

        assertThat(codes(validator.validate(incomplete))).contains(
                CaptureViolation.Code.LANGUAGE_NOT_RECORDED,
                CaptureViolation.Code.NOTICE_VERSION_NOT_RECORDED);
    }

    // -------------------------------------------------------------------------------------------
    // Korea PIPA — separate, itemised consent
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a single 'I agree to the above' across two purposes is rejected in Korea")
    void bundledConsentIsRejectedAtIngestion() {
        // Under PIPA this is not weak consent, it is invalid consent. Catching it at ingestion is
        // the only place the fix is cheap.
        CaptureSubmission bundled = new CaptureSubmission("DENAVE_IN", "subject-1", Jurisdiction.KR,
                "ko", null, Fixtures.APP, CaptureMethod.CHECKBOX_OPT_IN, ActorType.SUBJECT,
                "subject-1", "NOTICE_TEST", 1,
                List.of(new CaptureSubmission.PurposeChoice("MKT_OUTBOUND_CALL", true, false, false),
                        new CaptureSubmission.PurposeChoice("BGV_CRIMINAL_RECORD", true, false,
                                false)),
                true, NOW, "idem-4", null, Map.of());

        assertThat(codes(validator.validate(bundled)))
                .contains(CaptureViolation.Code.BUNDLED_CONSENT);
    }

    @Test
    @DisplayName("the same two purposes, each actioned on its own, are accepted in Korea")
    void itemisedConsentIsAcceptedInKorea() {
        CaptureSubmission itemised = new CaptureSubmission("DENAVE_IN", "subject-1",
                Jurisdiction.KR, "ko", null, Fixtures.APP, CaptureMethod.CHECKBOX_OPT_IN,
                ActorType.SUBJECT, "subject-1", "NOTICE_TEST", 1,
                List.of(CaptureSubmission.PurposeChoice.acceptedSeparately("MKT_OUTBOUND_CALL"),
                        CaptureSubmission.PurposeChoice.acceptedSeparately("BGV_CRIMINAL_RECORD")),
                true, NOW, "idem-5", null, Map.of());

        assertThat(validator.validate(itemised)).isEmpty();
    }

    @Test
    @DisplayName("a purpose flagged for separate consent needs its own step in any jurisdiction")
    void separateConsentIsNotOnlyAKoreanRule() {
        // A criminal-record check swept into a general agreement is the kind of thing that reads
        // fine in a form and badly in a complaint.
        List<CaptureViolation> violations = validator.validate(submission(Jurisdiction.IN,
                null, CaptureMethod.WET_SIGNATURE,
                new CaptureSubmission.PurposeChoice("BGV_CRIMINAL_RECORD", true, false, false)));

        assertThat(codes(violations)).contains(CaptureViolation.Code.SEPARATE_CONSENT_REQUIRED);
    }

    // -------------------------------------------------------------------------------------------
    // DPDP s.9 — children
    // -------------------------------------------------------------------------------------------

    /**
     * A consent-based purpose that is open to under-eighteens — a school-programme sign-up, say.
     * Built here rather than in the shared fixtures because it exists only to separate the two s.9
     * rules from each other: who may consent, and what they may consent to.
     */
    private final CaptureValidator childSafeValidator = new CaptureValidator(
            Fixtures.fullCatalog().with(Fixtures.purpose("WEB_PROGRAMME_SIGNUP",
                    Map.of(Jurisdiction.IN, com.uds.consent.core.model.LegalBasis.CONSENT),
                    java.util.Set.of(Channel.WEB), com.uds.consent.core.model.ExpiryPolicy.NONE,
                    null, com.uds.consent.core.model.FailureBehavior.FAIL_CLOSED,
                    java.util.Set.of("CONTACT_PERSONAL"), false, true, false)),
            Fixtures.applications(), Fixtures.notices(), Fixtures.allModules());

    @Test
    @DisplayName("a child's consent without verified parental action is rejected")
    void childConsentNeedsAVerifiedGuardian() {
        CaptureSubmission byChild = new CaptureSubmission("DENAVE_IN", "subject-1", Jurisdiction.IN,
                "en", Channel.WEB, Fixtures.APP, CaptureMethod.CHECKBOX_OPT_IN, ActorType.SUBJECT,
                "subject-1", "NOTICE_TEST", 1,
                List.of(CaptureSubmission.PurposeChoice.acceptedSeparately(
                        "WEB_PROGRAMME_SIGNUP")),
                true, NOW, "idem-6", null, Map.of("subject.isChild", "true"));

        // Two findings, not one. The child acted for themselves, and nothing records that any
        // guardian was checked — the second is true of this submission independently of the first,
        // and a surface fixing only the actor type would still be refused.
        assertThat(codes(childSafeValidator.validate(byChild)))
                .containsExactlyInAnyOrder(
                        CaptureViolation.Code.PARENTAL_CONSENT_REQUIRED,
                        CaptureViolation.Code.GUARDIAN_VERIFICATION_NOT_EVIDENCED);
    }

    @Test
    @DisplayName("a guardian consenting on a child's behalf to a permitted purpose is accepted")
    void verifiedParentalConsentIsAccepted() {
        CaptureSubmission byGuardian = new CaptureSubmission("DENAVE_IN", "subject-1",
                Jurisdiction.IN, "en", Channel.WEB, Fixtures.APP, CaptureMethod.PARENTAL_VERIFIED,
                ActorType.PARENT_GUARDIAN, "guardian-9", "NOTICE_TEST", 1,
                List.of(CaptureSubmission.PurposeChoice.acceptedSeparately(
                        "WEB_PROGRAMME_SIGNUP")),
                true, NOW, "idem-10", null, Map.of("subject.isChild", "true"),
                digilockerVerification());

        assertThat(childSafeValidator.validate(byGuardian)).isEmpty();
    }

    // -------------------------------------------------------------------------------------------
    // DPDP Rule 10 — the diligence, not just the consent
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a parental consent claim with no record of the diligence is refused")
    void parentalConsentMustCarryItsVerification() {
        // The gap this closes. Every field below is individually well-formed and internally
        // consistent: a child, a guardian actor, PARENTAL_VERIFIED as the method, a purpose open
        // to children. Before Rule 10 was enforced here this was accepted, and what it recorded
        // was the capture surface's own claim that it had done the checking. Rule 10 puts the duty
        // on the fiduciary, so the platform will not take the surface's word for it.
        CaptureSubmission unevidenced = new CaptureSubmission("DENAVE_IN", "subject-1",
                Jurisdiction.IN, "en", Channel.WEB, Fixtures.APP, CaptureMethod.PARENTAL_VERIFIED,
                ActorType.PARENT_GUARDIAN, "guardian-9", "NOTICE_TEST", 1,
                List.of(CaptureSubmission.PurposeChoice.acceptedSeparately(
                        "WEB_PROGRAMME_SIGNUP")),
                true, NOW, "idem-r10-1", null, Map.of("subject.isChild", "true"));

        assertThat(codes(childSafeValidator.validate(unevidenced)))
                .containsExactly(CaptureViolation.Code.GUARDIAN_VERIFICATION_NOT_EVIDENCED);
    }

    @Test
    @DisplayName("a guardian actor is a parental consent claim even where no child was declared")
    void aGuardianActorAloneTriggersTheRequirement() {
        // A surface that names a guardian and forgets the age attribute has a bug. What must not
        // follow from that bug is an unevidenced parental consent slipping through because the
        // condition was written to key off the age flag alone.
        CaptureSubmission byGuardian = new CaptureSubmission("DENAVE_IN", "subject-1",
                Jurisdiction.IN, "en", Channel.WEB, Fixtures.APP, CaptureMethod.PARENTAL_VERIFIED,
                ActorType.PARENT_GUARDIAN, "guardian-9", "NOTICE_TEST", 1,
                List.of(CaptureSubmission.PurposeChoice.acceptedSeparately(
                        "WEB_PROGRAMME_SIGNUP")),
                true, NOW, "idem-r10-2", null, Map.of());

        assertThat(codes(childSafeValidator.validate(byGuardian)))
                .contains(CaptureViolation.Code.GUARDIAN_VERIFICATION_NOT_EVIDENCED);
    }

    @Test
    @DisplayName("either of Rule 10's two routes satisfies the requirement")
    void bothVerificationRoutesAreAccepted() {
        // Rule 10 offers two: identity and age the fiduciary already reliably holds, or a virtual
        // token from a Digital Locker provider. The platform accepts either and records which,
        // because they carry different evidentiary weight and a boolean would lose the difference.
        for (GuardianVerificationMethod method : List.of(
                GuardianVerificationMethod.EXISTING_VERIFIED_ACCOUNT,
                GuardianVerificationMethod.DIGILOCKER_VIRTUAL_TOKEN)) {

            CaptureSubmission byGuardian = new CaptureSubmission("DENAVE_IN", "subject-1",
                    Jurisdiction.IN, "en", Channel.WEB, Fixtures.APP,
                    CaptureMethod.PARENTAL_VERIFIED, ActorType.PARENT_GUARDIAN, "guardian-9",
                    "NOTICE_TEST", 1,
                    List.of(CaptureSubmission.PurposeChoice.acceptedSeparately(
                            "WEB_PROGRAMME_SIGNUP")),
                    true, NOW, "idem-r10-" + method, null, Map.of("subject.isChild", "true"),
                    new GuardianVerification(method, "hash-of-whatever-was-checked",
                            NOW.minusSeconds(86_400), "denave-web"));

            assertThat(childSafeValidator.validate(byGuardian))
                    .as("route %s should satisfy Rule 10", method)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("a verification block with nothing behind it cannot be constructed")
    void aMethodWithNoReferenceIsNotEvidence() {
        // Enforced in the model rather than the validator, so there is no path by which a
        // half-filled block reaches a ledger event and looks like a completed check. The whole
        // point of the record is the reference; a method on its own is the assertion it replaced.
        assertThatThrownBy(() -> new GuardianVerification(
                GuardianVerificationMethod.DIGILOCKER_VIRTUAL_TOKEN, "  ", NOW, "denave-web"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reference");
    }

    private static GuardianVerification digilockerVerification() {
        return new GuardianVerification(GuardianVerificationMethod.DIGILOCKER_VIRTUAL_TOKEN,
                "hash-of-virtual-token", NOW.minusSeconds(3_600), "denave-web");
    }

    @Test
    @DisplayName("even a verified guardian cannot consent to advertising at a child")
    void somePurposesAreClosedToChildrenRegardlessOfWhoConsents() {
        // s.9 bars behavioural tracking and targeted advertising at children outright. A parent
        // agreeing does not reopen it.
        CaptureSubmission byGuardian = new CaptureSubmission("DENAVE_IN", "subject-1",
                Jurisdiction.IN, "en", Channel.WEB, Fixtures.APP, CaptureMethod.PARENTAL_VERIFIED,
                ActorType.PARENT_GUARDIAN, "guardian-9", "NOTICE_TEST", 1,
                List.of(CaptureSubmission.PurposeChoice.acceptedSeparately("WEB_ADVERTISING")),
                true, NOW, "idem-7", null, Map.of("subject.isChild", "true"),
                digilockerVerification());

        assertThat(codes(validator.validate(byGuardian)))
                .contains(CaptureViolation.Code.CHILD_PURPOSE_NOT_PERMITTED);
    }

    @Test
    @DisplayName("declining a child-restricted purpose is recordable, and worth recording")
    void aChildDecliningARestrictedPurposeIsStillARecord() {
        // The refusal is the most useful thing in the interaction. Refusing to store it because
        // the purpose is closed to children would discard the evidence that it was refused.
        CaptureSubmission byGuardian = new CaptureSubmission("DENAVE_IN", "subject-1",
                Jurisdiction.IN, "en", Channel.WEB, Fixtures.APP, CaptureMethod.PARENTAL_VERIFIED,
                ActorType.PARENT_GUARDIAN, "guardian-9", "NOTICE_TEST", 1,
                List.of(CaptureSubmission.PurposeChoice.declined("WEB_ADVERTISING")),
                true, NOW, "idem-8", null, Map.of("subject.isChild", "true"),
                digilockerVerification());

        assertThat(validator.validate(byGuardian)).isEmpty();
    }

    // -------------------------------------------------------------------------------------------
    // Consent relayed by a Consent Manager (DPDP Rule 4)
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("consent relayed by a Consent Manager is valid consent")
    void aRelayedGrantIsAffirmative() {
        // The judgement this pins. The principal took a clear affirmative action; they took it at
        // their Consent Manager, which is the mechanism Rule 4 exists to provide. A validator that
        // rejected it would be refusing the statutory channel itself — and the failure would look
        // like an integration bug rather than a policy decision, which is exactly why it is stated
        // here rather than left to be inferred from an enum flag.
        assertThat(validator.validate(submission(Jurisdiction.IN, Channel.WEB,
                CaptureMethod.RELAYED_BY_CONSENT_MANAGER,
                CaptureSubmission.PurposeChoice.acceptedSeparately("WEB_ADVERTISING"))))
                .isEmpty();
    }

    @Test
    @DisplayName("a relay is held to every other rule, not waved through for being statutory")
    void aRelayEarnsNoExemptions() {
        // The temptation is to accept whatever a registered Consent Manager sends. But validity
        // under s.6 does not depend on how the consent arrived, and a fiduciary that recorded an
        // invalid consent because an intermediary relayed it would be holding evidence against
        // itself — pre-ticked at the Consent Manager is still pre-ticked.
        List<CaptureViolation> violations = validator.validate(submission(Jurisdiction.IN,
                Channel.WEB, CaptureMethod.RELAYED_BY_CONSENT_MANAGER,
                new CaptureSubmission.PurposeChoice("WEB_ADVERTISING", true, true, true)));

        assertThat(codes(violations)).contains(CaptureViolation.Code.PRE_SELECTED_OPTION);

        assertThat(codes(validator.validate(submission(Jurisdiction.IN, null,
                CaptureMethod.RELAYED_BY_CONSENT_MANAGER,
                CaptureSubmission.PurposeChoice.acceptedSeparately("HR_EMPLOYMENT_ADMIN")))))
                .contains(CaptureViolation.Code.CONSENT_NOT_THE_BASIS);
    }

    // -------------------------------------------------------------------------------------------
    // The registry is the vocabulary
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a purpose code nobody registered is rejected; free text never becomes a purpose")
    void unknownPurposeIsRejected() {
        List<CaptureViolation> violations = validator.validate(submission(Jurisdiction.IN,
                Channel.WEB, CaptureMethod.CHECKBOX_OPT_IN,
                CaptureSubmission.PurposeChoice.acceptedSeparately("MARKETING_STUFF")));

        assertThat(codes(violations)).containsExactly(CaptureViolation.Code.UNKNOWN_PURPOSE);
    }

    @Test
    @DisplayName("a retired purpose cannot take new consent")
    void retiredPurposeIsRejected() {
        List<CaptureViolation> violations = validator.validate(submission(Jurisdiction.IN,
                Channel.EMAIL, CaptureMethod.CHECKBOX_OPT_IN,
                CaptureSubmission.PurposeChoice.acceptedSeparately("MKT_LEGACY_BLAST")));

        assertThat(codes(violations)).contains(CaptureViolation.Code.RETIRED_PURPOSE);
    }

    @Test
    @DisplayName("asking for consent where consent is not the basis is itself a violation")
    void consentIsNotSoughtForALegitimateUse() {
        // Not a harmless extra. It tells the subject they can withdraw and stop the processing,
        // which under s.7(i) they cannot — and that misunderstanding is what a grievance is made
        // of. Serve notice instead.
        List<CaptureViolation> violations = validator.validate(submission(Jurisdiction.IN,
                null, CaptureMethod.CHECKBOX_OPT_IN,
                CaptureSubmission.PurposeChoice.acceptedSeparately("HR_EMPLOYMENT_ADMIN")));

        assertThat(codes(violations)).contains(CaptureViolation.Code.CONSENT_NOT_THE_BASIS);
    }

    @Test
    @DisplayName("a purpose with no basis in the capture jurisdiction is rejected")
    void purposeOutsideItsJurisdictionIsRejected() {
        List<CaptureViolation> violations = validator.validate(submission(Jurisdiction.MY,
                Channel.VOICE_CALL, CaptureMethod.VERBAL_RECORDED,
                CaptureSubmission.PurposeChoice.acceptedSeparately("MKT_OUTBOUND_CALL")));

        assertThat(codes(violations))
                .contains(CaptureViolation.Code.PURPOSE_NOT_PERMITTED_IN_JURISDICTION);
    }

    @Test
    @DisplayName("consent to be telephoned may be captured on a web form")
    void captureChannelNeedNotMatchThePurposesDeliveryChannel() {
        // The two are different axes, and conflating them would reject the ordinary case: nobody
        // obtains consent to outbound calling by making an outbound call. The purpose's channel
        // list constrains the processing, and the decision engine enforces it there.
        assertThat(validator.validate(submission(Jurisdiction.IN, Channel.WEB,
                CaptureMethod.CHECKBOX_OPT_IN,
                CaptureSubmission.PurposeChoice.acceptedSeparately("MKT_OUTBOUND_CALL"))))
                .isEmpty();
    }

    @Test
    @DisplayName("every violation is returned at once, not one deployment at a time")
    void allViolationsAreReportedTogether() {
        // An integrator who gets one error, fixes it, redeploys and gets the next is an integrator
        // who ships late and stops trusting the API.
        CaptureSubmission bad = new CaptureSubmission("DENAVE_IN", "subject-1", Jurisdiction.IN,
                null, Channel.EMAIL, Fixtures.APP, CaptureMethod.IMPORTED_WITH_PROVENANCE,
                ActorType.IMPORT, "import-job-3", "NOTICE_TEST", null,
                List.of(new CaptureSubmission.PurposeChoice("WEB_ADVERTISING", true, true, true)),
                false, NOW, "idem-9", null, Map.of());

        assertThat(codes(validator.validate(bad))).contains(
                CaptureViolation.Code.NOT_AN_AFFIRMATIVE_ACTION,
                CaptureViolation.Code.PRE_SELECTED_OPTION,
                CaptureViolation.Code.REFUSAL_NOT_EQUALLY_AVAILABLE,
                CaptureViolation.Code.LANGUAGE_NOT_RECORDED,
                CaptureViolation.Code.NOTICE_VERSION_NOT_RECORDED);
    }

    // -------------------------------------------------------------------------------------------
    // Application registry — is this submission from a surface the group owns
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a submission from an unregistered surface is refused")
    void unregisteredApplicationIsRefused() {
        // Either an integration nobody reviewed, or someone with a stolen credential writing
        // consent records. The submission is otherwise perfectly well formed, which is the point:
        // nothing else in the platform would have noticed.
        assertThat(codes(validator.validate(fromApplication("SOME_UNKNOWN_TOOL"))))
                .contains(CaptureViolation.Code.APPLICATION_NOT_REGISTERED);
    }

    @Test
    @DisplayName("a decommissioned surface still writing consent is refused")
    void inactiveApplicationIsRefused() {
        assertThat(codes(validator.validate(fromApplication(Fixtures.APP_RETIRED))))
                .contains(CaptureViolation.Code.APPLICATION_NOT_REGISTERED);
    }

    @Test
    @DisplayName("a surface cannot capture consent for a different group entity")
    void applicationCannotCaptureForAnotherEntity() {
        // What a leaked credential actually looks like from the inside. Every field is
        // individually valid; only the relationship between the entity and the application is
        // wrong, and per-entity isolation in the database is still Phase 1 work — so for now this
        // check is the thing standing between Matrix's BGV workflow and Denave's ledger.
        assertThat(codes(validator.validate(fromApplication(Fixtures.APP_OTHER_ENTITY))))
                .contains(CaptureViolation.Code.APPLICATION_ENTITY_MISMATCH);
    }

    @Test
    @DisplayName("a registered, active surface belonging to the capturing entity passes")
    void registeredApplicationPasses() {
        assertThat(codes(validator.validate(fromApplication(Fixtures.APP))))
                .doesNotContain(CaptureViolation.Code.APPLICATION_NOT_REGISTERED,
                        CaptureViolation.Code.APPLICATION_ENTITY_MISMATCH);
    }

    @Test
    @DisplayName("an absent application id is permitted, deliberately and for now")
    void missingApplicationIdIsNotYetAViolation() {
        // Several surfaces predate the registry and send nothing. Rejecting them would drop real
        // consent on the floor — the worst possible failure for a control whose whole purpose is
        // preserving evidence. This test exists so that tightening the rule is a deliberate act
        // that breaks a named expectation, rather than something that happens by accident.
        assertThat(codes(validator.validate(fromApplication(null))))
                .doesNotContain(CaptureViolation.Code.APPLICATION_NOT_REGISTERED,
                        CaptureViolation.Code.APPLICATION_ENTITY_MISMATCH);
    }

    // -------------------------------------------------------------------------------------------

    // -------------------------------------------------------------------------------------------
    // Notice integrity — the reference must point at something real
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a notice version that was never published is rejected")
    void aCitationOfNothingIsRefused() {
        // The gap this closes. NOTICE_VERSION_NOT_RECORDED only ever tested that the field was
        // present; a capture citing version 99 of a notice published to version 1 sailed through,
        // producing a record that looks like sound evidence from every angle available afterwards.
        // The failure surfaces years later, when somebody asks to see what the person read.
        assertThat(codes(validator.validate(citing(Fixtures.NOTICE, 99, "en"))))
                .contains(CaptureViolation.Code.NOTICE_VERSION_UNKNOWN);
    }

    @Test
    @DisplayName("a notice id that does not exist at all is rejected")
    void anUnknownNoticeIsRefused() {
        assertThat(codes(validator.validate(citing("NOTICE_NEVER_PUBLISHED", 1, "en"))))
                .contains(CaptureViolation.Code.NOTICE_VERSION_UNKNOWN);
    }

    @Test
    @DisplayName("a language the notice does not exist in is rejected")
    void aNoticeTheSubjectCouldNotReadIsRefused() {
        // Separate code from the one above, because the remediation is different: this is usually
        // a translation-procurement gap rather than a bug, and it is answered with a purchase
        // order rather than by paging an engineer.
        assertThat(codes(validator.validate(citing(Fixtures.NOTICE, 1, "brx"))))
                .contains(CaptureViolation.Code.NOTICE_LANGUAGE_UNAVAILABLE);
    }

    @Test
    @DisplayName("an unknown version does not also report a missing translation")
    void oneFailureAtATime() {
        // Reporting both would send the integrator after the wrong problem — they would go looking
        // for a translation of a version that does not exist.
        assertThat(codes(validator.validate(citing(Fixtures.NOTICE, 99, "brx"))))
                .contains(CaptureViolation.Code.NOTICE_VERSION_UNKNOWN)
                .doesNotContain(CaptureViolation.Code.NOTICE_LANGUAGE_UNAVAILABLE);
    }

    @Test
    @DisplayName("a real version in a real language passes")
    void aValidCitationPasses() {
        assertThat(codes(validator.validate(citing(Fixtures.NOTICE, 1, "hi"))))
                .doesNotContain(CaptureViolation.Code.NOTICE_VERSION_UNKNOWN,
                        CaptureViolation.Code.NOTICE_LANGUAGE_UNAVAILABLE);
    }

    private static CaptureSubmission citing(String noticeId, int version, String languageTag) {
        return new CaptureSubmission("DENAVE_IN", "subject-1", Jurisdiction.IN, languageTag,
                Channel.WEB, Fixtures.APP, CaptureMethod.CHECKBOX_OPT_IN, ActorType.SUBJECT,
                "subject-1", noticeId, version,
                List.of(CaptureSubmission.PurposeChoice.acceptedSeparately("WEB_ADVERTISING")),
                true, NOW, "idem-" + System.nanoTime(), "evidence://form/1", Map.of());
    }

    private static CaptureSubmission fromApplication(String applicationId) {
        return new CaptureSubmission("DENAVE_IN", "subject-1", Jurisdiction.IN, "en", Channel.WEB,
                applicationId, CaptureMethod.CHECKBOX_OPT_IN, ActorType.SUBJECT, "subject-1",
                "NOTICE_TEST", 1,
                List.of(CaptureSubmission.PurposeChoice.acceptedSeparately("WEB_ADVERTISING")),
                true, NOW, "idem-" + System.nanoTime(), "evidence://form/1", Map.of());
    }

    private static CaptureSubmission submission(Jurisdiction jurisdiction, Channel channel,
                                                CaptureMethod method,
                                                CaptureSubmission.PurposeChoice... choices) {
        return new CaptureSubmission("DENAVE_IN", "subject-1", jurisdiction, "en", channel, Fixtures.APP,
                method, ActorType.SUBJECT, "subject-1", "NOTICE_TEST", 1, List.of(choices), true,
                NOW, "idem-" + System.nanoTime(), "evidence://form/1", Map.of());
    }

    private static List<CaptureViolation.Code> codes(List<CaptureViolation> violations) {
        return violations.stream().map(CaptureViolation::code).toList();
    }
}
