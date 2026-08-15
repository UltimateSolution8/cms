package com.uds.consent.policy.jurisdiction;

import com.uds.consent.core.decision.DecisionRequest;
import com.uds.consent.core.decision.DecisionResponse;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.ExpiryPolicy;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.LegalBasis;
import com.uds.consent.core.model.PurposeDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * India — TRAI's Telecom Commercial Communications Customer Preference Regulations, 2018, as
 * amended in February 2025.
 *
 * <p>This module governs the group's nearest-term regulatory exposure, and it applies on top of
 * DPDP rather than instead of it. TRAI enforces today, with financial penalties and the power to
 * disconnect telecom resources, while DPDP's substantive obligations do not bite until May 2027.
 * Denave's and Athena's outbound activity is squarely within its scope.
 *
 * <p>It also imposes consent mechanics that a boolean consent flag simply cannot express:
 * explicit consent for a transactional communication lapses after seven days, and consent
 * inferred from a contractual relationship lasts only as long as that relationship. The expiry
 * itself is enforced by the core engine through the purpose's expiry policy; what this module
 * adds is the surrounding obligations — registry scrubbing and DLT registration — that no amount
 * of valid consent removes.
 */
public class TccprModule implements JurisdictionModule {

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

        List<String> obligations = new ArrayList<>();

        // A valid consent record does not exempt a campaign from scrubbing. The preference
        // register is checked before every send, not once at list build.
        if (request.channel() == Channel.VOICE_CALL || request.channel() == Channel.SMS) {
            obligations.add("scrub-against-ncpr-before-send");
        }

        // No A2P SMS may go out without a registered header and a registered template on the
        // distributed ledger platform, regardless of consent.
        if (request.channel() == Channel.SMS) {
            obligations.add("use-dlt-registered-header");
            obligations.add("use-dlt-registered-template");
        }

        if (purpose.expiryPolicy() == ExpiryPolicy.TRAI_TRANSACTIONAL_7D) {
            obligations.add("transactional-consent-expires-seven-days-from-grant");
        }

        if (basis == LegalBasis.INFERRED_CONSENT) {
            obligations.add("valid-only-while-contractual-relationship-subsists");
        }

        return JurisdictionModule.withObligations(decision, obligations.toArray(String[]::new));
    }
}
