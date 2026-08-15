package com.uds.consent.service;

import com.uds.consent.core.crypto.IdentifierHasher;
import com.uds.consent.core.model.IdentifierType;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.RightsRequestStatus;
import com.uds.consent.core.model.RightsRequestType;
import com.uds.consent.ledger.store.AdminAuditStore;
import com.uds.consent.ledger.store.RightsRequestStore;
import com.uds.consent.ledger.store.SubjectStore;
import com.uds.consent.policy.rights.StatutoryClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rights request intake and the statutory clock over it.
 *
 * <p>What is here is intake, the deadline, and breach visibility. What is not here is fulfilment —
 * federated retrieval across DenCRM, HRMS and the BGV workflow, and the grievance routing that
 * goes with it, are the next phase.
 *
 * <p>That split is deliberate rather than a shortcut. A rights request that arrives before anyone
 * can fulfil it is a manual job, and manual jobs get done; a rights request that arrives before
 * anyone is <em>counting the days</em> is a statutory breach nobody notices until the principal
 * escalates. The clock is the part that cannot be retrofitted, because a deadline reconstructed
 * afterwards is not evidence of anything.
 */
@Service
public class RightsService {

    private static final Logger log = LoggerFactory.getLogger(RightsService.class);

    private final RightsRequestStore store;
    private final SubjectStore subjects;
    private final IdentifierHasher hasher;
    private final AdminAuditStore audit;

    public RightsService(RightsRequestStore store, SubjectStore subjects, IdentifierHasher hasher,
                         AdminAuditStore audit) {
        this.store = store;
        this.subjects = subjects;
        this.hasher = hasher;
        this.audit = audit;
    }

    /**
     * Logs a request and starts its clock.
     *
     * <p>Accepts an identifier rather than requiring a subject id, because the person filing is
     * typically doing so by email or over the phone and does not know one. Resolving here — and
     * creating the subject if it is genuinely new — means a request from someone the group holds
     * no consent record for is still tracked. Those are the ones that matter most: a principal
     * asking what is held about them when the answer might be "we bought your details".
     */
    @Transactional
    public RightsRequestStore.Request intake(Intake intake) {
        Instant receivedAt = intake.receivedAt() == null ? Instant.now() : intake.receivedAt();
        String subjectId = resolveSubject(intake);
        String requestId = "RR-" + UUID.randomUUID();

        StatutoryClock.Deadline deadline =
                StatutoryClock.dueAt(intake.type(), intake.jurisdiction(), receivedAt);

        store.create(requestId, intake.entityId(), subjectId, intake.type(), intake.jurisdiction(),
                receivedAt, deadline.dueAt(), deadline.basis(), intake.details());

        audit.record(intake.actorId(), "RIGHTS_REQUEST_RECEIVED", intake.entityId(),
                "rights_request", requestId,
                Map.of("type", intake.type().name(),
                        "jurisdiction", intake.jurisdiction().name(),
                        "dueAt", deadline.dueAt().toString()));

        log.info("rights request {} ({}) for entity {} due {} — {}", requestId, intake.type(),
                intake.entityId(), deadline.dueAt(), deadline.basis());

        return store.find(requestId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public RightsRequestStore.Request find(String requestId) {
        return store.find(requestId).orElseThrow(() ->
                new IllegalArgumentException("no rights request " + requestId));
    }

    @Transactional(readOnly = true)
    public List<RightsRequestStore.Request> forSubject(String entityId, String subjectId) {
        return store.findForSubject(entityId, subjectId);
    }

    @Transactional(readOnly = true)
    public List<RightsRequestStore.Request> open(String entityId, int limit, int offset) {
        return store.findOpen(entityId, limit, offset);
    }

    @Transactional(readOnly = true)
    public List<RightsRequestStore.Request> overdue(Instant asOf, int limit) {
        return store.findOverdue(asOf, limit);
    }

    @Transactional(readOnly = true)
    public List<RightsRequestStore.TypeSummary> summarise(String entityId) {
        return store.summarise(entityId, Instant.now());
    }

    /**
     * Moves a request along, and closes it when the new status is terminal.
     *
     * <p>A terminal status requires a resolution. Refusing an erasure request is a legitimate
     * outcome — data held under a legal obligation survives one — but an unexplained refusal is
     * not, and the resolution text is exactly what the Board would ask to see.
     */
    @Transactional
    public RightsRequestStore.Request transition(String requestId, RightsRequestStatus status,
                                                 String assignedTo, String resolution,
                                                 String actorId) {
        RightsRequestStore.Request before = find(requestId);

        if (!before.status().isOpen()) {
            throw new IllegalArgumentException("request " + requestId + " is already "
                    + before.status() + " and cannot be moved again. Corrections are made by "
                    + "filing a new request, not by editing a closed one.");
        }
        if (!status.isOpen() && (resolution == null || resolution.isBlank())) {
            throw new IllegalArgumentException(
                    "closing a request as " + status + " needs a resolution describing what was "
                            + "done and, where it was refused, on what ground");
        }

        Instant closedAt = status.isOpen() ? null : Instant.now();
        store.updateStatus(requestId, status, assignedTo, resolution, closedAt);

        audit.record(actorId, "RIGHTS_REQUEST_" + status.name(), before.entityId(),
                "rights_request", requestId,
                Map.of("from", before.status().name(), "to", status.name(),
                        "dueAt", before.dueAt().toString(),
                        "late", String.valueOf(closedAt != null && closedAt.isAfter(before.dueAt()))));

        if (closedAt != null && closedAt.isAfter(before.dueAt())) {
            // Logged at WARN on the way out, not only by the sweeper. A request answered late is
            // a breach whether or not a scheduled job happened to catch it while it was open.
            log.warn("rights request {} closed {} after its deadline of {}", requestId,
                    java.time.Duration.between(before.dueAt(), closedAt), before.dueAt());
        }

        return find(requestId);
    }

    /** Records that the principal was told their request was received. */
    @Transactional
    public void acknowledge(String requestId) {
        store.acknowledge(requestId, Instant.now());
    }

    private String resolveSubject(Intake intake) {
        if (intake.subjectId() != null && !intake.subjectId().isBlank()) {
            return intake.subjectId();
        }
        if (intake.identifierType() == null || intake.identifierValue() == null) {
            throw new IllegalArgumentException(
                    "a rights request needs either a subjectId or an identifier to attach to");
        }
        String hash = hasher.hash(intake.identifierType(), intake.identifierValue());
        return subjects.resolveOrCreate(intake.entityId(), intake.identifierType(), hash);
    }

    /**
     * @param receivedAt when the principal actually asked, which may be earlier than when it was
     *                   keyed in. The clock runs from their act, not from the group's data entry
     */
    public record Intake(
            String entityId,
            String subjectId,
            IdentifierType identifierType,
            String identifierValue,
            RightsRequestType type,
            Jurisdiction jurisdiction,
            Instant receivedAt,
            String details,
            String actorId) {
    }
}
