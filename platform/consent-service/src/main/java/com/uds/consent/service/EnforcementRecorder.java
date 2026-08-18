package com.uds.consent.service;

import com.uds.consent.core.decision.DecisionRequest;
import com.uds.consent.core.decision.DecisionResponse;
import com.uds.consent.core.decision.DenialReason;
import com.uds.consent.ledger.store.EnforcementEvidenceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes the evidence that a decision was taken, without ever being able to break the decision.
 *
 * <p>This class exists for one design decision, and the decision is the whole point of it:
 * <strong>a failure to record evidence must never turn an allowance into an error.</strong> If the
 * evidence table is full, or its tablespace is unavailable, or a constraint has been added that
 * this code does not satisfy, the alternative behaviour — propagate and fail the request — would
 * stop the dialer, stop the CRM export and stop lawful processing across the group, in order to
 * protect a log. That trade is wrong in both directions: it makes an availability incident out of
 * a logging fault, and it teaches whoever is paged to disable the recorder, which loses the
 * evidence permanently rather than for an afternoon.
 *
 * <p>So every write here is swallowed, logged at ERROR, and counted. The counter is the part that
 * matters — silent best-effort is indistinguishable from a recorder that was never wired up, and
 * {@link #failedWrites()} is what the health indicator and the alert read. Wire that alert, or
 * this class is a way of not knowing.
 */
@Service
public class EnforcementRecorder {

    private static final Logger log = LoggerFactory.getLogger(EnforcementRecorder.class);

    private final EnforcementEvidenceStore store;
    private final String policyVersion;
    private final AtomicLong failedWrites = new AtomicLong();
    private final AtomicLong recordedDenials = new AtomicLong();

    public EnforcementRecorder(EnforcementEvidenceStore store,
                               com.uds.consent.service.config.PlatformProperties properties) {
        this.store = store;
        // For the rows this class writes that are not the answer to a decision request and so carry
        // no policy version of their own. A refusal is still taken under a policy bundle, and an
        // investigator reproducing it in five years needs to know which.
        this.policyVersion = properties.getPolicyVersion();
    }

    /**
     * Records a decision if it was a denial.
     *
     * <p>Allowances return immediately. They are counted in aggregate on the scrub run rather than
     * enumerated here — see {@code V8__enforcement_evidence.sql} for why a hundred thousand rows a
     * day proving nothing happened is a worse outcome than not having them.
     */
    public void record(DecisionRequest request, DecisionResponse decision) {
        if (decision.isAllowed()) {
            return;
        }
        try {
            store.recordDenial(new EnforcementEvidenceStore.Denial(
                    request.entityId(),
                    request.subjectId(),
                    decision.purposeCode(),
                    decision.purposeVersion() == 0 ? null : decision.purposeVersion(),
                    request.channel() == null ? null : request.channel().name(),
                    request.jurisdiction().name(),
                    decision.outcome().name(),
                    decision.reason().name(),
                    decision.explanation(),
                    request.applicationId(),
                    request.vendorId(),
                    request.clientId(),
                    request.campaignId(),
                    decision.policyVersion(),
                    decision.evaluatedAt(),
                    null));
            recordedDenials.incrementAndGet();
        } catch (RuntimeException e) {
            failedWrites.incrementAndGet();
            // Deliberately without the subject id. This line goes to the application log, which is
            // aggregated, searchable and read by more people than the evidence table is.
            log.error("failed to record enforcement denial for entity={} purpose={} reason={}: {}",
                    request.entityId(), decision.purposeCode(), decision.reason(), e.toString(), e);
        }
    }

    /** Records every denial in a batch, one row each. Never throws, for the same reason. */
    public void recordAll(java.util.List<DecisionRequest> requests,
                          java.util.List<DecisionResponse> decisions) {
        for (int i = 0; i < requests.size() && i < decisions.size(); i++) {
            record(requests.get(i), decisions.get(i));
        }
    }

    /**
     * Records that a campaign list was screened.
     *
     * <p>The counts, not the identifiers. What TRAI asks is whether the check was run over the
     * population before it was used; storing the numbers themselves would rebuild the contact list
     * inside the evidence plane, which is the thing the whole hashing design exists to avoid.
     */
    public void recordScrub(String entityId, String channel, String clientId, String campaignId,
                            String actorId, int submitted,
                            SuppressionService.ScrubResult result) {
        try {
            Map<String, Integer> reasons = new HashMap<>();
            for (SuppressionService.Excluded excluded : result.excluded()) {
                reasons.merge(excluded.reason(), 1, Integer::sum);
            }
            store.recordScrubRun(new EnforcementEvidenceStore.ScrubRun(entityId, channel, clientId,
                    campaignId, actorId, submitted, result.permittedCount(), result.excludedCount(),
                    reasons));
        } catch (RuntimeException e) {
            failedWrites.incrementAndGet();
            log.error("failed to record scrub run for entity={} campaign={}: {}",
                    entityId, campaignId, e.toString(), e);
        }
    }

    /**
     * Records a refused inbound relay from something claiming to be a Consent Manager.
     *
     * <p>Not a decision about a purpose, so it carries none — what is being refused is the caller,
     * before any question about the principal has been asked. It lands in the same table as a
     * denial because the question an investigator puts is the same one ("what did the platform
     * refuse, and when"), and splitting it across two tables would mean the answer depended on
     * knowing to look in both.
     *
     * <p>Never throws, for the reason the rest of this class never throws: a refusal that failed to
     * be recorded is still a refusal, and turning it into a 500 would tell the caller that
     * something other than "no" happened.
     *
     * @param reason which refusal this was. Passed in rather than fixed, because the two cases are
     *               different incidents: a caller nobody has heard of, and a caller the platform has
     *               heard of naming somebody else's registration. The caller is told neither — all
     *               of them answer the same opaque 403 — so this column is the only place the
     *               distinction survives, and it is the column an investigator groups by
     */
    // In its own transaction, and this is the whole reason the annotation is here. The refusal is
    // raised from inside the relay's transaction and then aborts it — so a row written on the
    // ambient transaction would be rolled back along with the write it refused, leaving a platform
    // that refuses correctly and can prove none of it. The evidence of a refusal has to outlive the
    // thing being refused.
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void recordConsentManagerRefusal(String entityId, String registrationId,
                                            DenialReason reason, String explanation, Instant at) {
        try {
            store.recordDenial(new EnforcementEvidenceStore.Denial(
                    entityId, null, null, null, null, null,
                    "DENY",
                    reason.name(),
                    explanation,
                    null, null,
                    // The claimed registration goes in the client id, which is where every other
                    // row in this table carries "who was asking".
                    registrationId,
                    null, policyVersion, at, null));
            recordedDenials.incrementAndGet();
        } catch (RuntimeException e) {
            failedWrites.incrementAndGet();
            log.error("failed to record consent-manager refusal for entity={} registration={}: {}",
                    entityId, registrationId, e.toString(), e);
        }
    }

    /**
     * Evidence writes that failed since start-up.
     *
     * <p>Anything above zero means the platform is currently unable to prove decisions it is
     * taking. That is a compliance incident on a delay rather than an outage, which is exactly the
     * kind of problem that goes unnoticed without a number attached to it.
     */
    public long failedWrites() {
        return failedWrites.get();
    }

    public long recordedDenials() {
        return recordedDenials.get();
    }
}
