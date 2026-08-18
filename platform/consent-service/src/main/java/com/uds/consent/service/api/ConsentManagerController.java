package com.uds.consent.service.api;

import com.uds.consent.core.model.ConsentEvent;
import com.uds.consent.core.model.ConsentReceipt;
import com.uds.consent.ledger.store.ConsentManagerStore;
import com.uds.consent.policy.capture.CaptureSubmission;
import com.uds.consent.service.ConsentCaptureService;
import com.uds.consent.service.ConsentManagerRelayService;
import com.uds.consent.service.ReceiptService;
import com.uds.consent.service.api.dto.ConsentApi;
import com.uds.consent.service.api.dto.ConsentManagerApi;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * The Consent Manager interface (DPDP Rules 2025, Rule 4 — operational 13 November 2026).
 *
 * <p>Three routes, and the asymmetry between them is the design. Two inbound, because the framework
 * obliges a Data Fiduciary to accept what a registered Consent Manager relays; one outbound,
 * because a principal managing their consent elsewhere is entitled to see what UDS actually holds,
 * and a Consent Manager that cannot read it cannot show them.
 *
 * <p>The outbound route deliberately returns the same canonical receipt {@code ReceiptService}
 * already issues, rather than a Consent-Manager-shaped projection of it. One artefact, seen
 * identically by the principal, the Consent Manager and the auditor — a second format would be a
 * second thing to be wrong, and the disagreement would surface as two documents about the same
 * consent that do not match.
 *
 * <p><strong>Authentication is interim and says so.</strong> A Consent Manager authenticates as an
 * API client under the same HTTP Basic scheme as everything else, with its registration number tied
 * to the credential in the register. Rule 4 plainly contemplates signed relays, and
 * {@code consent_manager.public_key} exists for it — but the Board has published no signing
 * standard, and verifying against a scheme nobody else implements would be worse than not
 * verifying, because it would look like proof.
 */
@RestController
@RequestMapping("/v1/consent-manager")
@Tag(name = "Consent Manager", description = "Relays to and from a registered Consent Manager")
// Dark unless UDS has registered with the Board and a signing standard exists to verify against.
// Conditional on the bean rather than on the security rules, because the two produce different
// answers and only one of them is honest: removing the authorisation rule would leave the routes
// mapped and answering, while removing the controller means the platform does not offer a surface
// it cannot yet police. A caller gets 404 — which is true — rather than 403, which would say the
// relay exists and you may not use it.
//
// The register itself stays administrable through /v1/admin/consent-managers/**: an entity may
// legitimately want the registrations recorded before the relay opens, and those routes are
// ADMIN-only and write nothing to the ledger.
@ConditionalOnProperty(name = "uds.consent.features.consent-manager-relay", havingValue = "true")
public class ConsentManagerController {

    private final ConsentManagerRelayService relay;
    private final ReceiptService receipts;
    private final ConsentManagerStore managers;

    public ConsentManagerController(ConsentManagerRelayService relay, ReceiptService receipts,
                                    ConsentManagerStore managers) {
        this.relay = relay;
        this.receipts = receipts;
        this.managers = managers;
    }

