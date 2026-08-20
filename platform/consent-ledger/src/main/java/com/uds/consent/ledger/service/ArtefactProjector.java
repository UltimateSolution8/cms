package com.uds.consent.ledger.service;

import com.uds.consent.core.model.ClockTolerance;
import com.uds.consent.core.model.ConsentArtefact;
import com.uds.consent.core.model.ConsentEvent;
import com.uds.consent.core.model.ConsentEventType;
import com.uds.consent.core.model.ConsentStatus;
import com.uds.consent.ledger.store.ConsentArtefactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Folds ledger events into current state, deciding what to do when they arrive out of order.
 *
 * <p>Out-of-order arrival is the normal case, not an edge case. A field device captures consent
 * in a basement with no signal and syncs it four hours later, by which time the subject may have
 * withdrawn on the web. Getting this wrong in the permissive direction means contacting someone
 * who has opted out; getting it wrong in the restrictive direction means silently dropping a
 * consent the subject did give.
 */
@Service
public class ArtefactProjector {

    private static final Logger log = LoggerFactory.getLogger(ArtefactProjector.class);

    /**
     * How far apart two events' wall-clock times must be before their order is believed.
     *
     * <p>Within this window the devices' clocks cannot be trusted to disagree meaningfully, so
     * an out-of-order restrictive event is treated as a genuine conflict rather than as stale.
     * Five minutes is chosen to comfortably exceed the drift seen on Android devices that have
     * been offline for a working day; it should be revisited with real telemetry from the Denave
     * pilot rather than left at a guess.
     *
     * <p>Held in {@link com.uds.consent.core.model.ClockTolerance} rather than here, because rights
     * intake needs the same window to decide whether a {@code receivedAt} in the future is a wrong
     * clock or a claim about the future — and two independently chosen tolerances would be two
     * definitions of "now" inside one evidence plane. The alias stays so that the rule reads where
     * it is applied.
     */
    static final Duration CLOCK_SKEW_TOLERANCE = ClockTolerance.SKEW;

    private final ConsentArtefactStore artefacts;

    public ArtefactProjector(ConsentArtefactStore artefacts) {
        this.artefacts = artefacts;
    }

    /**
     * Applies an event to the projection.
     *
     * <p>Ordering is decided on {@code occurredAt} — when the subject actually acted — with the
     * server-assigned sequence number as the tiebreak. The sequence number alone would be wrong
     * here: it reflects arrival at the server, so a late-syncing device would always appear to
     * have acted most recently.
     */
    public void apply(ConsentEvent event) {
        Optional<ConsentArtefact> currentOpt = artefacts.find(
                event.entityId(), event.subjectId(), event.purposeCode());

        if (currentOpt.isEmpty()) {
            artefacts.upsert(project(event, null), 0);
            return;
        }

        ConsentArtefact current = currentOpt.get();
        int conflicts = current.sequenceNumber() < 0 ? 0
                : artefacts.conflictCount(event.entityId(), event.subjectId(), event.purposeCode());

        Step step = step(event, current);
        if (step == null) {
            // Clearly older than what is already projected, and not ambiguous. The event stays in
            // the ledger as evidence of what the subject did; it just does not change current state.
            log.debug("ignoring stale event for projection: entity={} subject={} purpose={} at={}",
                    event.entityId(), event.subjectId(), event.purposeCode(), event.occurredAt());
            return;
        }
        if (step.conflict()) {
            log.warn("consent conflict: entity={} subject={} purpose={} incoming seq={} at={} "
                            + "vs projected seq={} at={}",
                    event.entityId(), event.subjectId(), event.purposeCode(),
                    event.sequenceNumber(), event.occurredAt(),
                    current.sequenceNumber(), current.lastEventAt());
        }
        artefacts.upsert(step.artefact(), conflicts + (step.conflict() ? 1 : 0));
    }

    /**
     * Re-derives an artefact from a chain, touching nothing.
     *
     * <p>The reconciliation sweep's fold. It exists so that "what does the chain imply" and "what
     * does the projector do" are <strong>the same code</strong> — {@link #step} decides both. A
     * second fold written for the sweep would drift from this one and then report divergence where
     * there is none, or, far worse, agree with itself in exactly the places where the real
     * projector is wrong. That is the whole risk of reconciling a projection, and reuse is the only
     * thing that answers it.
     *
     * @param chain the subject's events for one purpose, in ledger order
     * @return the artefact the chain implies, or empty for an empty chain
     */
    public static Optional<ConsentArtefact> replay(List<ConsentEvent> chain) {
        ConsentArtefact current = null;
        for (ConsentEvent event : chain) {
            if (current == null) {
                current = project(event, null);
                continue;
            }
            Step step = step(event, current);
            if (step != null) {
                current = step.artefact();
            }
        }
        return Optional.ofNullable(current);
    }

