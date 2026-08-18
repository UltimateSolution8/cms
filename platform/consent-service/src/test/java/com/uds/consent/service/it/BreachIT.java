package com.uds.consent.service.it;

import com.uds.consent.core.model.ActorType;
import com.uds.consent.core.model.CaptureMethod;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.ledger.store.BreachStore;
import com.uds.consent.policy.capture.CaptureSubmission;
import com.uds.consent.service.BreachService;
import com.uds.consent.service.ConsentCaptureService;
import com.uds.consent.service.sweeper.BreachSlaSweeper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Both legs of Rule 7, and the population as it stood.
 *
 * <p>Two properties are under test and the second is the reason this belongs in the consent
 * platform at all.
 *
 * <p>First: a breach carries more than one clock. Rule 7 obliges an intimation to the affected
 * principals and to the Board <em>without delay</em>, and then a detailed report to the Board
 * within seventy-two hours. A platform modelling one countdown reports "on schedule" for three
 * days while the day-one obligation goes undischarged.
 *
 * <p>Second: the affected population is computed <strong>as at the breach instant</strong>. A
 * subject who withdraws the day after — quite possibly because the notification prompted them —
 * was still affected, and a query against current state would silently drop them. That is the
 * difference between a Rule 7 report and an estimate, and the ledger is the only system in the
 * group that can tell them apart.
 */
class BreachIT extends PostgresIntegrationTest {

    private static final String ENTITY = "DENAVE_IN";
    private static final String APP = "DENAVE_WEB";
    private static final String NOTICE = "NOTICE_DENAVE_B2B";

    @Autowired
    private BreachService breaches;

    @Autowired
    private ConsentCaptureService capture;

    @Autowired
    private BreachSlaSweeper sweeper;

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("Rule 7 files three obligations: two without delay, one at 72 hours")
    void bothLegsOfRuleSevenAreFiled() {
        Instant aware = Instant.now().minus(1, ChronoUnit.HOURS);
        BreachService.Reported reported = report(aware, aware.minus(2, ChronoUnit.HOURS));

        assertThat(reported.obligations()).hasSize(3);

        assertThat(reported.obligations()).filteredOn(BreachStore.Notification::immediate)
                .hasSize(2)
                .extracting(BreachStore.Notification::party)
                .containsExactlyInAnyOrder("DATA_PRINCIPALS", "REGULATOR");

        assertThat(reported.obligations()).filteredOn(n -> !n.immediate())
                .singleElement()
                .satisfies(report -> {
                    assertThat(report.party()).isEqualTo("REGULATOR");
                    assertThat(report.dueAt()).isEqualTo(aware.plus(Duration.ofHours(72)));
                    assertThat(report.basis()).contains("summary of the intimations");
                });
    }

