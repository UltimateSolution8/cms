package com.uds.consent.service;

import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.ledger.store.NoticeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Serving notices, including the exact one a subject read years ago.
 *
 * <p>The evidence plane's hardest promise is to reproduce in 2031 precisely what a person was
 * shown in 2026. Every consent event already points at a notice id, version and language; this is
 * the half that turns those three fields back into text. Without it the platform stored the
 * pointer and could not follow it.
 *
 * <p><strong>No silent language fallback.</strong> When the requested language has no translation
 * the answer is that it is missing, together with what is available. Substituting English would
 * produce a notice the subject cannot read and a consent record that looks valid and is not —
 * exactly the failure the language obligation exists to prevent. A caller that chooses to fall
 * back does so knowingly, and its choice is visible in its own code rather than hidden here.
 */
@Service
public class NoticeService {

    private static final Logger log = LoggerFactory.getLogger(NoticeService.class);

    private final NoticeStore store;

    public NoticeService(NoticeStore store) {
        this.store = store;
    }

    /**
     * The notice to show someone now.
     *
     * @param languageTag the language the subject asked for. Never silently substituted
     */
    @Transactional(readOnly = true)
    public Rendered current(String noticeId, Jurisdiction jurisdiction, String languageTag) {
        NoticeStore.NoticeVersion version = store.findCurrent(noticeId, jurisdiction.name())
                .orElseThrow(() -> new NoticeNotFoundException(
                        "no published notice '" + noticeId + "' for jurisdiction " + jurisdiction));
        return render(version, languageTag);
    }

    /**
     * A specific historical version — the audit and receipt reproduction path.
     *
     * <p>Takes no jurisdiction: a version number identifies a version outright, and requiring the
     * caller to also know the jurisdiction would mean a receipt could not be reproduced from the
     * consent event alone, which is all an auditor will have.
     */
    @Transactional(readOnly = true)
    public Rendered version(String noticeId, int version, String languageTag) {
        NoticeStore.NoticeVersion published = store.findVersion(noticeId, version)
                .orElseThrow(() -> new NoticeNotFoundException(
                        "notice '" + noticeId + "' has no version " + version));
        return render(published, languageTag);
    }

    /** Every published version of a notice, newest first. */
    @Transactional(readOnly = true)
    public List<NoticeStore.NoticeVersion> versions(String noticeId) {
        List<NoticeStore.NoticeVersion> versions = store.findVersions(noticeId);
        if (versions.isEmpty()) {
            throw new NoticeNotFoundException("no notice '" + noticeId + "'");
        }
        return versions;
    }

    /**
     * What a capture surface may offer, and what it cannot.
     *
     * <p>Returns the gap as well as the coverage. A language picker built only from
     * {@code available} looks complete to whoever builds it; one that can also see
     * {@code missingMandatory} is built by someone who knows the notice is not finished.
     */
    @Transactional(readOnly = true)
    public LanguageAvailability languages(String noticeId, Jurisdiction jurisdiction) {
        NoticeStore.NoticeVersion version = store.findCurrent(noticeId, jurisdiction.name())
                .orElseThrow(() -> new NoticeNotFoundException(
                        "no published notice '" + noticeId + "' for jurisdiction " + jurisdiction));

        List<String> available = store.availableLanguages(version.id());
        List<String> missing = store.missingMandatoryLanguages(noticeId, version.id());
        List<NoticeStore.LanguageRequirement> required = store.requiredLanguages(noticeId);

        return new LanguageAvailability(noticeId, version.version(), available, missing, required);
    }

    /**
     * Mandatory-language coverage across an entity's notices.
     *
     * <p>The number that says whether the group can currently give notice to everyone whose data
     * it processes. Expected to be well short of complete until translation is procured, and
     * expected to say so plainly rather than reporting the languages it does have.
     */
    @Transactional(readOnly = true)
    public List<NoticeStore.Coverage> coverage(String entityId) {
        return store.coverageForEntity(entityId);
    }

    private Rendered render(NoticeStore.NoticeVersion version, String languageTag) {
        Optional<NoticeStore.Translation> translation =
                store.findTranslation(version.id(), languageTag);

        if (translation.isEmpty()) {
            List<String> available = store.availableLanguages(version.id());
            // Logged at WARN because it is a real gap someone should close, not a client error.
            // The subject asked to be told something in their language and the group could not.
            log.warn("notice {} v{} has no '{}' translation; available: {}",
                    version.noticeId(), version.version(), languageTag, available);
            throw new TranslationNotAvailableException(version.noticeId(), version.version(),
                    languageTag, available);
        }

        return new Rendered(version.noticeId(), version.version(), version.jurisdiction(),
                translation.get().languageTag(), translation.get().title(),
                translation.get().body(), version.withdrawalUri(), version.rightsUri(),
                version.grievanceUri(), version.publishedAt(), version.materialChange());
    }

    /**
     * A notice as a subject sees it.
     *
     * <p>The three URIs are carried on every rendering rather than left to the surface, because
     * Rule 3 requires the notice itself to contain the means of withdrawing, exercising rights and
     * complaining to the Board. A surface that renders the body and drops the links has not given
     * a compliant notice, and the shape of this record makes that harder to do by accident.
     */
    public record Rendered(String noticeId, int version, String jurisdiction, String languageTag,
                           String title, String body, String withdrawalUri, String rightsUri,
                           String grievanceUri, java.time.Instant publishedAt,
                           boolean materialChange) {
    }

    public record LanguageAvailability(String noticeId, int version, List<String> available,
                                       List<String> missingMandatory,
                                       List<NoticeStore.LanguageRequirement> required) {

        public boolean complete() {
            return missingMandatory.isEmpty();
        }
    }

    /** No such notice, or none published for that jurisdiction. */
    public static class NoticeNotFoundException extends RuntimeException {

        public NoticeNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * The notice exists; the requested language does not.
     *
     * <p>A separate type from "not found" on purpose. The two need different responses: one means
     * the caller asked for something that does not exist, the other means the group has a
     * translation gap. Collapsing them would hide the second inside the first.
     */
    public static class TranslationNotAvailableException extends RuntimeException {

        private final String noticeId;
        private final int version;
        private final String requestedLanguage;
        private final List<String> availableLanguages;

        public TranslationNotAvailableException(String noticeId, int version,
                                                String requestedLanguage,
                                                List<String> availableLanguages) {
            super("notice " + noticeId + " v" + version + " is not available in '"
                    + requestedLanguage + "'. Available: " + availableLanguages);
            this.noticeId = noticeId;
            this.version = version;
            this.requestedLanguage = requestedLanguage;
            this.availableLanguages = List.copyOf(availableLanguages);
        }

        public String noticeId() {
            return noticeId;
        }

        public int version() {
            return version;
        }

        public String requestedLanguage() {
            return requestedLanguage;
        }

        public List<String> availableLanguages() {
            return availableLanguages;
        }
    }
}
