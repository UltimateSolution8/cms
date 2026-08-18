package com.uds.consent.policy.rights;

import com.uds.consent.core.model.Jurisdiction;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * How long the group has to report a personal data breach, and to whom.
 *
 * <p>Beside {@link StatutoryClock} and for the same reasons: a rule that varies by regime, changes
 * when the law changes, and must be testable without a database.
 *
 * <p><strong>Why this is not one deadline.</strong> DPDP Rule 7 is two-stage and the first stage
 * carries no hour figure at all. On becoming aware, the fiduciary must intimate the affected data
 * principals <em>and</em> the Board <strong>without delay</strong> — and then give the Board a
 * detailed report <strong>within seventy-two hours</strong>. Collapsing those into a single 72-hour
 * countdown, which is what a clock modelled on GDPR alone would do, produces a platform that says
 * "on schedule" for three days while the obligation that actually binds on day one goes
 * undischarged.
 *
 * <p>So "without delay" is modelled as an <em>immediate</em> obligation — one that is either
 * discharged or outstanding, never on time — rather than as a duration. There is no honest number
 * to put on it, and inventing one (24 hours, say) would be the platform quietly granting the group
 * a grace period no regulator has offered.
 *
 * <p>Other regimes are cleaner. GDPR Art.33 is 72 hours to the supervisory authority from becoming
 * aware, with notification to data subjects "without undue delay" where the risk is high. Malaysia's
 * PDPA (Amendment) 2024 is 72 hours to the Commissioner. Korea's PIPA is 72 hours to the affected
 * individuals and to the PIPC. An unmapped jurisdiction takes the shortest, for the same reason the
 * rights clock does: if the deadline is going to be wrong, it should be wrong early.
 *
 * <p><strong>Korea, and what "aware" means there.</strong> The PIPA amendment promulgated 10 March
 * 2026 and in force 11 September 2026 leaves the 72 hours alone and moves the thing the 72 hours
 * runs from: the obligation is triggered by a <em>reasonable likelihood</em> of a breach rather
 * than by a confirmed one, and the definition now reaches forgery, alteration and damage as well as
 * loss, theft and disclosure. This class cannot enforce that — {@code awareAt} is supplied by
 * whoever files the breach — which is exactly why it is written down here and in the runbook. The
 * failure mode is an incident team behaving reasonably by their own lights, waiting to confirm
 * before starting the clock, and being three days late by the time they do.
 *
 * <p>The same amendment raises the administrative ceiling to 10% of total turnover for the severe
 * tier and names the business owner or representative as the person ultimately responsible. Neither
 * changes a line of this class, and both change who reads its output.
 */
public final class BreachClock {

    /** The detailed-report window shared by DPDP Rule 7, GDPR Art.33, Malaysia and Korea. */
    private static final Duration SEVENTY_TWO_HOURS = Duration.ofHours(72);

    /** Singapore PDPA: assess within 30 days, notify within 3 calendar days if notifiable. */
    private static final Duration SG_NOTIFY = Duration.ofDays(3);

    private static final Map<Jurisdiction, Duration> DETAILED_REPORT = Map.of(
            Jurisdiction.IN, SEVENTY_TWO_HOURS,
            Jurisdiction.EU, SEVENTY_TWO_HOURS,
            Jurisdiction.UK, SEVENTY_TWO_HOURS,
            Jurisdiction.MY, SEVENTY_TWO_HOURS,
            Jurisdiction.KR, SEVENTY_TWO_HOURS,
            Jurisdiction.SG, SG_NOTIFY);

    private BreachClock() {
    }

