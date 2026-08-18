package com.uds.consent.policy.rights;

import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.RightsRequestType;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * How long the group has to answer a rights request.
 *
 * <p>Lives in {@code consent-policy} beside the jurisdiction modules for the same reason they do:
 * it is a rule that varies by regime, it changes when the law changes, and it should be testable
 * without a database or a Spring context.
 *
 * <p><strong>On the numbers below.</strong> The GDPR, CPRA, PDPA, PIPA and Malaysian periods are
 * fixed by statute and are stated here as such. India's work differently, and an earlier version of
 * this comment described them wrongly — it said the DPDP Act leaves the period to be prescribed and
 * that India therefore supplies no boundary at all. There is a boundary.
 *
 * <p><strong>DPDP Rule 14(3)</strong> requires a Data Fiduciary to prominently publish the period
 * within which it responds under its grievance redressal system, and that period must be
 * "<em>within a reasonable period not exceeding ninety days</em>". So the Rules set a <em>ceiling</em>
 * rather than a figure: the number is the group's to choose, the outer bound is not. The 30 days
 * below sit comfortably inside it and are therefore lawful as well as tight. They are not widened
 * to ninety, because widening would be a decision to answer people more slowly and that is not a
 * decision code should make on its own.
 *
 * <p>The Indian values remain <em>the group's undertaking</em> as a number, and they are still the
 * one set of values here that must be confirmed against the published notice and signed off by
 * legal before go-live — now for a second reason: Rule 14(3) makes publishing the period an
 * obligation, so the notice and this class must agree or the group is publishing one commitment and
 * operating another. A deadline the platform believes in and the privacy notice contradicts is
 * worse than having no clock: it makes the group's own records the evidence against it.
 *
 * <p>Rule 14 also requires (14(1)) publication of the means of exercising rights and of the
 * particulars needed to identify the principal, with 14(5) defining an Identifier. The means are
 * modelled — {@code NoticeStore} carries the rights, grievance and withdrawal URIs and the receipt
 * reproduces them. Which identifiers UDS will demand is a policy decision about how hard it is to
 * exercise a right, and it sits with legal rather than here; it is recorded in the hand-off.
 *
 * <p>Everything is overridable per entity, because a client contract can bind an entity to a
 * shorter period than the statute — and where it does, the contractual period is the real one.
 */
public final class StatutoryClock {

    /**
     * India. The number is the group's undertaking in its published notice; the ceiling is not.
     *
     * <p>Set well inside DPDP Rule 14(3)'s ninety-day maximum. Being early is free; being late is a
     * complaint to the Board.
     */
    private static final Duration IN_DEFAULT = Duration.ofDays(30);

    /**
     * India, grievances. The one a principal escalates when it goes unanswered.
     *
     * <p>DPDP Rule 14(3) caps the published grievance period at ninety days. Thirty is a choice
     * inside that cap, and {@link #IN_STATUTORY_CEILING} is asserted against it so that raising this
     * value past the bound fails a test rather than a filing.
     */
    private static final Duration IN_GRIEVANCE = Duration.ofDays(30);

    /**
     * DPDP Rule 14(3): "a reasonable period not exceeding ninety days". The outer bound on the
     * grievance period a fiduciary may publish — not a target, and not what the group undertakes.
     */
    static final Duration IN_STATUTORY_CEILING = Duration.ofDays(90);

    /** GDPR Art. 12(3): "without undue delay and in any event within one month". */
    private static final Duration GDPR = Duration.ofDays(30);

    /** CCPA/CPRA: 45 days, extendable once by a further 45 on notice to the consumer. */
    private static final Duration CPRA = Duration.ofDays(45);

    /** Singapore PDPA: 30 days, or the organisation must say when it will respond. */
    private static final Duration SG = Duration.ofDays(30);

    /**
     * Korea PIPA: 10 days. The tightest of the group's regimes by a wide margin.
     *
     * <p>Re-checked against the PIPA amendment promulgated 10 March 2026 and in force 11 September
     * 2026, and unchanged by it. That package restructured breach notification, raised the
     * administrative ceiling to 10% of total turnover and named the business owner as ultimately
     * responsible; it did not move the response period for a data subject's request. Recorded here
     * because "we looked and it did not change" is a different fact from "nobody looked", and only
     * one of them survives the next person to ask.
     */
    private static final Duration KR = Duration.ofDays(10);

    /** Malaysia PDPA: 21 days for a data access or correction request. */
    private static final Duration MY = Duration.ofDays(21);

