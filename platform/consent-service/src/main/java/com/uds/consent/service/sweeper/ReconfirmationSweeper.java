package com.uds.consent.service.sweeper;

import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.LegalBasis;
import com.uds.consent.core.model.PurposeDefinition;
import com.uds.consent.ledger.store.EntityStore;
import com.uds.consent.ledger.store.ReconfirmationStore;
import com.uds.consent.policy.port.PolicyPorts;
import com.uds.consent.service.config.PlatformProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Raises Korea's two-yearly confirmations as they fall due.
 *
 * <p>Enforcement Decree of the Network Act, Article 62-3. Built on the {@link RetentionSweeper}
 * pattern and for the same reason: the obligation is discharged somewhere else — a mail server, a
 * messaging platform — so what this can do is say what is owed, record what came back, and keep
 * the difference visible to somebody.
 *
 * <p><strong>What it will not do.</strong> It does not expire, deny or withdraw anything. Art.
 * 62-3 fixes the interval and the content of the confirmation and is silent on what follows from a
 * recipient who never answers. Treating silence as withdrawal would suppress lawful contact on
 * this platform's own authority; treating it as consent would be equally invented, and would at
 * least be the group's own risk to carry. So the row stays open and the count stays visible, which
 * is the honest position and is the one recorded in {@code REGULATORY_HANDOFF.md} as awaiting
 * Korean counsel.
 *
 * <p>Scoped to Korean-jurisdiction entities and to purposes that Korea treats as consent-based
 * marketing. Article 50 governs the transmission of commercial information for profit; a purpose
 * outside that scope owes nothing and must not be given a queue entry, because a queue full of
 * obligations that do not exist is how a real one goes unnoticed.
 */
@Component
public class ReconfirmationSweeper {

    private static final Logger log = LoggerFactory.getLogger(ReconfirmationSweeper.class);

    private final ReconfirmationStore reconfirmations;
    private final EntityStore entities;
    private final PolicyPorts.PurposeCatalog purposes;
    private final PlatformProperties properties;
    private final SweepLock lock;

    private volatile Report lastReport = new Report(Instant.EPOCH, 0, 0, List.of());

    public ReconfirmationSweeper(ReconfirmationStore reconfirmations, EntityStore entities,
                                 PolicyPorts.PurposeCatalog purposes,
                                 PlatformProperties properties, SweepLock lock) {
        this.reconfirmations = reconfirmations;
        this.entities = entities;
        this.purposes = purposes;
        this.properties = properties;
        this.lock = lock;
    }

    @Scheduled(fixedDelayString = "${uds.consent.sweeper.reconfirmation-interval:PT12H}")
    public void sweep() {
        // Two gates, and they answer different questions. The feature flag says whether the group
        // owes this obligation at all — off by default, because Art. 62-3's treatment of silence
        // is still unconfirmed and Denave Korea is not the pilot. The sweeper flag says whether
        // this deployment runs the pass on a timer, which is what the integration suites turn off
        // so they can drive run() with a controlled clock. Neither subsumes the other.
        if (!properties.getFeatures().isKoreaReconfirmation()
                || !properties.getSweeper().isReconfirmationEnabled()) {
            return;
        }
        // Twelve hours, and locked. The obligation is dated to the day, so a pass every twelve
        // hours is ample and a tighter loop would only multiply the chance of two replicas
        // racing on the same insert — which the natural key would absorb, but pointlessly.
        lock.runExclusively("reconfirmation", () -> run(Instant.now()));
    }

    /** Runs one pass as at {@code asOf}. Driven directly by the tests with a controlled clock. */
    public Report run(Instant asOf) {
        int batch = properties.getSweeper().getReconfirmationBatchSize();

        int raised = 0;
        for (EntityStore.FiduciaryEntity entity : entities.findAll()) {
            if (entity.primaryJurisdiction() != Jurisdiction.KR) {
                continue;
            }
            for (PurposeDefinition purpose : purposes.all()) {
                if (!inScopeOfArticle50(purpose)) {
                    continue;
                }
                for (ReconfirmationStore.DueConsent due : reconfirmations.findDue(
                        entity.entityId(), purpose.code(), asOf, batch)) {
                    reconfirmations.raise(entity.entityId(), due.subjectId(), purpose.code(),
                            due.consentedAt());
                    raised++;
                }
            }
        }

        List<ReconfirmationStore.Reconfirmation> overdue = reconfirmations.overdue(asOf, batch);
        for (ReconfirmationStore.Reconfirmation row : overdue) {
            // WARN, not ERROR — and the distinction is deliberate. An overdue retention action is
            // a breach that has already happened. This is an obligation whose date has passed and
            // which is still dischargeable by sending the confirmation today. Logging both at the
            // same level would teach whoever reads these that neither is urgent.
            log.warn("RECONFIRMATION OVERDUE: row {} for entity {} subject {} purpose {} fell due "
                            + "{} and has not been sent. Network Act Enforcement Decree Art. 62-3.",
                    row.id(), row.entityId(), row.subjectId(), row.purposeCode(), row.dueAt());
        }

        if (raised > 0 || !overdue.isEmpty()) {
            log.info("reconfirmation sweep at {}: {} raised, {} overdue", asOf, raised,
                    overdue.size());
        }

        Report report = new Report(asOf, raised, overdue.size(),
                overdue.stream().map(ReconfirmationStore.Reconfirmation::id).toList());
        this.lastReport = report;
        return report;
    }

    /**
     * Whether Article 50 reaches this purpose.
     *
     * <p>Article 50 governs the transmission of commercial information for profit, so the test is
     * that Korea treats the purpose as resting on consent — which under PIPA and the Network Act
     * is exactly the marketing surface — and that it is not one of the legitimate-use purposes
     * that never asked for consent in the first place.
     *
     * <p>Derived from the registry rather than hard-coded to a list of purpose codes. A list would
     * be correct on the day it was written and silently incomplete on the day somebody publishes
     * the next marketing purpose, which is the failure mode this platform keeps finding in
     * hand-maintained lists elsewhere.
     */
    private static boolean inScopeOfArticle50(PurposeDefinition purpose) {
        return purpose.legalBasisFor(Jurisdiction.KR) == LegalBasis.CONSENT;
    }

    public Report lastReport() {
        return lastReport;
    }

    public record Report(Instant sweptAt, int raised, int overdue, List<Long> overdueIds) {

        public boolean clean() {
            return overdue == 0;
        }
    }
}
