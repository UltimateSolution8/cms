package com.uds.consent.service.adapter;

import com.uds.consent.ledger.store.NoticeStore;
import com.uds.consent.policy.port.PolicyPorts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds which notice versions exist, and in which languages.
 *
 * <p>On the capture path, so it is cached for the same reason the purpose registry is: the answer
 * changes when a human publishes, not with traffic, and a database round trip per capture to
 * answer a question with a few hundred possible answers is a cost with no benefit.
 *
 * <p><strong>Negative answers are not cached.</strong> A miss falls through to the store. That is
 * the whole design decision here, and it is the opposite of what a cache usually wants: the
 * consequence of a stale positive is a capture accepted against a notice that exists, which is
 * fine, while the consequence of a stale negative is a capture <em>rejected</em> against a notice
 * that was published two minutes ago — real consent dropped on the floor because a cache had not
 * ticked. That is the worst failure available to a control whose entire purpose is preserving
 * evidence, so the miss path pays a query and remembers the answer.
 *
 * <p>The same rule reaches one level further down: a published version whose translations have not
 * been written yet is <em>also</em> not memoised. It looks like a positive — the version exists —
 * but memoising it would answer "that language is missing" for every question until the next
 * refresh, which rejects a capture in the language the surface was about to render. A version and
 * its languages are written seconds apart and a probe landing in that gap is entirely ordinary.
 */
@Component
@DependsOnDatabaseInitialization
public class CachingNoticeLookup implements PolicyPorts.NoticeLookup {

    private static final Logger log = LoggerFactory.getLogger(CachingNoticeLookup.class);

    private final NoticeStore store;

    /** notice:version → what is known about it. Populated lazily, dropped wholesale on refresh. */
    private final Map<String, Known> known = new ConcurrentHashMap<>();

    public CachingNoticeLookup(NoticeStore store) {
        this.store = store;
    }

    @Override
    public boolean exists(String noticeId, int version) {
        return resolve(noticeId, version).published();
    }

    @Override
    public boolean hasTranslation(String noticeId, int version, String languageTag) {
        return resolve(noticeId, version).languages().contains(languageTag);
    }

    /**
     * Existence and languages together, in one memo.
     *
     * <p>Both are needed because they are genuinely different facts: a version published minutes
     * ago with nothing translated yet is a real version — {@code PublishingService} allows that
     * deliberately, since a version and its languages arrive at different times — and an empty
     * language set must never be read as "no such version".
     */
    private Known resolve(String noticeId, int version) {
        Known memo = known.get(key(noticeId, version));
        if (memo != null && memo.published()) {
            return memo;
        }
        // Note what is *not* memoised, below: a published version with no translations yet. That
        // combination is a negative wearing a positive's clothes — the version exists, so the memo
        // would be kept, and every language question about it would then answer "missing" until
        // the next refresh. A version and its languages are written seconds apart, and a capture
        // surface probing in that gap would poison the answer for the whole interval, rejecting
        // real consent in the one language it was about to render. Same failure as a cached
        // negative, one level down, and it was found by NoticeCacheIT rather than by reasoning.
        // A negative is re-read rather than remembered. The consequence of a stale positive is a
        // capture accepted against a notice that exists; the consequence of a stale negative is
        // real consent rejected because a cache had not ticked since the publish two minutes ago.
        Known fresh = store.findVersion(noticeId, version)
                .map(found -> new Known(true, Set.copyOf(store.availableLanguages(found.id()))))
                .orElseGet(() -> new Known(false, Set.of()));
        if (fresh.published() && !fresh.languages().isEmpty()) {
            known.put(key(noticeId, version), fresh);
        }
        return fresh;
    }

    /**
     * Drops the memo.
     *
     * <p>On the same timer as the other registries, so that a translation added on one instance
     * stops being reported as missing by the others within the refresh interval rather than at the
     * next restart.
     */
    @Scheduled(fixedDelayString = "${uds.consent.registry-refresh-interval:PT5M}")
    public void refresh() {
        int held = known.size();
        known.clear();
        log.debug("notice lookup cleared: {} version(s) will be re-read on demand", held);
    }

    private static String key(String noticeId, int version) {
        return noticeId + ':' + version;
    }

    private record Known(boolean published, Set<String> languages) {
    }
}
