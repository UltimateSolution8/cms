package com.uds.consent.service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

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
