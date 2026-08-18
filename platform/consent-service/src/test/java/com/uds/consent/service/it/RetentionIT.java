package com.uds.consent.service.it;

import com.uds.consent.core.model.ActorType;
import com.uds.consent.core.model.CaptureMethod;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.ledger.store.ProcessingActivityStore;
import com.uds.consent.ledger.store.RetentionStore;
import com.uds.consent.policy.capture.CaptureSubmission;
import com.uds.consent.service.ConsentCaptureService;
import com.uds.consent.service.sweeper.RetentionSweeper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retention, proposed and tracked rather than executed.
 *
 * <p>{@code retention_period_days} has existed since V1 and fed one gap report. Nothing acted on
 * the rule where a rule existed, which made the RoPA a description of an intention rather than of
 * a practice — and DPDP s.8(7) obliges erasure once the purpose is no longer served.
 *
 * <p>Two properties matter here and both are easy to get backwards. The sweeper <strong>proposes
 * and never deletes</strong>, because the personal data is in DenCRM and the HRMS while the
 * evidence that holding it was lawful is here — erasing the second and leaving the first is
 * exactly the wrong way round. And the date it acts on is the <strong>notice</strong> date, because
 * Rule 8 requires the principal to be told before their data goes.
 */
class RetentionIT extends PostgresIntegrationTest {

    private static final String ENTITY = "DENAVE_IN";
    private static final String APP = "DENAVE_WEB";
    private static final String NOTICE = "NOTICE_DENAVE_B2B";

    @Autowired
    private RetentionSweeper sweeper;

    @Autowired
    private RetentionStore retention;

    @Autowired
    private ProcessingActivityStore activities;

    @Autowired
    private ConsentCaptureService capture;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("a dormant subject is proposed for erasure, and their evidence is untouched")
    void aDormantSubjectIsProposedNotDeleted() {
        String purpose = "MKT_OUTBOUND_CALL";
        String subject = grantLongAgo(purpose, Instant.now().minus(400, ChronoUnit.DAYS));
        activityWithRetention(purpose, 365, "DenCRM");

        sweeper.run(Instant.now());

        List<RetentionStore.Action> open = retention.open(ENTITY, 500);
        assertThat(open).anySatisfy(action -> {
            assertThat(action.subjectId()).isEqualTo(subject);
            assertThat(action.purposeCode()).isEqualTo(purpose);
            // The system that actually holds the data, carried through so the proposal can be
            // assigned to somebody rather than filed. Not asserted against a particular activity
            // id: several tests in this class register an activity for the same purpose, and which
            // one a proposal is attributed to is a detail of iteration order rather than a
            // property worth pinning.
            assertThat(action.systemName()).isEqualTo("DenCRM");
            assertThat(action.activityId()).isNotNull();
        });

        // And nothing was erased here. The ledger is append-only and its evidence has to outlive
        // the personal data it concerned — deleting it would destroy the proof that the group
        // held that data lawfully for as long as it did.
        assertThat(jdbc.queryForObject(
                "select count(*) from consent_event where subject_id = ?", Integer.class, subject))
                .isPositive();
    }

    @Test
    @DisplayName("the notice date precedes the erasure date by the configured lead time")
    void ruleEightOrderingHolds() {
        String purpose = "MKT_OUTBOUND_CALL";
        Instant lastActivity = Instant.now().minus(500, ChronoUnit.DAYS);
        String subject = grantLongAgo(purpose, lastActivity);
        activityWithRetention(purpose, 365, "DenCRM");

        sweeper.run(Instant.now());

        RetentionStore.Action action = retention.open(ENTITY, 500).stream()
                .filter(a -> a.subjectId().equals(subject))
                .findFirst().orElseThrow();

        // Getting this backwards produces a platform that erases punctually and unlawfully: Rule 8
        // requires the principal to be told BEFORE the period ends, so that they can act to keep
        // the account if they want it.
        assertThat(action.noticeDueAt()).isBefore(action.eraseDueAt());
        assertThat(Duration.between(action.noticeDueAt(), action.eraseDueAt()))
                .isGreaterThanOrEqualTo(Duration.ofHours(48));

        // Dated from the subject's last interaction rather than from today. A backlog is late, and
        // dating proposals from the sweep would quietly reset the clock on every one of them.
        assertThat(action.eraseDueAt()).isEqualTo(lastActivity.plus(Duration.ofDays(365)));
    }

