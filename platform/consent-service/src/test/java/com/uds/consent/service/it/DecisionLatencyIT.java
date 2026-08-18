package com.uds.consent.service.it;

import com.uds.consent.core.decision.DecisionRequest;
import com.uds.consent.core.decision.DecisionResponse;
import com.uds.consent.core.model.ActorType;
import com.uds.consent.core.model.CaptureMethod;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.policy.PolicyEngine;
import com.uds.consent.policy.capture.CaptureSubmission;
import com.uds.consent.service.ConsentCaptureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The service level objectives, measured rather than published.
 *
 * <p>{@code OPERATIONS.md} §6 commits to four numbers. Until this suite existed, none of them was
 * asserted anywhere: {@code ObservabilityIT} proved that a decision was <em>timed</em> and said
 * nothing about the time. Meanwhile a single decision makes several sequential round trips —
 * artefacts, suppression, provenance, subject age, vendors, and on the Korean path a
 * re-confirmation lookup added in the last release — and {@code /v1/evaluate/batch} does that N
 * times in a loop. A query was added to the hot path and nothing in the build could have noticed.
 *
 * <p><strong>Two assertions, and the second is the deliverable.</strong>
 *
 * <p>The latency floor is deliberately far looser than the SLO. The objective is p95 &lt; 30 ms on
 * production hardware; this asserts a much larger figure against a Testcontainers Postgres on
 * whatever machine happens to be running, under a JVM that has barely warmed. A test asserting the
 * real SLO would fail on CI for reasons having nothing to do with the code, be marked flaky, and
 * then be deleted — which is worse than not having it, because the deletion looks like a decision
 * somebody made about performance. What this catches is the order-of-magnitude regression: an N+1
 * on the hot path, a missing index, a cache that quietly stopped caching.
 *
 * <p>The <strong>query count</strong> is the assertion that earns its place. It is identical on a
 * laptop and on CI, it is exactly what regressed when the Korean lookup was added, and it says
 * <em>why</em> a latency test failed rather than only that it did. If the two ever disagree, trust
 * the count.
 *
 * <p>The batch path is pinned separately, because "the batch costs N times the single" is a
 * property somebody should have to change on purpose rather than discover during a campaign.
 */
@Import(DecisionLatencyIT.CountingDataSourceConfiguration.class)
class DecisionLatencyIT extends PostgresIntegrationTest {

    private static final String ENTITY = "DENAVE_IN";
    private static final String APP = "DENAVE_WEB";
    private static final String DIALER = "ATHENA_DIALER";
    private static final String NOTICE = "NOTICE_DENAVE_B2B";
    private static final String PURPOSE = "MKT_OUTBOUND_CALL";

    /** Enough samples for a p95 to mean something, few enough that the suite stays a test. */
    private static final int SAMPLES = 300;

    /** Discarded. The first decisions pay for cache population and JIT, and are not the question. */
    private static final int WARMUP = 50;

    /**
     * The floor, five times the published objective and then some.
     *
     * <p>Not the SLO. See the class javadoc: this is the figure that separates "the machine is
     * busy" from "somebody put a query in a loop".
     */
    private static final Duration P95_FLOOR = Duration.ofMillis(150);

    /**
     * Round trips one Indian voice decision is allowed to cost.
     *
     * <p>Six, and the ceiling is the measured figure rather than a comfortable one above it. A pin
     * with slack in it is not a pin: an extra query could be added and the test would still pass,
     * which is precisely the situation this replaces. Derived by measurement, not by design.
     *
     * <p>Raising it is a legitimate change — raising it <em>without noticing</em> is what this
     * exists to prevent. On the Korean path the figure is higher by one, because the Art. 62-3
     * re-confirmation lookup runs there; that path is not pinned here, and a KR pin is the obvious
     * next addition if this proves its worth.
     */
    private static final int MAX_QUERIES_PER_DECISION = 6;

    @Autowired
    private PolicyEngine engine;

    @Autowired
    private ConsentCaptureService capture;

    @Autowired
    private CountingDataSource counting;

    private String subject;

    @BeforeEach
    void grantConsent() {
        subject = "lat-" + UUID.randomUUID();
        ConsentCaptureService.Result result = capture.capture(new CaptureSubmission(ENTITY, subject,
                Jurisdiction.IN, "en", Channel.WEB, APP, CaptureMethod.CHECKBOX_OPT_IN,
                ActorType.SUBJECT, subject, NOTICE, 1,
                List.of(CaptureSubmission.PurposeChoice.acceptedSeparately(PURPOSE)),
                true, Instant.now().truncatedTo(ChronoUnit.SECONDS), "lat-" + subject, null,
                Map.of()));

        assertThat(result.isAccepted())
                .withFailMessage("capture rejected: %s", result.violations())
                .isTrue();
    }

