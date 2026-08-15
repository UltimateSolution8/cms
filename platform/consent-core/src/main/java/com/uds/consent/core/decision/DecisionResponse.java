package com.uds.consent.core.decision;

import com.uds.consent.core.model.LegalBasis;

import java.time.Instant;
import java.util.List;

/**
 * The enforcement plane's answer, with enough context attached that a caller can log it and an
 * auditor can later reconstruct why it was given.
 *
 * @param outcome           allow or deny
 * @param reason            denial reason code, {@link DenialReason#NONE} when allowed
 * @param explanation       one line of human-readable detail for logs and the console
 * @param legalBasis        basis relied on, where processing is permitted
 * @param purposeCode       purpose evaluated
 * @param purposeVersion    exact purpose version evaluated against
 * @param policyVersion     version of the policy bundle that produced this decision
 * @param evaluatedAt       instant the decision was evaluated for
 * @param consentExpiresAt  when the underlying consent lapses, if it does
 * @param obligations       conditions the caller must honour, e.g. {@code include-opt-out-link}
 * @param evaluatedLocally  true when answered from a signed snapshot without a network call
 */
public record DecisionResponse(
        DecisionOutcome outcome,
        DenialReason reason,
        String explanation,
        LegalBasis legalBasis,
        String purposeCode,
        int purposeVersion,
        String policyVersion,
        Instant evaluatedAt,
        Instant consentExpiresAt,
        List<String> obligations,
        boolean evaluatedLocally) {

    public DecisionResponse {
        obligations = obligations == null ? List.of() : List.copyOf(obligations);
    }

    public boolean isAllowed() {
        return outcome == DecisionOutcome.ALLOW;
    }

    public static DecisionResponse allow(String purposeCode, int purposeVersion,
                                         LegalBasis legalBasis, String policyVersion,
                                         Instant evaluatedAt, Instant consentExpiresAt,
                                         List<String> obligations) {
        return new DecisionResponse(DecisionOutcome.ALLOW, DenialReason.NONE, "permitted",
                legalBasis, purposeCode, purposeVersion, policyVersion, evaluatedAt,
                consentExpiresAt, obligations, false);
    }

    public static DecisionResponse deny(String purposeCode, int purposeVersion, DenialReason reason,
                                        String explanation, String policyVersion,
                                        Instant evaluatedAt) {
        return new DecisionResponse(DecisionOutcome.DENY, reason, explanation, null, purposeCode,
                purposeVersion, policyVersion, evaluatedAt, null, List.of(), false);
    }

    /** Copy of this response flagged as having been answered from a local signed snapshot. */
    public DecisionResponse asLocal() {
        return new DecisionResponse(outcome, reason, explanation, legalBasis, purposeCode,
                purposeVersion, policyVersion, evaluatedAt, consentExpiresAt, obligations, true);
    }
}