    @Test
    @DisplayName("a subject who interacted recently is not proposed")
    void recentActivityResetsTheClock() {
        String purpose = "MKT_OUTBOUND_CALL";
        String subject = grantLongAgo(purpose, Instant.now().minus(10, ChronoUnit.DAYS));
        activityWithRetention(purpose, 365, "DenCRM");

        sweeper.run(Instant.now());

        // Measured from the most recent event rather than the first. Measuring from creation would
        // propose erasing the records of the group's most engaged contacts.
        assertThat(retention.open(ENTITY, 500))
                .noneMatch(action -> action.subjectId().equals(subject));
    }

    @Test
    @DisplayName("an activity with no retention rule proposes nothing")
    void noRuleMeansNoEnforcement() {
        String purpose = "MKT_OUTBOUND_EMAIL";
        String subject = grantLongAgo(purpose, Instant.now().minus(2000, ChronoUnit.DAYS));
        activities.create(new ProcessingActivityStore.Activity(null, ENTITY,
                "No retention rule " + UUID.randomUUID(), null, purpose, "DenCRM",
                List.of("CONTACT_BUSINESS"), List.of(), List.of(), null, null, "owner", null));

        sweeper.run(Instant.now());

        // The absence is already a finding on the RoPA gap report. Inventing a default here would
        // enforce a period nobody agreed to, against data somebody may be obliged to keep.
        assertThat(retention.open(ENTITY, 500))
                .noneMatch(action -> action.subjectId().equals(subject));
    }

    @Test
    @DisplayName("the sweep is idempotent — running twice raises one proposal")
    void repeatedSweepsDoNotDuplicate() {
        String purpose = "MKT_OUTBOUND_CALL";
        String subject = grantLongAgo(purpose, Instant.now().minus(700, ChronoUnit.DAYS));
        activityWithRetention(purpose, 365, "DenCRM");

        sweeper.run(Instant.now());
        sweeper.run(Instant.now());
        sweeper.run(Instant.now());

        // A six-hourly sweep that re-proposed everything would emit the same erasure instruction
        // to DenCRM four times a day, and an integration that noisy gets muted.
        assertThat(retention.open(ENTITY, 500))
                .filteredOn(action -> action.subjectId().equals(subject))
                .hasSize(1);
    }

    @Test
    @DisplayName("the Rule 8 notice is emitted to the outbox, once, and the action advances")
    void noticeIsEmittedOnceAndRecorded() {
        String purpose = "MKT_OUTBOUND_CALL";
        String subject = grantLongAgo(purpose, Instant.now().minus(800, ChronoUnit.DAYS));
        activityWithRetention(purpose, 365, "DenCRM");

        sweeper.run(Instant.now());

        RetentionStore.Action action = retention.open(ENTITY, 500).stream()
                .filter(a -> a.subjectId().equals(subject))
                .findFirst().orElseThrow();

        // The event is the instruction to tell the principal. Delivery belongs to the group's
        // existing messaging — the platform's job ends at saying what is owed and to whom.
        assertThat(action.status()).isEqualTo("NOTICE_SENT");
        assertThat(action.notifiedAt()).isNotNull();

        // Counted for *this subject's* notice rather than against the global pending total, and
        // counted whether or not it has been published yet. The earlier version compared
        // outbox.pendingCount() before and after, which is a mechanism rather than the property:
        // OutboxRelay drains on a two-second schedule in this profile, so on a loaded machine the
        // global count falls between the two reads and the assertion fails while the platform is
        // behaving perfectly. Observed once, at 9 → 1, in a run with other work on the box.
        assertThat(retentionNoticesFor(subject))
                .withFailMessage("no Rule 8 notice reached the outbox for this subject")
                .isEqualTo(1);

        // Second pass emits nothing further for the same action. Still exactly one — which is the
        // assertion that matters, because a six-hourly sweep re-emitting the same erasure
        // instruction four times a day is an integration DenCRM will mute.
        sweeper.run(Instant.now());
        assertThat(retentionNoticesFor(subject)).isEqualTo(1);
    }

