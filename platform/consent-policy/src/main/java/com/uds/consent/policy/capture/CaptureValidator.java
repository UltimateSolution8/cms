package com.uds.consent.policy.capture;

import com.uds.consent.core.model.LegalBasis;
import com.uds.consent.core.model.PurposeDefinition;
import com.uds.consent.policy.jurisdiction.JurisdictionModule;
import com.uds.consent.policy.port.PolicyPorts;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.uds.consent.core.model.Jurisdiction;

/**
 * Checks a consent submission before anything reaches the ledger.
 *
 * <p>A consent record that was invalid the moment it was created is worse than no record at all.
 * It looks like evidence; every downstream system relies on it; and its invalidity surfaces only
 * when a complainant or a regulator goes looking, by which time the group has been acting on it
 * for months. Rejecting at the door means an integration fails during the pilot, loudly, in front
 * of the engineer who can fix it.
 *
 * <p>Universal checks live here; local ones live in the jurisdiction modules and are run too.
 */
public class CaptureValidator {

    private final PolicyPorts.PurposeCatalog purposes;
    private final Map<Jurisdiction, List<JurisdictionModule>> modules;

    public CaptureValidator(PolicyPorts.PurposeCatalog purposes,
                            List<JurisdictionModule> jurisdictionModules) {
        this.purposes = purposes;
        this.modules = new EnumMap<>(Jurisdiction.class);
        for (JurisdictionModule module : jurisdictionModules) {
            this.modules.computeIfAbsent(module.jurisdiction(), k -> new ArrayList<>()).add(module);
        }
    }

    /**
     * Validates a submission.
     *
     * @return every violation found, empty when the submission may be recorded. All violations
     *         are returned rather than only the first, so an integrator fixes their surface once
     *         instead of discovering the problems one deployment at a time.
     */
    public List<CaptureViolation> validate(CaptureSubmission submission) {
        List<CaptureViolation> violations = new ArrayList<>();
        List<PurposeDefinition> resolved = new ArrayList<>();

        for (CaptureSubmission.PurposeChoice choice : submission.choices()) {
            Optional<PurposeDefinition> found = purposes.find(choice.purposeCode());
            if (found.isEmpty()) {
                violations.add(CaptureViolation.of(choice.purposeCode(),
                        CaptureViolation.Code.UNKNOWN_PURPOSE,
                        "purpose is not in the registry; free-text purposes are never accepted"));
                continue;
            }

            PurposeDefinition purpose = found.get();
            resolved.add(purpose);

            if (!choice.granted()) {
                // A declined purpose still needs to be a real purpose, but nothing else about it
                // can be invalid — the subject said no.
                continue;
            }

            if (purpose.retired()) {
                violations.add(CaptureViolation.of(choice.purposeCode(),
                        CaptureViolation.Code.RETIRED_PURPOSE,
                        "purpose is retired; capture a current version instead"));
            }

            LegalBasis basis = purpose.legalBasisFor(submission.jurisdiction());
            if (basis == null) {
                violations.add(CaptureViolation.of(choice.purposeCode(),
                        CaptureViolation.Code.PURPOSE_NOT_PERMITTED_IN_JURISDICTION,
                        "no lawful basis configured for " + submission.jurisdiction()));
            } else if (!basis.requiresConsentRecord()) {
                // Recording consent for a purpose that does not rest on consent is not harmless.
                // It implies the subject can withdraw and stop the processing, which they cannot,
                // and it invites exactly the complaint that follows from that misunderstanding.
                violations.add(CaptureViolation.of(choice.purposeCode(),
                        CaptureViolation.Code.CONSENT_NOT_THE_BASIS,
                        "this purpose rests on " + basis + " in " + submission.jurisdiction()
                                + "; serve notice rather than asking for consent"));
            }

            // Note what is deliberately not checked here: the channel consent was captured over
            // against the channels the purpose may use. They are different axes. Consent to be
            // telephoned is normally given on a web form, and an agent may take consent for an
            // email programme over a recorded call. Requiring the two to match would reject the
            // ordinary case and push surfaces into misdeclaring how consent was obtained — which
            // would corrupt the evidence to satisfy a rule that protects nothing. The purpose's
            // channel list constrains the processing, and the decision engine enforces it there.

            // s.6 requires a clear affirmative action. A record imported from a purchased list or
            // inferred from a relationship is not one, and must not be written as a grant.
            if (basis == LegalBasis.CONSENT && !submission.captureMethod().isAffirmativeAction()) {
                violations.add(CaptureViolation.of(choice.purposeCode(),
                        CaptureViolation.Code.NOT_AN_AFFIRMATIVE_ACTION,
                        submission.captureMethod() + " is not a clear affirmative action; "
                                + "consent under s.6 cannot rest on it"));
            }

            if (purpose.requiresSeparateConsent() && !choice.separateAction()) {
                violations.add(CaptureViolation.of(choice.purposeCode(),
                        CaptureViolation.Code.SEPARATE_CONSENT_REQUIRED,
                        "this purpose must be agreed to on its own, not as part of a combined "
                                + "agreement"));
            }
        }

        for (JurisdictionModule module : modules.getOrDefault(submission.jurisdiction(), List.of())) {
            violations.addAll(module.validateCapture(submission, resolved));
        }

        return violations;
    }

    /** Convenience for callers that only need a yes or no. */
    public boolean isValid(CaptureSubmission submission) {
        return validate(submission).isEmpty();
    }
}
