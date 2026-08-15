package com.uds.consent.service.config;

import com.uds.consent.core.crypto.IdentifierHasher;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.ledger.store.ConsentArtefactStore;
import com.uds.consent.ledger.store.ProvenanceStore;
import com.uds.consent.ledger.store.SubjectStore;
import com.uds.consent.ledger.store.SuppressionStore;
import com.uds.consent.policy.PolicyEngine;
import com.uds.consent.policy.capture.CaptureValidator;
import com.uds.consent.policy.jurisdiction.CcpaModule;
import com.uds.consent.policy.jurisdiction.DpdpModule;
import com.uds.consent.policy.jurisdiction.GdprModule;
import com.uds.consent.policy.jurisdiction.JurisdictionModule;
import com.uds.consent.policy.jurisdiction.PdpaMalaysiaModule;
import com.uds.consent.policy.jurisdiction.PdpaSingaporeModule;
import com.uds.consent.policy.jurisdiction.PipaModule;
import com.uds.consent.policy.jurisdiction.TccprModule;
import com.uds.consent.policy.port.PolicyPorts;
import com.uds.consent.service.adapter.CachingPurposeCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Wires the decision engine to the stores.
 *
 * <p>This class is the only place the policy module meets the ledger module. The engine itself
 * knows nothing about either — it depends on the four small interfaces in {@code PolicyPorts},
 * which is what lets the golden decision suite run hundreds of cases across six jurisdictions
 * without a database.
 */
@Configuration
public class PolicyConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PolicyConfiguration.class);

    /**
     * The jurisdiction modules in force.
     *
     * <p>India appears twice, and must. DPDP and the TRAI regulations both bind Denave's and
     * Athena's outbound activity, and they impose different things — DPDP governs the lawful
     * basis and the notice, TCCCPR governs registry scrubbing and sender registration. Collapsing
     * them into one module would bury the distinction that TRAI is enforced today while DPDP's
     * substantive obligations arrive in May 2027.
     */
    @Bean
    public List<JurisdictionModule> jurisdictionModules() {
        return List.of(
                new DpdpModule(),
                new TccprModule(),
                new GdprModule(Jurisdiction.UK),
                new GdprModule(Jurisdiction.EU),
                new PipaModule(),
                new PdpaSingaporeModule(),
                new PdpaMalaysiaModule(),
                new CcpaModule());
    }

    @Bean
    public PolicyEngine policyEngine(CachingPurposeCatalog purposes,
                                     ConsentArtefactStore artefacts,
                                     SuppressionStore suppression,
                                     ProvenanceStore provenance,
                                     SubjectStore subjects,
                                     List<JurisdictionModule> modules,
                                     PlatformProperties properties) {
        PolicyPorts.SuppressionLookup suppressionLookup =
                (entityId, subjectId, channel, clientId, campaignId, at) ->
                        suppression.findForSubject(entityId, subjectId, channel, clientId,
                                        campaignId, at)
                                .map(hit -> PolicyPorts.Hit.of(hit.source(), hit.scope()));

        return new PolicyEngine(
                purposes,
                artefacts::find,
                suppressionLookup,
                provenance::isContactable,
                subjects::isChild,
                modules,
                properties.getPolicyVersion());
    }

    @Bean
    public CaptureValidator captureValidator(CachingPurposeCatalog purposes,
                                             List<JurisdictionModule> modules) {
        return new CaptureValidator(purposes, modules);
    }

    /**
     * Hashes identifiers before they reach the ledger.
     *
     * <p>Refuses to start without a pepper rather than falling back to a default. A silent
     * fallback would produce a database of hashes that are trivially reversible for phone numbers
     * — and it would do so without anyone noticing until the data had accumulated.
     */
    @Bean
    public IdentifierHasher identifierHasher(PlatformProperties properties) {
        String pepper = properties.getIdentifierPepper();
        if (pepper == null || pepper.isBlank()) {
            throw new IllegalStateException(
                    "uds.consent.identifier-pepper is not set. Identifier hashes without a pepper "
                            + "are reversible by enumeration for phone numbers. Set it from the KMS, "
                            + "or from an environment variable for local development.");
        }
        if (pepper.length() < 32) {
            log.warn("identifier pepper is shorter than 32 characters; use a high-entropy secret "
                    + "from the KMS in any non-development environment");
        }
        return new IdentifierHasher(pepper, properties.getDefaultCallingCode());
    }
}
