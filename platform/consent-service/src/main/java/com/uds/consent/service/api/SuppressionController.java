package com.uds.consent.service.api;

import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.IdentifierType;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.SuppressionScope;
import com.uds.consent.core.model.SuppressionSource;
import com.uds.consent.service.EnforcementRecorder;
import com.uds.consent.service.PlatformMetrics;
import com.uds.consent.service.SuppressionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Do-not-contact management and campaign scrubbing.
 *
 * <p>The scrub endpoint is the one that protects against the group's nearest-term regulatory risk.
 * TRAI acts against telemarketers today with financial penalties and disconnection, where DPDP's
 * substantive obligations arrive in May 2027 — so a dialer that skips this call is a live problem,
 * not a future one.
 */
@RestController
@RequestMapping("/v1/suppression")
public class SuppressionController {

    private final SuppressionService suppression;
    private final EnforcementRecorder recorder;
    private final PlatformMetrics metrics;

    public SuppressionController(SuppressionService suppression, EnforcementRecorder recorder,
                                 PlatformMetrics metrics) {
        this.suppression = suppression;
        this.recorder = recorder;
        this.metrics = metrics;
    }

    /** Records that someone asked not to be contacted on a channel. */
    @PostMapping("/opt-out")
    @PreAuthorize("hasAnyRole('CAPTURE', 'ADMIN')")
    public Map<String, Object> optOut(@Valid @RequestBody OptOutRequest request,
                                      Authentication authentication) {
        long id = suppression.optOut(
                request.entityId(),
                request.scope() == null ? SuppressionScope.ENTITY : request.scope(),
                request.source() == null ? SuppressionSource.INBOUND_OPT_OUT : request.source(),
                request.channel(),
                request.identifierType(),
                request.identifierValue(),
                request.subjectId(),
                request.clientId(),
                request.campaignId(),
                request.reason(),
                actorOf(authentication));
        return Map.of("suppressionId", id, "recorded", true);
    }

    /**
     * Removes contacts that must not be approached from a campaign list.
     *
     * <p>Call this immediately before sending, not when the list is built. A number added to the
     * preference register yesterday must be excluded today, and a list scrubbed at build time
     * would miss it — which is exactly the failing that draws enforcement.
     *
     * <p>Identifiers arrive in the clear and are hashed here; nothing plaintext is stored, and no
     * subject is created as a side effect of the check.
     *
     * <p>Every call writes one {@code scrub_run} row. That row is the artefact a TRAI
     * investigation asks for — not "was this number on the list" but "did you run the check, over
     * what population, and what came out". Until it existed the platform did the screening and
     * kept no evidence of having done it.
     *
     * <p>Recorded here rather than inside the service because the scrub itself runs in a
     * read-only transaction, and because the actor is an HTTP concern. The write is best-effort:
     * a scrub that succeeded must still return its answer if the evidence row cannot be written,
     * or a logging fault would stop the campaign it was screening.
     */
    @PostMapping("/scrub")
    @PreAuthorize("hasAnyRole('DECISION', 'ADMIN')")
    public SuppressionService.ScrubResult scrub(@Valid @RequestBody ScrubRequest request,
                                                Authentication authentication) {
        long start = System.nanoTime();
        SuppressionService.ScrubResult result = suppression.scrub(request.entityId(),
                request.channel(), request.identifierType(), request.identifiers(),
                request.clientId(), request.campaignId(), Instant.now());
        metrics.scrub(System.nanoTime() - start, request.identifiers().size(),
                result.excludedCount());

        recorder.recordScrub(request.entityId(), request.channel().name(), request.clientId(),
                request.campaignId(), actorOf(authentication), request.identifiers().size(),
                result);
        return result;
    }

    /**
     * Records a universal opt-out signal — Global Privacy Control or equivalent.
     *
     * <p>Twelve US states legally require honouring one, and it is actively enforced: the largest
     * settlement to date is $1.55m and three regulators ran a coordinated sweep in September 2025.
     * Until this endpoint existed the platform emitted an obligation to honour a GPC signal and
     * had no way to receive one, which is the wrong way round — an instruction to respect
     * something it could not record.
     *
     * <p>Recorded as a statutory suppression rather than as a consent withdrawal, because that is
     * what it is: it arrives from outside, it outranks any consent record, and it applies until
     * the subject says otherwise. The same machinery as a preference register, pointed at a
     * different source.
     *
     * <p>Open to {@code CAPTURE} as well as {@code ADMIN}, because the surface relaying the signal
     * is the web property that received the header — the same class of caller that submits
     * consent, and one that must never be turned away when it is trying to record a refusal.
     */
    @PostMapping("/universal-opt-out")
    @PreAuthorize("hasAnyRole('CAPTURE', 'ADMIN')")
    public Map<String, Object> universalOptOut(
            @Valid @RequestBody UniversalOptOutRequest request,
            Authentication authentication) {
        long id = suppression.optOut(
                request.entityId(),
                // Global scope. A universal opt-out is universal — honouring it only for the
                // entity whose page happened to carry the header would satisfy the letter of one
                // state's law and the purpose of none of them.
                SuppressionScope.GLOBAL,
                SuppressionSource.UNIVERSAL_OPT_OUT,
                request.channel(),
                request.identifierType(),
                request.identifierValue(),
                request.subjectId(),
                null,
                null,
                "Universal opt-out signal (" + (request.signal() == null ? "GPC" : request.signal())
                        + ") received from " + request.jurisdiction(),
                actorOf(authentication));

        return Map.of("suppressionId", id, "recorded", true,
                "statutory", true,
                "jurisdiction", request.jurisdiction().name(),
                // Reported back rather than assumed, so a surface relaying signals from a state
                // that does not mandate them can see that the platform honoured it anyway.
                "legallyRequired", request.jurisdiction().usesUniversalOptOut());
    }

    /**
     * @param signal       which signal was received — GPC today, whatever succeeds it later
     * @param jurisdiction the state the subject is in. Recorded because whether honouring was
     *                     legally required or merely correct is a question asked afterwards
     */
    public record UniversalOptOutRequest(
            @NotEmpty String entityId,
            @NotNull Jurisdiction jurisdiction,
            @NotNull Channel channel,
            @NotNull IdentifierType identifierType,
            @NotEmpty String identifierValue,
            String subjectId,
            String signal) {
    }

    /**
     * Loads a statutory registry export.
     *
     * <p>Entries loaded here are global and outrank any consent record: a number on the national
     * preference register is not contactable on a promotional purpose however valid the consent
     * on file appears.
     */
    @PostMapping("/registry")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> loadRegistry(@Valid @RequestBody RegistryLoadRequest request,
                                            Authentication authentication) {
        int written = suppression.loadStatutoryRegistry(request.source(), request.channel(),
                request.identifierType(), request.identifiers(), administratorOf(authentication));
        return Map.of("entriesLoaded", written, "source", request.source().name());
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

    public record OptOutRequest(
            String entityId,
            SuppressionScope scope,
            SuppressionSource source,
            @NotNull Channel channel,
            @NotNull IdentifierType identifierType,
            @NotEmpty String identifierValue,
            String subjectId,
            String clientId,
            String campaignId,
            String reason) {
    }

    public record ScrubRequest(
            @NotEmpty String entityId,
            @NotNull Channel channel,
            @NotNull IdentifierType identifierType,
            @NotEmpty List<String> identifiers,
            String clientId,
            String campaignId) {
    }

    public record RegistryLoadRequest(
            @NotNull SuppressionSource source,
            @NotNull Channel channel,
            @NotNull IdentifierType identifierType,
            @NotEmpty List<String> identifiers) {
    }
}
