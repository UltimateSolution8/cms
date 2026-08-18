package com.uds.consent.service.api;

import com.uds.consent.core.model.IdentifierType;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.RightsRequestStatus;
import com.uds.consent.core.model.RightsRequestType;
import com.uds.consent.core.model.RightsVerificationMethod;
import com.uds.consent.ledger.store.RightsRequestStore;
import com.uds.consent.service.RightsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Filing and tracking rights requests.
 *
 * <p>Intake is a CAPTURE-role operation, matching consent: both are a data principal exercising
 * agency over their own data, arriving through the same surfaces. Everything that changes the
 * state of a request is ADMIN, because deciding whether a request has been met is a compliance
 * judgement, not something a web form does.
 *
 * <p>Fulfilment — federated retrieval across DenCRM, the HRMS and the BGV workflow — is the next
 * phase. What is here is the part that cannot be added afterwards: the clock.
 */
@RestController
@RequestMapping("/v1/rights")
public class RightsController {

    private final RightsService rights;

    public RightsController(RightsService rights) {
        this.rights = rights;
    }

    /**
     * Files a request and starts its clock.
     *
     * <p>The response carries {@code dueAt} and the basis it was computed from, so the surface can
     * tell the principal when to expect an answer. Telling them is not decoration: a person who
     * knows the date waits for it, and a person who does not escalates.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('CAPTURE', 'ADMIN')")
    public RightsRequestStore.Request file(@Valid @RequestBody FileRequest request,
                                           Authentication authentication) {
        boolean asserted = request.verifiedAs() != null && !request.verifiedAs().isBlank();

        return rights.intake(new RightsService.Intake(
                request.entityId(),
                request.subjectId(),
                request.identifierType(),
                request.identifierValue(),
                request.type(),
                request.jurisdiction() == null ? Jurisdiction.IN : request.jurisdiction(),
                request.receivedAt(),
                request.details(),
                // OPERATOR_ASSERTED means a *person* states they established identity, and until
                // Phase 16's closure it recorded authentication.getName() — the credential. One
                // password held by a compliance team, attributing the assurance to fifteen people
                // at once, while three artefacts including V30's own column comment claimed it
                // named somebody. Rules §5: a credential is not a person.
                //
                // So the assertion path demands the header and the machine path does not. That
                // split is deliberate and is the same one actorOf documents: a system filing a
                // request is not claiming to have checked anything, and requiring a human name
                // there would let any caller write one into evidence about an act no person
                // performed.
                asserted ? Actor.required(authentication).actorId() : actorOf(authentication),
                // The weaker reading is the default. An operator who did establish identity says
                // how and gets OPERATOR_ASSERTED; an empty field is recorded as UNVERIFIED rather
                // than left to look like every other request. Nothing is refused either way — see
                // RightsVerificationMethod, which is a label and not a gate.
                asserted ? RightsVerificationMethod.OPERATOR_ASSERTED
                        : RightsVerificationMethod.UNVERIFIED,
                request.verifiedAs()));
    }

    /** One request, including how long is left on it. */
    @GetMapping("/{requestId}")
    @PreAuthorize("hasAnyRole('CAPTURE', 'ADMIN')")
    public RightsRequestStore.Request get(@PathVariable String requestId) {
        return rights.find(requestId);
    }

    /** Records that the principal was told their request was received. */
    @PostMapping("/{requestId}/acknowledge")
    @PreAuthorize("hasAnyRole('CAPTURE', 'ADMIN')")
    public RightsRequestStore.Request acknowledge(@PathVariable String requestId) {
        rights.acknowledge(requestId);
        return rights.find(requestId);
    }

    /** Everything one person has filed. */
    @GetMapping("/subject/{entityId}/{subjectId}")
    @PreAuthorize("hasRole('ADMIN')")
    public List<RightsRequestStore.Request> forSubject(@PathVariable String entityId,
                                                       @PathVariable String subjectId) {
        return rights.forSubject(entityId, subjectId);
    }

    /** The work queue: open requests, soonest deadline first. */
    @GetMapping("/queue")
    @PreAuthorize("hasRole('ADMIN')")
    public List<RightsRequestStore.Request> queue(@RequestParam String entityId,
                                                   @RequestParam(defaultValue = "100") int limit,
                                                   @RequestParam(defaultValue = "0") int offset) {
        return rights.open(entityId, limit, offset);
    }

    /**
     * Requests already past their deadline.
     *
     * <p>Every row here is a statutory breach that has already happened. The endpoint exists so
     * that the number is available on demand and not only in a log line at 02:30.
     */
    @GetMapping("/overdue")
    @PreAuthorize("hasRole('ADMIN')")
    public List<RightsRequestStore.Request> overdue(
            @RequestParam(defaultValue = "200") int limit) {
        return rights.overdue(Instant.now(), limit);
    }

    /** Open, overdue, closed and closed-late counts per request type. */
    @GetMapping("/summary")
    @PreAuthorize("hasRole('ADMIN')")
    public List<RightsRequestStore.TypeSummary> summary(@RequestParam String entityId) {
        return rights.summarise(entityId);
    }

