package com.uds.consent.service.it;

import com.uds.consent.ledger.store.NoticeStore;
import com.uds.consent.service.PublishingService;
import com.uds.consent.service.adapter.CachingNoticeLookup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The notice cache remembers what exists and never remembers what does not.
 *
 * <p>A deliberate and slightly surprising choice, and the reason this suite exists is that the next
 * person to look at {@code CachingNoticeLookup} will see a cache whose miss path pays a query every
 * time and reach for the obvious optimisation. This is the test that tells them what it would cost.
 *
 * <p>The asymmetry is between two failures that are not remotely equivalent. A stale
 * <em>positive</em> means a capture accepted against a notice version that does exist — harmless,
 * and self-correcting at the next refresh. A stale <em>negative</em> means a capture
 * <strong>rejected</strong> against a notice published two minutes ago: real consent, freely given
 * by a real person, dropped on the floor because a cache had not ticked. For a control whose whole
 * purpose is preserving evidence that is the worst failure on offer, and it is invisible — the
 * subject sees an error, retries or gives up, and nothing anywhere records that consent was lost.
 */
class NoticeCacheIT extends PostgresIntegrationTest {

    /**
     * A notice of this suite's own, registered here rather than borrowed from the seed data.
     *
     * <p>Publishing versions of a shared notice would change what every other suite's assertions
     * about language coverage are reading — the current version is what {@code NoticeService}
     * answers with, so a version published here with one translation makes another suite's "this
     * notice has English and Hindi" false. Found exactly that way.
     *
     * <p>Registered under MATRIX rather than DENAVE_IN for the second half of the same problem: a
     * new notice under Denave appears in Denave's coverage report, which another suite asserts over
     * every row. A fixture that is invisible to the suites around it has to be invisible in both
     * directions.
     */
    private static final String NOTICE = "NOTICE_CACHE_SUITE";
    private static final String LANGUAGE = "en";

    @Autowired
    private CachingNoticeLookup lookup;

    @Autowired
    private NoticeStore notices;

    @Autowired
    private PublishingService publishing;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @org.junit.jupiter.api.BeforeEach
    void registerNotice() {
        jdbc.update("""
                insert into notice (notice_id, entity_id, name)
                values (?, 'MATRIX', 'Cache suite fixture notice')
                on conflict (notice_id) do nothing
                """, NOTICE);
    }

    @Test
    @DisplayName("a version published after the miss is visible immediately, with no refresh")
    void negativesAreNotRemembered() {
        int unpublished = highestVersion() + 1;

        // The miss. Under a cache that memoised negatives, this call is what would poison the
        // answer for the whole refresh interval.
        assertThat(lookup.exists(NOTICE, unpublished)).isFalse();

        PublishingService.PublishedNotice published = publishing.publishNotice(NOTICE, "IN", false,
                "https://uds.example/withdraw", "https://uds.example/rights",
                "https://uds.example/grievance", "notice-cache-it");

        // No refresh() in between, and that is the assertion. The window between publishing a
        // notice and being able to take consent against it is zero, not "up to the refresh
        // interval" — because the alternative is a capture surface rejecting valid consent while
        // the compliance officer who has just published watches it happen and cannot explain why.
        assertThat(lookup.exists(NOTICE, published.version().version()))
                .withFailMessage("a negative was cached; consent captured now would be rejected")
                .isTrue();
    }

    @Test
    @DisplayName("a version with no translation yet is still a real version")
    void existenceAndLanguagesAreDifferentFacts() {
        // A version and its languages arrive at different times — PublishingService allows that
        // deliberately. An empty language set read as "no such version" would turn the gap between
        // the two writes into a window in which the notice does not exist.
        PublishingService.PublishedNotice published = publishing.publishNotice(NOTICE, "IN", false,
                "https://uds.example/withdraw", "https://uds.example/rights",
                "https://uds.example/grievance", "notice-cache-it");
        int version = published.version().version();

        assertThat(lookup.exists(NOTICE, version)).isTrue();
        assertThat(lookup.hasTranslation(NOTICE, version, "brx")).isFalse();

        publishing.addTranslation(NOTICE, version, LANGUAGE, "Cache test notice",
                "Text served for the cache suite.", "notice-cache-it");

        // And the language becomes visible without a refresh, for the same reason: a translation
        // added a minute ago is a language the subject can be shown, and reporting it missing
        // would refuse a capture in the one language the surface was about to render.
        assertThat(lookup.hasTranslation(NOTICE, version, LANGUAGE)).isTrue();
    }

    @Test
    @DisplayName("a positive survives, and a refresh does not change the answer")
    void positivesAreCachedAndTheRefreshIsInvisible() {
        // The half that justifies having a cache at all. What must never be observable is a
        // difference in the answer across a refresh: a cache whose correctness depends on when it
        // was last cleared is one that gets blamed for something else's bug.
        int version = publishing.publishNotice(NOTICE, "IN", false,
                "https://uds.example/withdraw", "https://uds.example/rights",
                "https://uds.example/grievance", "notice-cache-it").version().version();
        publishing.addTranslation(NOTICE, version, LANGUAGE, "Cache test notice",
                "Text served for the cache suite.", "notice-cache-it");

        assertThat(lookup.exists(NOTICE, version)).isTrue();
        assertThat(lookup.hasTranslation(NOTICE, version, LANGUAGE)).isTrue();

        lookup.refresh();

        assertThat(lookup.exists(NOTICE, version)).isTrue();
        assertThat(lookup.hasTranslation(NOTICE, version, LANGUAGE)).isTrue();
    }

    private int highestVersion() {
        return notices.findVersions(NOTICE).stream()
                .mapToInt(NoticeStore.NoticeVersion::version)
                .max()
                .orElse(0);
    }
}
