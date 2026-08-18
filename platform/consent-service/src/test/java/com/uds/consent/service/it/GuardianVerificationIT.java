package com.uds.consent.service.it;

import com.uds.consent.core.decision.DecisionRequest;
import com.uds.consent.core.decision.DecisionResponse;
import com.uds.consent.core.decision.DenialReason;
import com.uds.consent.core.model.ActorType;
import com.uds.consent.core.model.CaptureMethod;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.ConsentReceipt;
import com.uds.consent.core.model.ExpiryPolicy;
import com.uds.consent.core.model.FailureBehavior;
import com.uds.consent.core.model.GuardianVerification;
import com.uds.consent.core.model.GuardianVerificationMethod;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.LegalBasis;
import com.uds.consent.ledger.store.PurposeRegistryStore;
import com.uds.consent.ledger.store.SubjectStore;
import com.uds.consent.policy.PolicyEngine;
import com.uds.consent.policy.capture.CaptureSubmission;
import com.uds.consent.policy.capture.CaptureViolation;
import com.uds.consent.service.ConsentCaptureService;
import com.uds.consent.service.PublishingService;
import com.uds.consent.service.ReceiptService;
import com.uds.consent.service.adapter.CachingPurposeCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DPDP s.9 and Rule 10, end to end: the consent, the diligence behind it, and the history.
 *
 * <p>What this suite exists to pin is a distinction the platform previously could not draw. A
 * child capture used to be accepted on the strength of two values the capture surface chose for
 * itself — {@code captureMethod = PARENTAL_VERIFIED} and {@code actorType = PARENT_GUARDIAN} — and
 * nothing recorded whether any guardian had actually been checked, or how. The group could produce
 * a consent and could not produce the diligence, which is the wrong way round: Rule 10 puts the
 * duty of diligence on the fiduciary, and the consent is only its output.
 *
 * <p>Three properties are asserted here and nowhere else, because none of them can be seen without
 * a database:
 *
 * <ul>
 *   <li>the verification travels into {@code canonical_payload}, and so into the hash chain, which
 *       is what makes it evidence rather than a field;</li>
 *   <li>the raw reference the caller sent never lands anywhere, in any column;</li>
 *   <li>minority is recorded as a dated assertion with a source, so the question "was this subject
 *       a child on the day we decided that about them" has an answer after they turn eighteen.</li>
 * </ul>
 *
 * <p>The suite publishes its own purpose. The seeded taxonomy has no purpose that is both
 * consent-based and permitted for children — the two seeded child-permitted purposes rest on
 * legitimate use — so a fixture borrowed from the seed would be testing the wrong refusal.
 */
class GuardianVerificationIT extends PostgresIntegrationTest {

    private static final String ENTITY = "DENAVE_IN";
    private static final String APP = "DENAVE_WEB";
    private static final String NOTICE = "NOTICE_DENAVE_B2B";
    private static final Instant NOW = Instant.parse("2026-08-17T09:00:00Z");
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    /** The reference the caller sends. Must never appear in the database in this form. */
    private static final String RAW_TOKEN = "digilocker-virtual-token-8f21c4";

    @Autowired
    private ConsentCaptureService capture;

    @Autowired
    private PublishingService publishing;

    @Autowired
    private CachingPurposeCatalog catalog;

    @Autowired
    private ReceiptService receipts;

    @Autowired
    private SubjectStore subjects;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PolicyEngine policy;

    private String childPurpose;

    /** A purpose closed to under-eighteens, so the s.9 gate has something to bite on. */
    private String restrictedPurpose;

    @BeforeEach
    void publishAChildPermittedConsentPurpose() {
        int n = SEQUENCE.incrementAndGet();
        childPurpose = "TEST_CHILD_" + n;
        publishing.publishPurpose(new PublishingService.NewVersion(
                childPurpose, "Youth programme sign-up", "legal",
                "Registering a young person for a programme, with a guardian's consent.",
                Map.of(Jurisdiction.IN,
                        new PurposeRegistryStore.BasisEntry(LegalBasis.CONSENT, null, null)),
                Set.of("CONTACT_PERSONAL"), Set.of(Channel.WEB), ExpiryPolicy.NONE, null,
                FailureBehavior.FAIL_CLOSED, NOTICE, false, true, false, false),
                "compliance-console");

        restrictedPurpose = "TEST_ADULT_ONLY_" + n;
        publishing.publishPurpose(new PublishingService.NewVersion(
                restrictedPurpose, "Behavioural profiling", "legal",
                "The kind of processing DPDP s.9 closes to under-eighteens outright.",
                Map.of(Jurisdiction.IN,
                        new PurposeRegistryStore.BasisEntry(LegalBasis.CONSENT, null, null)),
                Set.of("CONTACT_PERSONAL"), Set.of(Channel.WEB), ExpiryPolicy.NONE, null,
                FailureBehavior.FAIL_CLOSED, NOTICE, false, false, false, false),
                "compliance-console");
        catalog.refresh();
    }

