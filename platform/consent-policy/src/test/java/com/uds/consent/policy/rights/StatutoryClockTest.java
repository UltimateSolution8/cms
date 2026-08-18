package com.uds.consent.policy.rights;

import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.RightsRequestType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rights clock, directly.
 *
 * <p>It has been exercised only through {@code RightsRequestIT} until now, which proves the
 * deadline is stored and not what it is. That is the wrong way round for this class: the numbers
 * are the whole of it, and each one is a statute somebody can point at — so when a period changes,
 * the test that encodes it should be findable by the jurisdiction's name.
 */
class StatutoryClockTest {

    private static final Instant RECEIVED = Instant.parse("2027-06-01T09:00:00Z");

    @Test
    @DisplayName("a withdrawal is same-day, not on the access-request clock")
    void withdrawalIsNotAMonthLongTask() {
        // The most important case in the class. DPDP requires withdrawal to be as easy as giving
        // consent, and the platform honours one the moment the event is appended — so a
        // withdrawal arriving as a written request must not sit in a queue for a month while the
        // dialer keeps calling. That is the precise failure the enforcement plane exists to
        // prevent, and putting it on the thirty-day clock would reintroduce it through the back
        // door.
        StatutoryClock.Deadline deadline = StatutoryClock.dueAt(
                RightsRequestType.CONSENT_WITHDRAWAL, Jurisdiction.IN, RECEIVED);

        assertThat(deadline.dueAt()).isEqualTo(RECEIVED.plus(Duration.ofDays(1)));
        assertThat(deadline.basis()).contains("as easily and as promptly as consent was given");
    }

    @Test
    @DisplayName("a withdrawal is same-day in every jurisdiction, not only India")
    void withdrawalOutranksEveryLocalPeriod() {
        // Korea's ten days is the shortest period the group operates under, and a withdrawal is
        // still faster. If this ever inverted, the tightest regime would be the one where a
        // withdrawal was handled most slowly.
        for (Jurisdiction jurisdiction : Jurisdiction.values()) {
            assertThat(StatutoryClock.dueAt(RightsRequestType.CONSENT_WITHDRAWAL, jurisdiction,
                            RECEIVED).dueAt())
                    .withFailMessage("withdrawal in %s is not same-day", jurisdiction)
                    .isEqualTo(RECEIVED.plus(Duration.ofDays(1)));
        }
    }

    @Test
    @DisplayName("Korea is ten days — the tightest period the group operates under")
    void koreaIsTheTightest() {
        assertThat(StatutoryClock.dueAt(RightsRequestType.ACCESS, Jurisdiction.KR, RECEIVED)
                .dueAt()).isEqualTo(RECEIVED.plus(Duration.ofDays(10)));
    }

    @Test
    @DisplayName("GDPR is one month, Malaysia twenty-one days, California forty-five")
    void thePeriodsAreTheStatutoryOnes() {
        assertThat(StatutoryClock.dueAt(RightsRequestType.ACCESS, Jurisdiction.EU, RECEIVED)
                .dueAt()).isEqualTo(RECEIVED.plus(Duration.ofDays(30)));
        assertThat(StatutoryClock.dueAt(RightsRequestType.ACCESS, Jurisdiction.UK, RECEIVED)
                .dueAt()).isEqualTo(RECEIVED.plus(Duration.ofDays(30)));
        assertThat(StatutoryClock.dueAt(RightsRequestType.ACCESS, Jurisdiction.MY, RECEIVED)
                .dueAt()).isEqualTo(RECEIVED.plus(Duration.ofDays(21)));
        assertThat(StatutoryClock.dueAt(RightsRequestType.ACCESS, Jurisdiction.US_CA, RECEIVED)
                .dueAt()).isEqualTo(RECEIVED.plus(Duration.ofDays(45)));
    }

    @Test
    @DisplayName("the US states that followed the CCPA share its period")
    void theStatesConvergedOnFortyFiveDays() {
        for (Jurisdiction jurisdiction : Jurisdiction.values()) {
            if (!jurisdiction.isUnitedStates()) {
                continue;
            }
            assertThat(StatutoryClock.dueAt(RightsRequestType.ACCESS, jurisdiction, RECEIVED)
                            .dueAt())
                    .withFailMessage("no period configured for %s", jurisdiction)
                    .isEqualTo(RECEIVED.plus(Duration.ofDays(45)));
        }
    }

