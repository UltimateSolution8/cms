package com.uds.consent.service.api;

import com.uds.consent.core.crypto.IdentifierHasher;
import com.uds.consent.core.model.ConsentArtefact;
import com.uds.consent.core.model.ConsentEvent;
import com.uds.consent.core.model.ConsentReceipt;
import com.uds.consent.core.model.GuardianVerification;
import com.uds.consent.core.model.IdentifierType;
import com.uds.consent.core.model.PurposeDefinition;
import com.uds.consent.ledger.service.ConsentLedger;
import com.uds.consent.ledger.store.StoredEvent;
import com.uds.consent.ledger.store.SubjectStore;
import com.uds.consent.policy.capture.CaptureSubmission;
import com.uds.consent.policy.port.PolicyPorts;
import com.uds.consent.service.ConsentCaptureService;
import com.uds.consent.service.ReceiptService;
import com.uds.consent.service.api.dto.ConsentApi;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * The consent ingestion API — one contract every capture surface writes to.
 *
 * <p>Everything below this endpoint is the group's own: the ledger, the decision point, the
 * suppression service, the provenance store. That is deliberate, and it is what keeps the
 * build-versus-buy decision genuinely open. A commercial cookie banner or a bought web capture
 * layer plugs in here as one more caller; swapping it later costs an integration, not a rewrite.
 */
@RestController
@RequestMapping("/v1/consent")
public class ConsentController {

    private final ConsentCaptureService capture;
    private final ConsentLedger ledger;
    private final ReceiptService receipts;
    private final SubjectStore subjects;
    private final IdentifierHasher hasher;
    private final PolicyPorts.PurposeCatalog purposes;

    public ConsentController(ConsentCaptureService capture, ConsentLedger ledger,
                             ReceiptService receipts, SubjectStore subjects,
                             IdentifierHasher hasher, PolicyPorts.PurposeCatalog purposes) {
        this.capture = capture;
        this.ledger = ledger;
        this.receipts = receipts;
        this.subjects = subjects;
        this.hasher = hasher;
        this.purposes = purposes;
    }

    /**
     * Records a consent interaction.
     *
     * <p>Returns 422 with the violations when the submission would produce an invalid record. That
     * is a deliberate choice over accepting and flagging: a consent record that was invalid when
     * written looks like evidence to every system that reads it, and its invalidity surfaces only
     * when someone complains.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('CAPTURE', 'ADMIN')")
    public ResponseEntity<ConsentApi.CaptureResponse> capture(
            @Valid @RequestBody ConsentApi.CaptureRequest request) {

        String subjectId = resolveSubject(request.entityId(), request.subjectId(), request.subject());
        linkAdditionalIdentifiers(request.entityId(), subjectId, request.alsoKnownAs());
        Instant occurredAt = request.occurredAt() == null ? Instant.now() : request.occurredAt();

        CaptureSubmission submission = new CaptureSubmission(
                request.entityId(),
                subjectId,
                request.jurisdiction(),
                request.languageTag(),
                request.channel(),
                request.applicationId(),
                request.captureMethod(),
                request.actorType(),
                request.actorId(),
                request.noticeId(),
                request.noticeVersion(),
                request.choices().stream()
                        .map(choice -> new CaptureSubmission.PurposeChoice(choice.purposeCode(),
                                choice.granted(), choice.preTicked(), choice.separateAction()))
                        .toList(),
                request.rejectAllOffered(),
                occurredAt,
                request.idempotencyKey(),
                request.evidenceRef(),
                request.attributes(),
                guardianVerification(request.guardianVerification()));

        ConsentCaptureService.Result result = capture.capture(submission);

        if (!result.isAccepted()) {
            return ResponseEntity.unprocessableEntity().body(new ConsentApi.CaptureResponse(
                    false, subjectId, List.of(),
                    result.violations().stream().map(ConsentApi.ViolationDto::from).toList()));
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(new ConsentApi.CaptureResponse(
                true, subjectId, result.events().stream().map(ConsentController::summarise).toList(),
                List.of()));
    }

    /**
     * Hashes the guardian reference at the edge, exactly as a subject identifier is hashed.
     *
     * <p>The caller sends whatever it verified against — an account id, a DigiLocker virtual token
     * — and nothing below this line ever sees it. The reasoning is the same one that keeps phone
     * numbers out of the ledger, and applies with more force here: a guardian is not the data
     * principal whose consent this is, they are a third party who appears in the record only
     * because the law requires the check, and the platform has no business being able to identify
     * them afterwards. It needs to prove a check happened against a stable reference, which a hash
     * does.
     *
     * <p>{@code EXTERNAL_ID} is the right identifier type for this: it normalises by case and
     * nothing else, which is correct for an opaque token and wrong for none of the plausible
     * inputs.
     */
    private GuardianVerification guardianVerification(ConsentApi.GuardianVerificationDto dto) {
        if (dto == null) {
            return null;
        }
        return new GuardianVerification(dto.method(),
                hasher.hash(IdentifierType.EXTERNAL_ID, dto.reference()),
                dto.verifiedAt(), dto.verifiedBy());
    }

