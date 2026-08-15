package com.uds.consent.core.snapshot;

import com.uds.consent.core.decision.DecisionRequest;
import com.uds.consent.core.decision.DecisionResponse;
import com.uds.consent.core.decision.DenialReason;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.ConsentStatus;
import com.uds.consent.core.model.FailureBehavior;

import java.time.Instant;
import java.util.List;

/**
 * Decides from a verified snapshot, with no network call and no allocation beyond the response.
 *
 * <p>This is the hot path on a field device and the target is a p95 under one millisecond. It is
 * deliberately a plain function over an immutable snapshot: no I/O, no logging, no clock read. The
 * evaluation instant comes from the request so that a decision can be replayed exactly during an
 * audit.
 *
 * <p>The server-side policy engine and this evaluator must agree. The golden decision suite runs
 * the same cases through both, because an offline device quietly reaching a different answer from
 * the server is the failure mode that would be hardest to detect in production.
 */
public final class LocalConsentEvaluator {

    private final ConsentSnapshot snapshot;

    public LocalConsentEvaluator(ConsentSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    /**
     * Evaluates a request against the snapshot.
     *
     * <p>Note that this answers only the consent question. The SDK must combine it with the
     * operating system's own permission state — an Android runtime grant, or Apple's App Tracking
     * Transparency status — and with the application's authorisation. Capability is the
     * conjunction of all three: a subject's consent to location processing means nothing if the
     * user has denied the OS location permission, and an OS grant is not consent.
     */
    public DecisionResponse evaluate(DecisionRequest request) {
        Instant at = request.at();
        PurposeState state = snapshot.purpose(request.purposeCode());

        if (state == null) {
            // The snapshot predates this purpose, or the purpose is not in scope for this subject.
            return deny(request, DenialReason.NO_CONSENT_RECORD,
                    "purpose not present in snapshot", 0);
        }

        // The gates below run in the same order as the server's policy engine — channel, then
        // suppression, then the consent record, then staleness. Matching the order matters as
        // much as matching the outcome: when a device and the server disagree about *why*
        // something was denied, reconciling the two logs during an investigation becomes guesswork.
        if (!state.channels().isEmpty() && request.channel() != null
                && !state.channels().contains(request.channel().name())) {
            return deny(request, DenialReason.CHANNEL_NOT_PERMITTED,
                    "purpose does not permit channel " + request.channel(), state.purposeVersion());
        }

        if (state.suppressed() && isCommercial(request.channel())) {
            return deny(request, DenialReason.SUPPRESSED_STATUTORY,
                    "subject suppressed for this channel", state.purposeVersion());
        }

        // Consent state is evaluated before snapshot staleness, deliberately. An expired or
        // withdrawn consent stays expired or withdrawn however old the snapshot is, and checking
        // it first does two things: it gives the accurate reason code rather than a generic
        // failure, and it stops a fail-open purpose permitting processing on the strength of a
        // consent that had already run out when the snapshot was issued.
        ConsentStatus status = effectiveStatus(state, at);
        if (status != ConsentStatus.GRANTED) {
            return deny(request, reasonFor(status), "consent status is " + status,
                    state.purposeVersion());
        }

        if (snapshot.isStaleAt(at)) {
            // Consent was live when this was issued, but the snapshot has aged past the point
            // where that can be relied on — the subject may have withdrawn since. Honour the
            // purpose's declared behaviour rather than guessing, and never extend its life.
            if (state.failureBehavior() == FailureBehavior.FAIL_OPEN) {
                return allow(request, state, "snapshot stale; purpose fails open",
                        List.of("refresh-snapshot"));
            }
            return deny(request, DenialReason.FAIL_CLOSED_DEFAULT,
                    "snapshot stale and purpose fails closed", state.purposeVersion());
        }

        return allow(request, state, "permitted", List.of());
    }

    private static ConsentStatus effectiveStatus(PurposeState state, Instant at) {
        if (state.status() == ConsentStatus.GRANTED
                && state.expiresAt() != null && !at.isBefore(state.expiresAt())) {
            // Expiry is evaluated locally rather than waiting for the server's sweeper. A TRAI
            // transactional consent that lapsed an hour ago is gone, whether or not the device
            // has spoken to the server since.
            return ConsentStatus.EXPIRED;
        }
        return state.status();
    }

    private static boolean isCommercial(Channel channel) {
        return channel != null && channel.isCommercialCommunication();
    }

    private static DenialReason reasonFor(ConsentStatus status) {
        return switch (status) {
            case WITHDRAWN -> DenialReason.CONSENT_WITHDRAWN;
            case EXPIRED -> DenialReason.CONSENT_EXPIRED;
            case DENIED -> DenialReason.CONSENT_DENIED;
            case INVALIDATED -> DenialReason.CONSENT_INVALIDATED;
            case CONFLICTED -> DenialReason.CONSENT_CONFLICTED;
            case PENDING_SYNC -> DenialReason.CONSENT_PENDING_SYNC;
            case NOT_ASKED -> DenialReason.NO_CONSENT_RECORD;
            case UNKNOWN -> DenialReason.FAIL_CLOSED_DEFAULT;
            case GRANTED -> DenialReason.NONE;
        };
    }

    private DecisionResponse allow(DecisionRequest request, PurposeState state, String explanation,
                                   List<String> obligations) {
        return new DecisionResponse(com.uds.consent.core.decision.DecisionOutcome.ALLOW,
                DenialReason.NONE, explanation, state.legalBasis(), request.purposeCode(),
                state.purposeVersion(), snapshot.policyVersion(), request.at(), state.expiresAt(),
                obligations, true);
    }

    private DecisionResponse deny(DecisionRequest request, DenialReason reason, String explanation,
                                  int purposeVersion) {
        return new DecisionResponse(com.uds.consent.core.decision.DecisionOutcome.DENY, reason,
                explanation, null, request.purposeCode(), purposeVersion,
                snapshot.policyVersion(), request.at(), null, List.of(), true);
    }
}
