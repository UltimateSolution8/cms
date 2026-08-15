package com.uds.consent.service.api;

import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.IdentifierType;
import com.uds.consent.core.model.SuppressionScope;
import com.uds.consent.core.model.SuppressionSource;
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

    public SuppressionController(SuppressionService suppression) {
        this.suppression = suppression;
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
     */
    @PostMapping("/scrub")
    @PreAuthorize("hasAnyRole('DECISION', 'ADMIN')")
    public SuppressionService.ScrubResult scrub(@Valid @RequestBody ScrubRequest request) {
        return suppression.scrub(request.entityId(), request.channel(), request.identifierType(),
                request.identifiers(), request.clientId(), request.campaignId(), Instant.now());
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
                request.identifierType(), request.identifiers(), actorOf(authentication));
        return Map.of("entriesLoaded", written, "source", request.source().name());
    }

    private static String actorOf(Authentication authentication) {
        return authentication == null ? "anonymous" : authentication.getName();
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
