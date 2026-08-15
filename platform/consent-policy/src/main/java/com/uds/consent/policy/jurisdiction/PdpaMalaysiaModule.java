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

    /** Prefix by which the seeded taxonomy marks biometric categories. */
    private static final String BIOMETRIC_PREFIX = "BIOMETRIC_";

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

        boolean touchesBiometrics = purpose.dataCategories().stream()
                .anyMatch(code -> code.startsWith(BIOMETRIC_PREFIX));
        if (touchesBiometrics) {
            obligations.add("biometric-data-is-sensitive-personal-data");
            obligations.add("explicit-consent-required");
        }

        return JurisdictionModule.withObligations(decision, obligations.toArray(String[]::new));
    }
}
