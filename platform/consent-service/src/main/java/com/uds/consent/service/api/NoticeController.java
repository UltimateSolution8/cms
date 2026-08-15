package com.uds.consent.service.api;

import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.ledger.store.NoticeStore;
import com.uds.consent.service.NoticeService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Reading notices.
 *
 * <p>The current-notice endpoints are unauthenticated by design. A notice is the document a person
 * reads <em>before</em> deciding whether to give consent, and requiring a credential to read it
 * would mean the only people who can see what they agreed to are the systems that already have
 * their data. Rule 3 requires the notice to be accessible; a login is not accessible.
 *
 * <p>The historical-version endpoint is ADMIN. Superseded versions are evidence rather than
 * information — publishing every draft the group ever ran would hand a competitor a change log of
 * its legal position, and a data principal who wants the version they agreed to gets it through
 * their receipt, which cites it.
 */
@RestController
@RequestMapping("/v1/notices")
public class NoticeController {

    private final NoticeService notices;

    public NoticeController(NoticeService notices) {
        this.notices = notices;
    }

    /**
     * The notice to show someone now.
     *
     * <p>A missing translation is a 404 naming the languages that do exist, never a silent
     * substitution into English.
     */
    @GetMapping("/{noticeId}")
    public NoticeService.Rendered current(
            @PathVariable String noticeId,
            @RequestParam(defaultValue = "IN") Jurisdiction jurisdiction,
            @RequestParam(name = "lang", defaultValue = "en") String languageTag) {
        return notices.current(noticeId, jurisdiction, languageTag);
    }

    /** What a capture surface may offer, and the mandatory languages it cannot. */
    @GetMapping("/{noticeId}/languages")
    public NoticeService.LanguageAvailability languages(
            @PathVariable String noticeId,
            @RequestParam(defaultValue = "IN") Jurisdiction jurisdiction) {
        return notices.languages(noticeId, jurisdiction);
    }

    /**
     * The exact text of a superseded version.
     *
     * <p>This is the endpoint an audit runs against. Given a consent event from 2026 — which
     * carries the notice id, version and language — it returns what that person actually read,
     * which is the claim the whole evidence plane is built to support.
     */
    @GetMapping("/{noticeId}/versions/{version}")
    @PreAuthorize("hasRole('ADMIN')")
    public NoticeService.Rendered version(
            @PathVariable String noticeId,
            @PathVariable int version,
            @RequestParam(name = "lang", defaultValue = "en") String languageTag) {
        return notices.version(noticeId, version, languageTag);
    }

    /** Every published version, newest first — the notice's own change history. */
    @GetMapping("/{noticeId}/versions")
    @PreAuthorize("hasRole('ADMIN')")
    public List<NoticeStore.NoticeVersion> versions(@PathVariable String noticeId) {
        return notices.versions(noticeId);
    }

    /**
     * Mandatory-language coverage across an entity's notices.
     *
     * <p>Expected to read short until translation is procured. It says so rather than reporting
     * the languages that are present, because a coverage report that only counts successes is how
     * a gap survives a review.
     */
    @GetMapping("/reports/coverage")
    @PreAuthorize("hasRole('ADMIN')")
    public List<NoticeStore.Coverage> coverage(@RequestParam String entityId) {
        return notices.coverage(entityId);
    }
}
