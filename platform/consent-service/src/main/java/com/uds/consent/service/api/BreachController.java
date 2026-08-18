package com.uds.consent.service.api;

import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.ledger.store.BreachStore;
import com.uds.consent.service.BreachService;
import com.uds.consent.service.sweeper.BreachSlaSweeper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
import java.util.Map;

/**
 * Personal data breach handling.
 *
 * <p>ADMIN throughout and every transition audited. A breach file whose history can be rewritten
 * is not a breach file — which is why the record itself is mutable (it is a working document for
 * seventy-two hours) while every change to it lands in the append-only administrative audit trail.
 */
@RestController
@RequestMapping("/v1/admin/breaches")
@PreAuthorize("hasRole('ADMIN')")
public class BreachController {

    private final BreachService breaches;
    private final BreachSlaSweeper sweeper;

    public BreachController(BreachService breaches, BreachSlaSweeper sweeper) {
        this.breaches = breaches;
        this.sweeper = sweeper;
    }

    /**
     * Files a breach and starts its clocks.
     *
     * <p>The response carries every obligation the filing has just created, with its deadline and
     * the rule it comes from. The first question anybody asks after reporting a breach is what
     * they now have to do and by when, and making them fetch that separately is how the "without
     * delay" leg gets missed.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BreachService.Reported report(@Valid @RequestBody ReportRequest request,
                                         Authentication authentication) {
        return breaches.report(request.entityId(), request.jurisdiction(), request.occurredAt(),
                request.detectedAt(),
                // Defaults to now rather than to the occurrence instant. Awareness is what every
                // clock runs from, and defaulting it backwards would silently start the countdown
                // earlier than the facts support.
                request.awareAt() == null ? Instant.now() : request.awareAt(),
                request.description(), request.dataCategories(), request.purposeCodes(),
                actorOf(authentication));
    }

    @GetMapping
    public List<BreachStore.Breach> forEntity(@RequestParam String entityId,
                                              @RequestParam(defaultValue = "100") int limit) {
        return breaches.forEntity(entityId, Math.min(limit, 1000));
    }

    @GetMapping("/{breachId}")
    public Map<String, Object> detail(@PathVariable String breachId) {
        return Map.of("breach", breaches.find(breachId),
                "notifications", breaches.notifications(breachId));
    }

    /**
     * Who was affected, computed from the ledger as at the breach instant.
     *
     * <p>Not as at now, and the distinction is the reason this endpoint is worth having: a subject
     * who withdrew the day after the breach — quite possibly because of the notification — was
     * still affected by it. This is the population the Rule 7 report has to summarise, and no
     * incident-management tool in the group can compute it.
     */
    @GetMapping("/{breachId}/affected")
    public List<BreachStore.AffectedSubject> affected(@PathVariable String breachId) {
        return breaches.affectedPopulation(breachId);
    }

    /** Records the risk assessment and whether the breach is notifiable. */
    @PostMapping("/{breachId}/assess")
    public Map<String, Object> assess(@PathVariable String breachId,
                                      @Valid @RequestBody AssessRequest request,
                                      Authentication authentication) {
        breaches.assess(breachId, request.severity(), request.riskAssessment(),
                request.affectedSubjects(), request.notifiable(), actorOf(authentication));
        return Map.of("breachId", breachId, "assessed", true,
                "notifiable", request.notifiable());
    }

    /** Records that an obligation was discharged. Refuses one that does not exist. */
    @PostMapping("/{breachId}/notifications/{notificationId}")
    public Map<String, Object> notify(@PathVariable String breachId,
                                      @PathVariable long notificationId,
                                      @Valid @RequestBody NotifyRequest request,
                                      Authentication authentication) {
        breaches.notify(breachId, notificationId,
                request.notifiedAt() == null ? Instant.now() : request.notifiedAt(),
                request.method(), request.reference(), request.recipientCount(), request.note(),
                actorOf(authentication));
        return Map.of("breachId", breachId, "notificationId", notificationId, "recorded", true);
    }

    /** Closes the file. Refused while any obligation is outstanding. */
    @PostMapping("/{breachId}/close")
    public Map<String, Object> close(@PathVariable String breachId,
                                     @Valid @RequestBody CloseRequest request,
                                     Authentication authentication) {
        breaches.close(breachId, request.closureNote(), actorOf(authentication));
        return Map.of("breachId", breachId, "closed", true);
    }

    /** Runs the SLA sweep now rather than waiting for the schedule. */
    @PostMapping("/sla/sweep")
    public BreachSlaSweeper.Report sweep() {
        return sweeper.run(Instant.now());
    }

    /**
     * The person taking the action, refusing the request if the caller did not say who.
     *
     * <p>This used to return {@code authentication.getName()} — the API credential — in six
     * identical copies across this package. {@code compliance-console} is one credential held by a
     * compliance team, so an append-only audit row attributing an action to it is permanently and
     * unfixably ambiguous about who authorised it, which is the one question an audit trail exists
     * to answer.
     *
     * <p>{@link Actor#required} throws when {@code X-UDS-Actor} is absent, which lands on the 400
     * handler. Applied to the audited operations rather than to every read: a query attributed to
     * the credential that ran it is accurate, and demanding the header on every GET would break
     * every integration for no attribution gained. The evidence bundle is the deliberate
     * exception — it is a GET, and it composes one named person's entire record, which is exactly
     * the disclosure worth having a name against.
     */
    private static String actorOf(Authentication authentication) {
        return Actor.required(authentication).actorId();
    }

    /**
     * @param occurredAt when the breach happened; the affected population is computed as at this
     * @param awareAt    when the group found out; every clock runs from this. Defaults to now
     */
    public record ReportRequest(
            @NotBlank String entityId,
            @NotNull Jurisdiction jurisdiction,
            @NotNull Instant occurredAt,
            Instant detectedAt,
            Instant awareAt,
            @NotBlank String description,
            List<String> dataCategories,
            List<String> purposeCodes) {

        public ReportRequest {
            dataCategories = dataCategories == null ? List.of() : List.copyOf(dataCategories);
            purposeCodes = purposeCodes == null ? List.of() : List.copyOf(purposeCodes);
        }
    }

    /**
     * @param notifiable the judgement that decides whether the group tells anyone. Recorded with
     *                   its reasoning because it is the call a regulator second-guesses hardest
     */
    public record AssessRequest(
            String severity,
            @NotBlank String riskAssessment,
            Integer affectedSubjects,
            boolean notifiable) {
    }

    public record NotifyRequest(
            Instant notifiedAt,
            String method,
            String reference,
            Integer recipientCount,
            String note) {
    }

    public record CloseRequest(@NotBlank String closureNote) {
    }
}