    /**
     * What one event does to an existing artefact, or null where it changes nothing.
     *
     * <p>Extracted from {@code apply} so the reconciliation sweep can ask the same question without
     * writing. Every branch and its reasoning is unchanged; only the logging and the store call
     * stayed behind.
     */
    private static Step step(ConsentEvent event, ConsentArtefact current) {
        if (event.type() == ConsentEventType.NOTICE_SERVED) {
            // Serving a notice is evidence that the person was TOLD. It is not evidence about what
            // they agreed to, and until Phase 18 the projection treated it as both.
            //
            // NOTICE_SERVED resolves to NOT_ASKED (ConsentEventType), and nothing here stopped that
            // reaching an artefact that already held a decision: supersedes() compares time only,
            // and isAmbiguous() is false once the skew window has passed. So a capture surface
            // re-serving a notice for a purpose the person had already granted set the artefact to
            // NOT_ASKED — and consent_artefact is what PolicyEngine reads, what ReceiptService
            // renders, and what the evidence bundle reports. The grant stayed in the ledger and
            // every hash still verified, which is exactly why nothing caught it.
            //
            // The damage was wider than the status. project() takes everything but grantedAt,
            // withdrawnAt and purposeVersion off the event, and recordNoticeServed writes
            // CaptureMethod.NOT_APPLICABLE, a null channel and a null expiresAt — so the overwrite
            // also destroyed how the consent was captured, through which channel, and when it
            // lapses. For a TRAI_TRANSACTIONAL_7D purpose that silently removed the seven-day
            // expiry the whole TCCCPR module exists to enforce.
            //
            // So: carry the artefact forward whole and update only what a notice establishes. Where
            // no artefact exists the behaviour is unchanged and a NOT_ASKED artefact is created —
            // that is the DPDP s.7(i) workforce path and the reason this route exists at all.
            //
            // It also cannot make the artefact CONFLICTED: isAmbiguous fires when a status
            // disagrees inside the skew window, and a notice asserts no status, so it disagrees
            // with nothing. A stale notice is still ignored — the update sits inside supersedes(),
            // the same ordering rule everything else here obeys.
            return supersedes(event, current) ? new Step(withNotice(current, event), false) : null;
        }

        if (supersedes(event, current)) {
            return new Step(project(event, current), false);
        }

        if (isAmbiguous(event, current)) {
            // Two surfaces disagree within the window where clocks cannot be trusted to order
            // them. Deny until a human resolves it: the ledger still holds both events, so
            // nothing is lost, and CONFLICTED is a denying status.
            return new Step(withStatus(current, ConsentStatus.CONFLICTED), true);
        }

        return null;
    }

    /** The outcome of one event: the artefact it produces, and whether it counts as a conflict. */
    private record Step(ConsentArtefact artefact, boolean conflict) { }

    /** Whether the incoming event is later than what is projected. */
    private static boolean supersedes(ConsentEvent event, ConsentArtefact current) {
        Instant currentAt = current.lastEventAt();
        if (currentAt == null) {
            return true;
        }
        int byTime = event.occurredAt().compareTo(currentAt);
        if (byTime != 0) {
            return byTime > 0;
        }
        return event.sequenceNumber() > current.sequenceNumber();
    }

    /**
     * Whether an out-of-order event genuinely conflicts, rather than merely being stale.
     *
     * <p>Only a disagreement about the outcome matters. A late GRANTED arriving behind another
     * GRANTED changes nothing and is simply ignored; a late WITHDRAWN arriving behind a GRANTED
     * within the skew window might be the subject's most recent wish, and the platform must not
     * quietly choose the permissive reading.
     */
    private static boolean isAmbiguous(ConsentEvent event, ConsentArtefact current) {
        if (event.resultingStatus() == current.status()) {
            return false;
        }
        Instant currentAt = current.lastEventAt();
        if (currentAt == null) {
            return false;
        }
        Duration gap = Duration.between(event.occurredAt(), currentAt).abs();
        return gap.compareTo(CLOCK_SKEW_TOLERANCE) <= 0;
    }

