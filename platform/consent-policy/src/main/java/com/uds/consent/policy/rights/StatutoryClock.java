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
 * fixed by statute and are stated here as such. India's are not comparable: the DPDP Act leaves
 * the response period to be prescribed, and the group's own published grievance period is a
 * commitment it makes rather than a number the Act supplies. The Indian defaults are therefore
 * <em>the group's undertaking</em>, deliberately set tighter than any figure under discussion, and
 * they are the one set of values here that must be confirmed against the published notice and
 * signed off by legal before go-live. A deadline the platform believes in and the privacy notice
 * contradicts is worse than having no clock: it makes the group's own records the evidence against
 * it.
 *
 * <p>Everything is overridable per entity, because a client contract can bind an entity to a
 * shorter period than the statute — and where it does, the contractual period is the real one.
 */
public final class StatutoryClock {

    /**
     * India. Not statutory: what the group undertakes in its published notice.
     *
     * <p>Set well inside any period likely to be prescribed. Being early is free; being late is a
     * complaint to the Board.
     */
    private static final Duration IN_DEFAULT = Duration.ofDays(30);

    /** India, grievances. The one a principal escalates when it goes unanswered. */
    private static final Duration IN_GRIEVANCE = Duration.ofDays(30);

    /** GDPR Art. 12(3): "without undue delay and in any event within one month". */
    private static final Duration GDPR = Duration.ofDays(30);

    /** CCPA/CPRA: 45 days, extendable once by a further 45 on notice to the consumer. */
    private static final Duration CPRA = Duration.ofDays(45);

    /** Singapore PDPA: 30 days, or the organisation must say when it will respond. */
    private static final Duration SG = Duration.ofDays(30);

    /** Korea PIPA: 10 days. The tightest of the group's regimes by a wide margin. */
    private static final Duration KR = Duration.ofDays(10);

    /** Malaysia PDPA: 21 days for a data access or correction request. */
    private static final Duration MY = Duration.ofDays(21);

    private static final Map<Jurisdiction, Duration> BY_JURISDICTION = Map.of(
            Jurisdiction.IN, IN_DEFAULT,
            Jurisdiction.EU, GDPR,
            Jurisdiction.UK, GDPR,
            Jurisdiction.US_CA, CPRA,
            Jurisdiction.SG, SG,
            Jurisdiction.KR, KR,
            Jurisdiction.MY, MY);

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
                    "India, grievance redressal — group undertaking, " + IN_GRIEVANCE.toDays()
                            + " days. Confirm against the published notice before go-live");
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
            case IN -> "India, DPDP — group undertaking, confirm against the published notice";
            case EU -> "EU GDPR Art. 12(3) — one month";
            case UK -> "UK GDPR Art. 12(3) — one month";
            case US_CA -> "California CCPA/CPRA — 45 days, one 45-day extension available";
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
