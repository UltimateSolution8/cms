package com.uds.consent.policy.jurisdiction;

import com.uds.consent.core.decision.DecisionRequest;
import com.uds.consent.core.decision.DecisionResponse;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.LegalBasis;
import com.uds.consent.core.model.PurposeDefinition;
import com.uds.consent.policy.capture.CaptureSubmission;
import com.uds.consent.policy.capture.CaptureViolation;
import com.uds.consent.policy.port.PolicyPorts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * South Korea — Personal Information Protection Act.
 *
 * <p>The strictest regime the group operates under, and the one that imposes a hard architectural
 * constraint rather than merely another set of obligations. PIPA requires consent to be
 * <em>separate and itemised</em>: once per purpose, again for sensitive data, and again for
 * automated decision-making. A single "I agree to the above" covering several purposes is not
 * weak consent under PIPA — it is invalid.
 *
 * <p>That is why {@code CaptureSubmission} carries a list of per-purpose choices with a
 * {@code separateAction} flag rather than a set of booleans. A schema that could only express "the
 * subject agreed to marketing and analytics" would make Korean compliance unreachable without
 * rebuilding the capture path, and Denave has a Korean entity.
 */
public class PipaModule implements JurisdictionModule {

    private final PolicyPorts.ReconfirmationStatus reconfirmation;

    public PipaModule() {
        this(PolicyPorts.ReconfirmationStatus.none());
    }

    /**
     * @param reconfirmation the Art. 62-3 queue. Injected rather than looked up so that the golden
     *                       decision suite can exercise the Korean rules with no database at all
     */
    public PipaModule(PolicyPorts.ReconfirmationStatus reconfirmation) {
        this.reconfirmation = reconfirmation;
    }

    @Override
    public Jurisdiction jurisdiction() {
        return Jurisdiction.KR;
    }

    @Override
    public DecisionResponse refine(DecisionRequest request, PurposeDefinition purpose,
                                   LegalBasis basis, DecisionResponse decision) {
        if (!decision.isAllowed() || basis != LegalBasis.CONSENT) {
            return decision;
        }

        List<String> obligations = new ArrayList<>(List.of(
                "consent-must-be-itemised-per-purpose",
                "provide-korean-language-notice"));

        // Enforcement Decree of the Information and Communications Network Act, Art. 62-3: consent
        // to receive advertising information must be re-confirmed every two years from the date it
        // was given, disclosing the sender's name, the fact and date of consent, and how to
        // maintain or withdraw it.
        //
        // ALLOW is deliberate and is the judgement most likely to be "corrected" by someone who
        // reads the obligation and assumes an overdue consent must be a dead one. It is not. The
        // Decree prescribes the interval and the disclosure and is silent on the effect of a
        // recipient who never answers; industry practice treats silence as maintaining consent,
        // and practice is not text. Denying here would enforce a rule nobody can cite, would
        // suppress contact that is lawful on any published reading, and would do it on this
        // platform's own authority. Surfacing it as an obligation puts the position in front of
        // the caller and leaves the decision where it belongs. See REGULATORY_HANDOFF.md, which
        // carries the counsel question this defers to.
        if (reconfirmation.isOverdue(request.entityId(), request.subjectId(), purpose.code(),
                request.at())) {
            obligations.add("reconfirmation-overdue");
        }

        return JurisdictionModule.withObligations(decision, obligations.toArray(String[]::new));
    }

    @Override
    public List<CaptureViolation> validateCapture(CaptureSubmission submission,
                                                  List<PurposeDefinition> purposes) {
        List<CaptureViolation> violations = new ArrayList<>();
        Map<String, PurposeDefinition> byCode = DpdpModule.index(purposes);

        List<CaptureSubmission.PurposeChoice> granted = submission.granted();

        // More than one purpose accepted by a single undifferentiated action is the exact pattern
        // PIPA forbids. Rejected at ingestion rather than recorded and regretted.
        List<CaptureSubmission.PurposeChoice> bundled = granted.stream()
                .filter(choice -> !choice.separateAction())
                .toList();

        if (granted.size() > 1 && !bundled.isEmpty()) {
            for (CaptureSubmission.PurposeChoice choice : bundled) {
                violations.add(CaptureViolation.of(choice.purposeCode(),
                        CaptureViolation.Code.BUNDLED_CONSENT,
                        "PIPA requires separate, itemised consent for each purpose; this one was "
                                + "swept up in a combined agreement"));
            }
        }

        // Sensitive data and automated decision-making need their own consent step even when
        // they are the only purpose in the submission.
        for (CaptureSubmission.PurposeChoice choice : granted) {
            PurposeDefinition purpose = byCode.get(choice.purposeCode());
            if (purpose != null && purpose.requiresSeparateConsent() && !choice.separateAction()) {
                violations.add(CaptureViolation.of(choice.purposeCode(),
                        CaptureViolation.Code.SEPARATE_CONSENT_REQUIRED,
                        "this purpose requires its own consent step"));
            }
        }

        return violations;
    }
}