    /**
     * Rule 8 notices enqueued for one subject, published or not.
     *
     * <p>Read from the table rather than through {@code OutboxStore}, which exposes only a global
     * pending count and the relay's own drain query — neither of which can answer "was the notice
     * for *this person* emitted", and the global one races the relay.
     */
    private long retentionNoticesFor(String subject) {
        return jdbc.queryForObject(
                "select count(*) from event_outbox where topic = 'uds.consent.retention' "
                        + "and event_key = ?", Long.class, ENTITY + ":" + subject);
    }

    @Test
    @DisplayName("an unconfirmed erasure past its date is reported overdue")
    void unconfirmedErasuresAreTheComplianceGap() {
        String purpose = "MKT_OUTBOUND_CALL";
        String subject = grantLongAgo(purpose, Instant.now().minus(800, ChronoUnit.DAYS));
        activityWithRetention(purpose, 365, "DenCRM");

        RetentionSweeper.Report report = sweeper.run(Instant.now());

        // The gap between "due" and "done" is the compliance position, and it is the number the
        // group does not currently have. A proposal nobody actions is not a neutral state.
        assertThat(report.overdue()).isPositive();
        assertThat(report.clean()).isFalse();

        RetentionStore.Action action = retention.open(ENTITY, 500).stream()
                .filter(a -> a.subjectId().equals(subject))
                .findFirst().orElseThrow();
        assertThat(report.overdueActionIds()).contains(action.id());
    }

    @Test
    @DisplayName("a confirmed erasure clears, and so does a documented retention")
    void completionClearsTheAction() {
        String purpose = "MKT_OUTBOUND_CALL";
        String erased = grantLongAgo(purpose, Instant.now().minus(900, ChronoUnit.DAYS));
        String retained = grantLongAgo(purpose, Instant.now().minus(901, ChronoUnit.DAYS));
        activityWithRetention(purpose, 365, "DenCRM");
        sweeper.run(Instant.now());

        long erasedId = actionFor(erased).id();
        long retainedId = actionFor(retained).id();

        assertThat(retention.complete(erasedId, "ERASED", "dencrm-batch", "deleted in DenCRM",
                Instant.now())).isEqualTo(1);
        // RETAINED is a legitimate outcome — a legal hold or a live contract is a basis the
        // expired one no longer supplies. What it must not be is indistinguishable from a
        // proposal nobody read, which is why it carries a note.
        assertThat(retention.complete(retainedId, "RETAINED", "legal", "litigation hold LH-2027-4",
                Instant.now())).isEqualTo(1);

        assertThat(retention.open(ENTITY, 500))
                .noneMatch(action -> action.subjectId().equals(erased)
                        || action.subjectId().equals(retained));

        // Completing twice does nothing rather than rewriting the first outcome.
        assertThat(retention.complete(erasedId, "CANCELLED", "someone", "oops", Instant.now()))
                .isZero();
    }

    // -----------------------------------------------------------------------------------

    private RetentionStore.Action actionFor(String subjectId) {
        return retention.open(ENTITY, 500).stream()
                .filter(action -> action.subjectId().equals(subjectId))
                .findFirst().orElseThrow();
    }

    private long activityWithRetention(String purposeCode, int days, String system) {
        return activities.create(new ProcessingActivityStore.Activity(null, ENTITY,
                "Retention test " + UUID.randomUUID(), null, purposeCode, system,
                List.of("CONTACT_BUSINESS"), List.of(), List.of(), days,
                "Test retention rule", "owner", null));
    }

    private String grantLongAgo(String purposeCode, Instant when) {
        String subject = "ret-" + UUID.randomUUID();
        capture.capture(new CaptureSubmission(ENTITY, subject, Jurisdiction.IN, "en", Channel.WEB,
                APP, CaptureMethod.CHECKBOX_OPT_IN, ActorType.SUBJECT, subject, NOTICE, 1,
                List.of(CaptureSubmission.PurposeChoice.acceptedSeparately(purposeCode)),
                true, when, "ret-" + subject, null, Map.of()));
        return subject;
    }
}
