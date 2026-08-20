package com.uds.consent.service.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The wildcard refusal, which three documents assert as a property of this platform.
 *
 * <p>A unit test rather than an integration one: the point is that the bean refuses to be built,
 * and a context that fails to start is a clumsy thing to assert through an HTTP client.
 */
class CorsConfigurationTest {

    private final CorsConfiguration cors = new CorsConfiguration();

    @Test
    @DisplayName("a wildcard origin is refused rather than quietly accepted")
    void aWildcardIsRefused() {
        // `docs/UI_CONTRACT.md`, `OPERATIONS.md` §12.8 and the class javadoc all state that this
        // platform has no wildcard origin. Until the check existed that was true because the author
        // avoided setAllowedOriginPatterns — not because anything refused CORS_ALLOWED_ORIGINS=*,
        // which Spring accepts and which emits Access-Control-Allow-Origin: * when credentials are
        // off, as they are here.
        assertThatThrownBy(() -> cors.udsCorsConfigurationSource(propertiesWith(List.of("*"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wildcard");

        // And a pattern, which is how the wildcard usually arrives — somebody wanting one subdomain.
        assertThatThrownBy(() -> cors.udsCorsConfigurationSource(
                propertiesWith(List.of("https://*.uds.example"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("an empty allowlist registers nothing at all")
    void anEmptyAllowlistRegistersNothing() {
        // The default, and the configuration every deployment runs today. Registering a
        // configuration here — even an empty one — would make CorsFilter start refusing requests
        // whose origin does not match, on every route.
        assertThat(cors.udsCorsConfigurationSource(propertiesWith(List.of()))
                .getCorsConfiguration(new org.springframework.mock.web.MockHttpServletRequest()))
                .isNull();
    }

    @Test
    @DisplayName("exact origins are accepted")
    void exactOriginsAreAccepted() {
        assertThat(cors.udsCorsConfigurationSource(
                propertiesWith(List.of("https://console.uds.example"))))
                .isNotNull();
    }

    private SecurityConfiguration.ApiClientProperties propertiesWith(List<String> origins) {
        SecurityConfiguration.ApiClientProperties properties =
                new SecurityConfiguration.ApiClientProperties();
        properties.getCors().setAllowedOrigins(origins);
        return properties;
    }
}
