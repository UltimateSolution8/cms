package com.uds.consent.service.api;

import com.uds.consent.core.model.IdentifierType;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.RightsRequestStatus;
import com.uds.consent.core.model.RightsRequestType;
import com.uds.consent.ledger.store.RightsRequestStore;
import com.uds.consent.service.RightsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

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
        return rights.intake(new RightsService.Intake(
                request.entityId(),
                request.subjectId(),
                request.identifierType(),
                request.identifierValue(),
                request.type(),
                request.jurisdiction() == null ? Jurisdiction.IN : request.jurisdiction(),
                request.receivedAt(),
                request.details(),
                actorOf(authentication)));
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
                request.resolution(), actorOf(authentication));
    }

    private static String actorOf(Authentication authentication) {
        return authentication == null ? "anonymous" : authentication.getName();
    }

    /**
     * @param receivedAt when the principal asked, if that differs from when this was keyed in.
     *                   The clock runs from their act; a form filled in three days late does not
     *                   buy the group three extra days
     */
    public record FileRequest(
            @NotBlank String entityId,
            String subjectId,
            IdentifierType identifierType,
            String identifierValue,
            @NotNull RightsRequestType type,
            Jurisdiction jurisdiction,
            Instant receivedAt,
            String details) {
    }

    public record TransitionRequest(
            @NotNull RightsRequestStatus status,
            String assignedTo,
            String resolution) {
    }
}
