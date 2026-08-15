package com.uds.consent.policy.capture;

import com.uds.consent.core.model.ActorType;
import com.uds.consent.core.model.CaptureMethod;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.policy.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
            new CaptureValidator(Fixtures.fullCatalog(), Fixtures.allModules());

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
                Jurisdiction.IN, "en", Channel.WEB, "APP", CaptureMethod.CHECKBOX_OPT_IN,
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
                Jurisdiction.IN, "en", Channel.WEB, "APP", CaptureMethod.CHECKBOX_OPT_IN,
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
                Jurisdiction.IN, null, Channel.WEB, "APP", CaptureMethod.CHECKBOX_OPT_IN,
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
                "ko", null, "APP", CaptureMethod.CHECKBOX_OPT_IN, ActorType.SUBJECT,
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
                Jurisdiction.KR, "ko", null, "APP", CaptureMethod.CHECKBOX_OPT_IN,
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
            Fixtures.allModules());

    @Test
    @DisplayName("a child's consent without verified parental action is rejected")
    void childConsentNeedsAVerifiedGuardian() {
        CaptureSubmission byChild = new CaptureSubmission("DENAVE_IN", "subject-1", Jurisdiction.IN,
                "en", Channel.WEB, "APP", CaptureMethod.CHECKBOX_OPT_IN, ActorType.SUBJECT,
                "subject-1", "NOTICE_TEST", 1,
                List.of(CaptureSubmission.PurposeChoice.acceptedSeparately(
                        "WEB_PROGRAMME_SIGNUP")),
                true, NOW, "idem-6", null, Map.of("subject.isChild", "true"));

        assertThat(codes(childSafeValidator.validate(byChild)))
                .containsExactly(CaptureViolation.Code.PARENTAL_CONSENT_REQUIRED);
    }

    @Test
    @DisplayName("a guardian consenting on a child's behalf to a permitted purpose is accepted")
    void verifiedParentalConsentIsAccepted() {
        CaptureSubmission byGuardian = new CaptureSubmission("DENAVE_IN", "subject-1",
                Jurisdiction.IN, "en", Channel.WEB, "APP", CaptureMethod.PARENTAL_VERIFIED,
                ActorType.PARENT_GUARDIAN, "guardian-9", "NOTICE_TEST", 1,
                List.of(CaptureSubmission.PurposeChoice.acceptedSeparately(
                        "WEB_PROGRAMME_SIGNUP")),
                true, NOW, "idem-10", null, Map.of("subject.isChild", "true"));

        assertThat(childSafeValidator.validate(byGuardian)).isEmpty();
    }

    @Test
    @DisplayName("even a verified guardian cannot consent to advertising at a child")
    void somePurposesAreClosedToChildrenRegardlessOfWhoConsents() {
        // s.9 bars behavioural tracking and targeted advertising at children outright. A parent
        // agreeing does not reopen it.
        CaptureSubmission byGuardian = new CaptureSubmission("DENAVE_IN", "subject-1",
                Jurisdiction.IN, "en", Channel.WEB, "APP", CaptureMethod.PARENTAL_VERIFIED,
                ActorType.PARENT_GUARDIAN, "guardian-9", "NOTICE_TEST", 1,
                List.of(CaptureSubmission.PurposeChoice.acceptedSeparately("WEB_ADVERTISING")),
                true, NOW, "idem-7", null, Map.of("subject.isChild", "true"));

        assertThat(codes(validator.validate(byGuardian)))
                .contains(CaptureViolation.Code.CHILD_PURPOSE_NOT_PERMITTED);
    }

    @Test
    @DisplayName("declining a child-restricted purpose is recordable, and worth recording")
    void aChildDecliningARestrictedPurposeIsStillARecord() {
        // The refusal is the most useful thing in the interaction. Refusing to store it because
        // the purpose is closed to children would discard the evidence that it was refused.
        CaptureSubmission byGuardian = new CaptureSubmission("DENAVE_IN", "subject-1",
                Jurisdiction.IN, "en", Channel.WEB, "APP", CaptureMethod.PARENTAL_VERIFIED,
                ActorType.PARENT_GUARDIAN, "guardian-9", "NOTICE_TEST", 1,
                List.of(CaptureSubmission.PurposeChoice.declined("WEB_ADVERTISING")),
                true, NOW, "idem-8", null, Map.of("subject.isChild", "true"));

        assertThat(validator.validate(byGuardian)).isEmpty();
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
                null, Channel.EMAIL, "APP", CaptureMethod.IMPORTED_WITH_PROVENANCE,
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

    private static CaptureSubmission submission(Jurisdiction jurisdiction, Channel channel,
                                                CaptureMethod method,
                                                CaptureSubmission.PurposeChoice... choices) {
        return new CaptureSubmission("DENAVE_IN", "subject-1", jurisdiction, "en", channel, "APP",
                method, ActorType.SUBJECT, "subject-1", "NOTICE_TEST", 1, List.of(choices), true,
                NOW, "idem-" + System.nanoTime(), "evidence://form/1", Map.of());
    }

    private static List<CaptureViolation.Code> codes(List<CaptureViolation> violations) {
        return violations.stream().map(CaptureViolation::code).toList();
    }
}
