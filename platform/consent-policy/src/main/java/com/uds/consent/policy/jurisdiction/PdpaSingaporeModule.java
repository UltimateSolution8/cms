package com.uds.consent.policy.jurisdiction;

import com.uds.consent.core.decision.DecisionRequest;
import com.uds.consent.core.decision.DecisionResponse;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.LegalBasis;
import com.uds.consent.core.model.PurposeDefinition;

/**
 * Singapore — Personal Data Protection Act.
 *
 * <p>The group has two Singapore entities under Denave. The operative constraint is the Do Not
 * Call Registry: checking it before telemarketing contact is mandatory and independent of
 * consent, in the same way India's preference register is. A consent record does not exempt a
 * number from the check.
 */
public class PdpaSingaporeModule implements JurisdictionModule {

    @Override
    public Jurisdiction jurisdiction() {
        return Jurisdiction.SG;
    }

    @Override
    public DecisionResponse refine(DecisionRequest request, PurposeDefinition purpose,
                                   LegalBasis basis, DecisionResponse decision) {
        if (!decision.isAllowed() || request.channel() == null) {
            return decision;
        }
        if (request.channel() == Channel.VOICE_CALL || request.channel() == Channel.SMS
                || request.channel() == Channel.WHATSAPP) {
            return JurisdictionModule.withObligations(decision, "check-dnc-registry-before-send");
        }
        return decision;
    }
}
