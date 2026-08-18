package com.uds.consent.policy.jurisdiction;

import com.uds.consent.core.decision.DecisionRequest;
import com.uds.consent.core.decision.DecisionResponse;
import com.uds.consent.core.decision.DenialReason;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.ExpiryPolicy;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.LegalBasis;
import com.uds.consent.core.model.PurposeDefinition;
import com.uds.consent.policy.port.PolicyPorts;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * India — TRAI's Telecom Commercial Communications Customer Preference Regulations, 2018, as
 * amended February 2025.
 *
 * <p>This module governs the group's nearest-term regulatory exposure, and it applies on top of
 * DPDP rather than instead of it. TRAI enforces today, with financial penalties and the power to
 * disconnect telecom resources, while DPDP's substantive obligations do not bite until May 2027.
 * Denave's and Athena's outbound activity is squarely within its scope.
 *
 * <p>It also imposes consent mechanics that a boolean consent flag simply cannot express: explicit
 * consent for a transactional communication lapses after seven days, and consent inferred from a
 * contractual relationship lasts only as long as that relationship. The expiry itself is enforced
 * by the core engine through the purpose's expiry policy; what this module adds is the surrounding
 * obligations — registry scrubbing and DLT registration — that no amount of valid consent removes.
 *
 * <p><strong>The February 2025 amendment added two things this module now models.</strong>
 *
 * <p>First, a <em>ninety-day cooling-off</em> before consent may be sought again from a subscriber
 * who opted out. This is the one that matters commercially: the re-permissioning campaign against
 * Denave's quarantined records is the entire point of the provenance work, and it is exactly the
 * activity the cooling-off restricts. It is modelled as a denial rather than as an obligation
 * string because an obligation is advice and this is a prohibition — and because a platform that
 * merely advised against it would be waving through the campaign it exists to govern.
 *
 * <p>Second, the DLT registry is <em>named</em> rather than referred to. The old obligations
 * "use-dlt-registered-header" and "use-dlt-registered-template" told a sender something it already
 * knew; what it needs is which header, which template, and under which category — the join a TRAI
 * investigation asks about.
 */
public class TccprModule implements JurisdictionModule {

    /** TCCCPR 2018 as amended, February 2025: no re-solicitation for ninety days. */
    private static final Duration COOLING_OFF = Duration.ofDays(90);

    private final PolicyPorts.OptOutHistory optOuts;
    private final PolicyPorts.DltRegistry dlt;

    /**
     * The module with no registry behind it.
     *
     * <p>Kept because a great many tests care about the seven-day window and nothing else, and
     * because the module must remain constructible without a database. The behavioural cost is
     * stated rather than hidden: with no opt-out history the cooling-off cannot be enforced, so
     * this constructor produces a module that is <em>less</em> strict, and it should not be the
     * one the running service uses.
     */
    public TccprModule() {
        this((entityId, subjectId, channel) -> Optional.empty(),
                (entityId, purposeCode) -> Optional.empty());
    }

    public TccprModule(PolicyPorts.OptOutHistory optOuts, PolicyPorts.DltRegistry dlt) {
        this.optOuts = optOuts;
        this.dlt = dlt;
    }

    @Override
    public Jurisdiction jurisdiction() {
        return Jurisdiction.IN;
    }