    private static final Map<Jurisdiction, Duration> BY_JURISDICTION = Map.ofEntries(
            Map.entry(Jurisdiction.IN, IN_DEFAULT),
            Map.entry(Jurisdiction.EU, GDPR),
            Map.entry(Jurisdiction.UK, GDPR),
            Map.entry(Jurisdiction.SG, SG),
            Map.entry(Jurisdiction.KR, KR),
            Map.entry(Jurisdiction.MY, MY),
            // The state laws that followed the CCPA converged on its 45-day period with one
            // extension. Listed individually rather than derived from the enum, because a rule
            // that reads "any jurisdiction whose name starts with US_" would silently absorb the
            // next state added and give it a period nobody checked.
            Map.entry(Jurisdiction.US_CA, CPRA),
            Map.entry(Jurisdiction.US_CO, CPRA),
            Map.entry(Jurisdiction.US_CT, CPRA),
            Map.entry(Jurisdiction.US_TX, CPRA),
            Map.entry(Jurisdiction.US_OR, CPRA),
            Map.entry(Jurisdiction.US_MT, CPRA),
            Map.entry(Jurisdiction.US_DE, CPRA),
            Map.entry(Jurisdiction.US_NJ, CPRA),
            Map.entry(Jurisdiction.US_NE, CPRA),
            Map.entry(Jurisdiction.US_NH, CPRA),
            Map.entry(Jurisdiction.US_MN, CPRA),
            Map.entry(Jurisdiction.US_MD, CPRA),
            Map.entry(Jurisdiction.US_VA, CPRA),
            Map.entry(Jurisdiction.US_UT, CPRA),
            Map.entry(Jurisdiction.US_IA, CPRA));

    private StatutoryClock() {
    }

    /**
     * When a request received now must be answered by.
     *
     * <p>Withdrawal of consent is the exception and is not on a multi-day clock at all. DPDP
     * requires withdrawal to be as easy as giving consent, and the platform honours a withdrawal
     * the moment the event is appended — so the deadline for one that arrives as a written request
     * is same-day. Treating it like an access request would let a withdrawal sit in a queue for a
     * month while the dialer kept calling, which is the precise failure the whole enforcement
     * plane exists to prevent.
     */
    public static Deadline dueAt(RightsRequestType type, Jurisdiction jurisdiction,
                                 Instant receivedAt) {
        if (type == RightsRequestType.CONSENT_WITHDRAWAL) {
            return new Deadline(receivedAt.plus(Duration.ofDays(1)),
                    "Withdrawal must take effect as easily and as promptly as consent was given; "
                            + "honoured on receipt, deadline set to one day to catch anything "
                            + "that has not been actioned");
        }

        if (jurisdiction == Jurisdiction.IN && type == RightsRequestType.GRIEVANCE) {
            return new Deadline(receivedAt.plus(IN_GRIEVANCE),
                    "India, grievance redressal — group undertaking of " + IN_GRIEVANCE.toDays()
                            + " days, inside DPDP Rule 14(3)'s ceiling of "
                            + IN_STATUTORY_CEILING.toDays() + ". Rule 14(3) also requires this "
                            + "period to be prominently published; confirm it against the "
                            + "published notice before go-live");
        }

        Duration period = BY_JURISDICTION.get(jurisdiction);
        if (period == null) {
            // An unmapped jurisdiction gets the shortest period the group operates under rather
            // than a comfortable default. If the clock is going to be wrong, it should be wrong
            // in the direction that produces an early answer.
            return new Deadline(receivedAt.plus(KR),
                    "No period configured for " + jurisdiction + "; defaulted to the shortest "
                            + "period the group operates under (" + KR.toDays() + " days)");
        }

        return new Deadline(receivedAt.plus(period),
                describe(jurisdiction) + " — " + period.toDays() + " days");
    }

    private static String describe(Jurisdiction jurisdiction) {
        return switch (jurisdiction) {
            case IN -> "India, DPDP — group undertaking inside Rule 14(3)'s ninety-day ceiling; "
                    + "confirm against the published notice";
            case EU -> "EU GDPR Art. 12(3) — one month";
            case UK -> "UK GDPR Art. 12(3) — one month";
            case US_CA -> "California CCPA/CPRA — 45 days, one 45-day extension available";
            // The state laws that followed the CCPA converged on 45 days with one extension, so
            // they share its period. Where a state is stricter this is early rather than wrong,
            // which is the direction to be wrong in.
            case US_CO, US_CT, US_TX, US_OR, US_MT, US_DE, US_NJ, US_NE, US_NH, US_MN, US_MD,
                 US_VA, US_UT, US_IA ->
                    "US state privacy law — 45 days, one 45-day extension typically available";
            case SG -> "Singapore PDPA — 30 days";
            case KR -> "Korea PIPA — 10 days";
            case MY -> "Malaysia PDPA — 21 days";
            case OTHER -> "No regime configured";
        };
    }

    /**
     * @param basis the rule in words, stored on the request so the working survives the people
     */
    public record Deadline(Instant dueAt, String basis) {
    }
}
