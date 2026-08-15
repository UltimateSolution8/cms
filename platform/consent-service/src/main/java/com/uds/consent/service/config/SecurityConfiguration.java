package com.uds.consent.service.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API authentication and authorisation.
 *
 * <p>Roles map to what a caller is allowed to do, not to who they are:
 * <ul>
 *   <li>{@code CAPTURE} — write consent. Held by capture surfaces.</li>
 *   <li>{@code DECISION} — ask the decision API and scrub lists. Held by dialers, CRMs, campaign
 *       tools. Deliberately cannot write consent: a dialer that can record consent is a dialer
 *       that can manufacture it.</li>
 *   <li>{@code ADMIN} — the compliance console.</li>
 * </ul>
 *
 * <p>HTTP Basic over TLS with per-client credentials is the starting point, which is honest about
 * what it is: adequate for the Denave pilot inside the group's network, and not the destination.
 * Production wires the group's OIDC provider as an OAuth2 resource server and drops the in-memory
 * users entirely — the role model above survives that change unaltered, which is the point of
 * defining it in terms of capability.
 *
 * <p>Per-entity isolation is a separate concern and is not solved here. A Matrix administrator
 * must not see Denave's records, which needs row-level security in the database driven by the
 * caller's entity claim. That is Phase 1 work and is called out in the operations notes rather
 * than left implied.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // No browser sessions and no forms, so there is no cross-site request forgery
                // surface to protect. Every caller authenticates on every request.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // Public by design: a verification key is public, and requiring a
                        // credential to fetch it would mean a device that lost its credential
                        // also lost the ability to verify snapshots it already holds.
                        .requestMatchers(HttpMethod.GET, "/v1/keys").permitAll()
                        // A notice is what someone reads before deciding whether to consent.
                        // Requiring a credential to read it would mean the only people who can
                        // see what they agreed to are the systems that already hold their data.
                        // Scoped to the current version and its language list: superseded
                        // versions are evidence, not public information, and stay ADMIN.
                        .requestMatchers(HttpMethod.GET, "/v1/notices/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/v1/notices/*/languages").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .hasRole("ADMIN")
                        .anyRequest().authenticated())
                .httpBasic(basic -> {
                });
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public InMemoryUserDetailsManager userDetailsService(ApiClientProperties properties,
                                                         PasswordEncoder encoder) {
        List<UserDetails> users = new ArrayList<>();
        properties.getClients().forEach((username, client) -> {
            if (client.getPassword() == null || client.getPassword().isBlank()) {
                throw new IllegalStateException(
                        "API client '" + username + "' has no password configured");
            }
            users.add(User.withUsername(username)
                    .password(encoder.encode(client.getPassword()))
                    .roles(client.getRoles().toArray(String[]::new))
                    .build());
        });

        if (users.isEmpty()) {
            throw new IllegalStateException(
                    "no API clients configured. Set uds.consent.security.clients.<name>.password "
                            + "and .roles, or the platform has no way to authenticate anyone.");
        }
        log.info("configured {} API client(s): {}", users.size(), properties.getClients().keySet());
        return new InMemoryUserDetailsManager(users);
    }

    /** Credentials for machine callers. Sourced from the secret store, never committed. */
    @ConfigurationProperties(prefix = "uds.consent.security")
    public static class ApiClientProperties {

        private Map<String, Client> clients = new LinkedHashMap<>();

        public Map<String, Client> getClients() {
            return clients;
        }

        public void setClients(Map<String, Client> clients) {
            this.clients = clients;
        }

        public static class Client {

            private String password;
            private List<String> roles = List.of("DECISION");

            public String getPassword() {
                return password;
            }

            public void setPassword(String password) {
                this.password = password;
            }

            public List<String> getRoles() {
                return roles;
            }

            public void setRoles(List<String> roles) {
                this.roles = roles;
            }
        }
    }
}
