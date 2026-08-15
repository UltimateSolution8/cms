package com.uds.consent.policy.jurisdiction;

import com.uds.consent.core.decision.DecisionRequest;
import com.uds.consent.core.decision.DecisionResponse;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.LegalBasis;
import com.uds.consent.core.model.PurposeDefinition;

/**
 * California — CCPA as amended by the CPRA.
 *
 * <p>Included because Denave's own published privacy policy already asserts California coverage.
 * The model here is opt-out rather than opt-in, which the platform expresses as a suppression
 * entry rather than as a consent record — the same machinery that carries a preference-register
 * entry, pointed at a different source.
 */
public class CcpaModule implements JurisdictionModule {

    @Override
    public Jurisdiction jurisdiction() {
        return Jurisdiction.US_CA;
    }

    @Override
    public DecisionResponse refine(DecisionRequest request, PurposeDefinition purpose,
                                   LegalBasis basis, DecisionResponse decision) {
        if (!decision.isAllowed()) {
            return decision;
        }
        return JurisdictionModule.withObligations(decision,
                "provide-do-not-sell-or-share-link",
                "honour-global-privacy-control-signal");
    }
}