    @Test
    @DisplayName("a child capture with no record of the diligence writes nothing")
    void anUnevidencedParentalConsentIsRefused() {
        String subject = newSubject();

        ConsentCaptureService.Result result = capture.capture(childSubmission(subject, null));

        assertThat(result.isAccepted()).isFalse();
        assertThat(result.violations()).extracting(CaptureViolation::code)
                .contains(CaptureViolation.Code.GUARDIAN_VERIFICATION_NOT_EVIDENCED);

        // Nothing at all, not merely no consent. A refused capture that still left an age
        // assertion behind would be recording a fact about a child extracted from a submission
        // the platform declined to believe.
        assertThat(eventCount(subject)).isZero();
        assertThat(subjects.ageAssertionsFor(ENTITY, subject)).isEmpty();
    }

    @Test
    @DisplayName("the verification lands in the hash chain, and the raw reference lands nowhere")
    void theDiligenceIsChainedAndTheReferenceIsNot() {
        String subject = newSubject();
        String referenceHash = "hash-" + UUID.randomUUID();

        ConsentCaptureService.Result result = capture.capture(childSubmission(subject,
                new GuardianVerification(GuardianVerificationMethod.DIGILOCKER_VIRTUAL_TOKEN,
                        referenceHash, NOW.minusSeconds(86_400), "denave-web")));

        assertThat(result.isAccepted()).isTrue();

        // canonical_payload is the exact bytes that were hashed. Asserting against it rather than
        // against the attributes column is the point: a value present in the column and absent
        // from the payload would be a value outside the chain, alterable without detection.
        String payload = jdbc.queryForObject(
                "select canonical_payload from consent_event where subject_id = ?",
                String.class, subject);
        assertThat(payload)
                .contains(GuardianVerification.ATTR_METHOD)
                .contains("DIGILOCKER_VIRTUAL_TOKEN")
                .contains(referenceHash);

        // And the raw value the caller sent is nowhere, in any column of any row. The hashing
        // happens at the HTTP boundary; this asserts nothing downstream of it quietly kept a copy.
        assertThat(payload).doesNotContain(RAW_TOKEN);
        assertThat(jdbc.queryForObject(
                "select count(*) from consent_event where attributes::text like ?",
                Integer.class, "%" + RAW_TOKEN + "%")).isZero();
    }

    @Test
    @DisplayName("minority is recorded as a dated assertion, not only as a mutable flag")
    void minorityHasAHistory() {
        String subject = newSubject();

        capture.capture(childSubmission(subject, verification()));

        assertThat(subjects.isChild(subject)).isTrue();

        List<SubjectStore.AgeAssertion> asserted = subjects.ageAssertionsFor(ENTITY, subject);
        assertThat(asserted).hasSize(1);
        assertThat(asserted.getFirst().isChild()).isTrue();
        assertThat(asserted.getFirst().source()).isEqualTo("capture:" + APP);
        assertThat(asserted.getFirst().assertedAt()).isEqualTo(NOW);
        assertThat(asserted.getFirst().actorType()).isEqualTo(ActorType.PARENT_GUARDIAN.name());

        // The question that outlives the flag. A subject who turns eighteen has is_child flipped
        // to false, and every behavioural decision taken while they were fifteen then looks
        // lawful — unless the assertion is dated, which is the whole reason it is dated.
        subjects.assertAge(ENTITY, subject, false, "birthday", NOW.plusSeconds(31_536_000),
                ActorType.SYSTEM.name(), "age-sweeper", null);

        assertThat(subjects.isChild(subject)).isFalse();
        assertThat(subjects.wasChildAt(ENTITY, subject, NOW.plusSeconds(3_600))).contains(true);
        assertThat(subjects.wasChildAt(ENTITY, subject, NOW.plusSeconds(40_000_000)))
                .contains(false);

        // Before anything was asserted is not the same answer as "not a child". Nobody had told
        // us, and a platform that returned false there would be asserting an absence as a fact.
        assertThat(subjects.wasChildAt(ENTITY, subject, NOW.minusSeconds(3_600))).isEmpty();
    }