    /**
     * Moves a request along.
     *
     * <p>Closing one requires a resolution. Refusing an erasure request against data held under a
     * legal obligation is a legitimate outcome; refusing it without saying why is not.
     */
    @PatchMapping("/{requestId}")
    @PreAuthorize("hasRole('ADMIN')")
    public RightsRequestStore.Request transition(@PathVariable String requestId,
                                                  @Valid @RequestBody TransitionRequest request,
                                                  Authentication authentication) {
        return rights.transition(requestId, request.status(), request.assignedTo(),
                request.resolution(), administratorOf(authentication));
    }

    /**
     * Records what one downstream system did about a request.
     *
     * <p>The platform does not perform the act — nothing here can reach DenCRM, the HRMS or the
     * BGV workflow, and a connector written against a system nobody on this side can call would be
     * worse than none, because it would look like fulfilment. What this records is a named person's
     * attestation against a named system, with a reference a reviewer can follow somewhere other
     * than back into this platform.
     *
     * <p>{@code evidenceRef} is required for exactly that reason. "We erased it" with nothing
     * behind it is the same unevidenced assertion the resolution field already was, and the point
     * of this endpoint is to stop that being sufficient.
     */
    @PostMapping("/{requestId}/fulfilment")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> recordFulfilment(@PathVariable String requestId,
                                                @Valid @RequestBody FulfilmentRequest request,
                                                Authentication authentication) {
        long actionId = rights.recordFulfilment(requestId, request.systemCode(),
                request.actionType(), request.status(), request.evidenceRef(), request.detail(),
                administratorOf(authentication));
        return Map.of("actionId", actionId,
                "outstanding", rights.outstandingFulfilment(requestId));
    }

    /** What each system did, and which mandatory ones still have not. */
    @GetMapping("/{requestId}/fulfilment")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> fulfilment(@PathVariable String requestId) {
        return Map.of(
                "actions", rights.fulfilmentActions(requestId),
                "outstanding", rights.outstandingFulfilment(requestId));
    }

    /**
     * @param systemCode  the downstream system: DENCRM, HRMS, BGV
     * @param actionType  ERASED, EXPORTED, CORRECTED, NOTHING_HELD, REFUSED
     * @param status      COMPLETED or FAILED. Only COMPLETED satisfies a mandatory target — a
     *                    failed attempt is worth recording precisely because it must not count
     * @param evidenceRef a ticket id, an export hash, a deletion job reference. Required
     */
    public record FulfilmentRequest(@NotBlank String systemCode,
                                    @NotBlank String actionType,
                                    @NotBlank String status,
                                    @NotBlank String evidenceRef,
                                    String detail) {
    }

    /**
     * The caller, for routes a machine holds.
     *
     * <p>Deliberately the credential and not the {@code X-UDS-Actor} header. A dialer scrubbing a
     * campaign list, a website recording an opt-out and a CRM asserting provenance are systems,
     * not people, and there is no human behind the call to name. Recording the credential is the
     * accurate answer — and honouring a header on these routes would be worse than useless, since
     * it would let any capture surface write an arbitrary name into evidence about who did
     * something no person did.
     *
     * <p>The administrative routes in this controller use {@link #administratorOf} instead, which
     * requires the header. The split follows the authorisation model rather than the file: this
     * class serves both machine and console callers, and attribution should mean different things
     * for each.
     */
    private static String actorOf(Authentication authentication) {
        return authentication == null ? "anonymous" : authentication.getName();
    }

    /**
     * The person taking an administrative action, refusing when the caller did not say who.
     *
     * <p>Used on the ADMIN-only routes here. {@code compliance-console} is one credential held by
     * a team, so an append-only audit row naming it is permanently ambiguous about who authorised
     * the action — which is the one question an audit trail exists to answer.
     */
    private static String administratorOf(Authentication authentication) {
        return Actor.required(authentication).actorId();
    }

    /**
     * @param receivedAt when the principal asked, if that differs from when this was keyed in.
     *                   The clock runs from their act; a form filled in three days late does not
     *                   buy the group three extra days. Bounded in both directions — a value in
     *                   the future is refused with 400, because it would move the deadline
     *                   outward, and one older than {@code uds.consent.rights.max-backdate} is
     *                   refused as a sanity bound — a value that old is more likely a typo
     *                   than a filing. A late filing inside the bound is accepted, and is
     *                   recorded as having arrived overdue if it already had
     * @param verifiedAs how the operator established that the person filing is the principal: a
     *                   call-back to a number already on file, an employee ID checked at a desk, a
     *                   document reference. Optional, and its absence is recorded as
     *                   {@code UNVERIFIED} rather than passed over — the request is accepted
     *                   either way, and the row says which it was
     */
    public record FileRequest(
            @NotBlank String entityId,
            String subjectId,
            IdentifierType identifierType,
            String identifierValue,
            @NotNull RightsRequestType type,
            Jurisdiction jurisdiction,
            Instant receivedAt,
            String details,
            String verifiedAs) {
    }

    public record TransitionRequest(
            @NotNull RightsRequestStatus status,
            String assignedTo,
            String resolution) {
    }
}
