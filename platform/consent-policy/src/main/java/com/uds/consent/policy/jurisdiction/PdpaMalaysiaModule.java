package com.uds.consent.policy.jurisdiction;

import com.uds.consent.core.decision.DecisionRequest;
import com.uds.consent.core.decision.DecisionResponse;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.LegalBasis;
import com.uds.consent.core.model.PurposeDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * Malaysia — Personal Data Protection Act, as amended in 2024.
 *
 * <p>Two changes from the amendment matter to this group. Biometric data is now sensitive
 * personal data, which reaches the fingerprint and facial templates used for attendance across
 * several UDS entities. And liability now attaches directly to processors, not only to
 * controllers — so Athena's and Denave's processor work for clients carries its own statutory
 * exposure rather than sitting entirely behind the client's.
 */
public class PdpaMalaysiaModule implements JurisdictionModule {

    @Override
    public Jurisdiction jurisdiction() {
        return Jurisdiction.MY;
    }

    @Override
    public DecisionResponse refine(DecisionRequest request, PurposeDefinition purpose,
                                   LegalBasis basis, DecisionResponse decision) {
        if (!decision.isAllowed()) {
            return decision;
        }

        List<String> obligations = new ArrayList<>();
        obligations.add("processor-carries-direct-statutory-liability");

        // Read from the registry's own flag rather than from the shape of the category code. The
        // previous check tested for a "BIOMETRIC_" prefix, which holds exactly until somebody adds
        // a biometric category and names it something else — and then fails silently, treating
        // sensitive personal data as ordinary personal data with nothing to notice it by.
        if (purpose.touchesBiometricData()) {
            obligations.add("biometric-data-is-sensitive-personal-data");
            obligations.add("explicit-consent-required");
        } else if (purpose.touchesSensitiveData()) {
            obligations.add("explicit-consent-required");
        }

        return JurisdictionModule.withObligations(decision, obligations.toArray(String[]::new));
    }
}
