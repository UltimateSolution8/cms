package com.uds.consent.service.it;

import com.uds.consent.core.decision.DecisionRequest;
import com.uds.consent.core.decision.DecisionResponse;
import com.uds.consent.core.model.ActorType;
import com.uds.consent.core.model.CaptureMethod;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.ledger.store.ReconfirmationStore;
import com.uds.consent.policy.PolicyEngine;
import com.uds.consent.policy.capture.CaptureSubmission;
import com.uds.consent.service.ConsentCaptureService;
import com.uds.consent.service.PublishingService;
import com.uds.consent.service.adapter.CachingApplicationRegistry;
import com.uds.consent.service.sweeper.ReconfirmationSweeper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Korea's two-yearly re-confirmation of consent to receive advertising information.
 *
 * <p>Enforcement Decree of the Information and Communications Network Act, Article 62-3. Two plans
 * deferred this for want of the interval; the Decree supplies it — every two years from the date
 * consent was obtained — together with the three things the confirmation must disclose.
 *
 * <p>The most important test in this file is {@link #anOverdueConfirmationDoesNotDenyTheDecision}.
 * Everything else here is arithmetic and plumbing; that one pins a judgement, and it is the one a
 * future reader is most likely to "fix". Art. 62-3 fixes the interval and the disclosure and says
 * nothing about what follows from a recipient who never answers. Denying on an unanswered
 * confirmation would enforce a rule nobody can cite and would suppress lawful contact on this
 * platform's own authority.
 */
class ReconfirmationIT extends PostgresIntegrationTest {

    /** The group's Korean entity. The sweeper is scoped to KR and must ignore everything else. */
    private static final String KR_ENTITY = "DENAVE_KR";

    private static final String IN_ENTITY = "DENAVE_IN";
    private static final String IN_APP = "DENAVE_WEB";
    private static final String IN_NOTICE = "NOTICE_DENAVE_B2B";
    private static final String PURPOSE = "MKT_OUTBOUND_EMAIL";

    /** Consent given here; the confirmation falls due exactly two years later. */
    private static final Instant CONSENTED_AT = Instant.parse("2024-08-17T09:00:00Z");
    private static final Instant DUE_AT = Instant.parse("2026-08-17T09:00:00Z");

    /**
     * The Korean surface and notice, created once for the whole suite.
     *
     * <p>The seed has a Korean entity and nothing that captures for it — no registered application
     * and no Korean-language notice — because until now no suite had ever exercised the Korean
     * capture path end to end. Built here rather than added to {@code V3} so that a fixture for one
     * suite does not change what every other suite's assertions resolve to.
     */
    private static final String KR_APP = "DENAVE_KR_WEB_IT";
    private static final String KR_NOTICE = "NOTICE_DENAVE_KR_IT";

    @Autowired
    private ConsentCaptureService capture;

    @Autowired
    private ReconfirmationStore reconfirmations;

    @Autowired
    private ReconfirmationSweeper sweeper;

    @Autowired
    private PolicyEngine policy;

    @Autowired
    private PublishingService publishing;

    @Autowired
    private CachingApplicationRegistry applications;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void ensureAKoreanCaptureSurfaceExists() {
        jdbc.update("""
                insert into application_registry (application_id, entity_id, name, platform,
                                                  environment, description)
                values (?, ?, 'Denave Korea site', 'WEB', 'PRODUCTION', 'Reconfirmation IT fixture')
                on conflict (application_id) do nothing
                """, KR_APP, KR_ENTITY);
        jdbc.update("""
                insert into application_entity_scope (application_id, entity_id, rationale)
                values (?, ?, 'Owning entity') on conflict do nothing
                """, KR_APP, KR_ENTITY);
        applications.refresh();

        if (jdbc.queryForObject("select count(*) from notice where notice_id = ?",
                Integer.class, KR_NOTICE) == 0) {
            jdbc.update("insert into notice (notice_id, entity_id, name) values (?, ?, ?)",
                    KR_NOTICE, KR_ENTITY, "Denave Korea privacy notice");
            // PIPA requires the notice in Korean; the capture validator refuses a submission
            // citing a version that exists in no language the subject was served in, which is
            // the check that makes this fixture non-optional rather than decorative.
            int version = publishing.publishNotice(KR_NOTICE, "KR", false,
                    "https://denave.example/kr/withdraw", "https://denave.example/kr/rights",
                    "https://denave.example/kr/grievance", "compliance-console")
                    .version().version();
            publishing.addTranslation(KR_NOTICE, version, "ko", "개인정보 처리방침",
                    "데나베 코리아의 개인정보 이용에 관한 안내입니다.", "compliance-console");
        }
    }

    // -------------------------------------------------------------------------------------
    // Art. 62-3(1) — the interval
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("the confirmation falls due on the same date two years later, not 730 days later")
    void theIntervalIsTwoCalendarYears() {
        assertThat(ReconfirmationStore.dueAfter(CONSENTED_AT)).isEqualTo(DUE_AT);

        // Why days are the wrong unit, shown on a window that actually contains a leap day.
        //
        // August 2024 to August 2026 contains none — 29 February 2024 falls before it — so 730
        // days and two years agree here, and a days-based implementation would pass the assertion
        // above while being wrong elsewhere. August 2023 to August 2025 does contain one, and
        // there the two answers differ by a day.
        //
        // The Decree names the date, not a count of days, so the disagreement is not a rounding
        // question: one of these is the obligation and the other is a day late.
        Instant acrossALeapDay = Instant.parse("2023-08-17T09:00:00Z");
        assertThat(ReconfirmationStore.dueAfter(acrossALeapDay))
                .isEqualTo(Instant.parse("2025-08-17T09:00:00Z"))
                .isNotEqualTo(acrossALeapDay.plus(java.time.Duration.ofDays(730)));
    }

    @Test
    @DisplayName("consent given on 29 February falls due on 28 February")
    void theLeapDayResolvesToTheLastDayOfTheMonth() {
        // There is no 29 February 2026 to wait for, and waiting until 1 March would put the
        // obligation outside the two years the Decree allows. The last day of the month is the
        // only reading that keeps the check inside the period.
        assertThat(ReconfirmationStore.dueAfter(Instant.parse("2024-02-29T00:00:00Z")))
                .isEqualTo(Instant.parse("2026-02-28T00:00:00Z"));
    }

    // -------------------------------------------------------------------------------------
    // The queue
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("a Korean consent two years old is raised, and raised once however often we sweep")
    void theSweeperRaisesEachObligationExactlyOnce() {
        String subject = grantKoreanConsent(CONSENTED_AT);

        ReconfirmationSweeper.Report first = sweeper.run(DUE_AT.plusSeconds(3_600));
        assertThat(first.raised()).isPositive();
        assertThat(rowsFor(subject)).hasSize(1);
        assertThat(rowsFor(subject).getFirst().dueAt()).isEqualTo(DUE_AT);
        assertThat(rowsFor(subject).getFirst().status()).isEqualTo("DUE");

        // A sweeper on a twelve-hour timer that raised the same obligation on every pass would
        // turn one duty into sixty a month, which is how a real queue becomes unreadable.
        sweeper.run(DUE_AT.plusSeconds(7_200));
        assertThat(rowsFor(subject)).hasSize(1);
    }

    @Test
    @DisplayName("a consent inside its two years is not raised")
    void aFreshConsentOwesNothing() {
        String subject = grantKoreanConsent(Instant.parse("2026-01-05T09:00:00Z"));

        sweeper.run(DUE_AT);

        assertThat(rowsFor(subject)).isEmpty();
    }

    @Test
    @DisplayName("an Indian consent of the same age is not raised — Art. 50 reaches Korea only")
    void theObligationIsScopedToKorea() {
        String subject = "recon-in-" + UUID.randomUUID();
        capture.capture(new CaptureSubmission(IN_ENTITY, subject, Jurisdiction.IN, "en",
                Channel.EMAIL, IN_APP, CaptureMethod.CHECKBOX_OPT_IN, ActorType.SUBJECT, subject,
                IN_NOTICE, 1, List.of(CaptureSubmission.PurposeChoice.acceptedSeparately(PURPOSE)),
                true, CONSENTED_AT, "recon-in-" + subject, null, Map.of()));

        sweeper.run(DUE_AT.plusSeconds(3_600));

        // A queue full of obligations that do not exist is how a real one goes unnoticed.
        assertThat(rowsFor(subject)).isEmpty();
    }

    // -------------------------------------------------------------------------------------
    // Art. 62-3(2) — what the confirmation has to say
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("all three disclosures are recorded, and a partial confirmation is refused")
    void theDisclosuresAreRequiredAndRecorded() {
        String subject = grantKoreanConsent(CONSENTED_AT);
        sweeper.run(DUE_AT.plusSeconds(3_600));
        long id = rowsFor(subject).getFirst().id();

        // The obligation is not "we sent something". It is "we sent something containing these
        // three things", so a call that could record the act without the content would let the
        // platform report an obligation discharged by something that did not discharge it.
        assertThatThrownBy(() -> reconfirmations.markSent(id, "Denave Korea", CONSENTED_AT, "  ",
                "EMAIL", DUE_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Art. 62-3(2)");

        assertThat(reconfirmations.markSent(id, "Denave Korea Co., Ltd.", CONSENTED_AT,
                "https://denave.example/kr/unsubscribe", "EMAIL", DUE_AT)).isOne();

        ReconfirmationStore.Reconfirmation sent = rowsFor(subject).getFirst();
        assertThat(sent.status()).isEqualTo("SENT");
        assertThat(sent.senderName()).isEqualTo("Denave Korea Co., Ltd.");
        assertThat(sent.disclosedConsentDate()).isEqualTo(CONSENTED_AT);
        assertThat(sent.withdrawalMethod()).isEqualTo("https://denave.example/kr/unsubscribe");
    }

    @Test
    @DisplayName("the recipient's answer closes the row and clears the overdue count")
    void anAnswerClosesTheObligation() {
        String subject = grantKoreanConsent(CONSENTED_AT);
        sweeper.run(DUE_AT.plusSeconds(3_600));
        long id = rowsFor(subject).getFirst().id();

        assertThat(reconfirmations.isOverdue(KR_ENTITY, subject, PURPOSE, DUE_AT.plusSeconds(60)))
                .isTrue();

        reconfirmations.markSent(id, "Denave Korea Co., Ltd.", CONSENTED_AT,
                "https://denave.example/kr/unsubscribe", "EMAIL", DUE_AT);
        assertThat(reconfirmations.complete(id, "MAINTAINED", "compliance-console",
                "recipient confirmed by return click", DUE_AT.plusSeconds(86_400))).isOne();

        // Off the open queue entirely — open() lists DUE and SENT, and a MAINTAINED row is
        // neither. The row itself is still there; what has gone is the outstanding obligation.
        assertThat(rowsFor(subject)).isEmpty();
        assertThat(reconfirmations.isOverdue(KR_ENTITY, subject, PURPOSE, DUE_AT.plusSeconds(60)))
                .isFalse();
    }

    // -------------------------------------------------------------------------------------
    // The judgement
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("an overdue confirmation is an obligation on the decision, never a denial")
    void anOverdueConfirmationDoesNotDenyTheDecision() {
        // Read the whole comment before changing this test.
        //
        // Art. 62-3 prescribes the interval and the disclosure. It does NOT say that a recipient
        // who fails to answer has withdrawn consent — that is industry practice, and practice is
        // not text. A platform that denied here would be enforcing a rule nobody can cite,
        // against the group's own commercial interest, on its own authority; a platform that said
        // nothing would leave a dated statutory duty invisible. The obligation is the honest
        // middle, and REGULATORY_HANDOFF.md carries the counsel question it defers to.
        //
        // If Korean counsel later advises that silence withdraws, this becomes a denial and this
        // test becomes its opposite. Until then, ALLOW.
        String subject = grantKoreanConsent(CONSENTED_AT);
        sweeper.run(DUE_AT.plusSeconds(3_600));

        DecisionResponse decision = policy.evaluate(new DecisionRequest(KR_ENTITY, subject,
                PURPOSE, Channel.EMAIL, Jurisdiction.KR, KR_APP, DUE_AT.plusSeconds(7_200),
                null, null, null, Map.of()));

        assertThat(decision.isAllowed())
                .as("an unanswered Art. 62-3 confirmation must not deny; see the comment above")
                .isTrue();
        assertThat(decision.obligations()).contains("reconfirmation-overdue");
    }

    @Test
    @DisplayName("a consent inside its two years carries no overdue obligation")
    void aFreshKoreanConsentCarriesNoObligation() {
        String subject = grantKoreanConsent(Instant.parse("2026-01-05T09:00:00Z"));
        sweeper.run(DUE_AT);

        DecisionResponse decision = policy.evaluate(new DecisionRequest(KR_ENTITY, subject,
                PURPOSE, Channel.EMAIL, Jurisdiction.KR, KR_APP, DUE_AT, null, null, null, Map.of()));

        assertThat(decision.isAllowed()).isTrue();
        // The Korean obligations that always apply are still there; the overdue one is not.
        assertThat(decision.obligations()).contains("consent-must-be-itemised-per-purpose");
        assertThat(decision.obligations()).doesNotContain("reconfirmation-overdue");
    }

    // -------------------------------------------------------------------------------------

    private String grantKoreanConsent(Instant at) {
        String subject = "recon-kr-" + UUID.randomUUID();
        ConsentCaptureService.Result result = capture.capture(new CaptureSubmission(
                KR_ENTITY, subject, Jurisdiction.KR, "ko", Channel.EMAIL, KR_APP,
                CaptureMethod.CHECKBOX_OPT_IN, ActorType.SUBJECT, subject, KR_NOTICE, 1,
                List.of(CaptureSubmission.PurposeChoice.acceptedSeparately(PURPOSE)),
                true, at, "recon-kr-" + subject, null, Map.of()));
        assertThat(result.violations()).isEmpty();
        return subject;
    }

    private List<ReconfirmationStore.Reconfirmation> rowsFor(String subject) {
        return reconfirmations.open(KR_ENTITY, 1000).stream()
                .filter(row -> row.subjectId().equals(subject))
                .toList();
    }
}