    @Test
    @DisplayName("an age assertion cannot be edited or deleted once written")
    void theAssertionIsAppendOnly() {
        String subject = newSubject();
        capture.capture(childSubmission(subject, verification()));

        // The same guarantee the ledger has, for the same reason. A record of what the group was
        // told about a child's age is worth nothing if it can be revised once someone complains.
        assertThat(catchUpdate("update subject_age_assertion set is_child = false where subject_id = ?",
                subject)).isTrue();
        assertThat(catchUpdate("delete from subject_age_assertion where subject_id = ?", subject))
                .isTrue();
        assertThat(subjects.ageAssertionsFor(ENTITY, subject)).hasSize(1);
    }

    @Test
    @DisplayName("the receipt names the route the guardian was verified by, and not the reference")
    void theReceiptStatesTheRouteAndNothingMore() {
        String subject = newSubject();
        String referenceHash = "hash-" + UUID.randomUUID();
        capture.capture(childSubmission(subject,
                new GuardianVerification(GuardianVerificationMethod.EXISTING_VERIFIED_ACCOUNT,
                        referenceHash, NOW.minusSeconds(600), "denave-web")));

        ConsentReceipt receipt = receipts.issue(ENTITY, subject, NOW);

        assertThat(receipt.parentalVerification())
                .isEqualTo(GuardianVerificationMethod.EXISTING_VERIFIED_ACCOUNT);

        // A receipt is a document that gets emailed and printed. The guardian is a third party who
        // appears in the record only because the law required a check on them, and reproducing
        // their reference on the child's receipt would put an identifier in front of readers who
        // can do nothing with it. What the reader can use is that a check was made, and which kind.
        assertThat(receipt.toString()).doesNotContain(referenceHash);
    }

    @Test
    @DisplayName("a subject id posted straight through still gets a usable child flag")
    void aSubjectTheResolverNeverSawIsStillProtected() {
        // The gap this suite found. consent_event carries no foreign key to `subject`, so a
        // surface holding its own subject id posts it straight through and only the
        // identifier-resolution path ever inserts a `subject` row. For every such subject
        // markChild updated nothing and isChild answered false — a child protection that silently
        // did nothing for a whole class of subjects, which is worse than an absent one because it
        // reads as present. Nothing in the tree exercised it, because until now nothing asserted
        // minority end to end.
        String subject = newSubject();
        assertThat(subjectRowCount(subject)).isZero();

        capture.capture(childSubmission(subject, verification()));

        assertThat(subjectRowCount(subject)).isOne();
        assertThat(subjects.isChild(subject)).isTrue();
    }

    @Test
    @DisplayName("a decision replayed after the subject turns eighteen still sees the child")
    void theDecisionPathReadsTheAgeAsAtTheDecisionInstant() {
        // The audit-replay defect, end to end and through the real wiring rather than a stub.
        //
        // The child gate used to read subject.is_child — a mutable column. So a subject who was
        // fifteen when the dialer asked, and whose flag has since been flipped by a birthday,
        // replayed as an adult, and every restricted decision taken during their minority read back
        // as lawful. The engine now asks the question as at DecisionRequest.at().
        String subject = newSubject();
        capture.capture(childSubmission(subject, verification()));

        // The birthday. This is the state the audit is conducted in: current flag says adult,
        // history says otherwise, and only the history is evidence.
        Instant eighteenth = NOW.plusSeconds(31_536_000);
        subjects.assertAge(ENTITY, subject, false, "birthday", eighteenth,
                ActorType.SYSTEM.name(), "age-sweeper", null);
        assertThat(subjects.isChild(subject)).isFalse();

        DecisionResponse asItStood = policy.evaluate(new DecisionRequest(ENTITY, subject,
                restrictedPurpose, Channel.WEB, Jurisdiction.IN, APP, NOW,
                null, null, null, Map.of()));

        assertThat(asItStood.isAllowed()).isFalse();
        assertThat(asItStood.reason()).isEqualTo(DenialReason.CHILD_SUBJECT_RESTRICTED);

        // And the answer is genuinely time-dependent rather than stuck: after the birthday the
        // same purpose is no longer barred on age grounds. A test that only asserted the first
        // half would pass against an engine that had simply started denying everyone.
        DecisionResponse afterwards = policy.evaluate(new DecisionRequest(ENTITY, subject,
                restrictedPurpose, Channel.WEB, Jurisdiction.IN, APP,
                eighteenth.plusSeconds(86_400), null, null, null, Map.of()));

        assertThat(afterwards.reason()).isNotEqualTo(DenialReason.CHILD_SUBJECT_RESTRICTED);
    }

