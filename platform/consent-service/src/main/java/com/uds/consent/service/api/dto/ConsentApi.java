package com.uds.consent.service.api.dto;

import com.uds.consent.core.model.ActorType;
import com.uds.consent.core.model.CaptureMethod;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.ConsentStatus;
import com.uds.consent.core.model.GuardianVerificationMethod;
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
            List<@Valid SubjectRef> alsoKnownAs,
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
            Map<String, String> attributes,
            @Valid GuardianVerificationDto guardianVerification) {
    }

    /**
     * How the parent or lawful guardian was verified, for a capture on a child's behalf.
     *
     * <p>Required whenever the submission declares a child, names {@code PARENTAL_VERIFIED} or acts
     * through a {@code GUARDIAN}; refused as {@code GUARDIAN_VERIFICATION_NOT_EVIDENCED} otherwise.
     * DPDP Rule 10 puts the duty of due diligence on the fiduciary, so the platform will not accept
     * the surface's word that it was done.
     *
     * @param method    which of Rule 10's routes was taken
     * @param reference the raw value the check was performed against — the parent's account id,
     *                  the DigiLocker virtual token, the reference of a documented check. Hashed
     *                  with the platform's pepper at this boundary and never stored in the clear,
     *                  exactly as {@link SubjectRef#value()} is. Callers send what they have; the
     *                  ledger keeps only what it can prove a check against
     * @param verifiedAt when the check was performed, which for a parent verified at their own
     *                  registration is not the same day as the child's capture
     * @param verifiedBy the system or person that performed it
     */
    public record GuardianVerificationDto(
            @NotNull GuardianVerificationMethod method,
            @NotBlank String reference,
            @NotNull Instant verifiedAt,
            String verifiedBy) {
    }

    /**
     * Identifies a subject by something real, for surfaces that do not yet hold a subject id.
     *
     * <p>The value is hashed with the platform's pepper before it reaches the ledger, so callers
     * never need the pepper and no plaintext identifier is stored.
     */
    public record SubjectRef(@NotNull IdentifierType identifierType, @NotBlank String value) {
    }

    // -----------------------------------------------------------------------------------
    // alsoKnownAs, on CaptureRequest above.
    //
    // Other identifiers the surface knows belong to the SAME person. Without it the platform
    // resolves one identifier to one subject, so somebody the group knows by a phone number and
    // by an email address is two subjects with two consent records — and a withdrawal by email
    // leaves the phone contactable, which is the failure a grievance surfaces first.
    //
    // Asserted by the capture surface, never inferred. A form that collected both a mobile and an
    // email from the person in front of it knows they are the same person; the platform matching
    // them itself would eventually merge two people, and the first evidence of that would be a
    // call to somebody who withdrew. Prevention at capture is cheap and safe; reconciliation
    // afterwards needs an administrator's assertion — see POST /v1/admin/subjects/merge.
    // -----------------------------------------------------------------------------------

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

    /**
     * Records that a notice was served without consent being sought.
     *
     * <p>The evidence trail for a legitimate use under DPDP s.7(i). Around 76,000 workforce records
     * across the group need this rather than a consent record, and without it there is no way to
     * show that people were told — which is the obligation that actually applies to them.
     */
    public record NoticeServedRequest(
            @NotBlank String entityId,
            String subjectId,
            @Valid SubjectRef subject,
            @NotBlank String purposeCode,
            @NotBlank String noticeId,
            @NotNull Integer noticeVersion,
            @NotBlank String languageTag,
            @NotNull Jurisdiction jurisdiction,
            String applicationId,
            Instant occurredAt,
            String idempotencyKey) {
    }

    /**
     * Strikes down consent the fiduciary can no longer rely on.
     *
     * <p>Used when a blast-radius calculation says a change is material and standing consent no
     * longer covers what will be done, and when imported provenance cannot be substantiated. The
     * reason is mandatory: this is the group revoking its own permission, and an auditor will ask
     * who decided that and why.
     */
    public record InvalidateRequest(
            @NotBlank String entityId,
            @NotBlank String subjectId,
            @NotBlank String purposeCode,
            @NotNull Integer purposeVersion,
            @NotBlank String reason,
            Instant occurredAt) {
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

    // -------------------------------------------------------------------------------------
    // Publishing — the writable control plane
    // -------------------------------------------------------------------------------------

    /**
     * A new notice version.
     *
     * <p>No version field, deliberately. The platform assigns {@code max + 1}; a caller who names
     * their own number eventually names one that exists, and the collision surfaces as a database
     * error on a publish the compliance team believes went through.
     *
     * <p>The three URIs are mandatory because Rule 3 makes them mandatory: a notice must tell the
     * principal how to withdraw, how to exercise their rights, and how to complain. They are
     * columns rather than prose for the same reason — a missing one is then a rejected publish
     * instead of a finding two years later.
     */
    public record PublishNoticeRequest(
            @NotNull Jurisdiction jurisdiction,
            boolean materialChange,
            @NotBlank String withdrawalUri,
            @NotBlank String rightsUri,
            @NotBlank String grievanceUri) {
    }

    /** One language of a published version. Adding one never bumps the version. */
    public record AddTranslationRequest(
            @NotBlank String languageTag,
            @NotBlank String title,
            @NotBlank String body) {
    }

    /**
     * A new purpose version.
     *
     * <p>{@code materialChange} is the field that matters and the one no machine can derive: it is
     * the human judgement about whether this alters what subjects agreed to. Get it wrong towards
     * "cosmetic" and the platform keeps relying on consent to something else.
     */
    public record PublishPurposeRequest(
            @NotBlank String name,
            String owner,
            @NotBlank String description,
            @NotEmpty Map<Jurisdiction, @Valid LegalBasisDto> legalBases,
            List<String> dataCategories,
            List<Channel> channels,
            @NotNull com.uds.consent.core.model.ExpiryPolicy expiryPolicy,
            Integer expiryDays,
            @NotNull com.uds.consent.core.model.FailureBehavior failureBehavior,
            String noticeId,
            boolean requiresSeparateConsent,
            boolean permittedForChildren,
            boolean materialChange,
            boolean retired) {
    }

    /**
     * @param assessmentRef the Legitimate Interests Assessment reference. Required whenever the
     *                      basis is {@code LEGITIMATE_INTEREST}, and refused with that explanation
     *                      rather than with a constraint name
     */
    public record LegalBasisDto(@NotNull LegalBasis legalBasis, String assessmentRef, String notes) {
    }

    /** Published verification key, so an SDK can check snapshots without trusting the transport. */
    public record VerificationKey(String keyId, String algorithm, String publicKeyBase64) {
    }
}
