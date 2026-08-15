package com.uds.consent.service.it;

import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.ledger.store.NoticeStore;
import com.uds.consent.service.NoticeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reproducing exactly what a person read.
 *
 * <p>The evidence plane's hardest promise: given a consent event from 2026 carrying a notice id, a
 * version and a language, return the text that was actually on the screen. Everything else the
 * platform proves rests on this — a consent record pointing at a notice nobody can produce is a
 * pointer into nothing.
 *
 * <p>The second theme here is the refusal to fall back. A missing translation must report as
 * missing. Silently returning English would produce a notice the subject cannot read and a consent
 * record that looks valid and is not, which is worse than either failure alone.
 */
class NoticeIT extends PostgresIntegrationTest {

    private static final String NOTICE = "NOTICE_DENAVE_B2B";

    @Autowired
    private NoticeService notices;

    @Autowired
    private NoticeStore store;

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("the current notice renders with the three URIs Rule 3 requires")
    void currentNoticeCarriesItsRule3Links() {
        NoticeService.Rendered rendered = notices.current(NOTICE, Jurisdiction.IN, "en");

        assertThat(rendered.title()).isNotBlank();
        assertThat(rendered.body()).isNotBlank();
        // Rule 3 requires the notice itself to contain the means of withdrawing, exercising rights
        // and complaining to the Board. A surface that renders the body and drops these has not
        // given a compliant notice.
        assertThat(rendered.withdrawalUri()).startsWith("https://");
        assertThat(rendered.rightsUri()).startsWith("https://");
        assertThat(rendered.grievanceUri()).startsWith("https://");
    }

    @Test
    @DisplayName("a historical version renders byte-identically to what is stored")
    void historicalVersionIsReproducedExactly() {
        NoticeService.Rendered rendered = notices.version(NOTICE, 1, "en");
        NoticeStore.NoticeVersion published = store.findVersion(NOTICE, 1).orElseThrow();
        NoticeStore.Translation stored =
                store.findTranslation(published.id(), "en").orElseThrow();

        assertThat(rendered.title()).isEqualTo(stored.title());
        assertThat(rendered.body()).isEqualTo(stored.body());
        assertThat(rendered.version()).isEqualTo(1);
    }

    @Test
    @DisplayName("a language with no translation reports as missing, never as English")
    void missingTranslationIsNotSilentlySubstituted() {
        assertThatThrownBy(() -> notices.current(NOTICE, Jurisdiction.IN, "brx"))
                .isInstanceOf(NoticeService.TranslationNotAvailableException.class)
                .satisfies(e -> {
                    NoticeService.TranslationNotAvailableException missing =
                            (NoticeService.TranslationNotAvailableException) e;
                    assertThat(missing.requestedLanguage()).isEqualTo("brx");
                    assertThat(missing.availableLanguages()).contains("en");
                });
    }

    @Test
    @DisplayName("over HTTP a missing translation is a 404 that names what does exist")
    void missingTranslationOverHttpNamesTheAlternatives() {
        // The capture surface needs enough to offer a real choice of language rather than a false
        // one, so the alternatives travel with the error.
        ResponseEntity<String> response =
                rest.getForEntity("/v1/notices/" + NOTICE + "?jurisdiction=IN&lang=sat",
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("availableLanguages").contains("\"en\"");
    }

    @Test
    @DisplayName("reading the current notice needs no credential")
    void currentNoticeIsPublic() {
        // A notice is what someone reads before deciding whether to consent. Requiring a
        // credential would mean the only people who can see what they agreed to are the systems
        // that already hold their data.
        ResponseEntity<String> response =
                rest.getForEntity("/v1/notices/" + NOTICE + "?jurisdiction=IN&lang=en",
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("superseded versions are not public, because they are evidence")
    void historicalVersionsRequireAdmin() {
        assertThat(rest.getForEntity("/v1/notices/" + NOTICE + "/versions/1", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(rest.withBasicAuth("compliance-console", "admin-secret")
                .getForEntity("/v1/notices/" + NOTICE + "/versions/1?lang=en", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("the language list reports the gap as well as the coverage")
    void languageListNamesWhatIsMissing() {
        NoticeService.LanguageAvailability availability =
                notices.languages(NOTICE, Jurisdiction.IN);

        // Two of twenty-three for this notice. Tamil is seeded for the workforce notice, not this
        // one — which is itself the shape of the problem: coverage is per notice, and a group that
        // reports "we have Tamil" without saying which notices have it is reporting nothing.
        assertThat(availability.available()).contains("en", "hi");
        // Nineteen of the twenty-two Eighth Schedule languages are not translated yet. That is a
        // procurement gap rather than an engineering one, and the platform's job is to keep it
        // visible instead of reporting only the languages it happens to have.
        assertThat(availability.missingMandatory()).contains("brx", "kok", "sat");
        assertThat(availability.complete()).isFalse();
        assertThat(availability.required()).hasSize(23);
    }

    @Test
    @DisplayName("coverage across an entity's notices reads short, and says so")
    void coverageReportsTheShortfall() {
        List<NoticeStore.Coverage> coverage = notices.coverage("DENAVE_IN");

        assertThat(coverage).isNotEmpty();
        assertThat(coverage).allSatisfy(entry -> {
            assertThat(entry.requiredLanguages()).isEqualTo(23);
            assertThat(entry.missingLanguages()).isPositive();
            assertThat(entry.complete()).isFalse();
        });
    }

    @Test
    @DisplayName("asking for a notice that does not exist is a 404, not an empty 200")
    void unknownNoticeIsNotFound() {
        assertThatThrownBy(() -> notices.current("NOTICE_DOES_NOT_EXIST", Jurisdiction.IN, "en"))
                .isInstanceOf(NoticeService.NoticeNotFoundException.class);
    }

    @Test
    @DisplayName("published notice versions cannot be edited, so the reproduction stays true")
    void publishedVersionsRemainImmutable() {
        // Belt and braces with LedgerAppendOnlyIT. That suite proves the trigger fires; this one
        // states why it matters here — every assertion above about reproducing 2026's text is
        // worth nothing if the text can be quietly rewritten in 2029.
        NoticeStore.NoticeVersion published = store.findVersion(NOTICE, 1).orElseThrow();
        assertThat(published.publishedAt()).isNotNull();
        assertThat(store.findTranslation(published.id(), "en")).isPresent();
    }
}