    @Test
    @DisplayName("India's period is the group's undertaking and says so in the basis")
    void indiaIsAnUndertakingRatherThanAStatute() {
        // The one set of numbers here the law does not fix outright. Rule 14(3) caps the published
        // grievance period at ninety days but does not set the figure, so thirty days is what the
        // group promises in its published notice — and a deadline the platform believes in while
        // the notice says otherwise would make the group's own records the evidence against it.
        StatutoryClock.Deadline deadline =
                StatutoryClock.dueAt(RightsRequestType.ACCESS, Jurisdiction.IN, RECEIVED);

        assertThat(deadline.dueAt()).isEqualTo(RECEIVED.plus(Duration.ofDays(30)));
        assertThat(deadline.basis()).contains("group undertaking")
                .contains("published notice");
    }

    @Test
    @DisplayName("an unmapped jurisdiction gets the shortest period, and is told so")
    void theFallbackIsStrictRatherThanComfortable() {
        // If the deadline is going to be wrong it should be wrong in the direction that produces
        // an early answer. A comfortable default would produce a platform that quietly misses a
        // period nobody had configured.
        StatutoryClock.Deadline deadline =
                StatutoryClock.dueAt(RightsRequestType.ACCESS, Jurisdiction.OTHER, RECEIVED);

        assertThat(deadline.dueAt()).isEqualTo(RECEIVED.plus(Duration.ofDays(10)));
        assertThat(deadline.basis()).contains("shortest period");
    }

    @Test
    @DisplayName("every jurisdiction and every request type produces a deadline")
    void nothingFallsThrough() {
        // A combination returning null or throwing would be a rights request with no clock at
        // all — the one outcome this class exists to make impossible, and the one that would go
        // unnoticed because the request would look perfectly normal until it was late.
        for (Jurisdiction jurisdiction : Jurisdiction.values()) {
            for (RightsRequestType type : RightsRequestType.values()) {
                StatutoryClock.Deadline deadline =
                        StatutoryClock.dueAt(type, jurisdiction, RECEIVED);

                assertThat(deadline.dueAt())
                        .withFailMessage("no deadline for %s in %s", type, jurisdiction)
                        .isAfter(RECEIVED);
                assertThat(deadline.basis())
                        .withFailMessage("no basis recorded for %s in %s", type, jurisdiction)
                        .isNotBlank();
            }
        }
    }

    @Test
    @DisplayName("an Indian grievance has its own period and its own basis")
    void grievancesAreTrackedSeparately() {
        StatutoryClock.Deadline deadline =
                StatutoryClock.dueAt(RightsRequestType.GRIEVANCE, Jurisdiction.IN, RECEIVED);

        assertThat(deadline.dueAt()).isEqualTo(RECEIVED.plus(Duration.ofDays(30)));
        assertThat(deadline.basis()).contains("grievance");
    }

    @Test
    @DisplayName("India's grievance period stays inside Rule 14(3)'s ninety-day ceiling")
    void theIndianGrievancePeriodIsWithinTheStatutoryCeiling() {
        // Rule 14(3): the fiduciary must publish the period within which it responds under its
        // grievance redressal system, "within a reasonable period not exceeding ninety days".
        //
        // The figure is the group's to choose and the bound is not, which is exactly the shape of
        // constant that gets widened one day by somebody reducing an operational backlog. This
        // asserts the bound so that widening past it fails a build rather than a filing — and it
        // asserts the basis names the ceiling, because a deadline whose stated working omits the
        // legal boundary teaches the next reader that there isn't one. Which is the belief this
        // class held in its own header until today.
        StatutoryClock.Deadline deadline =
                StatutoryClock.dueAt(RightsRequestType.GRIEVANCE, Jurisdiction.IN, RECEIVED);

        assertThat(Duration.between(RECEIVED, deadline.dueAt()))
                .isLessThanOrEqualTo(StatutoryClock.IN_STATUTORY_CEILING);
        assertThat(StatutoryClock.IN_STATUTORY_CEILING).isEqualTo(Duration.ofDays(90));
        assertThat(deadline.basis()).contains("14(3)").contains("90");
    }
}