    /** Builds the new projected state from an event, carrying forward what the event omits. */
    private static ConsentArtefact project(ConsentEvent event, ConsentArtefact previous) {
        ConsentStatus status = event.resultingStatus();

        Instant grantedAt = switch (event.type()) {
            case GRANTED, MODIFIED -> event.occurredAt();
            default -> previous == null ? null : previous.grantedAt();
        };

        Instant withdrawnAt = event.type() == ConsentEventType.WITHDRAWN
                ? event.occurredAt()
                : (previous == null ? null : previous.withdrawnAt());

        // The version the principal agreed to, carried forward — not the one the registry holds
        // today. WITHDRAWN, EXPIRED and INVALIDATED end an agreement without restating it, so the
        // version on the artefact must stay the one that was agreed.
        //
        // Taking the event's version on those three would let a taxonomy change landing between
        // the grant and the withdrawal rewrite what the receipt says the principal consented to,
        // because ReceiptService loads the purpose definition at the artefact's version and
        // renders that text. The ledger would still hold the truth and every hash would still
        // verify, which is exactly why nothing would catch it.
        //
        // The three are enumerated rather than reached through `default`, and that is the whole
        // correction: a `default` branch also caught DENIED and NOTICE_SERVED, both of which DO
        // restate terms. Each stamps `purpose.version()` at write time — a refusal is a refusal of
        // the terms the person was actually shown, and re-serving a notice is the platform stating
        // the current ones. Carrying an older version onto either would put a version the person
        // was never shown onto their receipt, which is the same defect one event type over.
        //
        // EXPIRED and INVALIDATED are written with `purposeVersion = 0` (ExpirySweeper,
        // ConsentCaptureService.invalidate) — a sentinel meaning "no version asserted", which is
        // the second reason the carry-forward is right for them and would be wrong for the two
        // that assert one.
        int purposeVersion = switch (event.type()) {
            case WITHDRAWN, EXPIRED, INVALIDATED ->
                    previous == null ? event.purposeVersion() : previous.purposeVersion();
            case GRANTED, MODIFIED, DENIED, NOTICE_SERVED -> event.purposeVersion();
        };

        return new ConsentArtefact(
                event.entityId(),
                event.subjectId(),
                event.purposeCode(),
                purposeVersion,
                status,
                event.legalBasis(),
                event.noticeId(),
                event.noticeVersion(),
                event.languageTag(),
                event.captureMethod(),
                event.channel(),
                event.applicationId(),
                event.jurisdiction(),
                grantedAt,
                event.expiresAt(),
                withdrawnAt,
                event.occurredAt(),
                event.sequenceNumber(),
                event.eventHash());
    }

    /**
     * Records that a notice was served against an artefact that already exists.
     *
     * <p>Everything the principal decided is carried forward untouched — status, the version they
     * agreed to, legal basis, capture method, channel, application, jurisdiction, and the three
     * instants. What moves is what the notice actually establishes: which notice, in which version
     * and language, and the chain position that proves when.
     *
     * <p>Discarding the event's notice fields instead would leave a stale notice id on somebody who
     * has since been served a newer one — trading a false statement about consent for a false
     * statement about notice. Recording the telling and inferring nothing about agreement is the
     * only reading that asserts nothing untrue.
     */
    private static ConsentArtefact withNotice(ConsentArtefact artefact, ConsentEvent event) {
        return new ConsentArtefact(artefact.entityId(), artefact.subjectId(), artefact.purposeCode(),
                artefact.purposeVersion(), artefact.status(), artefact.legalBasis(),
                event.noticeId(), event.noticeVersion(), event.languageTag(),
                artefact.captureMethod(), artefact.channel(), artefact.applicationId(),
                artefact.jurisdiction(), artefact.grantedAt(), artefact.expiresAt(),
                artefact.withdrawnAt(), event.occurredAt(), event.sequenceNumber(),
                event.eventHash());
    }

    private static ConsentArtefact withStatus(ConsentArtefact artefact, ConsentStatus status) {
        return new ConsentArtefact(artefact.entityId(), artefact.subjectId(), artefact.purposeCode(),
                artefact.purposeVersion(), status, artefact.legalBasis(), artefact.noticeId(),
                artefact.noticeVersion(), artefact.languageTag(), artefact.captureMethod(),
                artefact.channel(), artefact.applicationId(), artefact.jurisdiction(),
                artefact.grantedAt(), artefact.expiresAt(), artefact.withdrawnAt(),
                artefact.lastEventAt(), artefact.sequenceNumber(), artefact.lastEventHash());
    }
}
