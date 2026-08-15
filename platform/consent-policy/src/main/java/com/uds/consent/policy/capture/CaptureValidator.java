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
    private final PolicyPorts.ApplicationRegistry applications;
    private final Map<Jurisdiction, List<JurisdictionModule>> modules;

    public CaptureValidator(PolicyPorts.PurposeCatalog purposes,
                            PolicyPorts.ApplicationRegistry applications,
                            List<JurisdictionModule> jurisdictionModules) {
        this.purposes = purposes;
        this.applications = applications;
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

        validateApplication(submission, violations);

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

    /**
     * Checks that the submission came from a surface the group knows about.
     *
     * <p>Three ways to fail, and the third is the one worth having:
     *
     * <ul>
     *   <li><strong>Unknown.</strong> Either an integration nobody reviewed, or an attempt to
     *       manufacture evidence with a stolen credential.</li>
     *   <li><strong>Inactive.</strong> A decommissioned surface still writing is a surface someone
     *       forgot to turn off, and its records carry a claim nobody is maintaining.</li>
     *   <li><strong>Registered to a different entity.</strong> The one a credential leak actually
     *       looks like: a valid application id used to write consent into another group company's
     *       ledger. Nothing else in the platform would notice, because every field in the
     *       submission is individually well-formed.</li>
     * </ul>
     *
     * <p>An <em>absent</em> application id is deliberately not a violation yet. Several surfaces
     * predate the registry and omit it, and rejecting them would drop real consent on the floor —
     * the worst possible failure for a control whose entire purpose is preserving evidence.
     * Tightening this to require an id is the next step, and it wants a survey of what every
     * surface currently sends before it is taken, not a flag flipped optimistically.
     */
    private void validateApplication(CaptureSubmission submission,
                                     List<CaptureViolation> violations) {
        String applicationId = submission.applicationId();
        if (applicationId == null || applicationId.isBlank()) {
            return;
        }

        Optional<PolicyPorts.RegisteredApplication> found = applications.find(applicationId);
        if (found.isEmpty()) {
            violations.add(CaptureViolation.submission(
                    CaptureViolation.Code.APPLICATION_NOT_REGISTERED,
                    "'" + applicationId + "' is not in the application registry. Register the "
                            + "surface before it submits consent, so that what it captured can "
                            + "later be traced to something the group owns."));
            return;
        }

        PolicyPorts.RegisteredApplication application = found.get();
        if (!application.active()) {
            violations.add(CaptureViolation.submission(
                    CaptureViolation.Code.APPLICATION_NOT_REGISTERED,
                    "application '" + applicationId + "' is registered but inactive; a "
                            + "decommissioned surface must not still be writing consent"));
            return;
        }

        if (submission.entityId() != null && !submission.entityId().equals(application.entityId())) {
            violations.add(CaptureViolation.submission(
                    CaptureViolation.Code.APPLICATION_ENTITY_MISMATCH,
                    "application '" + applicationId + "' belongs to " + application.entityId()
                            + " and cannot capture consent for " + submission.entityId()));
        }
    }

    /** Convenience for callers that only need a yes or no. */
    public boolean isValid(CaptureSubmission submission) {
        return validate(submission).isEmpty();
    }
}
