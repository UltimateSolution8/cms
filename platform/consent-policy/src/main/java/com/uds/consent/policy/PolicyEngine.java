package com.uds.consent.policy;

import com.uds.consent.core.decision.DecisionOutcome;
import com.uds.consent.core.decision.DecisionRequest;
import com.uds.consent.core.decision.DecisionResponse;
import com.uds.consent.core.decision.DenialReason;
import com.uds.consent.core.model.ConsentArtefact;
import com.uds.consent.core.model.ConsentStatus;
import com.uds.consent.core.model.FailureBehavior;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.LegalBasis;
import com.uds.consent.core.model.PurposeDefinition;
import com.uds.consent.policy.jurisdiction.JurisdictionModule;
import com.uds.consent.policy.port.PolicyPorts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The single decision point.
 *
 * <p>Every system that touches personal data asks this one question through this one contract:
 * may I do this, to this person, for this purpose, over this channel, right now. The alternative
 * is five teams each implementing "can I contact this person" slightly differently, which is how
 * a group ends up with five answers and no way to tell a regulator which one applied on a given
 * day.
 *
 * <p>Evaluation is a fixed sequence of gates, ordered so that the cheapest and most categorical
 * checks run first and so that a denial always names one specific reason. The order also encodes
 * a hierarchy: a statutory suppression outranks a consent record, and no lawful basis in the
 * jurisdiction outranks everything.
 */
