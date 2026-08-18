package com.uds.consent.policy.rights;

import com.uds.consent.core.model.Jurisdiction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The breach clock, per regime.
 *
 * <p>The case worth reading first is {@link Dpdp#ruleSevenHasTwoStagesNotOne}. The temptation with
 * breach notification is to model one 72-hour countdown, because that is the number every summary
 * of GDPR quotes and it is the number Rule 7 also contains. Doing so produces a platform that
 * reports "on schedule" for three days while the obligation that actually binds on day one — the
 * intimation to affected principals and to the Board "without delay" — goes undischarged.
 */
class BreachClockTest {

    private static final Instant AWARE = Instant.parse("2027-06-01T09:00:00Z");

    @Nested
    @DisplayName("India — DPDP Rules 2025, Rule 7")
    class Dpdp {

        @Test
        @DisplayName("Rule 7 produces two stages and three obligations, not one deadline")
        void ruleSevenHasTwoStagesNotOne() {
            List<BreachClock.Obligation> obligations =
                    BreachClock.obligationsFor(Jurisdiction.IN, AWARE);

            assertThat(obligations).hasSize(3);

            // Stage one: the principals and the Board, without delay. Both immediate, because the
            // Rule gives no hour figure for either and inventing one would be the platform
            // granting a grace period no regulator has offered.
            assertThat(obligations).filteredOn(BreachClock.Obligation::immediate)
                    .extracting(BreachClock.Obligation::party)
                    .containsExactlyInAnyOrder(BreachClock.Party.DATA_PRINCIPALS,
                            BreachClock.Party.REGULATOR);

            // Stage two: the detailed report, at 72 hours.
            assertThat(obligations).filteredOn(obligation -> !obligation.immediate())
                    .singleElement()
                    .satisfies(report -> {
                        assertThat(report.party()).isEqualTo(BreachClock.Party.REGULATOR);
                        assertThat(report.dueAt()).isEqualTo(AWARE.plus(Duration.ofHours(72)));
                        // The content requirement that forces the platform to be able to compute
                        // the affected population as at the breach instant.
                        assertThat(report.basis()).contains("summary of the intimations");
                    });
        }

        @Test
        @DisplayName("an immediate obligation is overdue from the moment it exists")
        void immediateObligationsAreNeverOnSchedule() {
            BreachClock.Obligation immediate = BreachClock.obligationsFor(Jurisdiction.IN, AWARE)
                    .stream().filter(BreachClock.Obligation::immediate).findFirst().orElseThrow();

            // Reads harshly on a dashboard and is the correct reading of "without delay". There is
            // no window during which having told nobody is compliant, so there is no instant at
            // which this should render as green.
            assertThat(immediate.overdueAt(AWARE)).isTrue();
            assertThat(immediate.overdueAt(AWARE.plusSeconds(1))).isTrue();
        }

        @Test
        @DisplayName("the 72-hour report is not overdue at 71 hours and is at 73")
        void detailedReportRunsForSeventyTwoHours() {
            BreachClock.Obligation report = BreachClock.obligationsFor(Jurisdiction.IN, AWARE)
                    .stream().filter(obligation -> !obligation.immediate()).findFirst()
                    .orElseThrow();

            assertThat(report.overdueAt(AWARE.plus(Duration.ofHours(71)))).isFalse();
            assertThat(report.overdueAt(AWARE.plus(Duration.ofHours(73)))).isTrue();
        }
    }

    @Nested
    @DisplayName("The other regimes the group operates under")
    class OtherRegimes {

        @Test
        @DisplayName("GDPR gives the authority 72 hours and the subjects no fixed period")
        void gdprSplitsTheSameWay() {
            for (Jurisdiction jurisdiction : List.of(Jurisdiction.EU, Jurisdiction.UK)) {
                List<BreachClock.Obligation> obligations =
                        BreachClock.obligationsFor(jurisdiction, AWARE);

                assertThat(obligations).filteredOn(obligation -> !obligation.immediate())
                        .singleElement()
                        .satisfies(report -> {
                            assertThat(report.party()).isEqualTo(BreachClock.Party.REGULATOR);
                            assertThat(report.dueAt())
                                    .isEqualTo(AWARE.plus(Duration.ofHours(72)));
                            assertThat(report.basis()).contains("Art.33");
                        });

                // Art.34's communication to data subjects is "without undue delay" and applies
                // only on high risk — modelled as immediate, so it cannot sit looking compliant.
                assertThat(obligations).filteredOn(BreachClock.Obligation::immediate)
                        .singleElement()
                        .satisfies(direct -> assertThat(direct.party())
                                .isEqualTo(BreachClock.Party.DATA_PRINCIPALS));
            }
        }

        @Test
        @DisplayName("Malaysia is 72 hours to the Commissioner, and only that")
        void malaysiaIsASingleObligation() {
            List<BreachClock.Obligation> obligations =
                    BreachClock.obligationsFor(Jurisdiction.MY, AWARE);

            assertThat(obligations).singleElement().satisfies(obligation -> {
                assertThat(obligation.party()).isEqualTo(BreachClock.Party.REGULATOR);
                assertThat(obligation.dueAt()).isEqualTo(AWARE.plus(Duration.ofHours(72)));
                assertThat(obligation.basis()).contains("Amendment) 2024");
            });
        }

        @Test
        @DisplayName("Korea notifies the individuals and the PIPC")
        void koreaNotifiesBoth() {
            assertThat(BreachClock.obligationsFor(Jurisdiction.KR, AWARE))
                    .extracting(BreachClock.Obligation::party)
                    .containsExactlyInAnyOrder(BreachClock.Party.REGULATOR,
                            BreachClock.Party.DATA_PRINCIPALS);
        }

        @Test
        @DisplayName("Korea's subject notification is a 72-hour deadline, not an open obligation")
        void koreaNotifiesSubjectsOnADeadline() {
            // Changed by the amendment in force 11 September 2026, and asserted rather than left
            // to the basis text because it is the kind of difference that decides whether an
            // operator sees a countdown or a permanent red flag. "Without delay" told them they
            // were late from the first minute and gave them no way to know when they actually
            // were.
            assertThat(BreachClock.obligationsFor(Jurisdiction.KR, AWARE))
                    .filteredOn(obligation ->
                            obligation.party() == BreachClock.Party.DATA_PRINCIPALS)
                    .singleElement()
                    .satisfies(obligation -> {
                        assertThat(obligation.dueAt())
                                .isEqualTo(AWARE.plus(Duration.ofHours(72)));
                        assertThat(obligation.basis()).contains("justifiable reason");
                    });
        }

        @Test
        @DisplayName("Korea's basis names the amendment the periods were derived from")
        void koreaCitesTheAmendment() {
            // A deliberate tripwire. When Korea moves again — and the March 2026 package will not
            // be the last of it — this fails and points at the one place the numbers live, rather
            // than leaving somebody to discover that a deadline was derived from a repealed text.
            assertThat(BreachClock.obligationsFor(Jurisdiction.KR, AWARE))
                    .allSatisfy(obligation -> assertThat(obligation.basis())
                            .contains("10 March 2026")
                            .doesNotContain("without delay"));

            assertThat(BreachClock.obligationsFor(Jurisdiction.KR, AWARE))
                    .filteredOn(obligation -> obligation.party() == BreachClock.Party.REGULATOR)
                    .singleElement()
                    .satisfies(obligation -> assertThat(obligation.basis())
                            // The change most likely to be missed by an incident team: the clock
                            // starts on a reasonable likelihood, not on confirmation.
                            .contains("reasonable likelihood")
                            .contains("1,000"));
        }

        @Test
        @DisplayName("Singapore's window is three days, not seventy-two hours")
        void singaporeIsThreeDays() {
            assertThat(BreachClock.obligationsFor(Jurisdiction.SG, AWARE))
                    .singleElement()
                    .satisfies(obligation -> assertThat(obligation.dueAt())
                            .isEqualTo(AWARE.plus(Duration.ofDays(3))));
        }

        @Test
        @DisplayName("an unmapped jurisdiction takes the shortest period, and says so")
        void unmappedFallsBackShort() {
            // Same principle as the rights clock: if the deadline is going to be wrong, it should
            // be wrong in the direction that produces an early report.
            assertThat(BreachClock.obligationsFor(Jurisdiction.OTHER, AWARE))
                    .singleElement()
                    .satisfies(obligation -> {
                        assertThat(obligation.dueAt())
                                .isEqualTo(AWARE.plus(Duration.ofHours(72)));
                        assertThat(obligation.basis()).contains("No regime configured");
                    });
        }

        @Test
        @DisplayName("every regime produces at least one obligation")
        void noJurisdictionIsSilent() {
            // A jurisdiction that returned nothing would produce a breach with no clock at all,
            // which is the failure mode this whole class exists to make impossible.
            for (Jurisdiction jurisdiction : Jurisdiction.values()) {
                assertThat(BreachClock.obligationsFor(jurisdiction, AWARE))
                        .withFailMessage("no breach obligation configured for %s", jurisdiction)
                        .isNotEmpty();
            }
        }
    }

    @Test
    @DisplayName("clocks run from awareness, not from occurrence")
    void everythingRunsFromAwareness() {
        // A breach found in a log review three weeks after the intrusion starts its countdown at
        // the review. Every one of these regimes says so, and modelling it from occurrence would
        // produce deadlines that were already blown the moment anybody discovered anything.
        Instant late = AWARE.plus(Duration.ofDays(21));

        assertThat(BreachClock.obligationsFor(Jurisdiction.IN, late))
                .filteredOn(obligation -> !obligation.immediate())
                .singleElement()
                .satisfies(report -> assertThat(report.dueAt())
                        .isEqualTo(late.plus(Duration.ofHours(72))));
    }
}
