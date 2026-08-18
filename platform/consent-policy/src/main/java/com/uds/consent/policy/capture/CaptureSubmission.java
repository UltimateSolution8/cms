package com.uds.consent.policy.capture;

import com.uds.consent.core.model.ActorType;
import com.uds.consent.core.model.CaptureMethod;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.GuardianVerification;
import com.uds.consent.core.model.Jurisdiction;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What a capture surface says happened, before anything is written to the ledger.
 *
 * <p>The shape of this record is doing real work. It asks surfaces to declare things they would
 * otherwise leave implicit — whether an option was pre-ticked, whether refusing was offered as
 * plainly as accepting, whether each purpose was actioned separately — because those are exactly
 * the properties that decide whether the resulting consent is valid, and they cannot be recovered
 * after the fact from a row that says {@code marketing = true}.
 *
 * @param entityId       UDS entity capturing the consent
 * @param subjectId      resolved subject reference
 * @param jurisdiction   jurisdiction governing this capture
 * @param languageTag    BCP 47 tag of the language the notice was actually rendered in
 * @param channel        medium of capture
 * @param applicationId  surface capturing it
 * @param captureMethod  what the subject did
 * @param actorType      who acted — the subject, a guardian, or an agent on their behalf
 * @param actorId        attributable identity of the actor
 * @param noticeId       notice served
 * @param noticeVersion  exact version rendered
 * @param choices        one entry per purpose; never a single bundled answer
 * @param rejectAllOffered whether refusing everything was available in the same interaction, and
 *                       took no more effort than accepting
 * @param occurredAt     when the subject acted, which on an offline device is not when this
 *                       submission arrives
 * @param idempotencyKey makes replay from a retrying field device safe
 * @param evidenceRef    pointer to the recording, signed form or DOM snapshot
 * @param attributes     extra context, e.g. the contract end date for inferred consent
 * @param guardianVerification
 *                       the due diligence performed on the parent or lawful guardian, where the
 *                       subject is a child. Null for the ordinary adult capture, which is why it
 *                       is nullable rather than required — but null on a child capture is a
 *                       violation, not a default. See {@code DpdpModule.validateCapture}
 */
public record CaptureSubmission(
        String entityId,
        String subjectId,
        Jurisdiction jurisdiction,
        String languageTag,
        Channel channel,
        String applicationId,
        CaptureMethod captureMethod,
        ActorType actorType,
        String actorId,
        String noticeId,
        Integer noticeVersion,
        List<PurposeChoice> choices,
        boolean rejectAllOffered,
        Instant occurredAt,
        String idempotencyKey,
        String evidenceRef,
        Map<String, String> attributes,
        GuardianVerification guardianVerification) {

    /** Attribute a capture surface sets to declare the subject is under eighteen (DPDP s.9). */
    public static final String ATTR_IS_CHILD = "subject.isChild";

    public CaptureSubmission {
        choices = choices == null ? List.of() : List.copyOf(choices);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /**
     * The shape every surface used before guardian verification was recorded.
     *
     * <p>Kept so that adding the field did not require touching two dozen call sites that describe
     * adult captures and have nothing to say about a guardian. A submission built this way carries
     * no verification block, which is correct for an adult and a violation for a child — and the
     * validator, not this constructor, is where that distinction belongs.
     */
    public CaptureSubmission(String entityId, String subjectId, Jurisdiction jurisdiction,
                             String languageTag, Channel channel, String applicationId,
                             CaptureMethod captureMethod, ActorType actorType, String actorId,
                             String noticeId, Integer noticeVersion, List<PurposeChoice> choices,
                             boolean rejectAllOffered, Instant occurredAt, String idempotencyKey,
                             String evidenceRef, Map<String, String> attributes) {
        this(entityId, subjectId, jurisdiction, languageTag, channel, applicationId, captureMethod,
                actorType, actorId, noticeId, noticeVersion, choices, rejectAllOffered, occurredAt,
                idempotencyKey, evidenceRef, attributes, null);
    }

    /**
     * Whether the surface declared the subject to be under eighteen.
     *
     * <p>A declaration, not a finding. The platform has no way to determine anyone's age on its
     * own; what it can do is refuse to accept the declaration without the diligence that DPDP
     * Rule 10 attaches to it.
     */
    public boolean isChildSubject() {
        return "true".equalsIgnoreCase(attributes.get(ATTR_IS_CHILD));
    }

    /** Purposes the subject said yes to. */
    public List<PurposeChoice> granted() {
        return choices.stream().filter(PurposeChoice::granted).toList();
    }

    /**
     * One purpose's answer.
     *
     * @param purposeCode    registry code
     * @param granted        whether the subject agreed
     * @param preTicked      whether the control arrived already selected. DPDP Rule 8 prohibits
     *                       this outright; the field exists so that a surface which does it is
     *                       rejected loudly rather than producing a record that looks clean
     * @param separateAction whether this purpose was actioned on its own, rather than swept up in
     *                       a single "I agree". Korea's PIPA makes bundled consent invalid
     */
    public record PurposeChoice(String purposeCode, boolean granted, boolean preTicked,
                                boolean separateAction) {

        /** A purpose accepted by its own affirmative action, with nothing pre-selected. */
        public static PurposeChoice acceptedSeparately(String purposeCode) {
            return new PurposeChoice(purposeCode, true, false, true);
        }

        /** A purpose the subject declined. */
        public static PurposeChoice declined(String purposeCode) {
            return new PurposeChoice(purposeCode, false, false, true);
        }
    }
}
