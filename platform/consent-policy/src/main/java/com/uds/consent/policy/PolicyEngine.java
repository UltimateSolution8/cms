package com.uds.consent.policy;

import com.uds.consent.core.decision.DecisionOutcome;
import com.uds.consent.core.decision.DecisionRequest;
import com.uds.consent.core.decision.DecisionResponse;
import com.uds.consent.core.decision.DenialReason;
import com.uds.consent.core.model.CaptureMethod;
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
    private final PolicyPorts.ApplicationRegistry applications;
    private final PolicyPorts.VendorAuthorisation vendors;
    private final Map<Jurisdiction, List<JurisdictionModule>> modules;
    private final String policyVersion;

    public PolicyEngine(PolicyPorts.PurposeCatalog purposes,
                        PolicyPorts.ArtefactLookup artefacts,
                        PolicyPorts.SuppressionLookup suppression,
                        PolicyPorts.ProvenanceLookup provenance,
                        PolicyPorts.SubjectAttributeLookup subjects,
                        PolicyPorts.ApplicationRegistry applications,
                        PolicyPorts.VendorAuthorisation vendors,
                        List<JurisdictionModule> jurisdictionModules,
                        String policyVersion) {
        this.purposes = purposes;
        this.artefacts = artefacts;
        this.suppression = suppression;
        this.provenance = provenance;
        this.subjects = subjects;
        this.applications = applications;
        this.vendors = vendors;
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

        // Gate 5 — the caller. An applicationId that names a surface the group does not have, or
        // has deactivated, or that belongs to a different entity, is refused.
        //
        // Absent is not a violation. Many server-side callers legitimately have no application
        // identity — a batch job reconciling the CRM is not a surface — and denying them would
        // stop lawful processing to enforce a field they were never asked for. A supplied-but-wrong
        // value is the failure worth catching, and it is precisely what a leaked credential from
        // one entity being replayed against another produces.
        if (request.applicationId() != null && !request.applicationId().isBlank()) {
            Optional<PolicyPorts.RegisteredApplication> application =
                    applications.find(request.applicationId());
            if (application.isEmpty()) {
                return deny(request, purpose.version(), DenialReason.APPLICATION_NOT_AUTHORISED,
                        "application is not registered");
            }
            if (!application.get().active()) {
                return deny(request, purpose.version(), DenialReason.APPLICATION_NOT_AUTHORISED,
                        "application has been deactivated");
            }
            if (!application.get().serves(request.entityId())) {
                // The check that catches a credential moving between entities. Scope rather than
                // ownership, because shared operational systems are the group's business model —
                // but enumerated scope, so a surface reaches the entities somebody granted it and
                // no others. Until per-entity row-level security lands, this is the only place a
                // cross-entity request is visible at all.
                return deny(request, purpose.version(), DenialReason.APPLICATION_NOT_AUTHORISED,
                        "application is not authorised to act for this fiduciary entity");
            }
        }

        // Gate 6 — the recipient. A processor named on the request must be authorised for this
        // purpose, which is what a data processing agreement scopes. Before this gate the platform
        // would answer ALLOW for data being handed to a vendor whose DPA does not cover it, and
        // VendorStore.isAuthorisedFor — javadoc'd as "read on the decision path" — was called by
        // nothing but a test.
        //
        // Absent is not a violation, for the same reason as above.
        if (request.vendorId() != null && !request.vendorId().isBlank()
                && !vendors.isAuthorisedFor(request.vendorId(), request.purposeCode())) {
            return deny(request, purpose.version(), DenialReason.VENDOR_NOT_AUTHORISED,
                    "vendor is not authorised for this purpose");
        }

        // Gate 7 — children. DPDP s.9 closes some purposes to under-eighteens however consent
        // was obtained, so this precedes any look at the consent record.
        //
        // Asked as at request.at() rather than as at now. A replay of a decision taken in 2026 about
        // somebody who has since turned eighteen must answer the question the engine actually faced
        // that day; asking today's question makes every decision taken while they were a minor read
        // back as lawful, which is the one direction an evidence plane must never be wrong in.
        //
        // The caller's own declaration still wins on top: a surface that knows it is talking to a
        // minor is not overridden by a store nobody has told.
        boolean isChild = request.isChildSubject()
                || subjects.isChildAt(request.entityId(), request.subjectId(), request.at());
        if (isChild && !purpose.permittedForChildren()) {
            return deny(request, purpose.version(), DenialReason.CHILD_SUBJECT_RESTRICTED,
                    "purpose is not permitted for a subject under eighteen");
        }

        // Gate 8 — suppression. A statutory registry entry outranks any consent record: someone
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

        // Gate 9 — provenance. Applies to commercially-motivated processing, where the question
        // "where did this record come from" has a real answer that may be "we cannot say". It
        // does not apply to employment or legal-obligation processing, where the engagement
        // itself is the provenance.
        if (requiresProvenance(basis)
                && !provenance.isContactable(request.entityId(), request.subjectId())) {
            return deny(request, purpose.version(), DenialReason.NO_PROVENANCE,
                    "record is quarantined: its origin could not be substantiated");
        }

        // Gate 10 — bases that need no consent record are permitted from here.
        if (!basis.requiresConsentRecord()) {
            return applyModules(request, purpose, basis,
                    DecisionResponse.allow(purpose.code(), purpose.version(), basis, policyVersion,
                            request.at(), null, List.of()));
        }

        // Gate 11 — the consent record itself.
        Optional<ConsentArtefact> artefactOpt =
                artefacts.find(request.entityId(), request.subjectId(), request.purposeCode());

        if (artefactOpt.isEmpty()) {
            // A child with no consent record at all cannot be let through by a fail-open purpose.
            //
            // This branch ran BEFORE gate 11a for one build, and the result was exactly backwards:
            // a child whose consent WAS recorded but not PARENTAL_VERIFIED was denied, and the same
            // child with NO record was allowed — the gate refusing the weaker case and permitting
            // the stronger one. Found by qa-verifier, not by the plan or the suite.
            //
            // s.9(1) is about processing a child's data, and a fail-open default is a decision to
            // process in the absence of a record. It is a reasonable default for an adult and it
            // cannot be one here, because "no record" is precisely the state in which no guardian
            // was ever verified.
            if (isChild) {
                return deny(request, purpose.version(),
                        DenialReason.CHILD_GUARDIAN_NOT_EVIDENCED,
                        "subject is under eighteen and no consent record exists, so no parent or "
                                + "lawful guardian has been verified");
            }
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

        // Gate 11a — a child's consent must record who verified the guardian.
        //
        // Gate 7 asked whether the purpose is closed to children (s.9(3)) and this asks the other
        // half: for a purpose that IS open to them, was the consent being relied upon captured as
        // verifiably given by a parent or lawful guardian? DPDP s.9(1) requires that "before
        // PROCESSING any personal data of a child" — not before capturing it — and Rule 10 makes
        // the diligence the obligation, with the consent as its output.
        //
        // CaptureValidator already refuses a submission that claims parental consent without
        // recording how the guardian was verified, so the capture path was closed. The live hole
        // was a subject whose minority is established AFTER capture (subject_age_assertion): the
        // consent was captured when nobody knew, and nothing asked again. docs/TRACEABILITY.md
        // graded s.9(1) satisfied on the capture-time refusal alone.
        //
        // PARENTAL_VERIFIED on the artefact is a sound proxy for "the diligence is in the chain",
        // and that was checked rather than assumed: ConsentCaptureService.capture is the only path
        // that puts a submission's captureMethod on an event, it validates first and returns
        // rejected before recording, and every other write path stamps NOT_APPLICABLE. So the
        // value cannot reach the projection without having passed DpdpModule's Rule 10 check.
        //
        // Placed AFTER gate 10 deliberately, which is what scopes it. A basis needing no consent
        // record — s.7(i) employment, legal obligation — has already returned, so this never
        // refuses processing that consent was not carrying in the first place. s.9(1) read at its
        // widest would reach those too; the narrower reading is taken on the standing instruction
        // not to over-engineer the legal-policy side, and is recorded as a position rather than as
        // what the clause compels.
        // Reuses gate 7's answer rather than asking the store a second time: isChildAt is a
        // query on the decision path, and the question has not changed since it was asked.
        if (isChild && artefact.captureMethod() != CaptureMethod.PARENTAL_VERIFIED) {
            return deny(request, artefact.purposeVersion(),
                    DenialReason.CHILD_GUARDIAN_NOT_EVIDENCED,
                    "subject is under eighteen and the consent relied upon records no verified "
                            + "parent or lawful guardian");
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
        if (request.channel().isCommercialCommunication() || basis.honoursObjection()) {
            return true;
        }
        // And in the US states that mandate a universal opt-out. Those signals are carried by the
        // browser and apply to web activity — which is exactly the channel the two clauses above
        // skip, since a web page is not a commercial communication and consent does not honour
        // objection. Without this, a GPC signal could be recorded correctly, sit in the
        // suppression table, and never be read on the requests it was sent about.
        return request.jurisdiction().usesUniversalOptOut();
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
