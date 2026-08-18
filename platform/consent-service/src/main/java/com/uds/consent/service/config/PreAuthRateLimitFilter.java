package com.uds.consent.service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uds.consent.service.PlatformMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Refuses a flood before it reaches the password hasher.
 *
 * <p><strong>The measurement this exists for.</strong> Phase 12's load run established that
 * {@code RateLimitFilter} sits <em>behind</em> authentication — Spring Security's chain is ordered
 * {@code -100} and that filter is {@code LOWEST_PRECEDENCE - 120} — so 500 requests carrying an
 * invalid credential produced 500 × 401 and <em>zero</em> 429s. Each of those 401s cost the
 * platform one BCrypt verification at ~113 ms: the same price as a successful decision, for a
 * request that touches no database, evaluates no policy and returns an error body. One instance
 * serves roughly 50 rps on that path and the ceiling is CPU. A stranger holding no credential could
 * therefore saturate an instance, and every refusal cost the defender more than it cost them —
 * which is the property that makes a control not a safeguard. DPDP Rule 6 and GDPR Art. 32(1)(b)
 * both reach the availability of a system processing personal data, and both were being answered by
 * a filter that never ran for the traffic that mattered.
 *
 * <p><strong>This is a flood ceiling, not a fairness limit, and the distinction decides the
 * number.</strong> It runs before authentication, so it cannot know who is calling and keys purely
 * on the client address — which behind a corporate NAT, or behind an ingress without
 * {@code server.forward-headers-strategy} configured, is one bucket for an entire building or for
 * the whole fleet's traffic. A tight value here would refuse Denave's dialer at its ordinary 200/s.
 * So the default sits far above any legitimate per-address rate: its job is to make an
 * unauthenticated flood cost the attacker before it costs the defender, and nothing more. Fairness
 * between callers stays in {@link RateLimitFilter}, behind authentication, where the credential is
 * known and the ceilings can be per route class.
 *
 * <p><strong>{@code X-Forwarded-For} is deliberately ignored</strong>, for the reason
 * {@code RateLimitFilter.callerKey} already gives: it is caller-supplied, and trusting it would let
 * anyone reset their own bucket by inventing a header — which matters more here, where there is no
 * credential behind the address to fall back on. Where a trusted proxy is in front, configure
 * Boot's {@code server.forward-headers-strategy} and the remote address becomes correct for
 * everything at once rather than for this one class.
 *
 * <p><strong>Ordered ahead of the security chain, and that is the whole point.</strong>
 * {@code SecurityProperties.DEFAULT_FILTER_ORDER} is where Boot registers Spring Security; this
 * sits ten places in front of it. The assertion that proves the filter works is therefore not that
 * a 429 appears — that would pass with the filter still behind authentication — but that a request
 * carrying a <em>deliberately invalid</em> credential, over the ceiling, is answered <strong>429
 * and not 401</strong>. Only an order that puts this first can produce that.
 *
 * <p>This does not replace an ingress or WAF bucket, which is still worth having: an instance that
 * refuses a flood cheaply is still an instance receiving it. It does mean the platform no longer
 * depends on infrastructure it cannot prove is configured — {@code OPERATIONS.md} §12.2.
 */
@Component
@Order(SecurityProperties.DEFAULT_FILTER_ORDER - 10)
public class PreAuthRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PreAuthRateLimitFilter.class);

    /**
     * The metric's route-class label.
     *
     * <p>A constant rather than the address, for the reason {@code RateLimitFilter} already
     * records: a tag keyed by an attacker-chosen value is an unbounded time series, and a limiter
     * whose metric exhausts the monitoring system has moved the outage rather than prevented it.
     * The address goes in the log line, which ages out on retention.
     */
    private static final String METRIC_CLASS = "PRE_AUTH";

    private final PlatformProperties.RateLimit limits;
    private final PlatformMetrics metrics;
    private final ObjectMapper json;
    private final RateLimitBuckets buckets;

    public PreAuthRateLimitFilter(PlatformProperties properties, PlatformMetrics metrics,
                                  ObjectMapper json) {
        this.limits = properties.getRateLimit();
        this.metrics = metrics;
        this.json = json;
        this.buckets = new RateLimitBuckets(limits.getMaxTrackedCallers());
    }

    /**
     * Actuator is exempt, and this exemption matters more than the one it mirrors.
     *
     * <p>The probes are called by the orchestrator every few seconds. Refusing a readiness probe
     * during a flood would drain a healthy instance out of the load balancer at exactly the moment
     * the fleet needs it — turning a defence against overload into an amplifier of one. On a
     * separate management port the probes do not share this bucket in any case; the check stays so
     * that a single-port deployment behaves the same way.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!limits.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }
        PlatformProperties.RateLimit.Limit limit = limits.getPreAuth();

        String address = request.getRemoteAddr();
        if (buckets.tryAcquire(address, limit)) {
            chain.doFilter(request, response);
            return;
        }

        // WARN rather than ERROR: being limited is the control working. Logged with the address
        // and without the path, because at this point in the chain the path has not been
        // authorised and an attacker-chosen URI is a string kept for a year.
        log.warn("pre-authentication rate limit exceeded: address={} ({}/s, burst {})",
                address, limit.getPermitsPerSecond(), limit.getBurst());
        metrics.rateLimited(METRIC_CLASS);
        RateLimitRefusal.write(response, json,
                "Too many requests from this address. This ceiling applies before authentication, "
                        + "so it counts every request from here whether or not the credential on "
                        + "it is valid. The limit is " + limit.getPermitsPerSecond()
                        + " requests per second with a burst of " + limit.getBurst()
                        + ". Back off and retry; do not retry immediately in a loop.");
    }
}
