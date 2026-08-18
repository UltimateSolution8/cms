package com.uds.consent.service.sweeper;

import com.uds.consent.ledger.store.EntityStore;
import com.uds.consent.ledger.store.OutboxStore;
import com.uds.consent.ledger.store.ProcessingActivityStore;
import com.uds.consent.ledger.store.RetentionStore;
import com.uds.consent.ledger.store.RightsVerificationStore;
import com.uds.consent.service.config.PlatformProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Works out what is now past its retention period, and says so.
 *
 * <p><strong>It proposes; it does not delete.</strong> That is a structural decision rather than a
 * cautious one, and it holds in both directions. The personal data lives in DenCRM, the HRMS, the
 * BGV workflow and a dozen client systems — this platform holds consent evidence <em>about</em>
 * people, not the people's records — so a sweeper deleting from here would erase the proof that
 * the retention was lawful while leaving the data itself entirely untouched. And the ledger is
 * append-only, deliberately: the evidence of a consent interaction must outlive the personal data
 * it concerned, or the group loses its ability to show it held that data lawfully for as long as
 * it did.
 *
 * <p>So the platform emits {@code RETENTION_DUE} to the outbox for the owning system to act on,
 * records what came back, and keeps the gap between "due" and "done" visible. That gap is the
 * compliance position, and it is the number nobody currently has.
 *
 * <p><strong>Rule 8's ordering.</strong> The date this sweeper acts on is the <em>notice</em> date,
 * not the erasure date. The Rules require the principal to be told before their data goes, at
 * least forty-eight hours ahead, so that they can act to keep the account if they want it.
 * Computing only the erasure date and notifying on it produces a platform that erases punctually
 * and unlawfully. The notification channel itself is out of scope — the event carries what is
 * needed and the group's existing messaging owns delivery.
 */