    /**
     * Records a withdrawal.
     *
     * <p>Takes effect the moment it is committed: the projection updates in the same transaction,
     * and the outbox row that tells every downstream system is written with it. DPDP s.6(4)
     * requires withdrawal to be as easy as giving, which is a statement about the interface as
     * much as the API — a preference centre that buries this behind three screens fails the test
     * however fast this endpoint is.
     *
     * <p>The outbox row is also where <strong>s.6(6)</strong> — the separate duty to "cease and
     * cause its Data Processors to cease processing" — is discharged, or is not. That limb is
     * about the systems downstream of this one, and until a propagation register names them the
     * platform can show it published and cannot show anyone was told.
     */
    @PostMapping("/withdraw")
    @PreAuthorize("hasAnyRole('CAPTURE', 'ADMIN')")
    public ConsentApi.CaptureResponse withdraw(
            @Valid @RequestBody ConsentApi.WithdrawRequest request) {

        String subjectId = resolveSubject(request.entityId(), request.subjectId(), request.subject());
        Instant occurredAt = request.occurredAt() == null ? Instant.now() : request.occurredAt();

        List<ConsentEvent> events = capture.withdraw(
                request.entityId(), subjectId, request.purposeCodes(), request.channel(),
                request.applicationId(), request.actorType(), request.actorId(),
                request.jurisdiction(), occurredAt, request.idempotencyKey(), request.reason());

        return new ConsentApi.CaptureResponse(true, subjectId,
                events.stream().map(ConsentController::summarise).toList(), List.of());
    }

    /**
     * Records that a notice was served, without consent being sought.
     *
     * <p>The write path for legitimate uses under DPDP s.7(i) — employment processing and the like,
     * where consent is not the basis and asking for it would misrepresent what the subject can
     * change. Around 76,000 workforce records across the group belong here rather than in the
     * consent path, and the obligation that binds for them is proof that they were told.
     *
     * <p>Separate from {@code POST /v1/consent} deliberately. A capture surface that had to decide
     * for itself whether a purpose rests on consent would eventually decide wrongly, and the
     * capture validator already rejects an attempt to record consent for such a purpose.
     */
    @PostMapping("/notice-served")
    @PreAuthorize("hasAnyRole('CAPTURE', 'ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public ConsentApi.CaptureResponse noticeServed(
            @Valid @RequestBody ConsentApi.NoticeServedRequest request) {

        String subjectId = resolveSubject(request.entityId(), request.subjectId(), request.subject());
        Instant occurredAt = request.occurredAt() == null ? Instant.now() : request.occurredAt();

        ConsentEvent event = capture.recordNoticeServed(
                request.entityId(), subjectId, request.purposeCode(), request.noticeId(),
                request.noticeVersion(), request.languageTag(), request.jurisdiction(),
                request.applicationId(), occurredAt, request.idempotencyKey());

        return new ConsentApi.CaptureResponse(true, subjectId, List.of(summarise(event)), List.of());
    }

    /** Current state for every purpose on record. What a preference centre renders. */
    @GetMapping("/{entityId}/{subjectId}")
    @PreAuthorize("hasAnyRole('CAPTURE', 'DECISION', 'ADMIN')")
    public List<ConsentApi.ConsentStateDto> currentState(@PathVariable String entityId,
                                                         @PathVariable String subjectId) {
        Instant now = Instant.now();
        return ledger.currentStateForSubject(entityId, subjectId).stream()
                .map(artefact -> toStateDto(artefact, now))
                .toList();
    }

    /**
     * The full evidence trail.
     *
     * <p>The endpoint that answers an audit. Every event carries the notice version rendered, the
     * language it was rendered in, what the subject did and the hash tying it into the chain — so
     * "prove this person consented, and show what they were shown" is a request, not a project.
     */
    @GetMapping("/{entityId}/{subjectId}/history")
    @PreAuthorize("hasAnyRole('ADMIN')")
    public List<ConsentApi.HistoryEntry> history(@PathVariable String entityId,
                                                 @PathVariable String subjectId,
                                                 @RequestParam(required = false) String purposeCode) {
        List<StoredEvent> chain = purposeCode == null
                ? ledger.history(entityId, subjectId)
                : ledger.history(entityId, subjectId, purposeCode);
        return chain.stream().map(StoredEvent::event).map(ConsentController::toHistoryEntry).toList();
    }