    /**
     * A grant the principal made at their Consent Manager.
     *
     * <p>Answers 422 with the violations when the relayed consent would not be valid, exactly as
     * the first-party capture endpoint does. That is worth stating because the temptation is to
     * accept whatever a registered CM sends on the grounds that it is the statutory channel — but
     * the validity of consent under s.6 does not depend on how it arrived, and a fiduciary that
     * recorded an invalid consent because a CM relayed it would be holding evidence against itself.
     */
    @PostMapping("/{registrationId}/grant")
    @PreAuthorize("hasAnyRole('CONSENT_MANAGER', 'ADMIN')")
    @Operation(summary = "Relay a grant made at a Consent Manager")
    public ResponseEntity<ConsentManagerApi.RelayResponse> grant(
            @PathVariable String registrationId,
            @Valid @RequestBody ConsentManagerApi.RelayGrantRequest request,
            Authentication authentication) {

        Instant occurredAt = request.occurredAt() == null ? Instant.now() : request.occurredAt();

        ConsentCaptureService.Result result = relay.relayGrant(
                new ConsentManagerRelayService.Relay(
                        registrationId,
                        request.entityId(),
                        request.cmSubjectRef(),
                        request.subjectId(),
                        request.identifierType(),
                        request.identifierValue(),
                        request.jurisdiction(),
                        request.languageTag(),
                        request.channel(),
                        request.applicationId(),
                        request.noticeId(),
                        request.noticeVersion(),
                        request.choices().stream()
                                .map(choice -> new CaptureSubmission.PurposeChoice(
                                        choice.purposeCode(), choice.granted(), choice.preTicked(),
                                        choice.separateAction()))
                                .toList(),
                        request.rejectAllOffered(),
                        occurredAt,
                        request.idempotencyKey(),
                        request.evidenceRef()),
                callerOf(authentication));

        if (!result.isAccepted()) {
            return ResponseEntity.unprocessableEntity().body(
                    new ConsentManagerApi.RelayResponse(registrationId, request.cmSubjectRef(),
                            List.of(),
                            result.violations().stream().map(ConsentApi.ViolationDto::from)
                                    .toList()));
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(
                new ConsentManagerApi.RelayResponse(registrationId, request.cmSubjectRef(),
                        result.events().stream().map(ConsentManagerController::summarise).toList(),
                        List.of()));
    }

    /**
     * A withdrawal the principal made at their Consent Manager.
     *
     * <p>Takes effect on commit, through the same path a first-party withdrawal takes. There is no
     * queue, no review and no confirmation step: a withdrawal that had to be approved would not be
     * as easy as giving consent, and the fact that it arrived through an intermediary changes
     * nothing about whose decision it is.
     */
    @PostMapping("/{registrationId}/withdraw")
    @PreAuthorize("hasAnyRole('CONSENT_MANAGER', 'ADMIN')")
    @Operation(summary = "Relay a withdrawal made at a Consent Manager")
    public ConsentManagerApi.RelayResponse withdraw(
            @PathVariable String registrationId,
            @Valid @RequestBody ConsentManagerApi.RelayWithdrawRequest request,
            Authentication authentication) {

        Instant occurredAt = request.occurredAt() == null ? Instant.now() : request.occurredAt();

        List<ConsentEvent> events = relay.relayWithdrawal(
                new ConsentManagerRelayService.Relay(
                        registrationId,
                        request.entityId(),
                        request.cmSubjectRef(),
                        request.subjectId(),
                        request.identifierType(),
                        request.identifierValue(),
                        request.jurisdiction(),
                        null,
                        request.channel(),
                        request.applicationId(),
                        null, null, List.of(), false,
                        occurredAt,
                        request.idempotencyKey(),
                        null),
                callerOf(authentication),
                request.purposeCodes(),
                request.reason());

        return new ConsentManagerApi.RelayResponse(registrationId, request.cmSubjectRef(),
                events.stream().map(ConsentManagerController::summarise).toList(), List.of());
    }

    /**
     * What UDS holds for a principal, as the canonical receipt.
     *
     * <p>Issued fresh rather than reproduced from the last one. A Consent Manager asking this
     * question is asking what is true now — and issuing it means the read is itself durable
     * evidence that the record was disclosed on a date, which is the same reason
     * {@code POST /v1/admin/ropa/{entityId}/export} is separate from the read beside it.
     *
     * <p>Bound to the calling credential like the two writes, and if anything the binding matters
     * more here: this route discloses a named principal's whole consent record, so an unbound
     * registration number would let one Consent Manager read another's principals by quoting a
     * registration and a reference.
     */
    @GetMapping("/{registrationId}/subjects/{cmSubjectRef}/record")
    @PreAuthorize("hasAnyRole('CONSENT_MANAGER', 'ADMIN')")
    @Operation(summary = "The consent record UDS holds for a principal, as an ISO 27560 receipt")
    public ConsentReceipt record(@PathVariable String registrationId,
                                 @PathVariable String cmSubjectRef,
                                 @RequestParam String entityId,
                                 Authentication authentication) {

        Instant now = Instant.now();
        relay.requireActiveAndBound(registrationId, callerOf(authentication), entityId, now);

        String subjectId = managers.resolveSubject(entityId, registrationId, cmSubjectRef)
                .orElseThrow(() -> new ConsentManagerRelayService.RelayRefusedException(
                        registrationId,
                        "no principal is linked to that reference at this entity"));

        return receipts.issue(entityId, subjectId, now);
    }

    /** The register, as the platform holds it. Group configuration, so no entity scope. */
    @GetMapping("/registry")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Consent Managers on the register, and their status")
    public List<ConsentManagerApi.ConsentManagerDto> registry() {
        return managers.findAll().stream()
                .map(manager -> new ConsentManagerApi.ConsentManagerDto(
                        manager.registrationId(), manager.name(), manager.status().name(),
                        manager.registeredAt(), manager.statusChangedAt(), manager.statusReason(),
                        manager.publicKey() != null && !manager.publicKey().isBlank()))
                .toList();
    }

    /**
     * The caller, as authentication established them rather than as the request described them.
     *
     * <p>The registration number on the path is something the caller typed. This is not. Keeping
     * the two apart is the whole of the fix: before it, they were never compared and any
     * {@code CONSENT_MANAGER} credential could write consent into the ledger under another Consent
     * Manager's Board registration.
     */
    private static ConsentManagerRelayService.Caller callerOf(Authentication authentication) {
        if (authentication == null) {
            // Unreachable behind @PreAuthorize, and handled anyway: the relay service refuses a
            // null caller, so an ordering change in the filter chain fails closed rather than
            // silently skipping the binding check.
            return null;
        }
        boolean administrator = authentication.getAuthorities().stream()
                .anyMatch(granted -> "ROLE_ADMIN".equals(granted.getAuthority()));
        return new ConsentManagerRelayService.Caller(authentication.getName(), administrator);
    }

    private static ConsentApi.EventSummary summarise(ConsentEvent event) {
        return new ConsentApi.EventSummary(event.eventId(), event.purposeCode(),
                event.purposeVersion(), event.type().name(), event.resultingStatus(),
                event.occurredAt(), event.expiresAt(), event.sequenceNumber(), event.eventHash());
    }
}