public class PolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(PolicyEngine.class);

    private final PolicyPorts.PurposeCatalog purposes;
    private final PolicyPorts.ArtefactLookup artefacts;
    private final PolicyPorts.SuppressionLookup suppression;
    private final PolicyPorts.ProvenanceLookup provenance;
    private final PolicyPorts.SubjectAttributeLookup subjects;
    private final Map<Jurisdiction, List<JurisdictionModule>> modules;
    private final String policyVersion;

    public PolicyEngine(PolicyPorts.PurposeCatalog purposes,
                        PolicyPorts.ArtefactLookup artefacts,
                        PolicyPorts.SuppressionLookup suppression,
                        PolicyPorts.ProvenanceLookup provenance,
                        PolicyPorts.SubjectAttributeLookup subjects,
                        List<JurisdictionModule> jurisdictionModules,
                        String policyVersion) {
        this.purposes = purposes;
        this.artefacts = artefacts;
        this.suppression = suppression;
        this.provenance = provenance;
        this.subjects = subjects;
        this.policyVersion = policyVersion;
        this.modules = new EnumMap<>(Jurisdiction.class);
        // Several modules can govern one jurisdiction — India is subject to both DPDP and the
        // TRAI regulations, and they impose different things.
        for (JurisdictionModule module : jurisdictionModules) {
            this.modules.computeIfAbsent(module.jurisdiction(), k -> new ArrayList<>()).add(module);
        }
    }

    /** Answers a decision request. Never throws; an internal failure denies and is logged. */
    public DecisionResponse evaluate(DecisionRequest request) {
        try {
            return evaluateInternal(request);
        } catch (RuntimeException e) {
            // A policy engine that throws is a policy engine that gets wrapped in a try/catch
            // whose catch block allows the operation. Denying here keeps that decision ours.
            log.error("policy evaluation failed for entity={} purpose={}: {}",
                    request.entityId(), request.purposeCode(), e.toString(), e);
            return DecisionResponse.deny(request.purposeCode(), 0, DenialReason.POLICY_ERROR,
                    "policy evaluation failed", policyVersion, request.at());
        }
    }

    private DecisionResponse evaluateInternal(DecisionRequest request) {
        // Gate 1 — is this a purpose we know about at all?
        Optional<PurposeDefinition> found = purposes.find(request.purposeCode());
        if (found.isEmpty()) {
            return deny(request, 0, DenialReason.PURPOSE_UNKNOWN,
                    "purpose is not in the registry");
        }
        PurposeDefinition purpose = found.get();

        // Gate 2 — retired purposes can still be read and reported on, never newly relied upon.
        if (purpose.retired()) {
            return deny(request, purpose.version(), DenialReason.PURPOSE_RETIRED,
                    "purpose has been retired and may not support new processing");
        }

        // Gate 3 — a purpose with no basis in this jurisdiction is denied outright. It does not
        // fall back to consent: if legal has not established a basis, asking the subject does not
        // create one.
        LegalBasis basis = purpose.legalBasisFor(request.jurisdiction());
        if (basis == null) {
            return deny(request, purpose.version(),
                    DenialReason.PURPOSE_NOT_PERMITTED_IN_JURISDICTION,
                    "no lawful basis configured for " + request.jurisdiction());
        }

        // Gate 4 — channel.
        if (request.channel() != null && !purpose.permitsChannel(request.channel())) {
            return deny(request, purpose.version(), DenialReason.CHANNEL_NOT_PERMITTED,
                    "purpose does not permit channel " + request.channel());
        }

        // Gate 5 — children. DPDP s.9 closes some purposes to under-eighteens however consent
        // was obtained, so this precedes any look at the consent record.
        boolean isChild = request.isChildSubject() || subjects.isChild(request.subjectId());
        if (isChild && !purpose.permittedForChildren()) {
            return deny(request, purpose.version(), DenialReason.CHILD_SUBJECT_RESTRICTED,
                    "purpose is not permitted for a subject under eighteen");
        }

        // Gate 6 — suppression. A statutory registry entry outranks any consent record: someone
        // on the national preference register is not contactable on a promotional purpose even
        // with a valid consent row. Non-statutory opt-outs bind us just as firmly, but only
        // within their scope.
        if (shouldCheckSuppression(request, basis)) {
            Optional<PolicyPorts.Hit> hit = suppression.find(request.entityId(), request.subjectId(),
                    request.channel(), request.clientId(), request.campaignId(), request.at());
            if (hit.isPresent()) {
                PolicyPorts.Hit suppressed = hit.get();
                return deny(request, purpose.version(),
                        suppressed.statutory() ? DenialReason.SUPPRESSED_STATUTORY
                                : DenialReason.SUPPRESSED_OPT_OUT,
                        "suppressed by " + suppressed.source() + " at scope " + suppressed.scope());
            }
        }

        // Gate 7 — provenance. Applies to commercially-motivated processing, where the question
        // "where did this record come from" has a real answer that may be "we cannot say". It
        // does not apply to employment or legal-obligation processing, where the engagement
        // itself is the provenance.
        if (requiresProvenance(basis)
                && !provenance.isContactable(request.entityId(), request.subjectId())) {
            return deny(request, purpose.version(), DenialReason.NO_PROVENANCE,
                    "record is quarantined: its origin could not be substantiated");
        }

        // Gate 8 — bases that need no consent record are permitted from here.
        if (!basis.requiresConsentRecord()) {
            return applyModules(request, purpose, basis,
                    DecisionResponse.allow(purpose.code(), purpose.version(), basis, policyVersion,
                            request.at(), null, List.of()));
        }

        // Gate 9 — the consent record itself.
        Optional<ConsentArtefact> artefactOpt =
                artefacts.find(request.entityId(), request.subjectId(), request.purposeCode());

        if (artefactOpt.isEmpty()) {
            // No record at all is not the same as an indeterminate one. A purpose that fails open
            // is permitted; anything else denies for want of a record, which is the honest reason.
            if (purpose.failureBehavior() == FailureBehavior.FAIL_OPEN) {
                return applyModules(request, purpose, basis,
                        DecisionResponse.allow(purpose.code(), purpose.version(), basis,
                                policyVersion, request.at(), null, List.of("no-consent-on-record")));
            }
            return deny(request, purpose.version(), DenialReason.NO_CONSENT_RECORD,
                    "no consent interaction recorded for this subject and purpose");
        }

        ConsentArtefact artefact = artefactOpt.get();
        ConsentStatus status = artefact.effectiveStatus(request.at());

        if (status != ConsentStatus.GRANTED) {
            return deny(request, artefact.purposeVersion(), reasonFor(status),
                    "consent status is " + status);
        }

        DecisionResponse allowed = DecisionResponse.allow(purpose.code(), artefact.purposeVersion(),
                basis, policyVersion, request.at(), artefact.expiresAt(), List.of());
        return applyModules(request, purpose, basis, allowed);
    }

    /**
     * Whether suppression is relevant to this request.
     *
     * <p>Commercial communication channels always are. Beyond those, a basis that honours
     * objection — legitimate interest — is also defeated by an opt-out, even on a channel that
     * carries no registry, because the objection is what ends the basis.
     */
    private static boolean shouldCheckSuppression(DecisionRequest request, LegalBasis basis) {
        if (request.channel() == null) {
            return false;
        }
        return request.channel().isCommercialCommunication() || basis.honoursObjection();
    }

    private static boolean requiresProvenance(LegalBasis basis) {
        return basis == LegalBasis.CONSENT
                || basis == LegalBasis.INFERRED_CONSENT
                || basis == LegalBasis.LEGITIMATE_INTEREST;
    }

    /**
     * Runs every module governing the request's jurisdiction.
     *
     * <p>Modules may add obligations and may deny. The assertion below enforces the one rule they
     * may not break: a module cannot turn a denial into an allowance. By this point the core has
     * established there is no lawful basis or no valid consent, and no local rule manufactures
     * one — a module that tried would be a defect worth failing loudly for.
     */
    private DecisionResponse applyModules(DecisionRequest request, PurposeDefinition purpose,
                                          LegalBasis basis, DecisionResponse decision) {
        DecisionResponse current = decision;
        for (JurisdictionModule module : modules.getOrDefault(request.jurisdiction(), List.of())) {
            DecisionResponse refined = module.refine(request, purpose, basis, current);
            if (refined.outcome() == DecisionOutcome.ALLOW
                    && current.outcome() == DecisionOutcome.DENY) {
                throw new IllegalStateException(module.getClass().getSimpleName()
                        + " attempted to upgrade a denial to an allowance for purpose "
                        + purpose.code());
            }
            current = refined;
        }
        return current;
    }

    private static DenialReason reasonFor(ConsentStatus status) {
        return switch (status) {
            case WITHDRAWN -> DenialReason.CONSENT_WITHDRAWN;
            case EXPIRED -> DenialReason.CONSENT_EXPIRED;
            case DENIED -> DenialReason.CONSENT_DENIED;
            case INVALIDATED -> DenialReason.CONSENT_INVALIDATED;
            case CONFLICTED -> DenialReason.CONSENT_CONFLICTED;
            case PENDING_SYNC -> DenialReason.CONSENT_PENDING_SYNC;
            case NOT_ASKED -> DenialReason.NO_CONSENT_RECORD;
            case UNKNOWN -> DenialReason.FAIL_CLOSED_DEFAULT;
            case GRANTED -> DenialReason.NONE;
        };
    }

    private DecisionResponse deny(DecisionRequest request, int purposeVersion, DenialReason reason,
                                  String explanation) {
        return DecisionResponse.deny(request.purposeCode(), purposeVersion, reason, explanation,
                policyVersion, request.at());
    }

    /** The policy bundle version stamped on every decision, so any answer can be reproduced. */
    public String policyVersion() {
        return policyVersion;
    }
}
