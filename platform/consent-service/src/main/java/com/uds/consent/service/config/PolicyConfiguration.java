package com.uds.consent.service.config;

import com.uds.consent.core.crypto.IdentifierHasher;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.snapshot.SigningKeyProvider;
import com.uds.consent.ledger.store.ConsentArtefactStore;
import com.uds.consent.ledger.store.DltRegistryStore;
import com.uds.consent.ledger.store.ProvenanceStore;
import com.uds.consent.ledger.store.ReconfirmationStore;
import com.uds.consent.ledger.store.SubjectStore;
import com.uds.consent.ledger.store.SuppressionStore;
import com.uds.consent.ledger.store.VendorStore;
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
import com.uds.consent.service.EnvironmentSigningKeyProvider;
import com.uds.consent.service.adapter.CachingApplicationRegistry;
import com.uds.consent.service.adapter.CachingNoticeLookup;
import com.uds.consent.service.adapter.CachingPurposeCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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
     * Where the snapshot signing key lives, unless something else says otherwise.
     *
     * <p>Declared here as a {@code @Bean} rather than annotated as a {@code @Component}, because
     * {@link ConditionalOnMissingBean} is only reliable on bean methods: on a scanned component the
     * condition is evaluated in an order Spring does not define, and the component is skipped
     * whether or not a replacement exists. The symptom is the whole application failing to start
     * with "required a bean of type SigningKeyProvider that could not be found" — a puzzling
     * message for an annotation that was supposed to mean "unless one is present".
     *
     * <p>So: a KMS-backed {@code SigningKeyProvider} bean anywhere in the context wins, and this
     * one steps aside. See {@link com.uds.consent.core.snapshot.SigningKeyProvider} for what such
     * an implementation must satisfy.
     */
    @Bean
    @ConditionalOnMissingBean(SigningKeyProvider.class)
    public SigningKeyProvider signingKeyProvider(PlatformProperties properties) {
        return new EnvironmentSigningKeyProvider(properties);
    }

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
    public List<JurisdictionModule> jurisdictionModules(SuppressionStore suppression,
                                                        DltRegistryStore dlt,
                                                        ReconfirmationStore reconfirmations) {
        // TccprModule is the one module that needs the database. Its no-argument constructor
        // exists for the unit suites and is deliberately less strict — with no opt-out history
        // the ninety-day cooling-off cannot be enforced — so the running service must build it
        // with both ports, which is what this does.
        PolicyPorts.OptOutHistory optOuts = suppression::lastOptOutAt;
        PolicyPorts.DltRegistry registry = (entityId, purposeCode) ->
                dlt.find(entityId, purposeCode)
                        .map(found -> new PolicyPorts.DltRegistration(found.header(),
                                found.category(), found.series(), found.templateRef(),
                                found.usable()));

        return List.of(
                new DpdpModule(),
                new TccprModule(optOuts, registry),
                new GdprModule(Jurisdiction.UK),
                new GdprModule(Jurisdiction.EU),
                // Same shape as TccprModule above: the no-argument constructor is for the unit
                // suites and reports nothing overdue, so the running service must supply the
                // Art. 62-3 queue or the obligation would silently never appear.
                new PipaModule(reconfirmations::isOverdue),
                new PdpaSingaporeModule(),
                new PdpaMalaysiaModule(),
                // One module per US state, constructed from the enum. What varies between these
                // regimes is a handful of predicates, so sixteen near-identical classes would be
                // sixteen places to forget the same change.
                new CcpaModule(Jurisdiction.US_CA),
                new CcpaModule(Jurisdiction.US_CO),
                new CcpaModule(Jurisdiction.US_CT),
                new CcpaModule(Jurisdiction.US_TX),
                new CcpaModule(Jurisdiction.US_OR),
                new CcpaModule(Jurisdiction.US_MT),
                new CcpaModule(Jurisdiction.US_DE),
                new CcpaModule(Jurisdiction.US_NJ),
                new CcpaModule(Jurisdiction.US_NE),
                new CcpaModule(Jurisdiction.US_NH),
                new CcpaModule(Jurisdiction.US_MN),
                new CcpaModule(Jurisdiction.US_MD),
                new CcpaModule(Jurisdiction.US_VA),
                new CcpaModule(Jurisdiction.US_UT),
                new CcpaModule(Jurisdiction.US_IA));
    }

    @Bean
    public PolicyEngine policyEngine(CachingPurposeCatalog purposes,
                                     ConsentArtefactStore artefacts,
                                     SuppressionStore suppression,
                                     ProvenanceStore provenance,
                                     SubjectStore subjects,
                                     CachingApplicationRegistry applications,
                                     VendorStore vendors,
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
                // Age as at the decision instant, with the current flag as a fallback — and the
                // fallback is the part that matters.
                //
                // wasChildAt returns empty when nothing had been asserted about the subject by that
                // instant, and empty is not "adult". Every subject captured before the age-assertion
                // table existed is in exactly that state, so an .orElse(false) here would silently
                // un-protect the entire pre-existing population — a change that would pass review,
                // pass every test written after it, and only show up as under-eighteens being
                // profiled. So an absent assertion falls back to subject.is_child, which is what the
                // engine read before and is still true for those subjects.
                //
                // This is a migration artefact and should shrink on its own: every capture that
                // declares a child now writes an assertion, so the fallback covers a fixed and
                // ageing population rather than a growing one.
                (entityId, subjectId, at) -> subjects.wasChildAt(entityId, subjectId, at)
                        .orElseGet(() -> subjects.isChild(subjectId)),
                // The same cached registry the capture validator reads. One registry answering
                // both paths means an application deactivated in the console stops submitting
                // consent and stops asking about it at the same moment, rather than at two
                // moments a refresh interval apart.
                applications,
                // Straight to the store rather than through a cache. Vendor authorisation is read
                // only when a request names a vendor, which is a minority of traffic, and a stale
                // answer here means data continuing to reach a processor whose agreement has been
                // terminated — the one staleness in this platform with a counterparty on the other
                // end of it.
                vendors::isAuthorisedFor,
                modules,
                properties.getPolicyVersion());
    }

    @Bean
    public CaptureValidator captureValidator(CachingPurposeCatalog purposes,
                                             CachingApplicationRegistry applications,
                                             CachingNoticeLookup notices,
                                             List<JurisdictionModule> modules) {
        return new CaptureValidator(purposes, applications, notices, modules);
    }

    /**
     * Shortest pepper the platform will start with.
     *
     * <p>256 bits of entropy expressed as characters, roughly. The threshold is not arbitrary: the
     * attack it defends against is an offline dictionary of every Indian mobile number — about
     * 10<sup>10</sup> candidates, which is minutes of GPU time — so the pepper has to be the part
     * that is genuinely unguessable, and a memorable string is not.
     */
    private static final int MINIMUM_PEPPER_LENGTH = 32;

    /**
     * Hashes identifiers before they reach the ledger.
     *
     * <p>Refuses to start without a pepper, and refuses to start with a weak one. A silent
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
        if (pepper.length() < MINIMUM_PEPPER_LENGTH) {
            // Promoted from a WARN. The absent case was already fatal and the weak case was a log
            // line, which is the wrong way round: an absent pepper is noticed on the first
            // start-up, while a short one starts the platform, looks entirely normal, and produces
            // hashes an attacker with the database can brute-force. Both failures have the same
            // consequence — a recoverable phone number — so both get the same answer.
            //
            // Nothing in the tree trips this. The local and integration-test peppers are 45 and 44
            // characters; a pepper below 32 is a hand-typed placeholder, which is exactly what
            // this is for.
            throw new IllegalStateException(
                    "uds.consent.identifier-pepper is " + pepper.length() + " characters; at least "
                            + MINIMUM_PEPPER_LENGTH + " are required. A short pepper is brute-"
                            + "forceable against the space of Indian mobile numbers, which makes "
                            + "every identifier hash in the ledger recoverable from a database "
                            + "copy. Generate a high-entropy secret and hold it in the KMS.");
        }
        return new IdentifierHasher(pepper, properties.getDefaultCallingCode());
    }
}