    /** The subject-facing consent receipt, shaped along ISO/IEC TS 27560. */
    @GetMapping("/{entityId}/{subjectId}/receipt")
    @PreAuthorize("hasAnyRole('CAPTURE', 'ADMIN')")
    public ConsentReceipt receipt(@PathVariable String entityId, @PathVariable String subjectId) {
        return receipts.issue(entityId, subjectId, Instant.now());
    }

    /**
     * Resolves the subject a request is about.
     *
     * <p>An explicit subject id wins; otherwise the identifier is normalised and hashed, and a
     * subject is created if this is the first time the group has seen it. Capture is the one place
     * where creating a subject is right — the person is in front of a surface, answering.
     */
    private String resolveSubject(String entityId, String subjectId, ConsentApi.SubjectRef ref) {
        if (subjectId != null && !subjectId.isBlank()) {
            // Canonicalised, because a surface may be holding an id from before a merge. Answering
            // under a superseded id would write new events onto a chain nothing reads any more.
            return subjects.canonical(subjectId);
        }
        if (ref == null) {
            throw new IllegalArgumentException(
                    "provide either subjectId or subject{identifierType,value}");
        }
        return subjects.resolveOrCreate(entityId, ref.identifierType(),
                hasher.hash(ref.identifierType(), ref.value()));
    }

    /**
     * Records the other identifiers a capture surface says belong to the same person.
     *
     * <p>The cheap half of identity resolution, and the half that prevents the problem instead of
     * repairing it. Without this the platform resolves one identifier to one subject, so a person
     * the group knows by a mobile number and by an email address is two subjects — and a
     * withdrawal by email leaves the phone contactable, which is the failure a grievance surfaces
     * first and the one a dialer will trip over at volume.
     *
     * <p>Asserted, never inferred. A form that collected both values from the person standing in
     * front of it knows they are the same person. The platform matching them on its own would
     * eventually join two people's records, and the first evidence of that would be a call to
     * somebody who had withdrawn — strictly worse than the incompleteness it would be fixing.
     *
     * <p>An identifier already belonging to a <em>different</em> subject is left alone rather than
     * moved: {@code linkIdentifier} does nothing on conflict. Silently re-pointing it here would
     * let any capture surface reassign another person's identifier by asserting it in a form,
     * which is a merge without an administrator, a reason, or a record. Reconciling two subjects
     * that genuinely are one person goes through {@code POST /v1/admin/subjects/merge}, where it
     * is attributed and immutable.
     */
    private void linkAdditionalIdentifiers(String entityId, String subjectId,
                                           List<ConsentApi.SubjectRef> alsoKnownAs) {
        if (alsoKnownAs == null || alsoKnownAs.isEmpty()) {
            return;
        }
        for (ConsentApi.SubjectRef ref : alsoKnownAs) {
            subjects.linkIdentifier(entityId, subjectId, ref.identifierType(),
                    hasher.hash(ref.identifierType(), ref.value()));
        }
    }

    private ConsentApi.ConsentStateDto toStateDto(ConsentArtefact artefact, Instant at) {
        PurposeDefinition purpose = purposes.find(artefact.purposeCode()).orElse(null);
        return new ConsentApi.ConsentStateDto(
                artefact.purposeCode(),
                purpose == null ? artefact.purposeCode() : purpose.name(),
                artefact.purposeVersion(),
                artefact.effectiveStatus(at),
                artefact.legalBasis(),
                artefact.grantedAt(),
                artefact.expiresAt(),
                artefact.withdrawnAt(),
                // Surfaced so a preference centre can show a purpose the subject cannot switch
                // off as information rather than as a broken toggle — which is what a workforce
                // purpose resting on s.7(i) actually is.
                artefact.legalBasis() != null && artefact.legalBasis().requiresConsentRecord());
    }

    private static ConsentApi.EventSummary summarise(ConsentEvent event) {
        return new ConsentApi.EventSummary(event.eventId(), event.purposeCode(),
                event.purposeVersion(), event.type().name(), event.resultingStatus(),
                event.occurredAt(), event.expiresAt(), event.sequenceNumber(), event.eventHash());
    }

    private static ConsentApi.HistoryEntry toHistoryEntry(ConsentEvent event) {
        return new ConsentApi.HistoryEntry(
                event.eventId(), event.purposeCode(), event.purposeVersion(), event.type().name(),
                event.captureMethod() == null ? null : event.captureMethod().name(),
                event.channel() == null ? null : event.channel().name(),
                event.noticeId(), event.noticeVersion(), event.languageTag(),
                event.actorType() == null ? null : event.actorType().name(),
                event.actorId(), event.reason(), event.occurredAt(), event.recordedAt(),
                event.expiresAt(), event.sequenceNumber(), event.eventHash());
    }
}