    @Test
    @DisplayName("a single decision stays inside a floor set well above the published objective")
    void theDecisionPathIsNotOrdersOfMagnitudeOffTheObjective() {
        for (int i = 0; i < WARMUP; i++) {
            evaluate();
        }

        long[] nanos = new long[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            long start = System.nanoTime();
            DecisionResponse decision = evaluate();
            nanos[i] = System.nanoTime() - start;
            // Measuring the wrong thing is the classic way a performance test passes forever: a
            // decision that started failing fast would look like an improvement.
            assertThat(decision.isAllowed()).isTrue();
        }

        Arrays.sort(nanos);
        Duration p95 = Duration.ofNanos(nanos[(int) (SAMPLES * 0.95)]);

        assertThat(p95)
                .withFailMessage("""
                        p95 decision latency was %s, floor %s. This floor is five times the \
                        published objective, so exceeding it is not a slow machine — look for a \
                        query added to the hot path, a lost index, or a cache that stopped \
                        caching. The query-count test in this class says which.""",
                        p95, P95_FLOOR)
                .isLessThan(P95_FLOOR);
    }

    @Test
    @DisplayName("one decision costs a pinned number of round trips, and adding one is deliberate")
    void theRoundTripCountIsPinned() {
        // Warm first. The caches are populated on the first read and counting a cold decision would
        // pin the cost of the cache miss rather than of the decision.
        for (int i = 0; i < WARMUP; i++) {
            evaluate();
        }

        counting.reset();
        evaluate();
        counting.stop();

        assertThat(counting.count())
                .withFailMessage("""
                        one decision now costs %d database round trips, ceiling %d. If the extra \
                        query is intentional, raise MAX_QUERIES_PER_DECISION and say in the commit \
                        why the hot path is worth it — Athena's dialer pre-flights every call \
                        against this endpoint, so the cost is paid per outbound contact, not per \
                        deploy.""", counting.count(), MAX_QUERIES_PER_DECISION)
                .isLessThanOrEqualTo(MAX_QUERIES_PER_DECISION);
    }

    @Test
    @DisplayName("a batch costs its size times a single decision, and nothing worse")
    void theBatchPathDoesNotAmplify() {
        // The batch endpoint loops. That is a deliberate and documented choice — a batched
        // implementation would be a second decision path to keep correct, and correctness beats
        // throughput on the gate that decides whether somebody gets called. What must not happen
        // is silent super-linear growth: a per-request lookup that used to be cached, or a
        // transaction opened per element.
        for (int i = 0; i < WARMUP; i++) {
            evaluate();
        }

        counting.reset();
        evaluate();
        counting.stop();
        int single = counting.count();

        int batchSize = 20;
        counting.reset();
        for (int i = 0; i < batchSize; i++) {
            evaluate();
        }
        counting.stop();

        assertThat(counting.count())
                .withFailMessage("""
                        a batch of %d cost %d round trips against %d for one decision — more than \
                        linear. Something in the loop is not being reused across the batch.""",
                        batchSize, counting.count(), single)
                .isLessThanOrEqualTo(single * batchSize);
    }

    private DecisionResponse evaluate() {
        return engine.evaluate(new DecisionRequest(ENTITY, subject, PURPOSE, Channel.VOICE_CALL,
                Jurisdiction.IN, DIALER, Instant.now(), null, null, null, Map.of()));
    }

    /**
     * Wraps whatever DataSource the application built, rather than building a second one.
     *
     * <p>A post-processor so that every store, the connection pool and the RLS session settings are
     * unchanged and what gets counted is the path production uses. Registered on this class alone —
     * it gives the suite its own application context, which costs one extra context start and keeps
     * a counting proxy out of every other test's hot path.
     */
    @TestConfiguration
    static class CountingDataSourceConfiguration {

        /**
         * Static, as Spring requires of a BeanPostProcessor factory method: a non-static one forces
         * the whole configuration class to be instantiated before post-processing begins, which
         * would drag its dependencies up with it.
         */
        @Bean
        static BeanPostProcessor countingDataSourceWrapper() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName)
                        throws BeansException {
                    return bean instanceof DataSource dataSource
                            && !(bean instanceof CountingDataSource)
                            ? new CountingDataSource(dataSource)
                            : bean;
                }
            };
        }
    }
}
