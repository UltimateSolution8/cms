package com.uds.consent.service.api.dto;

import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.IdentifierType;
import com.uds.consent.core.model.Jurisdiction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/** Wire contract for relays to and from a Consent Manager registered under DPDP Rule 4. */
public final class ConsentManagerApi {

    private ConsentManagerApi() {
    }

    /**
     * A grant as a Consent Manager relays it.
     *
     * <p>{@code cmSubjectRef} is mandatory on every relay and is the only field here without an
     * obvious first-party equivalent. It is how the Consent Manager identifies the principal, and
     * therefore the only stable join between the two systems: UDS knows the principal by a peppered
     * hash the CM will never see, and the CM knows them by a reference UDS cannot guess. A relay
     * without it can be honoured once and never referred to again.
     *
     * <p>The identifier fields are the fallback for a principal UDS has not seen before. They are
     * hashed with the platform's pepper before anything is written, exactly as a first-party
     * capture would be — the Consent Manager gains nothing by sending a phone number that it did
     * not already have.
     */
    public record RelayGrantRequest(
            @NotBlank String cmSubjectRef,
            @NotBlank String entityId,
            String subjectId,
            IdentifierType identifierType,
            String identifierValue,
            @NotNull Jurisdiction jurisdiction,
            @NotBlank String languageTag,
            Channel channel,
            String applicationId,
            String noticeId,
            Integer noticeVersion,
            @NotEmpty @Valid List<ConsentApi.PurposeChoiceDto> choices,
            boolean rejectAllOffered,
            Instant occurredAt,
            String idempotencyKey,
            /*
             * The Consent Manager's own reference for the record that proves what the principal
             * did. UDS does not hold that proof and should not pretend to: what it holds is a
             * pointer, and an auditor following it goes to the CM.
             */
            String evidenceRef) {
    }

    /** A withdrawal as a Consent Manager relays it. */
    public record RelayWithdrawRequest(
            @NotBlank String cmSubjectRef,
            @NotBlank String entityId,
            String subjectId,
            IdentifierType identifierType,
            String identifierValue,
            @NotNull Jurisdiction jurisdiction,
            Channel channel,
            String applicationId,
            @NotEmpty List<String> purposeCodes,
            Instant occurredAt,
            String idempotencyKey,
            String reason) {
    }

    /**
     * @param registrationId the Board registration the relay was honoured under, echoed so the CM
     *                       can reconcile against what it thought it was
     */
    public record RelayResponse(
            String registrationId,
            String cmSubjectRef,
            List<ConsentApi.EventSummary> events,
            List<ConsentApi.ViolationDto> violations) {
    }

    /** What the register says about a Consent Manager. Read by the compliance console. */
    public record ConsentManagerDto(
            String registrationId,
            String name,
            String status,
            Instant registeredAt,
            Instant statusChangedAt,
            String statusReason,
            boolean signingKeyOnFile) {
    }

    /**
     * A Consent Manager the Board has registered, as an administrator records it.
     *
     * <p>No status field. Registering and suspending are different acts with different authority
     * behind them, and a create that also set status would mean a routine re-transcription of the
     * Board's published list could silently restore a registration suspended last week. Status moves
     * only through {@link StatusRequest}.
     *
     * @param apiClientId the credential this registration relays under. Every relay is checked
     *                    against it, so an entry without one can be recorded but cannot transact —
     *                    which is the right shape for a registration noted from the Board's list
     *                    before the Consent Manager has been onboarded technically
     * @param publicKey   for verifying signatures on relayed requests. Accepted and stored and not
     *                    yet used: the Board has published no signing standard, and verifying
     *                    against a scheme nobody else implements would look like proof
     */
    public record RegisterRequest(
            @NotBlank String registrationId,
            @NotBlank String name,
            String apiClientId,
            String publicKey,
            Instant registeredAt,
            String contactEmail) {
    }

    /**
     * A status change, with the reason it was made.
     *
     * <p>The reason is required. This is the record somebody reads after a relay that should not
     * have been honoured, or after one that was refused and should not have been, and "SUSPENDED"
     * with no explanation answers neither question.
     */
    public record StatusRequest(
            @NotBlank String status,
            @NotBlank String reason) {
    }

    /**
     * One entry on the register as UDS holds it, including how stale it is.
     *
     * <p>Distinct from {@link ConsentManagerDto}, which is what the relay-facing registry read
     * returns. This one carries the reconciliation fields, because they are an operational property
     * of UDS's copy rather than a fact about the Consent Manager.
     *
     * @param lastReconciledAt when a person last compared this entry against the Board's published
     *                         register, or null for never. Null is the worse state, not the absent
     *                         one: the copy has never been checked at all
     */
    public record RegisterEntryDto(
            String registrationId,
            String name,
            String status,
            String apiClientId,
            Instant registeredAt,
            Instant statusChangedAt,
            String statusReason,
            String contactEmail,
            boolean signingKeyOnFile,
            Instant lastReconciledAt,
            String lastReconciledBy) {

        public static RegisterEntryDto from(
                com.uds.consent.ledger.store.ConsentManagerStore.ConsentManager manager) {
            return new RegisterEntryDto(
                    manager.registrationId(), manager.name(), manager.status().name(),
                    manager.apiClientId(), manager.registeredAt(), manager.statusChangedAt(),
                    manager.statusReason(), manager.contactEmail(),
                    manager.publicKey() != null && !manager.publicKey().isBlank(),
                    manager.lastReconciledAt(), manager.lastReconciledBy());
        }
    }
}
