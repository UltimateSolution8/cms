package com.uds.consent.service;

import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.ledger.store.AdminAuditStore;
import com.uds.consent.ledger.store.BreachStore;
import com.uds.consent.policy.rights.BreachClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reporting, assessing, notifying and closing a personal data breach.
 *
 * <p>The clock starts here and cannot be started later. That is the whole argument for putting
 * this in the platform rather than leaving it to the group's incident process: a breach clock is
 * the one deadline that cannot be reconstructed after the fact, because the thing it measures from
 * — when the group became aware — is a fact about a moment that has already passed by the time
 * anybody thinks to record it.
 *
 * <p>The second argument is {@link #affectedPopulation}. Rule 7's 72-hour report must carry a
 * summary of the intimations given to data principals, which requires knowing which consents were
 * live for whom <em>at the breach instant</em>. Only the ledger can answer that, and a report
 * scoped to whatever the person filing it estimated is not evidence of anything.
 */
@Service
public class BreachService {

    private static final Logger log = LoggerFactory.getLogger(BreachService.class);

    private final BreachStore store;
    private final AdminAuditStore audit;

    public BreachService(BreachStore store, AdminAuditStore audit) {
        this.store = store;
        this.audit = audit;
    }

    /**
     * Records a breach and derives every notification obligation it carries.
     *
     * <p>The obligations are written at intake rather than computed on read, exactly as the rights
     * clock's deadlines are. A deadline recomputed on every read is a deadline that changes when
     * the code changes, and a statutory clock that silently moves is worse than no clock.
     */
    @Transactional
    public Reported report(String entityId, Jurisdiction jurisdiction, Instant occurredAt,
                           Instant detectedAt, Instant awareAt, String description,
                           List<String> dataCategories, List<String> purposeCodes,
                           String actor) {
        if (occurredAt.isAfter(awareAt)) {
            throw new IllegalArgumentException("a breach cannot be discovered before it happened: "
                    + "occurredAt " + occurredAt + " is after awareAt " + awareAt);
        }

        String breachId = "BR-" + UUID.randomUUID();
        store.create(new BreachStore.Breach(breachId, entityId, jurisdiction.name(), occurredAt,
                detectedAt, awareAt, description,
                com.uds.consent.core.crypto.CanonicalJson.serialize(dataCategories),
                com.uds.consent.core.crypto.CanonicalJson.serialize(purposeCodes),
                null, null, "UNASSESSED", "REPORTED", actor, Instant.now(), null, null));

        List<BreachClock.Obligation> obligations =
                BreachClock.obligationsFor(jurisdiction, awareAt);
        for (BreachClock.Obligation obligation : obligations) {
            store.addObligation(breachId, obligation.party().name(),
                    obligation.immediate() ? null : obligation.dueAt(), obligation.immediate(),
                    obligation.basis());
        }

        audit.record(actor, "BREACH_REPORTED", entityId, "personal_data_breach", breachId,
                Map.of("jurisdiction", jurisdiction.name(),
                        "occurredAt", occurredAt.toString(),
                        "awareAt", awareAt.toString(),
                        "obligations", String.valueOf(obligations.size())));

        // At ERROR from the first moment. A breach is not a WARN — under Rule 7 there is an
        // obligation outstanding the instant this row exists, and the log is the first place
        // anybody on call will look.
        log.error("BREACH REPORTED: {} for entity {} in {}, aware at {}. {} notification "
                        + "obligation(s) now outstanding.",
                breachId, entityId, jurisdiction, awareAt, obligations.size());

        return new Reported(breachId, store.notifications(breachId));
    }

    /**
     * Records the assessment.
     *
     * <p>Deliberately mutable. A breach record is a working document for the first seventy-two
     * hours — the severity is revised, the affected count firms up. Freezing it would force a
     * second record to correct the first, and an incident with three records is one nobody can
     * report on. The immutable trail lives in the audit log, which records every transition.
     */
    @Transactional
    public void assess(String breachId, String severity, String riskAssessment,
                       Integer affectedSubjects, boolean notifiable, String actor) {
        BreachStore.Breach breach = require(breachId);
        String status = notifiable ? "NOTIFYING" : "NOT_NOTIFIABLE";
        store.updateAssessment(breachId, severity, riskAssessment, affectedSubjects, status);

        audit.record(actor, "BREACH_ASSESSED", breach.entityId(), "personal_data_breach", breachId,
                Map.of("severity", severity == null ? "UNASSESSED" : severity,
                        "notifiable", String.valueOf(notifiable),
                        "affectedSubjects", String.valueOf(affectedSubjects)));

        if (!notifiable) {
            // Worth its own line. "We decided not to notify" is the judgement a regulator
            // second-guesses hardest, and it must be attributable to a person and a reason.
            log.warn("breach {} assessed NOT NOTIFIABLE by {}: {}", breachId, actor,
                    riskAssessment);
        }
    }

    /**
     * Records that a party was told.
     *
     * <p>Refuses an obligation that does not exist or is already discharged, rather than writing
     * nothing and returning success. A notification recorded against nothing is exactly the quiet
     * failure a breach file must not contain — it would read, later, as evidence of a notification
     * that never happened.
     */
    @Transactional
    public void notify(String breachId, long notificationId, Instant notifiedAt, String method,
                       String reference, Integer recipientCount, String note, String actor) {
        BreachStore.Breach breach = require(breachId);
        int updated = store.markNotified(notificationId, notifiedAt, actor, method, reference,
                recipientCount, note);
        if (updated == 0) {
            throw new IllegalArgumentException("notification " + notificationId + " is not an "
                    + "outstanding obligation of breach " + breachId + "; it does not exist or has "
                    + "already been discharged");
        }

        audit.record(actor, "BREACH_NOTIFICATION_SENT", breach.entityId(), "breach_notification",
                breachId + ":" + notificationId,
                Map.of("method", method == null ? "" : method,
                        "reference", reference == null ? "" : reference,
                        "recipientCount", String.valueOf(recipientCount),
                        "notifiedAt", notifiedAt.toString()));
    }

    /**
     * Closes a breach.
     *
     * <p>Refuses while any obligation is outstanding. Closing over an undischarged notification
     * would produce a file that says the incident is finished and a regulator that has not been
     * told — the exact combination the penalty ceiling of ₹200 crore attaches to.
     */
    @Transactional
    public void close(String breachId, String closureNote, String actor) {
        BreachStore.Breach breach = require(breachId);
        List<BreachStore.Notification> outstanding = store.notifications(breachId).stream()
                .filter(notification -> !notification.discharged())
                .toList();

        if (!outstanding.isEmpty()) {
            throw new IllegalArgumentException("breach " + breachId + " has "
                    + outstanding.size() + " outstanding notification obligation(s): "
                    + outstanding.stream().map(BreachStore.Notification::party).toList()
                    + ". Discharge or record them before closing.");
        }

        store.close(breachId, closureNote, Instant.now());
        audit.record(actor, "BREACH_CLOSED", breach.entityId(), "personal_data_breach", breachId,
                Map.of("closureNote", closureNote == null ? "" : closureNote));
        log.info("breach {} closed by {}", breachId, actor);
    }

    /**
     * Who was affected, as at the breach instant.
     *
     * <p>Not as at now. A subject who withdrew consent the day after the breach — quite possibly
     * <em>because</em> of the notification — was still affected by it, and a query against current
     * state would silently drop them from the report. That is the difference between a Rule 7
     * report and an estimate.
     */
    @Transactional(readOnly = true)
    public List<BreachStore.AffectedSubject> affectedPopulation(String breachId) {
        BreachStore.Breach breach = require(breachId);
        List<String> purposes = parsePurposes(breach.purposeCodes());
        return store.affectedAsAt(breach.entityId(), breach.occurredAt(), purposes);
    }

    @Transactional(readOnly = true)
    public BreachStore.Breach find(String breachId) {
        return require(breachId);
    }

    @Transactional(readOnly = true)
    public List<BreachStore.Notification> notifications(String breachId) {
        require(breachId);
        return store.notifications(breachId);
    }

    @Transactional(readOnly = true)
    public List<BreachStore.Breach> forEntity(String entityId, int limit) {
        return store.findForEntity(entityId, limit);
    }

    private BreachStore.Breach require(String breachId) {
        return store.find(breachId).orElseThrow(() ->
                new IllegalArgumentException("no breach " + breachId));
    }

    /**
     * Purpose codes off the stored JSON array.
     *
     * <p>Parsed by hand rather than with a mapper because the shape is a flat array of codes
     * written by {@code CanonicalJson} on the way in — the same reason the rest of the ledger
     * treats its jsonb columns as opaque.
     */
    private static List<String> parsePurposes(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return List.of();
        }
        String inner = json.trim();
        inner = inner.substring(1, inner.length() - 1);
        if (inner.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(inner.split(","))
                .map(String::trim)
                .map(value -> value.replaceAll("^\"|\"$", ""))
                .filter(value -> !value.isBlank())
                .toList();
    }

    /**
     * @param obligations returned with the report rather than fetched separately, because the
     *                    first question after filing a breach is always "what do I now have to do
     *                    and by when"
     */
    public record Reported(String breachId, List<BreachStore.Notification> obligations) {
    }
}
