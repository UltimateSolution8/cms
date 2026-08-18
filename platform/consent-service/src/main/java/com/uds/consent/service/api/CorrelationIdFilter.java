package com.uds.consent.service.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Puts a correlation id on every request, in the log and on the response.
 *
 * <p>{@code ApiExceptionHandler} has always told callers to "check the service logs with the trace
 * id". Nothing produced one. This makes that advice true, which matters more here than in an
 * ordinary service: error responses from this platform deliberately carry no personal data — no
 * number, no email, no name — because they end up in tickets and screenshots. That is the right
 * trade, and its cost is that an integrator reporting "the decision API refused my call" has
 * nothing to hand over. The correlation id is what they hand over instead.
 *
 * <p>An inbound {@code X-Correlation-Id} is honoured rather than replaced, so a trace started at
 * the dialer survives into this platform's logs and back out again. That does mean a caller can
 * choose their own value, which is why it is bounded and sanitised below: this identifier reaches
 * log files and response headers, and an unbounded caller-supplied string in either is a log
 * injection waiting to happen.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    /** Long enough for a UUID, short enough that nothing useful fits after it. */
    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String correlationId = sanitise(request.getHeader(HEADER));
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Removed in a finally because the thread goes back to a pool. A correlation id left
            // behind attaches itself to the next request on that thread, which is worse than
            // having none — it points an investigation confidently at the wrong call.
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * Accepts a caller's id only if it is plausibly one.
     *
     * <p>Newlines and control characters would let a caller forge log lines; length is bounded so
     * that a header cannot carry a payload into every log aggregator the group runs.
     */
    private static String sanitise(String supplied) {
        if (supplied == null || supplied.isBlank() || supplied.length() > MAX_LENGTH) {
            return null;
        }
        for (int i = 0; i < supplied.length(); i++) {
            char c = supplied.charAt(i);
            boolean allowed = Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == ':';
            if (!allowed) {
                return null;
            }
        }
        return supplied;
    }
}
