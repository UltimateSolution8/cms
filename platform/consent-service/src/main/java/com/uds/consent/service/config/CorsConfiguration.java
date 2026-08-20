package com.uds.consent.service.config;

import jakarta.servlet.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * Cross-origin access for browser clients, registered in front of everything that can refuse them.
 *
 * <p>The platform was built for machine callers — {@code SecurityConfiguration} says so, and there
 * are no sessions and no forms — so until a browser needed to reach it there was no CORS
 * configuration at all. That is not a gap somebody forgot; it is the correct configuration for the
 * callers that existed. What it means now is that a data principal cannot reach
 * {@code /v1/portal/**} from the only client they have, which makes DPDP Rule 14(1)'s published
 * means unusable in practice, and a compliance console cannot reach anything.
 *
 * <p><strong>The order is the whole design, and it took two attempts.</strong> Registering this
 * inside the security chain — which {@code .cors(withDefaults())} does — puts it behind
 * {@link PreAuthRateLimitFilter}, whose 429 carries no {@code Access-Control-Allow-Origin}; a
 * rate-limited browser would then be told its origin was wrong.
 *
 * <p><strong>Registering it ahead of that limiter was the second attempt and opened a hole.</strong>
 * Spring's {@code CorsFilter} short-circuits on <em>any</em> request whose origin fails the check,
 * not only on preflights — so {@code POST /v1/evaluate} carrying {@code Origin: https://evil.example}
 * would have been answered here and never reached the flood ceiling at all: unmetered, unlogged
 * above DEBUG, and evadable with a single header. The ceiling exists because invalid credentials
 * produced 401s and zero 429s (rules §9); making it skippable by adding a header would have
 * reopened that in a new place.
 *
 * <p><strong>So the limiter runs first, at {@code DEFAULT_FILTER_ORDER - 30}, and exempts
 * preflights itself; this filter sits at {@code - 20} behind it.</strong> A preflight costs
 * nothing and is not counted. Every other method is counted, whatever origin it claims. And the
 * limiter's own refusal echoes an allowlisted origin, so a browser over the ceiling reads a 429
 * rather than a CORS error. {@code CorsIT} asserts both halves.
 *
 * <p><strong>{@code WebMvcConfigurer.addCorsMappings} is ruled out for a related reason.</strong>
 * MVC-level CORS runs inside the {@code DispatcherServlet}, so a preflight would traverse the whole
 * security chain first and be refused 401 by {@code anyRequest().authenticated()} — a browser
 * failure with no useful signal anywhere.
 *
 * <p><strong>Credentials are not allowed, and that is not a weakening.</strong>
 * {@code allowCredentials} governs <em>ambient</em> credentials — cookies, TLS client certificates,
 * browser-managed HTTP auth. This platform is {@code STATELESS} with CSRF disabled and uses none of
 * them. A bearer token is an ordinary header the client sets deliberately, sent regardless of this
 * flag, and it is already in the allowed list below.
 */
@Configuration
public class CorsConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CorsConfiguration.class);

    /**
     * Headers a browser client must be able to send.
     *
     * <p>{@code X-UDS-Actor} is here because rules §5 requires it on every administrative mutation
     * under Basic auth; a console that cannot send it cannot administer anything.
     * {@code X-Correlation-Id} is here because a caller may supply its own.
     */
    private static final List<String> ALLOWED_HEADERS = List.of(
            "Authorization", "Content-Type", "Accept", "X-UDS-Actor", "X-Correlation-Id");

    /**
     * Headers a browser client must be able to read back.
     *
     * <p>Without this list both are invisible to JavaScript, whatever the server sends. That is not
     * a detail: {@code application.yml} describes the correlation id as the identifier an engineer
     * quotes in a support thread, and a browser console that cannot read it cannot quote it.
     * {@code Retry-After} is what turns a 429 into a client that backs off rather than one that
     * retries into the same wall.
     */
    private static final List<String> EXPOSED_HEADERS = List.of(
            "X-Correlation-Id", "Retry-After");

    @Bean
    public CorsConfigurationSource udsCorsConfigurationSource(
            SecurityConfiguration.ApiClientProperties properties) {
        List<String> origins = properties.getCors().getAllowedOrigins();
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

        if (origins == null || origins.isEmpty()) {
            // No registration, so CorsFilter finds no configuration and passes every request
            // through untouched — byte-identical to the platform before this class existed.
            log.info("CORS is not configured; browser clients on another origin will be refused by "
                    + "the browser. Set uds.consent.security.cors.allowed-origins to enable it.");
            return source;
        }

        org.springframework.web.cors.CorsConfiguration config =
                new org.springframework.web.cors.CorsConfiguration();
        // Exact origins. setAllowedOrigins, never setAllowedOriginPatterns: a pattern is how "*"
        // gets reintroduced by somebody who wanted to allow one subdomain.
        //
        // And the wildcard is refused rather than merely avoided. Three documents state that this
        // platform has no wildcard origin; until this check existed that was a property of the
        // author's restraint, not of the code, and CORS_ALLOWED_ORIGINS=* would have been accepted
        // in silence.
        if (origins.stream().anyMatch(origin -> origin.contains("*"))) {
            throw new IllegalStateException(
                    "uds.consent.security.cors.allowed-origins contains a wildcard: " + origins
                            + ". List exact origins. A wildcard would let any page in any browser "
                            + "read this platform's responses, and the allowlist is the record of "
                            + "which browser applications exist.");
        }
        config.setAllowedOrigins(List.copyOf(origins));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(ALLOWED_HEADERS);
        config.setExposedHeaders(EXPOSED_HEADERS);
        config.setAllowCredentials(false);
        config.setMaxAge(properties.getCors().getMaxAgeSeconds());

        source.registerCorsConfiguration("/**", config);
        log.info("CORS enabled for {} origin(s): {}", origins.size(), origins);
        return source;
    }

    /**
     * Registered explicitly so the order is stated rather than inherited.
     *
     * <p>{@code DEFAULT_FILTER_ORDER - 20} puts this behind {@link PreAuthRateLimitFilter}
     * ({@code - 30}, which exempts preflights itself) and ahead of the security chain
     * ({@code DEFAULT_FILTER_ORDER}). The class javadoc argues why that exact position and not
     * either neighbour.
     */
    @Bean
    public FilterRegistrationBean<Filter> udsCorsFilter(
            @Qualifier("udsCorsConfigurationSource") CorsConfigurationSource source) {
        // Qualified by name, and not for style. Spring MVC's own mvcHandlerMappingIntrospector
        // also implements CorsConfigurationSource, so injecting by type is ambiguous — and the
        // failure mode if it resolved silently would be this filter consulting MVC's per-handler
        // CORS resolution instead of the allowlist above, which is a different policy wearing the
        // same interface.
        FilterRegistrationBean<Filter> registration =
                new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER - 20);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
