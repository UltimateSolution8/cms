package com.uds.consent.policy.jurisdiction;

import com.uds.consent.core.decision.DecisionRequest;
import com.uds.consent.core.decision.DecisionResponse;
import com.uds.consent.core.model.CaptureMethod;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.LegalBasis;
import com.uds.consent.core.model.PurposeDefinition;
import com.uds.consent.policy.capture.CaptureSubmission;
import com.uds.consent.policy.capture.CaptureViolation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * India — Digital Personal Data Protection Act 2023 and the DPDP Rules 2025.
 *
 * <p>The regime that sets the programme's deadline: the substantive rules become enforceable on
 * 13 May 2027, with penalties assessed per violation up to ₹250 crore under the Act's Schedule,
 * and a power under s.33 to enhance that in serious cases.
 *
 * <p>Note what this module does <em>not</em> do. Rule 4, which commences on 13 November 2026,
 * governs registration as a Consent Manager — a regulated intermediary that holds data it cannot
 * itself read. UDS entities are Data Fiduciaries, not Consent Managers, so that date does not
 * bind them and nothing here is built to it.
 */
public class DpdpModule implements JurisdictionModule {

    private final Jurisdiction jurisdiction;

    public DpdpModule() {
        this(Jurisdiction.IN);
    }

    DpdpModule(Jurisdiction jurisdiction) {
        this.jurisdiction = jurisdiction;
    }

    @Override
    public Jurisdiction jurisdiction() {
        return jurisdiction;
    }

    @Override
    public DecisionResponse refine(DecisionRequest request, PurposeDefinition purpose,
                                   LegalBasis basis, DecisionResponse decision) {
        if (!decision.isAllowed()) {
            return decision;
        }

        List<String> obligations = new ArrayList<>();

        if (basis.requiresConsentRecord()) {
            // Rule 3 makes each of these a required element of the notice, and s.6(6) requires
            // withdrawal to be as easy as giving. Surfaced as obligations so that a caller
            // rendering its own UI cannot omit them and still claim the decision permitted it.
            obligations.add("provide-withdrawal-link");
            obligations.add("provide-grievance-link");
        }

        if (basis == LegalBasis.LEGITIMATE_USE_EMPLOYMENT) {
            // s.7(i) removes the need for consent, not the need for transparency. This is the
            // obligation that keeps roughly 76,000 workforce records honest: no consent to
            // collect, but notice, retention discipline and working rights machinery still apply.
            obligations.add("serve-notice-no-consent-required");
            obligations.add("enforce-retention-schedule");
        }

        if (request.isChildSubject()) {
            obligations.add("no-behavioural-tracking-of-children");
            obligations.add("no-targeted-advertising-to-children");
        }

        return JurisdictionModule.withObligations(decision, obligations.toArray(String[]::new));
    }

    @Override
    public List<CaptureViolation> validateCapture(CaptureSubmission submission,
                                                  List<PurposeDefinition> purposes) {
        List<CaptureViolation> violations = new ArrayList<>();
        Map<String, PurposeDefinition> byCode = index(purposes);

        // Rule 8 prohibits dark patterns outright: pre-selected options, disguised refusal and
        // confirm-shaming. Pre-selection and unequal refusal are structural and can be checked
        // here; wording that shames a refusal cannot be, and stays a design-review gate before
        // any consent interface ships.
        for (CaptureSubmission.PurposeChoice choice : submission.choices()) {
            if (choice.preTicked()) {
                violations.add(CaptureViolation.of(choice.purposeCode(),
                        CaptureViolation.Code.PRE_SELECTED_OPTION,
                        "consent controls must arrive unselected; the subject has to act to agree"));
            }
        }

        if (!submission.granted().isEmpty() && !submission.rejectAllOffered()) {
            violations.add(CaptureViolation.submission(
                    CaptureViolation.Code.REFUSAL_NOT_EQUALLY_AVAILABLE,
                    "refusing must be offered in the same interaction and take no more effort "
                            + "than accepting"));
        }

        if (submission.languageTag() == null || submission.languageTag().isBlank()) {
            violations.add(CaptureViolation.submission(
                    CaptureViolation.Code.LANGUAGE_NOT_RECORDED,
                    "record the language the notice was rendered in; English or any of the "
                            + "twenty-two Eighth Schedule languages"));
        }

        if (submission.noticeVersion() == null) {
            violations.add(CaptureViolation.submission(
                    CaptureViolation.Code.NOTICE_VERSION_NOT_RECORDED,
                    "record the exact notice version rendered, so it can be reproduced later"));
        }

        // s.9: a subject under eighteen needs verifiable parental consent, and some purposes are
        // closed to them however consent was obtained.
        boolean isChild = "true".equalsIgnoreCase(submission.attributes().get("subject.isChild"));
        if (isChild) {
            if (submission.captureMethod() != CaptureMethod.PARENTAL_VERIFIED) {
                violations.add(CaptureViolation.submission(
                        CaptureViolation.Code.PARENTAL_CONSENT_REQUIRED,
                        "subject is under eighteen; consent must be verifiably given by a parent "
                                + "or lawful guardian"));
            }
            for (CaptureSubmission.PurposeChoice choice : submission.granted()) {
                PurposeDefinition purpose = byCode.get(choice.purposeCode());
                if (purpose != null && !purpose.permittedForChildren()) {
                    violations.add(CaptureViolation.of(choice.purposeCode(),
                            CaptureViolation.Code.CHILD_PURPOSE_NOT_PERMITTED,
                            "this purpose may not be applied to a subject under eighteen"));
                }
            }
        }

        return violations;
    }

    static Map<String, PurposeDefinition> index(List<PurposeDefinition> purposes) {
        Map<String, PurposeDefinition> byCode = new java.util.HashMap<>();
        for (PurposeDefinition purpose : purposes) {
            byCode.put(purpose.code(), purpose);
        }
        return byCode;
    }
}
