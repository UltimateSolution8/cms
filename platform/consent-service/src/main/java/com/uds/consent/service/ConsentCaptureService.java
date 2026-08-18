package com.uds.consent.service;

import com.uds.consent.core.model.ActorType;
import com.uds.consent.core.model.CaptureMethod;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.ConsentEvent;
import com.uds.consent.core.model.ConsentEventType;
import com.uds.consent.core.model.ExpiryPolicy;
import com.uds.consent.core.model.GuardianVerification;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.LegalBasis;
import com.uds.consent.core.model.PurposeDefinition;
import com.uds.consent.ledger.service.ConsentLedger;
import com.uds.consent.ledger.store.ConsentArtefactStore;
import com.uds.consent.ledger.store.SubjectStore;
import com.uds.consent.policy.capture.CaptureSubmission;
import com.uds.consent.policy.capture.CaptureValidator;
import com.uds.consent.policy.capture.CaptureViolation;
import com.uds.consent.policy.port.PolicyPorts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns what a subject did into events in the ledger.
 *
 * <p>Every capture surface in the group — the website banner, a field app, an agent's screen, a
 * kiosk, a bulk load — comes through here. That is the point of a single ingestion contract: the
 * rules about what makes consent valid are applied once, and a new surface cannot accidentally
 * bring its own interpretation.
 */
@Service
public class ConsentCaptureService {

    private static final Logger log = LoggerFactory.getLogger(ConsentCaptureService.class);

    /** Attribute a surface sets to carry the end date of the relationship behind inferred consent. */
    public static final String ATTR_CONTRACT_END_DATE = "contract.endDate";

    private final CaptureValidator validator;
    private final PolicyPorts.PurposeCatalog purposes;
    private final ConsentLedger ledger;
    private final PlatformMetrics metrics;
    private final SubjectStore subjects;
    private final ConsentArtefactStore artefacts;

    public ConsentCaptureService(CaptureValidator validator, PolicyPorts.PurposeCatalog purposes,
                                 ConsentLedger ledger, PlatformMetrics metrics,
                                 SubjectStore subjects, ConsentArtefactStore artefacts) {
        this.validator = validator;
        this.purposes = purposes;
        this.ledger = ledger;
        this.metrics = metrics;
        this.subjects = subjects;
        this.artefacts = artefacts;
    }

    /**
     * Records a submission.
     *
     * <p>All or nothing. A submission covering four purposes either writes four events or writes
     * none: a partial write would leave the subject having agreed to some things and, as far as
     * the ledger is concerned, never having been asked about the rest.
     */
    @Transactional
    public Result capture(CaptureSubmission submission) {
        long start = System.nanoTime();
        List<CaptureViolation> violations = validator.validate(submission);
        metrics.capture(System.nanoTime() - start, violations);
        if (!violations.isEmpty()) {
            log.warn("rejected consent capture from application={} entity={} with {} violation(s)",
                    submission.applicationId(), submission.entityId(), violations.size());
            return Result.rejected(violations);
        }

        recordAgeAssertion(submission);
        Map<String, String> attributes = withGuardianVerification(submission);

        List<ConsentEvent> written = new ArrayList<>();
        for (CaptureSubmission.PurposeChoice choice : submission.choices()) {
            PurposeDefinition purpose = purposes.find(choice.purposeCode()).orElseThrow();
            LegalBasis basis = purpose.legalBasisFor(submission.jurisdiction());

            ConsentEventType type = choice.granted()
                    ? ConsentEventType.GRANTED
                    : ConsentEventType.DENIED;

            Instant expiresAt = choice.granted()
                    ? expiryFor(purpose, submission)
                    : null;

            written.add(ledger.record(new ConsentEvent(
                    UUID.randomUUID().toString(),
                    submission.entityId(),
                    submission.subjectId(),
                    purpose.code(),
                    purpose.version(),
                    type,
                    basis,
                    submission.noticeId(),
                    submission.noticeVersion(),
                    submission.languageTag(),
                    submission.captureMethod(),
                    submission.channel(),
                    submission.applicationId(),
                    submission.jurisdiction(),
                    submission.occurredAt(),
                    null,
                    expiresAt,
                    submission.actorType(),
                    submission.actorId(),
                    null,
                    submission.evidenceRef(),
                    // One submission becomes several events, so the caller's key is qualified per
                    // purpose. Without this, the second event in a submission would collide with
                    // the first on the ledger's uniqueness constraint.
                    perPurposeKey(submission.idempotencyKey(), purpose.code()),
                    attributes,
                    0L, null, null)));
        }

        return Result.accepted(written);
    }