    @Test
    @DisplayName("a subject with no age assertion at all is still protected by the flag")
    void aSubjectPredatingTheAssertionTableIsStillProtected() {
        // The fallback, and the reason it exists.
        //
        // wasChildAt returns empty when nothing had been asserted by the instant asked about, and
        // empty is not "adult". Every subject captured before subject_age_assertion existed is in
        // that state, so wiring the port with a bare .orElse(false) would have silently un-protected
        // the entire pre-existing population — a change that passes review and every test written
        // after it, and shows up only as under-eighteens being profiled.
        //
        // The subject is created directly rather than through a capture, deliberately: every child
        // capture written since the assertion table landed leaves an assertion behind, so a
        // capture-built fixture would pass this test without testing anything.
        String subject = newSubject();
        jdbc.update("insert into subject (subject_id, entity_id, is_child) values (?, ?, true)",
                subject, ENTITY);
        assertThat(subjects.ageAssertionsFor(ENTITY, subject)).isEmpty();
        assertThat(subjects.wasChildAt(ENTITY, subject, NOW)).isEmpty();

        DecisionResponse decision = policy.evaluate(new DecisionRequest(ENTITY, subject,
                restrictedPurpose, Channel.WEB, Jurisdiction.IN, APP, NOW,
                null, null, null, Map.of()));

        assertThat(decision.reason()).isEqualTo(DenialReason.CHILD_SUBJECT_RESTRICTED);
    }

    @Test
    @DisplayName("an ordinary adult capture carries no verification and asserts nothing about age")
    void anAdultCaptureIsUntouched() {
        String subject = newSubject();

        ConsentCaptureService.Result result = capture.capture(new CaptureSubmission(
                ENTITY, subject, Jurisdiction.IN, "en", Channel.WEB, APP,
                CaptureMethod.CHECKBOX_OPT_IN, ActorType.SUBJECT, subject, NOTICE, 1,
                List.of(CaptureSubmission.PurposeChoice.acceptedSeparately(childPurpose)),
                true, NOW, "adult-" + subject, null, Map.of()));

        assertThat(result.isAccepted()).isTrue();

        // Silence about age is not an assertion that the subject is an adult. Writing one here
        // would fill the table with claims nobody made and destroy the one thing it is for.
        assertThat(subjects.ageAssertionsFor(ENTITY, subject)).isEmpty();
        assertThat(receipts.issue(ENTITY, subject, NOW).parentalVerification()).isNull();
    }

    // -----------------------------------------------------------------------------------------

    private CaptureSubmission childSubmission(String subject, GuardianVerification verification) {
        return new CaptureSubmission(ENTITY, subject, Jurisdiction.IN, "en", Channel.WEB, APP,
                CaptureMethod.PARENTAL_VERIFIED, ActorType.PARENT_GUARDIAN, "guardian-1", NOTICE, 1,
                List.of(CaptureSubmission.PurposeChoice.acceptedSeparately(childPurpose)),
                true, NOW, "child-" + subject, null,
                Map.of(CaptureSubmission.ATTR_IS_CHILD, "true"), verification);
    }

    private static GuardianVerification verification() {
        return new GuardianVerification(GuardianVerificationMethod.DIGILOCKER_VIRTUAL_TOKEN,
                "hash-" + UUID.randomUUID(), NOW.minusSeconds(600), "denave-web");
    }

    private static String newSubject() {
        return "gv-" + UUID.randomUUID();
    }

    private int subjectRowCount(String subject) {
        return jdbc.queryForObject("select count(*) from subject where subject_id = ?",
                Integer.class, subject);
    }

    private int eventCount(String subject) {
        return jdbc.queryForObject("select count(*) from consent_event where subject_id = ?",
                Integer.class, subject);
    }

    /** True when the statement was refused, which for these two is the expected outcome. */
    private boolean catchUpdate(String sql, Object... args) {
        try {
            jdbc.update(sql, args);
            return false;
        } catch (RuntimeException expected) {
            return true;
        }
    }
}
