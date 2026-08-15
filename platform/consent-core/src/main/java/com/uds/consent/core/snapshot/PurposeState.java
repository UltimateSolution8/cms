package com.uds.consent.core.snapshot;

import com.uds.consent.core.model.ConsentStatus;
import com.uds.consent.core.model.FailureBehavior;
import com.uds.consent.core.model.LegalBasis;

import java.time.Instant;
import java.util.Set;

/**
 * One purpose's standing within a snapshot: everything a device needs to decide locally, and
 * nothing more.
 *
 * @param status          state at the moment the snapshot was issued
 * @param legalBasis      basis relied on
 * @param purposeVersion  version the subject consented against
 * @param expiresAt       when consent lapses, or {@code null} if it does not
 * @param failureBehavior what to do if this snapshot is stale and the server is unreachable
 * @param channels        channels the purpose permits; empty means all
 * @param suppressed      subject is on a suppression list for this purpose's channels
 */
public record PurposeState(
        ConsentStatus status,
        LegalBasis legalBasis,
        int purposeVersion,
        Instant expiresAt,
        FailureBehavior failureBehavior,
        Set<String> channels,
        boolean suppressed) {

    public PurposeState {
        channels = channels == null ? Set.of() : Set.copyOf(channels);
        failureBehavior = failureBehavior == null ? FailureBehavior.FAIL_CLOSED : failureBehavior;
    }
}