    /**
     * The obligations arising from a breach the group became aware of at {@code awareAt}.
     *
     * <p>Keyed on awareness rather than on occurrence, which is what every one of these regimes
     * says and what makes the clock defensible: a breach discovered in a log review three weeks
     * later starts its countdown at the review, not at the intrusion. The occurrence instant still
     * matters enormously — it is what the affected population is computed as at — but it is not
     * what the deadline runs from.
     */
    public static List<Obligation> obligationsFor(Jurisdiction jurisdiction, Instant awareAt) {
        Duration report = DETAILED_REPORT.getOrDefault(jurisdiction, SEVENTY_TWO_HOURS);

        return switch (jurisdiction) {
            case IN -> List.of(
                    Obligation.immediate(Party.DATA_PRINCIPALS,
                            "DPDP Rules 2025, Rule 7(1) — intimate each affected data principal "
                                    + "without delay, in a concise and plain manner, describing "
                                    + "the breach, its likely consequences, the measures taken and "
                                    + "the safety measures the principal may take", awareAt),
                    Obligation.immediate(Party.REGULATOR,
                            "DPDP Rules 2025, Rule 7(2)(a) — intimate the Data Protection Board "
                                    + "without delay with a description of the breach, its nature, "
                                    + "extent and timing", awareAt),
                    Obligation.within(Party.REGULATOR, report,
                            "DPDP Rules 2025, Rule 7(2)(b) — detailed report to the Board within "
                                    + "72 hours: the events leading to the breach, the remedial "
                                    + "measures, the findings on who caused it, and a summary of "
                                    + "the intimations given to affected data principals", awareAt));

            case EU, UK -> List.of(
                    Obligation.within(Party.REGULATOR, report,
                            "GDPR Art.33(1) — notify the supervisory authority within 72 hours of "
                                    + "becoming aware, unless the breach is unlikely to result in "
                                    + "a risk to rights and freedoms", awareAt),
                    Obligation.immediate(Party.DATA_PRINCIPALS,
                            "GDPR Art.34(1) — where the breach is likely to result in a high risk, "
                                    + "communicate it to the data subjects without undue delay",
                            awareAt));

            case MY -> List.of(Obligation.within(Party.REGULATOR, report,
                    "Malaysia PDPA (Amendment) 2024 s.12B — notify the Commissioner within 72 "
                            + "hours; notify affected data subjects where the breach causes or is "
                            + "likely to cause significant harm", awareAt));

            // Re-derived against the amendment promulgated 10 March 2026, in force 11 September
            // 2026. Three things moved and only one of them is a number.
            //
            // The 72 hours did not change — that was the 2023 amendment's doing, which replaced the
            // old split of 24 hours for online providers and five days for everyone else. What
            // changed is when the 72 hours starts and what counts as a breach at all.
            //
            // The trigger is now a reasonable likelihood of a breach rather than a confirmed one,
            // so the clock starts before the incident is understood — which is the opposite of how
            // an incident response instinctively runs, and is the change most likely to be missed.
            // The definition also now reaches forgery, alteration and damage, so a ransomware
            // event that encrypts without exfiltrating is reportable where previously it argued
            // it was not.
            //
            // Notification to the affected individuals is a 72-hour obligation with a justifiable-
            // reason exception, not an open-ended "without delay". Modelled as a deadline
            // accordingly: the platform previously carried it as immediate, which was the safe
            // direction to be wrong in but told an operator nothing about when they had actually
            // failed.
            case KR -> List.of(
                    Obligation.within(Party.REGULATOR, report,
                            "Korea PIPA Art.34 (as amended 10 March 2026, in force 11 September "
                                    + "2026) — report to the PIPC or KISA within 72 hours where "
                                    + "the breach touches 1,000 or more data subjects, sensitive "
                                    + "or unique identifying information, or results from unlawful "
                                    + "external access. The clock runs from a reasonable "
                                    + "likelihood of a breach, not from confirmation", awareAt),
                    Obligation.within(Party.DATA_PRINCIPALS, report,
                            "Korea PIPA Art.34(1) (as amended 10 March 2026) — notify the affected "
                                    + "data subjects "
                                    + "within 72 hours unless there is a justifiable reason for "
                                    + "delay", awareAt));

            case SG -> List.of(Obligation.within(Party.REGULATOR, report,
                    "Singapore PDPA s.26D — notify the Commission within 3 calendar days of "
                            + "assessing the breach to be notifiable", awareAt));

            // Every US state has a breach-notification statute and they are all built the same
            // way: notify affected residents without unreasonable delay, with the attorney
            // general above a threshold. The thresholds and the AG duty differ per state and are
            // deliberately not modelled — a per-state count would be a table of numbers nobody
            // maintains, and getting one wrong reads as precision the platform does not have.
            // What holds everywhere is the duty to the individuals, so that is what is stated.
            case US_CA, US_CO, US_CT, US_TX, US_OR, US_MT, US_DE, US_NJ, US_NE, US_NH, US_MN,
                 US_MD, US_VA, US_UT, US_IA -> List.of(Obligation.immediate(Party.DATA_PRINCIPALS,
                    "US state breach-notification law (e.g. California Civ. Code s.1798.82) — "
                            + "disclose to affected residents in the most expedient time possible "
                            + "and without unreasonable delay. Confirm the state's attorney-general "
                            + "threshold before relying on this alone", awareAt));

            case OTHER -> List.of(Obligation.within(Party.REGULATOR, SEVENTY_TWO_HOURS,
                    "No regime configured for this jurisdiction; defaulted to the shortest "
                            + "reporting period the group operates under (72 hours). Confirm the "
                            + "local requirement before relying on this", awareAt));
        };
    }

    /** Who has to be told. */
    public enum Party {
        /** The supervisory authority — the Board, the ICO, the PIPC, the Commissioner. */
        REGULATOR,

        /** The affected individuals themselves. */
        DATA_PRINCIPALS,

        /**
         * A client whose data it was. Not a statutory party under any of these regimes — it is a
         * contractual duty under the processing agreements Denave and Matrix sign, and it is the
         * one most likely to be forgotten because no regulator enforces it.
         */
        CLIENT
    }

    /**
     * One notification obligation.
     *
     * @param dueAt     when it must be discharged by, or null when the obligation is "without
     *                  delay" — see {@link #immediate}
     * @param basis     the rule in words, stored on the row so the working survives the people who
     *                  did it
     */
    public record Obligation(Party party, Instant dueAt, boolean immediate, String basis) {

        /**
         * An obligation with no grace period.
         *
         * <p>{@code dueAt} is the awareness instant itself, and {@code immediate} is set so that
         * nothing downstream can mistake it for a deadline that has time left on it. An obligation
         * of this kind is never "on schedule"; it is discharged or it is outstanding.
         */
        public static Obligation immediate(Party party, String basis, Instant awareAt) {
            return new Obligation(party, awareAt, true, basis);
        }

        public static Obligation within(Party party, Duration period, String basis,
                                        Instant awareAt) {
            return new Obligation(party, awareAt.plus(period), false, basis);
        }

        /** Whether this obligation is late as at {@code asOf}, given it has not been discharged. */
        public boolean overdueAt(Instant asOf) {
            // An immediate obligation is overdue the moment it exists and remains undischarged.
            // That reads harshly on a dashboard and is the correct reading of "without delay":
            // there is no window in which not having told anyone is compliant.
            return immediate || asOf.isAfter(dueAt);
        }
    }
}
