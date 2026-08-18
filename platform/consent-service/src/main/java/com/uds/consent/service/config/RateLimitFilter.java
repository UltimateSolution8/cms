package com.uds.consent.service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uds.consent.service.PlatformMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Bounds what one caller can do per second.
 *
 * <p>The platform had no rate limiting anywhere, including on the two routes that need no
 * credential: {@code GET /v1/notices/*} and {@code GET /v1/keys}. Both are permitted deliberately
 * and correctly — a person deciding whether to consent must be able to read the notice, and a
 * device that lost its credential must still be able to verify snapshots it already holds — which
 * makes them the two routes anyone at all can hold open.
 *
 * <p>The sharper case is authenticated. {@code POST /v1/evaluate/batch} caps at a thousand
 * identifiers per call and had no cap per second, so a dialer in a retry loop was an outage; and
 * the outage takes the decision API with it, at which point every downstream system either stops
 * calling or starts guessing, and guessing is how somebody who withdrew gets phoned.
 *
 * <p><strong>Token bucket, not fixed window.</strong> A fixed window lets a caller send two full
 * windows' worth across the boundary and be within its limit both times, which is precisely the
 * shape of a retry storm. A bucket refilling continuously has no boundary to exploit.
 *
 * <p><strong>Per instance, not fleet-wide.</strong> Said plainly because the difference matters
 * operationally: four replicas allow four times these numbers in aggregate, and a caller pinned to
 * one instance is limited harder than one spread evenly. It still does the job it is here for —
 * bounding what a single runaway client does to a single instance — and fleet-wide limiting needs
 * shared state the platform does not have. When there is a Redis the counter moves there and the
 * rest of this class is unchanged.
 *
 * <p><strong>Refuses in the same shape as everything else.</strong> RFC 7807 {@code ProblemDetail}
 * with a {@code Retry-After}, serialised by hand for the same reason {@link EntityAccessGuard} does
 * it by hand: a filter's exception never reaches {@code @RestControllerAdvice}, which lives inside
 * the {@code DispatcherServlet} this runs in front of.
 */
@Component
// Before the entity guard and the controllers, and after authentication — the limit is per
// credential where there is one, and the credential is not known until Spring Security has run.
@Order(Ordered.LOWEST_PRECEDENCE - 120)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final PlatformProperties.RateLimit limits;
    private final PlatformMetrics metrics;
    private final ObjectMapper json;

    /** One bucket per caller per route class. See {@link RateLimitBuckets} for the bound. */
    private final RateLimitBuckets buckets;

    public RateLimitFilter(PlatformProperties properties, PlatformMetrics metrics,
                           ObjectMapper json) {
        this.limits = properties.getRateLimit();
        this.metrics = metrics;
        this.json = json;
        this.buckets = new RateLimitBuckets(limits.getMaxTrackedCallers());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Actuator is exempt. The probes are called by the orchestrator every few seconds from a
        // small set of addresses, and a limiter that refused a readiness probe would drain an
        // instance that was working perfectly — turning a defence against overload into a cause
        // of one.
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!limits.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        RouteClass routeClass = RouteClass.of(request);
        PlatformProperties.RateLimit.Limit limit = routeClass.limitFrom(limits);
        String caller = callerKey(request);

        if (buckets.tryAcquire(routeClass.name() + '|' + caller, limit)) {
            chain.doFilter(request, response);
            return;
        }

        // Logged with the caller, which the metric deliberately is not: a tag keyed by an
        // attacker-chosen IP is an unbounded time series, while a log line ages out on retention.
        // WARN rather than ERROR — being limited is the control working, not a fault.
        log.warn("rate limit exceeded: caller={} route={} path={} ({}/s, burst {})",
                caller, routeClass, request.getRequestURI(), limit.getPermitsPerSecond(),
                limit.getBurst());
        metrics.rateLimited(routeClass.name());
        refuse(response, limit);
    }

    /**
     * Who is being limited: the credential, or the address if there is none.
     *
     * <p>Credential first, so that one misbehaving dialer is throttled wherever it calls from
     * rather than spreading itself across source addresses. IP only for the two unauthenticated
     * routes, where there is nothing else — and {@code X-Forwarded-For} is deliberately ignored,
     * because it is caller-supplied and trusting it would let anyone reset their own bucket by
     * inventing a header. When there is a trusted proxy in front, configure Boot's
     * {@code server.forward-headers-strategy} and the remote address becomes correct for
     * everything at once, rather than this one class believing something the rest does not.
     */
    private static String callerKey(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getName())) {
            return "client:" + authentication.getName();
        }
        return "ip:" + request.getRemoteAddr();
    }

    private void refuse(HttpServletResponse response, PlatformProperties.RateLimit.Limit limit)
            throws IOException {
        RateLimitRefusal.write(response, json,
                "This caller is over its rate limit for this route. The limit is "
                        + limit.getPermitsPerSecond() + " requests per second with a burst of "
                        + limit.getBurst() + ". Back off and retry; do not retry immediately in a "
                        + "loop.");
    }

    /**
     * Which ceiling applies.
     *
     * <p>Matched on path and method, in the order below, because the classes overlap:
     * {@code /v1/evaluate/batch} is also {@code /v1/evaluate}, and a check in the other order would
     * give batch the decision path's far higher limit — which is the entire reason batch has its
     * own.
     */
    private enum RouteClass {
        PUBLIC, DECISION, BATCH, CAPTURE, ADMIN, OTHER;

        static RouteClass of(HttpServletRequest request) {
            String path = request.getRequestURI();
            boolean get = "GET".equals(request.getMethod());

            if (path.startsWith("/v1/evaluate/batch")) {
                return BATCH;
            }
            if (path.startsWith("/v1/evaluate")) {
                return DECISION;
            }
            if (path.startsWith("/v1/admin")) {
                return ADMIN;
            }
            // The unauthenticated routes, and — for the two read-only ones — only in the direction
            // that is permitted. A superseded notice version is ADMIN and belongs on the admin
            // ceiling, not the public one.
            //
            // The portal is here in every direction, POST included, because it is the one
            // unauthenticated surface that writes. Keyed by IP like the rest of this class, which
            // for a corporate NAT is one bucket for a whole building — that is why the public
            // ceiling is 20/s rather than something tighter, and why the portal has its own attempt
            // cap per reference rather than relying on this for brute-force resistance.
            if (path.startsWith("/v1/portal/")) {
                return PUBLIC;
            }
            if (get && (path.startsWith("/v1/notices/") || path.equals("/v1/keys"))) {
                return PUBLIC;
            }
            if (path.startsWith("/v1/consent") || path.startsWith("/v1/provenance")
                    || path.startsWith("/v1/suppression") || path.startsWith("/v1/rights")) {
                return CAPTURE;
            }
            return OTHER;
        }

        PlatformProperties.RateLimit.Limit limitFrom(PlatformProperties.RateLimit limits) {
            return switch (this) {
                case PUBLIC -> limits.getPublicRoutes();
                case DECISION -> limits.getDecision();
                case BATCH -> limits.getBatch();
                case CAPTURE -> limits.getCapture();
                case ADMIN -> limits.getAdmin();
                case OTHER -> limits.getOther();
            };
        }
    }

}
