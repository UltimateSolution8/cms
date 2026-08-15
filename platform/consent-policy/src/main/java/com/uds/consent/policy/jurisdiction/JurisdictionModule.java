package com.uds.consent.policy.jurisdiction;

import com.uds.consent.core.decision.DecisionRequest;
import com.uds.consent.core.decision.DecisionResponse;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.LegalBasis;
import com.uds.consent.core.model.PurposeDefinition;
import com.uds.consent.policy.capture.CaptureSubmission;
import com.uds.consent.policy.capture.CaptureViolation;

import java.util.List;

/**
 * Jurisdiction-specific rules layered over a jurisdiction-agnostic core.
 *
 * <p>The alternative — a single decision function studded with {@code if (jurisdiction == KR)} —
 * collapses the moment two regimes disagree, and they do disagree: Korea requires consent to be
 * itemised per purpose, the UK permits legitimate interest for the same outreach, India requires
 * a registry scrub neither of them mentions. Each module owns its own rules, and the core stays
 * readable.
 *
 * <p>Modules may add obligations and may turn an ALLOW into a DENY. They may never turn a DENY
 * into an ALLOW: the core has already established there is no lawful basis or no valid consent,
 * and no local rule can manufacture one.
 */
public interface JurisdictionModule {

    /** The jurisdiction this module governs. */
    Jurisdiction jurisdiction();

    /**
     * Applies local rules to a decision the core has already made.
     *
     * @param request  the original question
     * @param purpose  the purpose version evaluated
     * @param basis    the lawful basis relied on in this jurisdiction
     * @param decision what the core concluded
     * @return the refined decision; never more permissive than {@code decision}
     */
    DecisionResponse refine(DecisionRequest request, PurposeDefinition purpose, LegalBasis basis,
                            DecisionResponse decision);

    /**
     * Local constraints on how consent may be captured, checked before anything is written.
     *
     * <p>Returns the violations found, empty when the submission is acceptable. Rejecting at
     * capture matters more than it might appear: a consent record that was invalid when created
     * is worse than no record, because it looks like evidence and will be relied on.
     */
    default List<CaptureViolation> validateCapture(CaptureSubmission submission,
                                                   List<PurposeDefinition> purposes) {
        return List.of();
    }

    /** Convenience: adds an obligation to an allowed decision, leaving denials untouched. */
    static DecisionResponse withObligations(DecisionResponse decision, String... obligations) {
        if (!decision.isAllowed() || obligations.length == 0) {
            return decision;
        }
        List<String> combined = new java.util.ArrayList<>(decision.obligations());
        for (String obligation : obligations) {
            if (!combined.contains(obligation)) {
                combined.add(obligation);
            }
        }
        return new DecisionResponse(decision.outcome(), decision.reason(), decision.explanation(),
                decision.legalBasis(), decision.purposeCode(), decision.purposeVersion(),
                decision.policyVersion(), decision.evaluatedAt(), decision.consentExpiresAt(),
                combined, decision.evaluatedLocally());
    }
}