    /**
     * Records a withdrawal.
     *
     * <p>Note what this does <em>not</em> do: it does not add a channel suppression. Withdrawing
     * consent to promotional email is not the same as asking never to be emailed, and treating it
     * that way would silently block transactional messages the subject still expects. A
     * channel-level opt-out is a separate, explicit act — see {@code SuppressionService}.
     */
    @Transactional
    public List<ConsentEvent> withdraw(String entityId, String subjectId, List<String> purposeCodes,
                                       Channel channel, String applicationId, ActorType actorType,
                                       String actorId, Jurisdiction jurisdiction, Instant occurredAt,
                                       String idempotencyKey, String reason) {
        List<ConsentEvent> written = new ArrayList<>();
        for (String purposeCode : purposeCodes) {
            Optional<PurposeDefinition> purpose = purposes.find(purposeCode);
            written.add(ledger.record(new ConsentEvent(
                    UUID.randomUUID().toString(),
                    entityId,
                    subjectId,
                    purposeCode,
                    withdrawnVersion(entityId, subjectId, purposeCode, purpose),
                    ConsentEventType.WITHDRAWN,
                    purpose.map(p -> p.legalBasisFor(jurisdiction)).orElse(null),
                    null, null, null,
                    CaptureMethod.NOT_APPLICABLE,
                    channel,
                    applicationId,
                    jurisdiction,
                    occurredAt,
                    null,
                    null,
                    actorType,
                    actorId,
                    reason,
                    null,
                    perPurposeKey(idempotencyKey, purposeCode),
                    Map.of(),
                    0L, null, null)));
        }
        return written;
    }

