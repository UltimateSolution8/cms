package com.uds.consent.service;

import com.uds.consent.ledger.store.AlgorithmicSystemStore;
import com.uds.consent.ledger.store.EntityStore;
import com.uds.consent.ledger.store.SdfObligationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * The Significant Data Fiduciary register, and the thing that finally reads the flag.
 *
 * <p>{@code fiduciary_entity.significant_fiduciary} has existed since the first migration and was
 * read by nothing at all. It is the gate here: an entity the Government has not notified owes
 * nothing, its register is empty, and its overdue count is zero. That is the correct answer for a
 * non-SDF rather than a hidden one, and it is why this service refuses to raise obligations
 * speculatively — an overdue count made of duties nobody has is a count nobody reads.
 *
 * <p><strong>What Rule 13 asks for.</strong> A Data Protection Impact Assessment at least once
 * every twelve months; an independent data audit on the same cycle; algorithmic due diligence that
 * the systems processing personal data do not risk data principals' rights; and the observations
 * from the first two furnished to the Board.
 *
 * <p>The annual pair run from the entity's designation, rolling — each completion sets the next
 * period. Algorithmic diligence runs per registered system, because the check is about a system
 * and a group with three of them owes three answers.
 */
@Service
public class SdfObligationService {

    private static final Logger log = LoggerFactory.getLogger(SdfObligationService.class);

    /** Rule 13's cycle. "At least once every twelve months" is a ceiling, not a target. */
    static final int CYCLE_MONTHS = 12;

    static final String DPIA = "DPIA";
    static final String AUDIT = "INDEPENDENT_AUDIT";
    static final String DILIGENCE = "ALGORITHMIC_DUE_DILIGENCE";

    private final SdfObligationStore obligations;
    private final AlgorithmicSystemStore systems;
    private final EntityStore entities;

    public SdfObligationService(SdfObligationStore obligations, AlgorithmicSystemStore systems,
                                EntityStore entities) {
        this.obligations = obligations;
        this.systems = systems;
        this.entities = entities;
    }

    /**
     * Raises whatever this entity now owes, and returns how many were new.
     *
     * <p>Idempotent: the natural key on {@code sdf_obligation} means calling this twice in a day
     * raises one set. Safe to call from a scheduler, an admin endpoint, or a test.
     *
     * @param from the date the entity was designated, or from which the group chooses to run its
     *             cycle. Not read from the database because the Rules attach the clock to a
     *             notification the platform has no feed for — the same honesty {@code
     *             last_reconciled_at} applies to the Consent Manager register
     */
    @Transactional
    public int raiseDue(String entityId, LocalDate from, Instant asOf) {
        if (!isSignificant(entityId)) {
            // Not an error and not silent. An entity that is not an SDF owes nothing under Rule
            // 13, and manufacturing a register for it would put obligations in front of an
            // operator that no law imposes.
            log.debug("entity {} is not notified a Significant Data Fiduciary; nothing raised",
                    entityId);
            return 0;
        }

        int raised = 0;
        raised += raiseCycle(entityId, DPIA, from, asOf, null);
        raised += raiseCycle(entityId, AUDIT, from, asOf, null);

        for (AlgorithmicSystemStore.AlgorithmicSystem system
                : systems.forEntity(entityId, true)) {
            raised += raiseCycle(entityId, DILIGENCE, from, asOf, system.id());
        }
        return raised;
    }

    /**
     * Raises the current period for one obligation type, if it is not already on the register.
     *
     * <p>The period runs from the last completion where there is one, and from {@code from}
     * otherwise. Anchoring on the last completion rather than on the calendar is what makes the
     * cycle "at least once every twelve months" rather than "once per calendar year" — an entity
     * that completed its DPIA in November owes the next one the following November, not in
     * January.
     */
    private int raiseCycle(String entityId, String type, LocalDate from, Instant asOf,
                           Long systemId) {
        LocalDate periodStart = obligations.lastCompleted(entityId, type, systemId)
                .map(SdfObligationStore.Obligation::periodEnd)
                .orElse(from);

        // Nothing is owed until the period this would cover has actually begun.
        if (periodStart.isAfter(LocalDate.ofInstant(asOf, ZoneOffset.UTC))) {
            return 0;
        }

        LocalDate periodEnd = periodStart.plusMonths(CYCLE_MONTHS);
        long id = obligations.raise(new SdfObligationStore.Obligation(entityId, type, periodStart,
                periodEnd, periodEnd.atStartOfDay(ZoneOffset.UTC).toInstant(), systemId));
        return id == 0 ? 0 : 1;
    }

    /**
     * Marks an obligation done and, for algorithmic diligence, dates the system it covered.
     *
     * <p>The store refuses a completion with no conductor, reference and hash, and the database
     * refuses it again via a check constraint. Two layers because a register of assessments that
     * were never evidenced is exactly the artefact Rule 13's "available on audit" wording exists
     * to prevent, and it is the kind of gap that only shows up when it is too late to fix.
     */
    @Transactional
    public void complete(long id, String conductedBy, String artefactRef, String artefactSha256,
                         String findings, Instant at) {
        int updated = obligations.complete(id, conductedBy, artefactRef, artefactSha256, findings,
                at);
        if (updated == 0) {
            throw new IllegalArgumentException("SDF obligation " + id + " is not open; it does "
                    + "not exist or has already been completed");
        }
        // Dating the system too, so that "when was this last checked" is answerable from the
        // register of systems without joining back through the obligations every time.
        obligations.find(id)
                .map(SdfObligationStore.Obligation::algorithmicSystemId)
                .ifPresent(systemId -> systems.recordDiligence(systemId, at));
    }

    /** The register as it stands, with what is late and what has not reached the Board. */
    public Register register(String entityId, Instant asOf) {
        boolean significant = isSignificant(entityId);
        if (!significant) {
            return new Register(entityId, false, List.of(), List.of(), List.of(), List.of());
        }
        return new Register(entityId, true,
                obligations.forEntity(entityId),
                obligations.overdue(entityId, asOf),
                obligations.completedButUnreported(entityId),
                systems.forEntity(entityId, true));
    }

    /** Overdue obligations across every notified entity. Zero when the group has no SDF. */
    public int countOverdueAcrossGroup(Instant asOf) {
        return entities.findAll().stream()
                .filter(EntityStore.FiduciaryEntity::significantFiduciary)
                .mapToInt(entity -> obligations.countOverdue(entity.entityId(), asOf))
                .sum();
    }

    private boolean isSignificant(String entityId) {
        return entities.find(entityId)
                .map(EntityStore.FiduciaryEntity::significantFiduciary)
                .orElse(false);
    }

    private String conductedByEntityOf(long id) {
        // The obligation's entity, found the cheap way: the completion has already succeeded, so
        // the row exists, and the register read that follows is small by construction.
        return entities.findAll().stream()
                .map(EntityStore.FiduciaryEntity::entityId)
                .filter(entityId -> obligations.forEntity(entityId).stream()
                        .anyMatch(o -> o.id() == id))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no entity holds obligation " + id));
    }

    /**
     * @param significantFiduciary whether the Government has notified this entity. False means the
     *                             three lists below are empty because nothing is owed, not because
     *                             nothing was found
     */
    public record Register(String entityId, boolean significantFiduciary,
                           List<SdfObligationStore.Obligation> obligations,
                           List<SdfObligationStore.Obligation> overdue,
                           List<SdfObligationStore.Obligation> completedButUnreported,
                           List<AlgorithmicSystemStore.AlgorithmicSystem> algorithmicSystems) {
    }
}
