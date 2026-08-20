package com.uds.consent.service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The 429, written the same way by both limiters.
 *
 * <p>RFC 7807 {@code ProblemDetail}, serialised by hand for the same reason
 * {@link EntityAccessGuard} does it by hand: a filter's exception never reaches
 * {@code @RestControllerAdvice}, which lives inside the {@code DispatcherServlet} these filters run
 * in front of. The pre-authentication limiter runs in front of Spring Security as well, so it has
 * even less of a framework to fall back on.
 */
final class RateLimitRefusal {

    private RateLimitRefusal() {
    }

    static void write(HttpServletResponse response, ObjectMapper json, String detail)
            throws IOException {
        write(null, response, json, detail, List.of());
    }

    /**
     * The same refusal, carrying the CORS origin header where the caller is a browser.
     *
     * <p>Needed only by the pre-authentication limiter, which runs <em>ahead</em> of the CORS
     * filter — so when it short-circuits, nothing downstream ever adds the header, and a browser
     * would report a CORS failure for what is actually a 429. A client cannot back off from a
     * diagnosis that names the wrong subsystem. The per-credential limiter runs behind CORS and
     * needs none of this.
     *
     * <p>Echoed only for an origin already on the allowlist. Reflecting an arbitrary {@code Origin}
     * would turn a refusal into a way to discover that the platform is there.
     */
    static void write(HttpServletRequest request, HttpServletResponse response, ObjectMapper json,
                      String detail, List<String> allowedOrigins) throws IOException {
        if (request != null) {
            String origin = request.getHeader(HttpHeaders.ORIGIN);
            if (origin != null && allowedOrigins.contains(origin)) {
                response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
                response.setHeader(HttpHeaders.VARY, HttpHeaders.ORIGIN);
                response.setHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                        HttpHeaders.RETRY_AFTER);
            }
        }
        writeBody(response, json, detail);
    }

    private static void writeBody(HttpServletResponse response, ObjectMapper json, String detail)
            throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                detail);
        problem.setTitle("Rate limit exceeded");

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        // One second, because the bucket refills continuously — by the time a well-behaved client
        // has waited, there are permits. A longer value would punish a caller that briefly spiked.
        response.setHeader(HttpHeaders.RETRY_AFTER, "1");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        json.writeValue(response.getOutputStream(), problem);
    }
}
