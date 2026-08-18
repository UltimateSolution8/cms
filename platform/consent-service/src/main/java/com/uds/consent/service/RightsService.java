package com.uds.consent.service;

import com.uds.consent.core.crypto.IdentifierHasher;
import com.uds.consent.core.model.ClockTolerance;
import com.uds.consent.core.model.IdentifierType;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.RightsRequestStatus;
import com.uds.consent.core.model.RightsRequestType;
import com.uds.consent.core.model.RightsVerificationMethod;
import com.uds.consent.ledger.store.AdminAuditStore;
import com.uds.consent.ledger.store.RightsFulfilmentStore;
import com.uds.consent.ledger.store.RightsRequestStore;
import com.uds.consent.ledger.store.SubjectStore;
import com.uds.consent.policy.rights.StatutoryClock;
import com.uds.consent.service.config.PlatformProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
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
    private final RightsFulfilmentStore fulfilment;
    private final Duration maxBackdate;

    public RightsService(RightsRequestStore store, SubjectStore subjects, IdentifierHasher hasher,
                         AdminAuditStore audit, RightsFulfilmentStore fulfilment,
                         PlatformProperties properties) {
        this.store = store;
        this.subjects = subjects;
        this.hasher = hasher;
        this.audit = audit;
        this.fulfilment = fulfilment;
        this.maxBackdate = properties.getRights().getMaxBackdate();
    }

    /**
     * Refuses a start instant the deadline cannot honestly be computed from.
     *
     * <p>{@code receivedAt} is the input to {@link StatutoryClock}, and until this existed it was
     * whatever the caller said it was. The two refusals defend against opposite abuses and both
     * are worth stating, because only one of them is obvious.
     *
     * <p><strong>Forward is the compliance hole.</strong> An instant in the future moves the
     * deadline outward, so a request answered late can be made to look timely by a value typed
     * into a form. In any Rule 14(3) dispute the group's own record would then be evidence
     * supplied by the party the dispute is with. Refused beyond
     * {@link ClockTolerance#SKEW} — the same window the artefact projector uses, because two
     * independently chosen tolerances would be two definitions of "now" inside one evidence plane.
     *
     * <p><strong>Backward is a sanity window, and nothing more than that.</strong> It was
     * documented for three phases as preventing a request "already past its deadline", and that
     * was never true: every period the platform computes is shorter than the bound — IN 30, GDPR
     * 30, SG 30, CPRA 45, MY 21, KR 10, and a consent withdrawal 1 — so everything between the
     * applicable period and the bound is accepted <em>and is</em> overdue on arrival. The
     * platform's own suite is the counter-example: a Korean access request filed sixty days back
     * against a ten-day period is accepted, and the SLA sweeper correctly reports it breached.
     *
     * <p>Accepting those is right. A letter found in a postbag is a real filing, and refusing it
     * teaches an operator to file with today's date, which destroys the provenance this whole
     * mechanism exists to preserve. What the bound actually buys is narrower and worth keeping: an
     * instant that old is far more likely a typo or a broken clock than a filing, and beyond it
     * the platform declines to guess. The fact the false reasoning was standing in for is recorded
     * instead — see {@code bornOverdue} on the audit event, which distinguishes <em>the group was
     * late</em> from <em>the request arrived late</em>. Those are different facts and a Rule 14(3)
     * dispute turns on which one it was.
     *
     * <p>Neither refusal is a judgement about who filed the request. That is
     * {@link RightsVerificationMethod}'s job, and it refuses nothing.
     */
    private void requireUsableStartInstant(Instant receivedAt, Instant now) {
        if (receivedAt.isAfter(now.plus(ClockTolerance.SKEW))) {
            throw new IllegalArgumentException(
                    "receivedAt " + receivedAt + " is in the future, and the statutory deadline is "
                            + "computed from it. Clocks may differ by up to " + ClockTolerance.SKEW
                            + "; beyond that the value is a claim about the future rather than a "
                            + "record of when the principal asked. Leave it unset to use now.");
        }
        if (receivedAt.isBefore(now.minus(maxBackdate))) {
            throw new IllegalArgumentException(
                    "receivedAt " + receivedAt + " is more than " + maxBackdate + " ago. That is a "
                            + "sanity bound, not a statement about the deadline: a value that old "
                            + "is more likely a typo or a wrong clock than a filing, and the "
                            + "platform declines to guess which. A genuinely late filing inside "
                            + "the bound is accepted, and is recorded as having arrived overdue if "
                            + "it did.");
        }
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
        Instant now = Instant.now();
        Instant receivedAt = intake.receivedAt() == null ? now : intake.receivedAt();
        requireUsableStartInstant(receivedAt, now);

        RightsVerificationMethod verification = intake.verification() == null
                ? RightsVerificationMethod.UNVERIFIED
                : intake.verification();
        // Held in step with the method rather than taken from the caller, because V30's check
        // constraint is a biconditional and a caller that supplied one without the other would
        // fail at the database with a message about a constraint rather than about what it did.
        Instant verifiedAt = verification.isVerified()
                ? (intake.verifiedAt() == null ? now : intake.verifiedAt())
                : null;

        String subjectId = resolveSubject(intake);
        String requestId = "RR-" + UUID.randomUUID();

        StatutoryClock.Deadline deadline =
                StatutoryClock.dueAt(intake.type(), intake.jurisdiction(), receivedAt);

        store.create(requestId, intake.entityId(), subjectId, intake.type(), intake.jurisdiction(),
                receivedAt, deadline.dueAt(), deadline.basis(), intake.details(),
                verification, verifiedAt, intake.verificationDetail());

        audit.record(intake.actorId(), "RIGHTS_REQUEST_RECEIVED", intake.entityId(),
                "rights_request", requestId,
                Map.of("type", intake.type().name(),
                        "jurisdiction", intake.jurisdiction().name(),
                        "dueAt", deadline.dueAt().toString(),
                        // On the audit row as well as the request row. The question an audit asks
                        // is "when did the clock start and why then", and the answer should be in
                        // the append-only trail rather than only on a table an operator can see.
                        "verification", verification.name(),
                        // The distinction the backdate bound was wrongly documented as enforcing.
                        // A request whose deadline has already passed at the moment it is filed is
                        // not the group missing a deadline — it is a late filing, and the two are
                        // answered very differently under Rule 14(3). Recorded here rather than as
                        // a column because it is derivable from receivedAt and dueAt, and a
                        // derived value stored twice is a value that can disagree with itself.
                        "bornOverdue", Boolean.toString(deadline.dueAt().isBefore(now))));

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
     * <p>Three rules, and they are checked in the order an operator would hit them. The request
     * must still be open; the move must be one {@link RightsRequestStatus#permittedNext()} allows;
     * and a terminal status requires a resolution. Refusing an erasure request is a legitimate
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
        if (!before.status().canMoveTo(status)) {
            // Named moves rather than a bare refusal. The operator is looking at a queue, not at
            // this enum, and "you cannot do that" without saying what you can do is how a console
            // ends up with a button that sometimes fails for reasons nobody can explain.
            throw new IllegalArgumentException("request " + requestId + " is " + before.status()
                    + " and cannot move to " + status + ". From " + before.status()
                    + " the permitted moves are " + before.status().permittedNext() + ".");
        }
        if (!status.isOpen() && (resolution == null || resolution.isBlank())) {
            throw new IllegalArgumentException(
                    "closing a request as " + status + " needs a resolution describing what was "
                            + "done and, where it was refused, on what ground");
        }
        if (status == RightsRequestStatus.FULFILLED) {
            requireFulfilmentEvidence(before);
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

    /**
     * Refuses {@code FULFILLED} while a system that had to act has not.
     *
     * <p>The gate this whole workstream exists for. Before it, {@code FULFILLED} was a sentence an
     * operator typed — and because the audit trail is append-only, a closure by somebody who had
     * done the work and a closure by somebody who had not were permanently indistinguishable on the
     * record. Intake and a clock are the easy half of DPDP ss.11-13; this is the half that makes
     * the answer to "what did you actually erase" something other than prose.
     *
     * <p><strong>An empty register blocks nothing, and that is deliberate rather than lax.</strong>
     * A platform that refused every closure until somebody configured a table would be a platform
     * that gets the table filled with placeholder rows on the first busy afternoon. The register is
     * a statement by UDS about which systems hold a principal's data; until it is made, the
     * platform cannot know it and will not pretend to. What it must not do is let that silence read
     * as fulfilment — hence the scope statement in {@code REGULATORY_HANDOFF.md} §8.5, which is the
     * other half of this control and needs a signature rather than code.
     *
     * <p>Only {@code COMPLETED} satisfies a target. A recorded {@code FAILED} attempt is evidence
     * that somebody tried and is precisely not evidence that it worked.
     */
    private void requireFulfilmentEvidence(RightsRequestStore.Request request) {
        List<String> outstanding = fulfilment.outstandingTargets(
                request.requestId(), request.entityId(), request.type().name());
        if (outstanding.isEmpty()) {
            return;
        }
        throw new FulfilmentIncompleteException(request.requestId(), outstanding);
    }

    /**
     * A request cannot be closed as fulfilled because a system that had to act has not.
     *
     * <p>Answered as 409 rather than 400: nothing about the request is malformed, and the caller is
     * not wrong to be trying. The state of the world is not yet what the closure would assert. The
     * outstanding systems are named on the response, because the operator's next question is
     * always "which one".
     */
    public static class FulfilmentIncompleteException extends RuntimeException {

        private final transient List<String> outstanding;

        FulfilmentIncompleteException(String requestId, List<String> outstanding) {
            super("rights request " + requestId + " cannot be closed as FULFILLED: "
                    + outstanding + " have not recorded a completed fulfilment action. Record what "
                    + "each system did — with a reference a reviewer can follow — or reconfigure "
                    + "the target if it no longer holds this principal's data.");
            this.outstanding = List.copyOf(outstanding);
        }

        public List<String> outstanding() {
            return outstanding;
        }
    }

    /**
     * Records what one downstream system did about a request.
     *
     * <p>The platform does not perform the act. It cannot: nothing here can reach DenCRM, the HRMS
     * or the BGV workflow, and a connector written against a system nobody on this side can call
     * would be worse than none because it would look like fulfilment. What this records is a named
     * person's attestation, against a named system, with a reference — which is the difference
     * between a manual SOP that is defensible and one that is the exposure.
     */
    @Transactional
    public long recordFulfilment(String requestId, String systemCode, String actionType,
                                 String status, String evidenceRef, String detail,
                                 String actorId) {
        RightsRequestStore.Request request = find(requestId);
        long actionId = fulfilment.recordAction(requestId, request.entityId(), systemCode,
                actionType, status, actorId, evidenceRef, detail, Instant.now());

        audit.record(actorId, "RIGHTS_FULFILMENT_" + status, request.entityId(),
                "rights_fulfilment_action", String.valueOf(actionId),
                Map.of("requestId", requestId, "systemCode", systemCode,
                        "actionType", actionType, "evidenceRef", evidenceRef));
        return actionId;
    }

    /** What each system did, for the console and for the evidence bundle. */
    public List<RightsFulfilmentStore.Action> fulfilmentActions(String requestId) {
        return fulfilment.actions(requestId);
    }

    /** Systems that still have to act before this request can be closed as fulfilled. */
    public List<String> outstandingFulfilment(String requestId) {
        RightsRequestStore.Request request = find(requestId);
        return fulfilment.outstandingTargets(requestId, request.entityId(),
                request.type().name());
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
        if (intake.identifierType() == null) {
            throw new IllegalArgumentException(
                    "a rights request needs either a subjectId or an identifier to attach to");
        }
        // Either the raw value, which this hashes, or a hash computed earlier. The portal takes
        // the second path: it never holds the raw identifier past the moment of submission, so by
        // the time a verified request is created there is nothing left to hash. Same pepper, same
        // hasher, same resulting subject — which is the point, since a portal request and a
        // console-filed one about the same person must land on the same subject.
        String hash = intake.identifierHash() != null && !intake.identifierHash().isBlank()
                ? intake.identifierHash()
                : requireValue(intake);
        return subjects.resolveOrCreate(intake.entityId(), intake.identifierType(), hash);
    }

    private String requireValue(Intake intake) {
        if (intake.identifierValue() == null) {
            throw new IllegalArgumentException(
                    "a rights request needs either a subjectId or an identifier to attach to");
        }
        return hasher.hash(intake.identifierType(), intake.identifierValue());
    }

    /**
     * @param identifierHash an identifier already hashed with the platform's pepper, for callers
     *                       that never held the raw value — see the portal
     * @param receivedAt when the principal actually asked, which may be earlier than when it was
     *                   keyed in. The clock runs from their act, not from the group's data entry —
     *                   bounded in both directions by {@code requireUsableStartInstant}
     * @param verification what that instant rests on. Null is read as
     *                   {@link RightsVerificationMethod#UNVERIFIED}, deliberately: the weaker
     *                   reading is the default, so silence is never mistaken for diligence
     * @param verifiedAt when identity was established. Defaulted to now for a verified method and
     *                   forced to null for an unverified one, so the pair cannot contradict itself
     */
    public record Intake(
            String entityId,
            String subjectId,
            IdentifierType identifierType,
            String identifierValue,
            String identifierHash,
            RightsRequestType type,
            Jurisdiction jurisdiction,
            Instant receivedAt,
            String details,
            String actorId,
            RightsVerificationMethod verification,
            Instant verifiedAt,
            String verificationDetail) {

        /** The console and API form, where the caller holds the identifier itself. */
        public Intake(String entityId, String subjectId, IdentifierType identifierType,
                      String identifierValue, RightsRequestType type, Jurisdiction jurisdiction,
                      Instant receivedAt, String details, String actorId,
                      RightsVerificationMethod verification, String verificationDetail) {
            this(entityId, subjectId, identifierType, identifierValue, null, type, jurisdiction,
                    receivedAt, details, actorId, verification, null, verificationDetail);
        }
    }
}