    /**
     * Marks consent as no longer relied upon.
     *
     * <p>Used when a notice or purpose changes materially and the blast-radius calculation says
     * standing consent no longer covers what will be done, and when provenance for an imported
     * record cannot be substantiated. Always carries a reason and an actor: this is the
     * fiduciary striking down its own permission, and an auditor will ask who decided that.
     */
    @Transactional
    public ConsentEvent invalidate(String entityId, String subjectId, String purposeCode,
                                   int purposeVersion, ActorType actorType, String actorId,
                                   String reason, Instant occurredAt) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("invalidation requires a reason for the audit trail");
        }
        return ledger.record(new ConsentEvent(
                UUID.randomUUID().toString(), entityId, subjectId, purposeCode, purposeVersion,
                ConsentEventType.INVALIDATED, null, null, null, null, CaptureMethod.NOT_APPLICABLE,
                null, null, null, occurredAt, null, null, actorType, actorId, reason, null,
                null, Map.of(), 0L, null, null));
    }

    /**
     * Records that a notice was served without consent being sought.
     *
     * <p>The evidence trail for a legitimate use under s.7(i). Roughly 76,000 workforce records
     * need this rather than a consent record — and without it the group would have no way to show
     * that people were told, which is the obligation that actually applies to them.
     */
    @Transactional
    public ConsentEvent recordNoticeServed(String entityId, String subjectId, String purposeCode,
                                           String noticeId, int noticeVersion, String languageTag,
                                           Jurisdiction jurisdiction, String applicationId,
                                           Instant occurredAt, String idempotencyKey) {
        PurposeDefinition purpose = purposes.find(purposeCode)
                .orElseThrow(() -> new IllegalArgumentException("unknown purpose: " + purposeCode));
        return ledger.record(new ConsentEvent(
                UUID.randomUUID().toString(), entityId, subjectId, purposeCode, purpose.version(),
                ConsentEventType.NOTICE_SERVED, purpose.legalBasisFor(jurisdiction), noticeId,
                noticeVersion, languageTag, CaptureMethod.NOT_APPLICABLE, null, applicationId,
                jurisdiction, occurredAt, null, null, ActorType.SYSTEM, "notice-service", null,
                null, perPurposeKey(idempotencyKey, purposeCode), Map.of(), 0L, null, null));
    }

    /**
     * When consent for this purpose lapses, or {@code null} if it does not.
     *
     * <p>Expiry is computed from when the subject acted, not from when the event reached the
     * server. A transactional consent given on a field device at nine in the morning and synced
     * at five in the afternoon expires seven days from nine in the morning.
     */
    private Instant expiryFor(PurposeDefinition purpose, CaptureSubmission submission) {
        Instant from = submission.occurredAt();
        return switch (purpose.expiryPolicy()) {
            case NONE -> null;
            case TRAI_TRANSACTIONAL_7D -> from.plus(Duration.ofDays(7));
            case FIXED_DAYS -> purpose.expiryDays() == null
                    ? null
                    : from.plus(Duration.ofDays(purpose.expiryDays()));
            case CONTRACT_LIFETIME -> contractEnd(submission).orElseGet(() -> {
                // Inferred consent with no end date recorded would never expire, which is exactly
                // what TRAI's contract-lifetime rule forbids. Logged loudly rather than silently
                // producing perpetual consent; the capture surface has a gap to fix.
                log.warn("purpose {} uses CONTRACT_LIFETIME expiry but submission from "
                                + "application={} carried no {} attribute; consent recorded with no "
                                + "expiry and must be reviewed",
                        purpose.code(), submission.applicationId(), ATTR_CONTRACT_END_DATE);
                return null;
            });
        };
    }

    /**
     * Folds the guardian verification into the event attributes.
     *
     * <p>Attributes are inside {@code hashableBody()} and therefore inside the canonical payload
     * and the hash chain. That is the whole reason this goes here rather than into a side table:
     * the record of the diligence performed on a guardian cannot be altered afterwards without
     * breaking the chain from that event forward, which is a stronger guarantee than any table of
     * its own could offer and costs no schema at all.
     *
     * <p>Only the hashed reference travels. The raw account id or token never reaches this service
     * — {@code ConsentController} hashes it at the edge — so there is nothing here to leak into a
     * payload that is, by design, kept forever.
     */
    private static Map<String, String> withGuardianVerification(CaptureSubmission submission) {
        GuardianVerification verification = submission.guardianVerification();
        if (verification == null) {
            return submission.attributes();
        }
        Map<String, String> attributes = new LinkedHashMap<>(submission.attributes());
        attributes.put(GuardianVerification.ATTR_METHOD, verification.method().name());
        attributes.put(GuardianVerification.ATTR_REFERENCE, verification.referenceHash());
        attributes.put(GuardianVerification.ATTR_VERIFIED_AT, verification.verifiedAt().toString());
        if (verification.verifiedBy() != null) {
            attributes.put(GuardianVerification.ATTR_VERIFIED_BY, verification.verifiedBy());
        }
        return Map.copyOf(attributes);
    }

    /**
     * Writes the minority assertion that came with this submission, if one did.
     *
     * <p>Only when the surface declares a child. A submission that says nothing about age is not
     * asserting the subject is an adult, and recording {@code is_child = false} for every adult
     * capture would fill the table with assertions nobody made — which would destroy the one thing
     * it is for, namely being able to say who told us what, and when.
     */
    private void recordAgeAssertion(CaptureSubmission submission) {
        if (!submission.isChildSubject()) {
            return;
        }
        subjects.assertAge(submission.entityId(), submission.subjectId(), true,
                "capture:" + submission.applicationId(), submission.occurredAt(),
                submission.actorType() == null ? null : submission.actorType().name(),
                submission.actorId(),
                submission.guardianVerification() == null ? null
                        : "guardian verified via " + submission.guardianVerification().method());
    }

    private static Optional<Instant> contractEnd(CaptureSubmission submission) {
        String raw = submission.attributes().get(ATTR_CONTRACT_END_DATE);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant());
        } catch (DateTimeParseException e) {
            log.warn("unparseable {} value '{}' from application={}", ATTR_CONTRACT_END_DATE, raw,
                    submission.applicationId());
            return Optional.empty();
        }
    }

    private static String perPurposeKey(String submissionKey, String purposeCode) {
        return submissionKey == null ? null : submissionKey + ':' + purposeCode;
    }

    /**
     * Outcome of a capture attempt.
     *
     * @param events     what was written, empty when rejected
     * @param violations why it was rejected, empty when accepted
     */
    public record Result(List<ConsentEvent> events, List<CaptureViolation> violations) {

        public Result {
            events = List.copyOf(events);
            violations = List.copyOf(violations);
        }

        static Result accepted(List<ConsentEvent> events) {
            return new Result(events, List.of());
        }

        static Result rejected(List<CaptureViolation> violations) {
            return new Result(List.of(), violations);
        }

        public boolean isAccepted() {
            return violations.isEmpty();
        }
    }

    /**
     * The version being withdrawn from — the one the subject actually agreed to.
     *
     * <p>Read off the projected artefact rather than the registry. The registry answers "what does
     * this purpose say today", and a taxonomy change between the grant and the withdrawal makes
     * that a different question from "what did this person agree to". Stamping the current version
     * on a WITHDRAWN event writes a statement into the evidence plane that is hash-valid and
     * untrue, and {@code ReceiptService} then renders that version's wording back to the principal
     * as the terms they accepted.
     *
     * <p>Falls back to the registry, then to zero, where no artefact exists — a withdrawal against
     * a purpose the subject was never recorded as holding is unusual but not refusable, and
     * inventing a version for it would be the same error in the other direction.
     */
    private int withdrawnVersion(String entityId, String subjectId, String purposeCode,
                                 Optional<PurposeDefinition> purpose) {
        return artefacts.find(entityId, subjectId, purposeCode)
                .map(com.uds.consent.core.model.ConsentArtefact::purposeVersion)
                .orElseGet(() -> purpose.map(PurposeDefinition::version).orElse(0));
    }
}
