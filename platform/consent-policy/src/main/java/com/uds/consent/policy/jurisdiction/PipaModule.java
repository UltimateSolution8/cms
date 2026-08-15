package com.uds.consent.policy.jurisdiction;

import com.uds.consent.core.decision.DecisionRequest;
import com.uds.consent.core.decision.DecisionResponse;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.LegalBasis;
import com.uds.consent.core.model.PurposeDefinition;
import com.uds.consent.policy.capture.CaptureSubmission;
import com.uds.consent.policy.capture.CaptureViolation;

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
        return JurisdictionModule.withObligations(decision,
                "consent-must-be-itemised-per-purpose",
                "provide-korean-language-notice");
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
