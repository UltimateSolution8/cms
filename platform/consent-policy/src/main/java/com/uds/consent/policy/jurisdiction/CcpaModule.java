package com.uds.consent.policy.jurisdiction;

import com.uds.consent.core.decision.DecisionRequest;
import com.uds.consent.core.decision.DecisionResponse;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.LegalBasis;
import com.uds.consent.core.model.PurposeDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * The United States — CCPA/CPRA and the state laws that followed it.
 *
 * <p>One module per state would be sixteen near-identical classes, so this one is constructed per
 * jurisdiction and asks the enum what applies. That is the right shape here and not merely the
 * convenient one: what varies between these regimes is a handful of predicates, while what varies
 * between, say, India and Korea is the entire model of consent.
 *
 * <p>The model is opt-out rather than opt-in, which the platform expresses as a suppression entry
 * rather than as a consent record — the same machinery that carries a preference-register entry,
 * pointed at a different source.
 *
 * <p><strong>Two things this module deliberately does not do.</strong>
 *
 * <p>It does not implement Maryland's ban on selling sensitive data. The engine already expresses
 * a prohibition consent cannot cure — a purpose with no legal-basis row for a jurisdiction is
 * denied at Gate 3, before any consent record is read — so encoding it here would be a second,
 * weaker copy of a rule the core already enforces. It is a taxonomy decision: do not publish a
 * Maryland basis for a sensitive-data sale purpose.
 *
 * <p>And it does not ingest the universal opt-out signal. That arrives through the suppression
 * API and is enforced at Gate 8 alongside every other do-not-contact entry, which is where a
 * statutory opt-out belongs. What this module does is state the obligation, on the jurisdictions
 * where it is a legal requirement rather than everywhere.
 */
public class CcpaModule implements JurisdictionModule {

    private final Jurisdiction jurisdiction;

    /** California, for callers that predate the other states. */
    public CcpaModule() {
        this(Jurisdiction.US_CA);
    }

    public CcpaModule(Jurisdiction jurisdiction) {
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
        obligations.add("provide-do-not-sell-or-share-link");

        // Twelve states require honouring a universal opt-out; the rest do not. Emitting the
        // obligation everywhere would be harmless advice in eleven jurisdictions and would make
        // the twelve that matter indistinguishable from the ones that do not — which is precisely
        // the distinction an operations team needs when it decides where to spend the integration
        // effort.
        if (jurisdiction.usesUniversalOptOut()) {
            obligations.add("honour-universal-opt-out-signal");
        }

        if (purpose.touchesSensitiveData()) {
            // Every one of these regimes treats sensitive data differently from ordinary personal
            // data, and Maryland bans selling it outright. Flagged rather than enforced here: the
            // prohibition lives in the taxonomy, where the core engine can act on it before any
            // consent record is read.
            obligations.add("sensitive-data-restrictions-apply");
        }

        return JurisdictionModule.withObligations(decision, obligations.toArray(String[]::new));
    }
}
