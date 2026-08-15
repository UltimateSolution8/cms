package com.uds.consent.core.decision;

import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.Jurisdiction;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A question put to the enforcement plane: may this entity process this subject's data for this
 * purpose, over this channel, right now.
 *
 * <p>One contract, one endpoint. The alternative — every team writing its own version of "can I
 * contact this person" — is how a group ends up with five subtly different answers and no way to
 * tell a regulator which one applied.
 *
 * @param entityId      the UDS entity that would process
 * @param subjectId     privacy-minimal subject reference
 * @param purposeCode   registry code being requested
 * @param channel       medium the processing would use
 * @param jurisdiction  jurisdiction governing this subject; drives which policy module applies
 * @param applicationId calling application, checked against the application registry
 * @param at            evaluation instant; injected rather than read from the clock so that
 *                      decisions are reproducible in tests and in audit replay
 * @param clientId      client on whose behalf the work is done, for client-scoped suppression
 * @param campaignId    campaign, for campaign-scoped suppression
 * @param vendorId      processor that would receive the data, if any
 * @param context       additional evaluation context, e.g. subject age band
 */
public record DecisionRequest(
        String entityId,
        String subjectId,
        String purposeCode,
        Channel channel,
        Jurisdiction jurisdiction,
        String applicationId,
        Instant at,
        String clientId,
        String campaignId,
        String vendorId,
        Map<String, String> context) {

    public DecisionRequest {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(purposeCode, "purposeCode");
        Objects.requireNonNull(at, "at");
        jurisdiction = jurisdiction == null ? Jurisdiction.IN : jurisdiction;
        context = context == null ? Map.of() : Map.copyOf(context);
    }

    /** Convenience constructor for the common case: no client, campaign or vendor scoping. */
    public static DecisionRequest of(String entityId, String subjectId, String purposeCode,
                                     Channel channel, Jurisdiction jurisdiction, Instant at) {
        return new DecisionRequest(entityId, subjectId, purposeCode, channel, jurisdiction, null,
                at, null, null, null, Map.of());
    }

    /** Marker used by capture surfaces to declare a subject is under eighteen (DPDP s.9). */
    public boolean isChildSubject() {
        return "true".equalsIgnoreCase(context.get("subject.isChild"));
    }
}