    @Override
    public DecisionResponse refine(DecisionRequest request, PurposeDefinition purpose,
                                   LegalBasis basis, DecisionResponse decision) {
        if (!decision.isAllowed() || request.channel() == null) {
            return decision;
        }
        if (!request.channel().isCommercialCommunication()) {
            return decision;
        }

        // The cooling-off is checked before any obligation is added, because it can turn the
        // decision into a denial and there is no sense decorating an answer that is about to be
        // replaced.
        Optional<DecisionResponse> refused = coolingOff(request, purpose, decision);
        if (refused.isPresent()) {
            return refused.get();
        }

        List<String> obligations = new ArrayList<>();

        // A valid consent record does not exempt a campaign from scrubbing. The preference
        // register is checked before every send, not once at list build.
        if (request.channel() == Channel.VOICE_CALL || request.channel() == Channel.SMS) {
            obligations.add("scrub-against-ncpr-before-send");
        }

        // No A2P SMS may go out without a registered header and a registered template on the
        // distributed ledger platform, regardless of consent.
        if (request.channel() == Channel.SMS) {
            obligations.addAll(dltObligations(request, purpose));
        }

        if (purpose.expiryPolicy() == ExpiryPolicy.TRAI_TRANSACTIONAL_7D) {
            obligations.add("transactional-consent-expires-seven-days-from-grant");
        }

        if (basis == LegalBasis.INFERRED_CONSENT) {
            obligations.add("valid-only-while-contractual-relationship-subsists");
        }

        return JurisdictionModule.withObligations(decision, obligations.toArray(String[]::new));
    }

    /**
     * Denies a re-solicitation inside ninety days of an opt-out.
     *
     * <p>Deliberately keyed on the <em>last</em> opt-out rather than on a current suppression. The
     * two differ exactly where this matters: an opt-out scoped to one campaign, or one that has
     * since lapsed, stops suppressing while the cooling-off it started is still running. A check
     * built on the suppression lookup would therefore pass in the one case the rule exists for.
     */
    private Optional<DecisionResponse> coolingOff(DecisionRequest request,
                                                  PurposeDefinition purpose,
                                                  DecisionResponse decision) {
        if (request.subjectId() == null) {
            return Optional.empty();
        }

        Optional<Instant> lastOptOut =
                optOuts.lastOptOutAt(request.entityId(), request.subjectId(), request.channel());
        if (lastOptOut.isEmpty()) {
            return Optional.empty();
        }

        Instant reSolicitableFrom = lastOptOut.get().plus(COOLING_OFF);
        if (!request.at().isBefore(reSolicitableFrom)) {
            return Optional.empty();
        }

        return Optional.of(DecisionResponse.deny(purpose.code(), purpose.version(),
                DenialReason.WITHIN_COOLING_OFF_PERIOD,
                "subscriber opted out on " + lastOptOut.get() + "; TCCCPR 2018 as amended "
                        + "February 2025 bars re-soliciting consent for ninety days, so this "
                        + "purpose may not be pursued until " + reSolicitableFrom,
                decision.policyVersion(), request.at()));
    }

    /**
     * Names the registration rather than asserting that one is needed.
     *
     * <p>An unregistered purpose returns an obligation saying so. That reads oddly — an obligation
     * the caller cannot discharge — but the alternatives are worse: denying would stop a campaign
     * over a configuration gap the platform is not the system of record for, and staying silent
     * would let a send go out that the operator will reject anyway. Surfacing it here means the
     * gap is found before a campaign rather than during one.
     */
    private List<String> dltObligations(DecisionRequest request, PurposeDefinition purpose) {
        Optional<PolicyPorts.DltRegistration> registration =
                dlt.find(request.entityId(), purpose.code());

        if (registration.isEmpty()) {
            return List.of("dlt-registration-missing-for-purpose");
        }

        PolicyPorts.DltRegistration found = registration.get();
        List<String> obligations = new ArrayList<>();
        obligations.add("use-dlt-registered-header:" + found.header());
        obligations.add("use-dlt-registered-template:" + found.templateRef());
        // The category decides which header may carry the traffic and which numbering series it
        // originates from — 140 for promotional, 1600 for transactional. A mis-send is caught on
        // exactly this, so it travels with the answer rather than being looked up separately.
        obligations.add("dlt-message-category:" + found.category());
        if (found.series() != null) {
            obligations.add("originate-from-series:" + found.series());
        }
        if (!found.usable()) {
            obligations.add("dlt-template-not-yet-registered");
        }

        // Added by the amendment: every promotional message must carry a working opt-out.
        if ("P".equals(found.category())) {
            obligations.add("include-opt-out-link-in-message");
        }
        return obligations;
    }
}