    @Test
    @DisplayName("the without-delay obligations are overdue immediately, and stay so until sent")
    void immediateObligationsPageFromTheStart() {
        Instant aware = Instant.now();
        BreachService.Reported reported = report(aware, aware.minus(1, ChronoUnit.HOURS));

        BreachSlaSweeper.Report sweep = sweeper.run(aware);

        // Not an off-by-one. There is no window in which having told nobody is compliant, so an
        // obligation of this kind must never render as on schedule — not even in its first minute.
        assertThat(sweep.overdue()).isGreaterThanOrEqualTo(2);
        assertThat(sweep.overdueObligations())
                .contains(reported.breachId() + ":DATA_PRINCIPALS",
                        reported.breachId() + ":REGULATOR");

        // The 72-hour report is pending rather than overdue, and both facts coexist on one breach.
        assertThat(sweep.pending()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("a discharged obligation stops paging")
    void dischargingAnObligationClearsIt() {
        Instant aware = Instant.now();
        BreachService.Reported reported = report(aware, aware.minus(1, ChronoUnit.HOURS));

        for (BreachStore.Notification obligation : reported.obligations()) {
            breaches.notify(reported.breachId(), obligation.id(), aware, "EMAIL",
                    "REF-" + obligation.id(), 12, "sent", "compliance-console");
        }

        assertThat(sweeper.run(aware.plus(Duration.ofHours(96))).overdueObligations())
                .noneMatch(entry -> entry.startsWith(reported.breachId()));
    }

    @Test
    @DisplayName("recording a notification against an obligation that does not exist is refused")
    void aNotificationMustHaveAnObligation() {
        Instant aware = Instant.now();
        BreachService.Reported reported = report(aware, aware.minus(1, ChronoUnit.HOURS));
        long real = reported.obligations().getFirst().id();

        breaches.notify(reported.breachId(), real, aware, "EMAIL", "REF", 1, null, "tester");

        // Twice would be a file recording two notifications where one happened. Silently
        // succeeding here would put a fiction in the evidence rather than an error in the log.
        assertThatThrownBy(() -> breaches.notify(reported.breachId(), real, aware, "EMAIL", "REF",
                1, null, "tester"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already been discharged");

        assertThatThrownBy(() -> breaches.notify(reported.breachId(), 999_999L, aware, "EMAIL",
                "REF", 1, null, "tester"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an outstanding obligation");
    }

    @Test
    @DisplayName("a breach cannot be closed while anybody is still owed a notification")
    void closureRequiresEveryObligationDischarged() {
        Instant aware = Instant.now();
        BreachService.Reported reported = report(aware, aware.minus(1, ChronoUnit.HOURS));

        // A closed file over an undischarged notification is the exact combination the ₹200 crore
        // ceiling attaches to: an incident the group believes is finished and a regulator that
        // has not been told.
        assertThatThrownBy(() -> breaches.close(reported.breachId(), "done", "compliance-console"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outstanding notification obligation");

        for (BreachStore.Notification obligation : reported.obligations()) {
            breaches.notify(reported.breachId(), obligation.id(), aware, "EMAIL", "REF", 1, null,
                    "compliance-console");
        }
        breaches.close(reported.breachId(), "all parties notified", "compliance-console");

        assertThat(breaches.find(reported.breachId()).status()).isEqualTo("CLOSED");
        assertThat(breaches.find(reported.breachId()).open()).isFalse();
    }

    @Test
    @DisplayName("a subject who withdrew AFTER the breach still counts as affected")
    void theAffectedPopulationIsAsAtTheBreachInstant() {
        // The assertion the whole item exists for. Consent granted before the breach, withdrawn
        // after it. Asking "who is granted" today gives the wrong answer to a question about
        // last Tuesday, and the wrong answer is a Rule 7 report that under-counts the people it
        // was supposed to summarise intimations for.
        String subject = "br-" + UUID.randomUUID();
        Instant granted = Instant.now().minus(10, ChronoUnit.DAYS);
        Instant breachAt = Instant.now().minus(5, ChronoUnit.DAYS);
        Instant withdrew = Instant.now().minus(1, ChronoUnit.DAYS);

        capture.capture(new CaptureSubmission(ENTITY, subject, Jurisdiction.IN, "en", Channel.WEB,
                APP, CaptureMethod.CHECKBOX_OPT_IN, ActorType.SUBJECT, subject, NOTICE, 1,
                List.of(CaptureSubmission.PurposeChoice.acceptedSeparately("MKT_OUTBOUND_CALL")),
                true, granted, "br-grant-" + subject, null, Map.of()));

        capture.withdraw(ENTITY, subject, List.of("MKT_OUTBOUND_CALL"), Channel.WEB, APP,
                ActorType.SUBJECT, subject, Jurisdiction.IN, withdrew, "br-wd-" + subject,
                "withdrew after being told about the breach");

        BreachService.Reported reported = breaches.report(ENTITY, Jurisdiction.IN, breachAt, null,
                breachAt.plus(1, ChronoUnit.HOURS), "Exported prospect list left on a share",
                List.of("CONTACT_BUSINESS"), List.of("MKT_OUTBOUND_CALL"), "compliance-console");

        List<BreachStore.AffectedSubject> affected =
                breaches.affectedPopulation(reported.breachId());

        assertThat(affected).extracting(BreachStore.AffectedSubject::subjectId).contains(subject);
        assertThat(affected).filteredOn(a -> a.subjectId().equals(subject))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.purposeCode()).isEqualTo("MKT_OUTBOUND_CALL");
                    assertThat(entry.lastEventType()).isEqualTo("GRANTED");
                });
    }

    @Test
    @DisplayName("a subject who granted only AFTER the breach does not count")
    void consentGivenAfterTheBreachIsNotAffected() {
        String subject = "br-" + UUID.randomUUID();
        Instant breachAt = Instant.now().minus(5, ChronoUnit.DAYS);

        capture.capture(new CaptureSubmission(ENTITY, subject, Jurisdiction.IN, "en", Channel.WEB,
                APP, CaptureMethod.CHECKBOX_OPT_IN, ActorType.SUBJECT, subject, NOTICE, 1,
                List.of(CaptureSubmission.PurposeChoice.acceptedSeparately("MKT_OUTBOUND_CALL")),
                true, breachAt.plus(1, ChronoUnit.DAYS), "br-late-" + subject, null, Map.of()));

        BreachService.Reported reported = breaches.report(ENTITY, Jurisdiction.IN, breachAt, null,
                breachAt.plus(1, ChronoUnit.HOURS), "Same incident, later joiner",
                List.of("CONTACT_BUSINESS"), List.of("MKT_OUTBOUND_CALL"), "compliance-console");

        // The mirror of the case above, and it matters commercially rather than only for
        // correctness: over-notifying tells people their data was exposed when it was not.
        assertThat(breaches.affectedPopulation(reported.breachId()))
                .extracting(BreachStore.AffectedSubject::subjectId)
                .doesNotContain(subject);
    }

    @Test
    @DisplayName("a breach cannot be discovered before it happened")
    void awarenessCannotPrecedeOccurrence() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> breaches.report(ENTITY, Jurisdiction.IN, now, null,
                now.minus(1, ChronoUnit.DAYS), "impossible", List.of(), List.of(), "tester"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be discovered before it happened");
    }

    @Test
    @DisplayName("an assessment of not-notifiable is recorded rather than silently applied")
    void notNotifiableIsAJudgementSomebodyMakes() {
        Instant aware = Instant.now();
        BreachService.Reported reported = report(aware, aware.minus(1, ChronoUnit.HOURS));

        breaches.assess(reported.breachId(), "LOW",
                "Encrypted volume, keys held separately; no risk to rights and freedoms", 0, false,
                "compliance-console");

        BreachStore.Breach breach = breaches.find(reported.breachId());
        assertThat(breach.status()).isEqualTo("NOT_NOTIFIABLE");
        assertThat(breach.severity()).isEqualTo("LOW");
        assertThat(breach.riskAssessment()).contains("Encrypted volume");

        // And it stops paging, because the sweeper skips breaches that are not notifiable.
        assertThat(sweeper.run(aware.plus(Duration.ofDays(7))).overdueObligations())
                .noneMatch(entry -> entry.startsWith(reported.breachId()));
    }

    @Test
    @DisplayName("breach handling is ADMIN only")
    void breachEndpointsAreAdminOnly() {
        Map<String, Object> body = Map.of("entityId", ENTITY, "jurisdiction", "IN",
                "occurredAt", Instant.now().minus(2, ChronoUnit.HOURS).toString(),
                "description", "Test breach over HTTP");

        assertThat(rest.postForEntity("/v1/admin/breaches", body, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.withBasicAuth("denave-web", "capture-secret")
                .postForEntity("/v1/admin/breaches", body, String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rest.withBasicAuth("compliance-console", "admin-secret")
                .postForEntity("/v1/admin/breaches", body, String.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    private BreachService.Reported report(Instant aware, Instant occurred) {
        return breaches.report(ENTITY, Jurisdiction.IN, occurred, occurred, aware,
                "Test breach " + UUID.randomUUID(), List.of("CONTACT_BUSINESS"), List.of(),
                "compliance-console");
    }

}
