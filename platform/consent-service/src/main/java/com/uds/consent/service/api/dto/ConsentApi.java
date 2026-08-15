package com.uds.consent.service.api.dto;

import com.uds.consent.core.model.ActorType;
import com.uds.consent.core.model.CaptureMethod;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.ConsentStatus;
import com.uds.consent.core.model.IdentifierType;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.LegalBasis;
import com.uds.consent.policy.capture.CaptureViolation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Request and response shapes for the consent APIs.
 *
 * <p>Kept separate from the domain model on purpose. The wire contract has to stay stable for
 * every SDK and every integrating team across the group, while the domain model needs to be free
 * to change; binding them together would make an internal refactor a breaking change for a dozen
 * callers.
 */
public final class ConsentApi {

    private ConsentApi() {
    }

    /**
     * A consent capture.
     *
     * <p>The fields that look like ceremony are the ones that decide validity. {@code preTicked}
     * and {@code separateAction} on each choice, and {@code rejectAllOffered} on the submission,
     * cannot be reconstructed after the fact from a stored boolean — so surfaces are made to
     * declare them, and a surface that declares a pre-ticked box is rejected rather than trusted.
     */
    public record CaptureRequest(
            @NotBlank String entityId,
            String subjectId,
            @Valid SubjectRef subject,
            @NotNull Jurisdiction jurisdiction,
            @NotBlank String languageTag,
            Channel channel,
            String applicationId,
            @NotNull CaptureMethod captureMethod,
            @NotNull ActorType actorType,
            String actorId,
            String noticeId,
            Integer noticeVersion,
            @NotEmpty List<@Valid PurposeChoiceDto> choices,
            boolean rejectAllOffered,
            Instant occurredAt,
            String idempotencyKey,
            String evidenceRef,
            Map<String, String> attributes) {
    }

    /**
     * Identifies a subject by something real, for surfaces that do not yet hold a subject id.
     *
     * <p>The value is hashed with the platform's pepper before it reaches the ledger, so callers
     * never need the pepper and no plaintext identifier is stored.
     */
    public record SubjectRef(@NotNull IdentifierType identifierType, @NotBlank String value) {
    }

    public record PurposeChoiceDto(
            @NotBlank String purposeCode,
            boolean granted,
            boolean preTicked,
            boolean separateAction) {
    }

    /**
     * @param accepted   whether anything was written
     * @param subjectId  the subject the events were recorded against
     * @param events     one summary per purpose
     * @param violations why the submission was refused, when it was
     */
    public record CaptureResponse(
            boolean accepted,
            String subjectId,
            List<EventSummary> events,
            List<ViolationDto> violations) {
    }

    public record EventSummary(
            String eventId,
            String purposeCode,
            int purposeVersion,
            String eventType,
            ConsentStatus status,
            Instant occurredAt,
            Instant expiresAt,
            long sequenceNumber,
            String eventHash) {
    }

    public record ViolationDto(String purposeCode, String code, String detail) {

        public static ViolationDto from(CaptureViolation violation) {
            return new ViolationDto(violation.purposeCode(), violation.code().name(),
                    violation.detail());
        }
    }

    public record WithdrawRequest(
            @NotBlank String entityId,
            String subjectId,
            @Valid SubjectRef subject,
            @NotEmpty List<String> purposeCodes,
            @NotNull Jurisdiction jurisdiction,
            Channel channel,
            String applicationId,
            @NotNull ActorType actorType,
            String actorId,
            Instant occurredAt,
            String idempotencyKey,
            String reason) {
    }

    /** A decision request over the wire. */
    public record EvaluateRequest(
            @NotBlank String entityId,
            @NotBlank String subjectId,
            @NotBlank String purposeCode,
            Channel channel,
            Jurisdiction jurisdiction,
            String applicationId,
            String clientId,
            String campaignId,
            String vendorId,
            Instant at,
            Map<String, String> context) {
    }

    public record EvaluateResponse(
            String outcome,
            String reason,
            String explanation,
            LegalBasis legalBasis,
            String purposeCode,
            int purposeVersion,
            String policyVersion,
            Instant evaluatedAt,
            Instant consentExpiresAt,
            List<String> obligations) {
    }

    /** Current state for one purpose, as returned to a preference centre or an SDK. */
    public record ConsentStateDto(
            String purposeCode,
            String purposeName,
            int purposeVersion,
            ConsentStatus status,
            LegalBasis legalBasis,
            Instant grantedAt,
            Instant expiresAt,
            Instant withdrawnAt,
            boolean requiresConsent) {
    }

    /** One event in a subject's evidence trail. */
    public record HistoryEntry(
            String eventId,
            String purposeCode,
            int purposeVersion,
            String eventType,
            String captureMethod,
            String channel,
            String noticeId,
            Integer noticeVersion,
            String languageTag,
            String actorType,
            String actorId,
            String reason,
            Instant occurredAt,
            Instant recordedAt,
            Instant expiresAt,
            long sequenceNumber,
            String eventHash) {
    }

    public record SnapshotResponse(String snapshot, String keyId, Instant issuedAt,
                                   Instant expiresAt) {
    }

    /** Published verification key, so an SDK can check snapshots without trusting the transport. */
    public record VerificationKey(String keyId, String algorithm, String publicKeyBase64) {
    }
}
