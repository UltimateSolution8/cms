package com.uds.consent.service.api;

import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.ledger.store.NoticeStore;
import com.uds.consent.ledger.store.PurposeRegistryStore;
import com.uds.consent.service.PublishingService;
import com.uds.consent.service.adapter.CachingPurposeCatalog;
import com.uds.consent.service.api.dto.ConsentApi;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The control plane, made writable.
 *
 * <p>Everything here is {@code ADMIN}, every call is audited with the authenticated principal, and
 * every write is an insert. The alternative this replaces was a Flyway migration and a deployment
 * per notice, per translation and per purpose — which is why ninety-five outstanding translations
 * had not moved.
 *
 * <p>Separate from {@link AdminController} rather than folded into it because publishing is the one
 * administrative surface that changes what the decision engine will answer. Keeping it in its own
 * class keeps that visible, and makes the eventual four-eyes approval a change to one file.
 */
@RestController
@RequestMapping("/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class PublishingController {

    private final PublishingService publishing;
    private final CachingPurposeCatalog purposes;

    public PublishingController(PublishingService publishing, CachingPurposeCatalog purposes) {
        this.publishing = publishing;
        this.purposes = purposes;
    }

    /**
     * Publishes the next version of a notice.
     *
     * <p>The response carries the blast radius computed <em>before</em> the insert. The operations
     * manual has always instructed running that calculation first; returning it here means a
     * publish cannot happen without its author being shown how many people it obliges them to go
     * back to.
     */
    @PostMapping("/notices/{noticeId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public PublishingService.PublishedNotice publishNotice(
            @PathVariable String noticeId,
            @Valid @RequestBody ConsentApi.PublishNoticeRequest request,
            Authentication authentication) {
        return publishing.publishNotice(noticeId, request.jurisdiction().name(),
                request.materialChange(), request.withdrawalUri(), request.rightsUri(),
                request.grievanceUri(), actorOf(authentication));
    }

    /** Adds a language to a published version. Not a version bump — see the service for why. */
    @PostMapping("/notices/{noticeId}/versions/{version}/translations")
    @ResponseStatus(HttpStatus.CREATED)
    public NoticeStore.Translation addTranslation(
            @PathVariable String noticeId,
            @PathVariable int version,
            @Valid @RequestBody ConsentApi.AddTranslationRequest request,
            Authentication authentication) {
        return publishing.addTranslation(noticeId, version, request.languageTag(), request.title(),
                request.body(), actorOf(authentication));
    }

    /**
     * Publishes the next version of a purpose, creating the purpose if it is new.
     *
     * <p>The cache is refreshed after the transaction commits rather than inside it. Refreshing
     * inside would load rows that a later rollback erases, leaving this instance answering
     * decisions from a version that does not exist — and the refresh is the point at which a
     * publish becomes live, so it must never be the point at which it becomes live incorrectly.
     *
     * <p>Only this instance is refreshed. Others converge within
     * {@code uds.consent.registry-refresh-interval}, or immediately via
     * {@code POST /v1/admin/purposes/refresh}.
     */
    @PostMapping("/purposes/{purposeCode}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public PublishingService.PublishedPurpose publishPurpose(
            @PathVariable String purposeCode,
            @Valid @RequestBody ConsentApi.PublishPurposeRequest request,
            Authentication authentication) {
        PublishingService.PublishedPurpose published = publishing.publishPurpose(
                toNewVersion(purposeCode, request), actorOf(authentication));
        purposes.refresh();
        return published;
    }

    private static PublishingService.NewVersion toNewVersion(
            String purposeCode, ConsentApi.PublishPurposeRequest request) {
        Map<Jurisdiction, PurposeRegistryStore.BasisEntry> bases = new LinkedHashMap<>();
        request.legalBases().forEach((jurisdiction, dto) -> bases.put(jurisdiction,
                new PurposeRegistryStore.BasisEntry(dto.legalBasis(), dto.assessmentRef(),
                        dto.notes())));

        Set<String> categories = request.dataCategories() == null ? Set.of()
                : Set.copyOf(request.dataCategories());
        Set<Channel> channels = request.channels() == null ? Set.of()
                : request.channels().stream().collect(Collectors.toUnmodifiableSet());

        return new PublishingService.NewVersion(purposeCode, request.name(), request.owner(),
                request.description(), bases, categories, channels, request.expiryPolicy(),
                request.expiryDays(), request.failureBehavior(), request.noticeId(),
                request.requiresSeparateConsent(), request.permittedForChildren(),
                request.materialChange(), request.retired());
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
}
