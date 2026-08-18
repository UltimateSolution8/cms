package com.uds.consent.service.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded token buckets, keyed by whatever the caller of this class decides identifies a caller.
 *
 * <p>Extracted from {@link RateLimitFilter} when a second limiter was added in front of
 * authentication. Two copies of an eviction-bounded map is two places to get eviction wrong, and
 * the argument for bounding it applies with more force to the pre-authentication limiter than to
 * the one it came from: that filter keys purely on a client address, which is a value the caller
 * chooses, so an unbounded map there is a memory-exhaustion primitive wearing the costume of a
 * defence against one.
 *
 * <p><strong>Token bucket, not fixed window.</strong> A fixed window lets a caller send two full
 * windows' worth across the boundary and be within its limit both times, which is exactly the shape
 * of a retry storm. A bucket refilling continuously has no boundary to exploit.
 *
 * <p><strong>Per instance, not fleet-wide.</strong> Four replicas allow four times any configured
 * number in aggregate, and a caller pinned to one instance is limited harder than one spread
 * evenly. That is a real limitation rather than a simplification worth glossing; it still does the
 * job it is here for, which is bounding what one caller does to one instance. Fleet-wide limiting
 * needs shared state the platform does not have, and when there is a Redis the counter moves there
 * and nothing else changes.
 */
final class RateLimitBuckets {

    /**
     * One bucket per key, oldest evicted under pressure.
     *
     * <p>A {@code LinkedHashMap} in access order behind a synchronised wrapper rather than a
     * {@code ConcurrentHashMap}: the map needs a bound, bounding it needs eviction, and eviction
     * needs to know what was used least recently. Contention is on a map operation measured in
     * nanoseconds, against a request that will spend milliseconds in the database.
     *
     * <p>Evicting means a caller may occasionally get a fresh bucket, which is the right way to
     * fail: a limiter that exhausts the heap has caused the outage it existed to prevent.
     */
    private final Map<String, Bucket> buckets;

    RateLimitBuckets(int capacity) {
        this.buckets = Collections.synchronizedMap(
                new LinkedHashMap<>(1024, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                        return size() > capacity;
                    }
                });
    }

    /** Takes one token for this key, or reports that there was none to take. */
    boolean tryAcquire(String key, PlatformProperties.RateLimit.Limit limit) {
        return buckets.computeIfAbsent(key, ignored -> new Bucket(limit.getBurst()))
                .tryAcquire(limit);
    }

    /**
     * A token bucket for one key.
     *
     * <p>Tokens held as a double and refilled from elapsed nanoseconds rather than by a scheduled
     * task. A refill thread would be one more thing to run, to size, and to get wrong, and it would
     * add latency quantised to its period; computing the refill on read is exact and costs a
     * subtraction.
     */
    private static final class Bucket {

        private double tokens;
        private long lastRefillNanos;

        Bucket(int burst) {
            this.tokens = burst;
            this.lastRefillNanos = System.nanoTime();
        }

        /**
         * Takes one token if there is one.
         *
         * <p>Synchronised on the bucket rather than lock-free. The contended case is one caller's
         * concurrent requests, the critical section is arithmetic, and a CAS loop here would be
         * more code defending against a cost that does not exist.
         */
        synchronized boolean tryAcquire(PlatformProperties.RateLimit.Limit limit) {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
            lastRefillNanos = now;

            tokens = Math.min(limit.getBurst(),
                    tokens + elapsedSeconds * limit.getPermitsPerSecond());

            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
