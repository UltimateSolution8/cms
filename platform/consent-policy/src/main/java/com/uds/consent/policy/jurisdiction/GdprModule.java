package com.uds.consent.policy.jurisdiction;

import com.uds.consent.core.decision.DecisionRequest;
import com.uds.consent.core.decision.DecisionResponse;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.LegalBasis;
import com.uds.consent.core.model.PurposeDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * European Union and United Kingdom — GDPR / UK GDPR, with the ePrivacy Directive and PECR
 * sitting alongside them.
 *
 * <p>Instantiated once per jurisdiction because the two regimes have diverged and will diverge
 * further. Denave has a UK entity whose published privacy policy still cites the superseded
 * Personal Data Protection Bill and which runs analytics with no consent controls at all — a
 * live exposure under PECR that predates anything this platform is being built for.
 *
 * <p>The point most often missed: the lawful basis under GDPR and the consent requirement under
 * ePrivacy are separate questions. Legitimate interest can support B2B outreach, and it does
 * nothing at all for setting a cookie — that needs consent regardless.
 *
 * <p>The Digital Omnibus proposal of November 2025 would fold the cookie rules into the GDPR
 * itself. The Council's June 2026 text dropped both the browser-signal and single-click
 * provisions, and the privacy half remains contested with no realistic force before late 2027.
 * That is precisely why the cookie rules here are configuration in the purpose registry rather
 * than logic in this class.
 */
public class GdprModule implements JurisdictionModule {

    private final Jurisdiction jurisdiction;

    public GdprModule(Jurisdiction jurisdiction) {
        if (jurisdiction != Jurisdiction.EU && jurisdiction != Jurisdiction.UK) {
            throw new IllegalArgumentException("GdprModule governs EU and UK only, not " + jurisdiction);
        }
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

        if (basis == LegalBasis.LEGITIMATE_INTEREST) {
            // Art.6(1)(f) is conditional in a way consent is not: it holds only while the subject
            // has not objected, and only with a documented assessment on file. The registry
            // refuses to publish this basis without an assessment reference; these obligations
            // carry the rest of the condition to the caller.
            obligations.add("honour-objection-immediately");
            obligations.add("include-opt-out-mechanism");
            obligations.add("identify-sender-clearly");
        }

        if (request.channel() == Channel.EMAIL || request.channel() == Channel.SMS) {
            obligations.add(jurisdiction == Jurisdiction.UK
                    ? "pecr-electronic-marketing-rules-apply"
                    : "eprivacy-electronic-marketing-rules-apply");
        }

        if (request.channel() == Channel.VOICE_CALL && jurisdiction == Jurisdiction.UK) {
            // Both registers matter for this group: Denave calls businesses, which is what CTPS
            // covers, and TPS covers the individuals among them who are sole traders.
            obligations.add("scrub-against-tps-and-ctps");
        }

        if (request.channel() == Channel.WEB || request.channel() == Channel.MOBILE_APP) {
            obligations.add("consent-required-for-non-essential-storage");
        }

        return JurisdictionModule.withObligations(decision, obligations.toArray(String[]::new));
    }
}