@Component
public class RetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(RetentionSweeper.class);

    /** The topic the owning systems subscribe to. */
    public static final String TOPIC_RETENTION = "uds.consent.retention";

    private final ProcessingActivityStore activities;
    private final RetentionStore retention;
    private final EntityStore entities;
    private final OutboxStore outbox;
    private final RightsVerificationStore verifications;
    private final PlatformProperties properties;
    private final SweepLock lock;

    private volatile Report lastReport = new Report(Instant.EPOCH, 0, 0, 0, List.of());

    public RetentionSweeper(ProcessingActivityStore activities, RetentionStore retention,
                            EntityStore entities, OutboxStore outbox,
                            RightsVerificationStore verifications,
                            PlatformProperties properties, SweepLock lock) {
        this.activities = activities;
        this.retention = retention;
        this.entities = entities;
        this.outbox = outbox;
        this.verifications = verifications;
        this.properties = properties;
        this.lock = lock;
    }

    @Scheduled(fixedDelayString = "${uds.consent.sweeper.retention-interval:PT6H}")
    public void sweep() {
        if (!properties.getSweeper().isRetentionEnabled()) {
            return;
        }
        // Locked because every proposal emits an event, and the same erasure proposed twice to
        // DenCRM by two replicas is an integration nobody will trust twice.
        lock.runExclusively("retention", () -> run(Instant.now()));
    }

    /** Runs one pass as at {@code asOf}. Driven directly by the tests with a controlled clock. */
    public Report run(Instant asOf) {
        int batch = properties.getSweeper().getRetentionBatchSize();
        Duration noticeLead = properties.getSweeper().getRetentionNoticeLeadTime();

        int raised = 0;
        int noticed = 0;
        for (EntityStore.FiduciaryEntity entity : entities.findAll()) {
            for (ProcessingActivityStore.Activity activity
                    : activities.findForEntity(entity.entityId())) {
                if (activity.retentionPeriodDays() == null) {
                    // No rule to enforce. The absence is already a finding on the RoPA gap
                    // report, and inventing a default here would enforce a period nobody agreed.
                    continue;
                }
                raised += raiseFor(entity.entityId(), activity, asOf, noticeLead, batch);
            }
        }

        for (RetentionStore.Action action : retention.noticeDue(asOf, batch)) {
            // Rule 8: the principal is told before the erasure, not after it. The event is the
            // instruction to tell them; delivery belongs to the group's messaging.
            outbox.enqueue(TOPIC_RETENTION, action.entityId() + ":" + action.subjectId(),
                    Map.of("type", "RETENTION_NOTICE_DUE",
                            "actionId", action.id(),
                            "entityId", action.entityId(),
                            "subjectId", action.subjectId(),
                            "purposeCode", action.purposeCode(),
                            "systemName", action.systemName() == null ? "" : action.systemName(),
                            "eraseDueAt", action.eraseDueAt().toString(),
                            "basis", "DPDP Rules 2025, Rule 8 — intimation before erasure"));
            retention.markNoticeSent(action.id(), asOf);
            noticed++;
        }

        List<RetentionStore.Action> overdue = retention.overdue(asOf, batch);
        for (RetentionStore.Action action : overdue) {
            // ERROR because this is the state the obligation is actually breached in: the period
            // has run, the platform said so, and nothing confirmed the data went. A RETAINED with
            // a documented reason clears this; silence does not.
            log.error("RETENTION OVERDUE: action {} for entity {} purpose {} was due {} and is "
                            + "still {}. The system holding the data ({}) has not confirmed "
                            + "erasure.",
                    action.id(), action.entityId(), action.purposeCode(), action.eraseDueAt(),
                    action.status(), action.systemName());
        }

        // Rights-portal submissions nobody ever verified.
        //
        // Belongs on this sweep rather than on a timer of its own, because it is the same
        // obligation the rest of this class enforces: data collected for a purpose that did not
        // happen. Each row is an identifier hash for a person who started a rights request and
        // never finished it — often because the code reached nobody, which means the platform is
        // holding a hash of somebody who may never have asked for anything at all.
        int purged = verifications.purgeExpired(asOf, batch);

        if (raised > 0 || noticed > 0 || purged > 0) {
            log.info("retention sweep at {}: {} proposal(s) raised, {} notice(s) emitted, {} "
                            + "overdue, {} unverified portal submission(s) discarded",
                    asOf, raised, noticed, overdue.size(), purged);
        }

        Report report = new Report(asOf, raised, noticed, overdue.size(),
                overdue.stream().map(RetentionStore.Action::id).toList());
        this.lastReport = report;
        return report;
    }

    private int raiseFor(String entityId, ProcessingActivityStore.Activity activity, Instant asOf,
                         Duration noticeLead, int batch) {
        Duration period = Duration.ofDays(activity.retentionPeriodDays());

        // Everyone whose last interaction is already older than the period. The erasure date is
        // that interaction plus the period — which is in the past for a backlog, and that is the
        // honest answer: the group is late, and dating the action from today would hide it.
        List<RetentionStore.DueSubject> due = retention.findDue(entityId, activity.purposeCode(),
                asOf.minus(period), batch);

        List<RetentionStore.Action> raised = new ArrayList<>(due.size());
        for (RetentionStore.DueSubject subject : due) {
            Instant eraseDueAt = subject.lastActivityAt().plus(period);
            raised.add(new RetentionStore.Action(entityId, activity.id(), activity.purposeCode(),
                    subject.subjectId(), subject.lastActivityAt(),
                    eraseDueAt.minus(noticeLead), eraseDueAt, activity.systemName()));
        }
        raised.forEach(retention::raise);
        return raised.size();
    }

    public Report lastReport() {
        return lastReport;
    }

    /**
     * @param overdueActionIds named rather than counted, for the same reason the other sweeps name
     *                         theirs: the response is always "who is picking these up"
     */
    public record Report(Instant sweptAt, int raised, int noticesEmitted, int overdue,
                         List<Long> overdueActionIds) {

        public boolean clean() {
            return overdue == 0;
        }
    }
}
