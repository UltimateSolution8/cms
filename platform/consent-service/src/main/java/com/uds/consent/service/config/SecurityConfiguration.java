package com.uds.consent.service.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
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
 *   <li>{@code CONSENT_MANAGER} — a Consent Manager registered with the Board under DPDP Rule 4,
 *       relaying what a principal did there. Narrower than {@code CAPTURE} rather than a variant of
 *       it: it writes consent only for principals it manages, through
 *       {@code /v1/consent-manager/**} and nothing else. Holding the role is not sufficient — the
 *       registration must also be active on the register, which is checked per request.</li>
 * </ul>
 *
 * <p><strong>Two authentication schemes, on purpose.</strong> HTTP Basic over TLS with per-client
 * credentials was the starting point and is honest about what it is: adequate for the Denave pilot
 * inside the group's network, and not the destination. Bearer tokens from the group's OIDC provider
 * now sit alongside it — {@code uds.consent.security.jwt.issuer-uri} or {@code .public-key} turns
 * them on — rather than replacing it.
 *
 * <p>Alongside rather than instead, because a replacement is a flag day: the Athena dialer, DenCRM
 * and every capture surface would have to cut over in the same maintenance window, coordinated
 * against a provider none of them has been pointed at yet. Each integrator moves when it is ready,
 * and {@code uds.consent.security.basic-enabled: false} closes the old door per environment when
 * the last one has. The role model above survives both unaltered — scopes map onto the same four
 * roles — which is the point of having defined it in terms of capability rather than identity.
 *
 * <p>The gain is not only credential hygiene. Under Basic, {@code admin_audit_event} records a
 * shared client name and the human comes from an {@code X-UDS-Actor} header the caller typed;
 * under a token, the human is a claim the provider signed. See {@code Actor}.
 *
 * <p><strong>Per-entity isolation.</strong> A Matrix credential must not reach Denave's records.
 * That is enforced in two independent places, because one of them is not enough:
 *
 * <ul>
 *   <li>{@link EntityAccessGuard} refuses a request naming an entity the credential is not scoped
 *       to, before any query runs. This is the layer that produces a comprehensible 403 and the
 *       one an integrator will meet.</li>
 *   <li>Row-level security in the database, keyed on a session variable set from the same claim,
 *       so that a query which somehow escapes the guard still returns nothing. See
 *       {@code V13__row_level_security.sql}.</li>
 * </ul>
 *
 * <p>Two layers rather than one for the reason the ledger has three: the guard is code and code
 * gets a new endpoint added to it that somebody forgets to check, while the database policy
 * applies to every statement whether or not anybody remembered.
 *
 * <p>A credential with no {@code entity-id} is group level and bypasses both. That is a grant, not
 * a gap — group compliance has to see everything — and the clients holding it are logged at
 * start-up so the list is checkable.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ApiClientProperties properties)
            throws Exception {
        http
                // No browser sessions and no forms, so there is no cross-site request forgery
                // surface to protect. Every caller authenticates on every request.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // The actuator, which since the management port split is served on
                        // management.server.port and 404s on this one. Matched by path rather than
                        // by EndpointRequest, and that is not a style preference: on a separate
                        // management port the endpoints are mapped in a child context, so the
                        // parent's PathMappedEndpoints is empty and EndpointRequest.toAnyEndpoint()
                        // matches nothing at all. A chain built on it silently never applies, and
                        // the symptom is a scraper getting 401 from the port that exists for it.
                        //
                        // Health, info and the scrape endpoint are open; everything else — heap
                        // dumps, environment, loggers — needs ADMIN even there, because "the
                        // management port is not routable" is a property of a deployment and this
                        // file should not assume every deployment gets it right.
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
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
                        // The data principal's way in. Public because the person exercising a
                        // right is, by definition, the one party in this system who holds no
                        // credential — and DPDP Rule 14(1) requires the means to be published, not
                        // issued. Authentication is per request, by a single-use code sent to the
                        // identifier being claimed; see PrincipalPortalController.
                        //
                        // These carry the PUBLIC rate-limit class. They are also the only
                        // unauthenticated routes that write, which is why the submission path is
                        // built so that it cannot report whether an identifier is known.
                        .requestMatchers(HttpMethod.POST, "/v1/portal/requests",
                                "/v1/portal/requests/*/verify").permitAll()
                        .requestMatchers(HttpMethod.GET, "/v1/portal/requests/*").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .hasRole("ADMIN")

                        // Everything below mirrors the @PreAuthorize annotations on the
                        // controllers, and is here rather than only there because of an ordering
                        // property that is easy to miss: method security is a proxy around the
                        // handler, so it runs *after* Spring has deserialised and validated the
                        // request body. A dialer credential POSTing to a publishing endpoint was
                        // therefore getting its body parsed, bean-validated and answered with a 400
                        // describing the fields it got wrong — a refusal, but one that had already
                        // let an unauthorised caller enumerate the shape of a write it may not
                        // make. Deciding authorisation in the filter chain moves the refusal in
                        // front of all of it.
                        //
                        // The duplication is deliberate and is covered: AdminApiIT sweeps every
                        // route in both directions, so a rule here that disagrees with an
                        // annotation there fails the build rather than quietly winning.

                        // The administrative surface: one subtree, one role, no exceptions.
                        .requestMatchers("/v1/admin/**").hasRole("ADMIN")
                        // Superseded notice versions and the coverage report are evidence, not
                        // public information. The current version and its language list are
                        // permitted above and this catches the rest.
                        .requestMatchers(HttpMethod.GET, "/v1/notices/**").hasRole("ADMIN")

                        // The decision path. Batch evaluation is not offered to capture surfaces:
                        // a form asks about the person in front of it, and a form asking about ten
                        // thousand people is a list being scrubbed by something that should be
                        // holding a DECISION credential.
                        .requestMatchers(HttpMethod.POST, "/v1/evaluate")
                        .hasAnyRole("DECISION", "CAPTURE", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/v1/evaluate/batch")
                        .hasAnyRole("DECISION", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/v1/snapshot/**")
                        .hasAnyRole("DECISION", "CAPTURE", "ADMIN")

                        // Consent. A dialer can ask what the answer is and cannot write one.
                        .requestMatchers(HttpMethod.POST, "/v1/consent", "/v1/consent/withdraw",
                                "/v1/consent/notice-served").hasAnyRole("CAPTURE", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/v1/consent/*/*/history")
                        .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/v1/consent/*/*/receipt")
                        .hasAnyRole("CAPTURE", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/v1/consent/*/*")
                        .hasAnyRole("CAPTURE", "DECISION", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/v1/receipts/**")
                        .hasAnyRole("CAPTURE", "ADMIN")

                        // Provenance. A bulk import asserts the origin of data for thousands of
                        // people at once and is administrative; a single record is capture.
                        .requestMatchers(HttpMethod.POST, "/v1/provenance/bulk").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/v1/provenance")
                        .hasAnyRole("CAPTURE", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/v1/provenance/summary").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/v1/provenance/*/*")
                        .hasAnyRole("DECISION", "CAPTURE", "ADMIN")

                        // Suppression. Scrubbing is what a dialer does; adding an entry to the
                        // registry is what a compliance officer does.
                        .requestMatchers(HttpMethod.POST, "/v1/suppression/scrub")
                        .hasAnyRole("DECISION", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/v1/suppression/registry")
                        .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/v1/suppression/opt-out",
                                "/v1/suppression/universal-opt-out").hasAnyRole("CAPTURE", "ADMIN")

                        // Rights. Raising a request is capture — it is a person asking. Deciding
                        // one is administrative.
                        .requestMatchers(HttpMethod.GET, "/v1/rights/subject/**", "/v1/rights/queue",
                                "/v1/rights/overdue", "/v1/rights/summary").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/v1/rights/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/v1/rights",
                                "/v1/rights/*/acknowledge").hasAnyRole("CAPTURE", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/v1/rights/*")
                        .hasAnyRole("CAPTURE", "ADMIN")

                        // Consent Manager relays (DPDP Rule 4). A narrow role of its own rather
                        // than reusing CAPTURE: a Consent Manager writes consent for principals it
                        // manages and must not be able to reach a capture surface's other routes,
                        // and a capture surface must not be able to relay as though it were an
                        // intermediary. The registry read is administrative.
                        .requestMatchers(HttpMethod.GET, "/v1/consent-manager/registry")
                        .hasRole("ADMIN")
                        .requestMatchers("/v1/consent-manager/**")
                        .hasAnyRole("CONSENT_MANAGER", "ADMIN")

                        .anyRequest().authenticated());

        // Both, deliberately, and in this order. A caller presenting Bearer is authenticated by the
        // resource server; a caller presenting Basic keeps working exactly as it did. Spring picks
        // by the scheme on the Authorization header, so the two do not contend.
        if (properties.getJwt().isConfigured()) {
            http.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                    jwt.jwtAuthenticationConverter(JwtRoleConverter.of(properties.getJwt()))));
            log.info("bearer tokens accepted; scopes map to roles as {}",
                    properties.getJwt().getScopeRoles());
        }

        if (properties.isBasicEnabled()) {
            http.httpBasic(basic -> {
            });
        } else if (!properties.getJwt().isConfigured()) {
            // Both off is not a hardened deployment, it is one with no way in at all — and it would
            // present as every route returning 401 with nothing in the logs explaining why.
            throw new IllegalStateException(
                    "no authentication is configured: uds.consent.security.basic-enabled is false "
                            + "and no JWT issuer-uri or public-key is set. Set one, or every "
                            + "request will be refused with no way to authenticate it.");
        } else {
            log.info("HTTP Basic is disabled; bearer tokens only");
        }

        return http.build();
    }

    /**
     * Validates bearer tokens.
     *
     * <p>Two sources, and the choice is about where the platform runs rather than about security
     * strength. {@code issuer-uri} discovers the provider's keys and picks up a rotation at the
     * provider without a redeploy, which is the production answer. A pinned {@code public-key}
     * suits a test and an environment whose issuer the platform cannot reach — a pinned key that
     * the provider rotates away from fails closed, which is the right direction to fail.
     */
    @Bean
    @ConditionalOnProperty(prefix = "uds.consent.security.jwt", name = {"issuer-uri", "public-key"},
            matchIfMissing = true)
    @ConditionalOnMissingBean(JwtDecoder.class)
    public JwtDecoder jwtDecoder(ApiClientProperties properties) {
        ApiClientProperties.Jwt jwt = properties.getJwt();
        if (!jwt.isConfigured()) {
            // Registered but inert. Returning a decoder that rejects everything would be worse than
            // returning null here only if something used it — and nothing does: the filter chain
            // above does not register the resource server when the token config is absent.
            return token -> {
                throw new IllegalStateException("no JWT issuer or public key is configured");
            };
        }

        NimbusJwtDecoder decoder;
        if (jwt.getIssuerUri() != null && !jwt.getIssuerUri().isBlank()) {
            decoder = JwtDecoders.fromIssuerLocation(jwt.getIssuerUri());
        } else {
            decoder = NimbusJwtDecoder.withPublicKey(readRsaPublicKey(jwt.getPublicKey())).build();
        }

        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtTimestampValidator());
        if (jwt.getIssuerUri() != null && !jwt.getIssuerUri().isBlank()) {
            validators.add(new JwtIssuerValidator(jwt.getIssuerUri()));
        }
        if (jwt.getAudience() != null && !jwt.getAudience().isBlank()) {
            // Without this, any token the issuer signed authenticates here — including one minted
            // for a different service behind the same provider.
            validators.add(new JwtClaimValidator<List<String>>("aud",
                    audiences -> audiences != null && audiences.contains(jwt.getAudience())));
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    private static RSAPublicKey readRsaPublicKey(String base64X509) {
        try {
            byte[] decoded = Base64.getDecoder().decode(base64X509.replaceAll("\\s", "")
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", ""));
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(decoded));
        } catch (Exception e) {
            throw new IllegalStateException("uds.consent.security.jwt.public-key must be a base64 "
                    + "X.509 RSA public key", e);
        }
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
            if (properties.getJwt().isConfigured()) {
                // A bearer-token-only deployment. Legitimate, and the destination — but the entity
                // claim then comes from the token rather than from this map, so the group-level
                // warning below has nothing to report and the issuer carries that responsibility.
                log.info("no API clients configured; authenticating by bearer token only");
                return new InMemoryUserDetailsManager();
            }
            throw new IllegalStateException(
                    "no API clients configured. Set uds.consent.security.clients.<name>.password "
                            + "and .roles, or configure uds.consent.security.jwt.issuer-uri, or "
                            + "the platform has no way to authenticate anyone.");
        }
        log.info("configured {} API client(s): {}", users.size(), properties.getClients().keySet());

        // Named explicitly rather than counted. A credential with no entity claim can read every
        // entity in the group, which is sometimes right and always worth being able to check
        // against a list somebody signed off.
        List<String> groupLevel = properties.getClients().entrySet().stream()
                .filter(entry -> entry.getValue().getEntityId() == null
                        || entry.getValue().getEntityId().isBlank())
                .map(Map.Entry::getKey)
                .toList();
        if (!groupLevel.isEmpty()) {
            log.warn("group-level API client(s) with access to every entity: {}", groupLevel);
        }
        return new InMemoryUserDetailsManager(users);
    }

    /** Credentials for machine callers. Sourced from the secret store, never committed. */
    @ConfigurationProperties(prefix = "uds.consent.security")
    public static class ApiClientProperties {

        private Map<String, Client> clients = new LinkedHashMap<>();
        private boolean basicEnabled = true;
        private Jwt jwt = new Jwt();
        private Cors cors = new Cors();

        public Map<String, Client> getClients() {
            return clients;
        }

        public void setClients(Map<String, Client> clients) {
            this.clients = clients;
        }

        /**
         * Whether HTTP Basic is still accepted.
         *
         * <p>Defaults to true, and that default is the whole migration strategy. Turning bearer
         * tokens on does not turn basic auth off, so adopting OIDC is not a flag day coordinated
         * across the dialer, DenCRM and every capture surface at once. Each integrator moves when
         * it is ready; this is set to false per environment when the last one has, which makes
         * switching it off a decision somebody takes and can see, rather than a side effect.
         */
        public boolean isBasicEnabled() {
            return basicEnabled;
        }

        public void setBasicEnabled(boolean basicEnabled) {
            this.basicEnabled = basicEnabled;
        }

        public Cors getCors() {
            return cors;
        }

        public void setCors(Cors cors) {
            this.cors = cors;
        }

        /**
         * Cross-origin access, for the browser clients this platform did not originally have.
         *
         * <p><strong>Empty by default, which means off.</strong> Every existing caller is a machine
         * sending an {@code Authorization} header from a server, and none of them is subject to a
         * preflight. So an empty allowlist leaves the platform behaving exactly as it did.
         *
         * <p>Exact origins only, never {@code *}. The list is short and it is the one place that
         * says which browser applications exist.
         */
        public static class Cors {

            private List<String> allowedOrigins = new ArrayList<>();
            private long maxAgeSeconds = 1800;

            /**
             * Origins permitted to make cross-origin requests. Empty disables CORS entirely.
             *
             * <p>Includes the data-principal portal's origin rather than opening
             * {@code /v1/portal/**} to everything. The portal is deliberately not a subject
             * enumeration oracle (rules §6) so a wildcard would not disclose anything — but its
             * verify route consumes a single-use code against a per-address bucket, and a JSON
             * preflight is currently the only thing stopping that being driven from arbitrary
             * visitors' browsers, which is a distributed-address path around a per-address limit.
             */
            public List<String> getAllowedOrigins() {
                return allowedOrigins;
            }

            public void setAllowedOrigins(List<String> allowedOrigins) {
                this.allowedOrigins = allowedOrigins;
            }

            /**
             * How long a browser may cache a preflight.
             *
             * <p>Not a tuning knob so much as a load one: without it every non-simple request costs
             * two, and the second one lands on the pre-authentication bucket that exists to keep a
             * flood cheap.
             */
            public long getMaxAgeSeconds() {
                return maxAgeSeconds;
            }

            public void setMaxAgeSeconds(long maxAgeSeconds) {
                this.maxAgeSeconds = maxAgeSeconds;
            }
        }

        public Jwt getJwt() {
            return jwt;
        }

        public void setJwt(Jwt jwt) {
            this.jwt = jwt;
        }

        /**
         * Bearer-token authentication against the group's OIDC provider.
         *
         * <p>Inert unless {@link #getIssuerUri()} or {@link #getPublicKey()} is set. That matters:
         * a resource server registered with no way to validate anything is a decoder that rejects
         * every token, which surfaces as a 500 on the authentication path rather than as the "not
         * configured" it actually is.
         */
        public static class Jwt {

            private String issuerUri;
            private String publicKey;
            private String audience;
            private List<String> rolesClaims = new ArrayList<>(List.of("scope", "scp", "roles"));
            private String entityClaim = "entity_id";
            private String entityRolePrefix = "entity.";
            private Map<String, String> scopeRoles = new LinkedHashMap<>(Map.of(
                    "consent.decision", "DECISION",
                    "consent.capture", "CAPTURE",
                    "consent.admin", "ADMIN",
                    "consent.relay", "CONSENT_MANAGER"));

            /** The OIDC issuer, discovered at start-up. Production. */
            public String getIssuerUri() {
                return issuerUri;
            }

            public void setIssuerUri(String issuerUri) {
                this.issuerUri = issuerUri;
            }

            /**
             * A base64 X.509 RSA public key, as an alternative to discovery.
             *
             * <p>For tests and for an environment whose issuer is not reachable from the platform's
             * network. Discovery is better where it is available — it picks up a key rotation at
             * the provider without a redeploy, which a pinned key does not.
             */
            public String getPublicKey() {
                return publicKey;
            }

            public void setPublicKey(String publicKey) {
                this.publicKey = publicKey;
            }

            /**
             * The audience every token must carry, if set.
             *
             * <p>Unset means any token the issuer signed is accepted, which is usually wrong in a
             * group running more than one service behind the same provider: a token minted for the
             * expense system would otherwise authenticate against the consent ledger.
             */
            public String getAudience() {
                return audience;
            }

            public void setAudience(String audience) {
                this.audience = audience;
            }

            /**
             * Claims that may carry granted scopes. Every one of them is read, and the results are
             * unioned.
             *
             * <p><strong>A union rather than the first non-empty claim, and the difference is not
             * cosmetic.</strong> Providers disagree about the name: Keycloak emits {@code scope},
             * Entra emits {@code scp} for delegated scopes and {@code roles} for app roles. They
             * also emit more than one at once — an Entra delegated token routinely carries
             * {@code scp: "openid profile"}, none of which this platform maps, beside
             * {@code roles: ["consent.admin"]}, which it does. First-non-empty would take
             * {@code scp}, find nothing mappable, and refuse a token that carries a valid grant
             * while naming the wrong claim in the diagnostic.
             *
             * <p>There is no security cost to reading all of them: {@code JwtRoleConverter} does an
             * allowlist lookup against {@link #getScopeRoles()}, so a union cannot grant anything
             * that map does not name.
             */
            public List<String> getRolesClaims() {
                return rolesClaims;
            }

            public void setRolesClaims(List<String> rolesClaims) {
                this.rolesClaims = rolesClaims;
            }

            /**
             * Claim carrying the fiduciary entity a token is scoped to.
             *
             * <p>Load-bearing. Entity isolation was resolved by looking the caller up in
             * {@link #getClients()}, and a bearer token's subject is not in that map — so without
             * this claim every JWT caller would resolve to "no claim", which the platform reads as
             * <em>group level</em> and grants access to every entity in the group. A claim absent
             * from the token is still group level, so an issuer must set it deliberately for a
             * scoped credential; that is the same grant the basic-auth clients have and it is
             * logged at start-up the same way.
             */
            public String getEntityClaim() {
                return entityClaim;
            }

            public void setEntityClaim(String entityClaim) {
                this.entityClaim = entityClaim;
            }

            /**
             * Prefix marking a role value that names a fiduciary entity — {@code entity.DENAVE_IN}.
             *
             * <p><strong>Why a role and not a custom claim.</strong> Microsoft Entra will not put an
             * arbitrary claim such as {@code entity_id} into an access token for a custom API
             * without a claims-mapping policy and a custom signing key. App roles need neither, are
             * assignable to users and groups rather than to applications, and arrive in a claim
             * this converter already reads. So an entity can be carried by an issuer that cannot
             * carry {@link #getEntityClaim()}.
             *
             * <p><strong>Keying it on the application instead would have been wrong</strong>, and it
             * was the first design. In a delegated flow {@code azp} is the browser client's id —
             * one value for every human who signs in — so a map from it to an entity says "everyone
             * who can reach this console is Denave", and the isolation boundary becomes which
             * application you authenticated to rather than who you are.
             *
             * <p><strong>Two entity roles on one token are refused, never first-wins.</strong>
             * Iteration order would otherwise decide which fiduciary a caller could read.
             */
            public String getEntityRolePrefix() {
                return entityRolePrefix;
            }

            public void setEntityRolePrefix(String entityRolePrefix) {
                this.entityRolePrefix = entityRolePrefix;
            }

            /**
             * Scope to role. Defaults cover the four the platform defines.
             *
             * <p>The mapping exists so that not one of the forty {@code requestMatchers} above
             * changes when tokens arrive. The authorisation model was already right; only the
             * authentication was weak, and a change that rewrote both at once would be impossible
             * to review.
             */
            public Map<String, String> getScopeRoles() {
                return scopeRoles;
            }

            public void setScopeRoles(Map<String, String> scopeRoles) {
                this.scopeRoles = scopeRoles;
            }

            /** Whether a token can be validated at all. */
            public boolean isConfigured() {
                return (issuerUri != null && !issuerUri.isBlank())
                        || (publicKey != null && !publicKey.isBlank());
            }
        }

        public static class Client {

            private String password;
            private List<String> roles = List.of("DECISION");
            private String entityId;

            public String getPassword() {
                return password;
            }

            /**
             * The fiduciary entity this credential may act for.
             *
             * <p>Blank means group level — the compliance function genuinely does need to see
             * every entity, and pretending otherwise produces a shared credential nobody can
             * attribute. It is a deliberate grant rather than a default: a client with no
             * entity-id set is one somebody decided should see everything, and the platform logs
             * which those are at start-up so the list can be checked.
             */
            public String getEntityId() {
                return entityId;
            }

            public void setEntityId(String entityId) {
                this.entityId = entityId;
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
