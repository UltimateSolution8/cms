package com.uds.consent.service.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a token's scopes into the four roles the platform already authorises against.
 *
 * <p>This class is the reason adding OIDC changed no authorisation rule. {@code
 * SecurityConfiguration} decides what each route needs in terms of {@code DECISION},
 * {@code CAPTURE}, {@code ADMIN} and {@code CONSENT_MANAGER}, and forty {@code requestMatchers}
 * express that. Mapping scopes onto the same vocabulary means a bearer token is authorised by
 * exactly the rules a basic credential is, asserted by exactly the same tests. Rewriting
 * authentication and authorisation in one change would have produced a diff nobody could review and
 * a security model nobody could compare against the one it replaced.
 *
 * <p><strong>Unmapped scopes are dropped, not rejected.</strong> A group OIDC provider issues
 * tokens carrying scopes for every service behind it, and refusing a token because it also grants
 * something in the expense system would be this platform enforcing another system's model. What it
 * will not do is invent a role: a scope with no mapping grants nothing here, so a token that
 * carries only unmapped scopes authenticates and then fails authorisation with a 403 — which is the
 * accurate answer.
 *
 * <p><strong>The principal name.</strong> Left as the token's {@code sub}, which is what
 * {@code authentication.getName()} returns and what reaches the audit trail as the client. The
 * human is read separately from {@code preferred_username} by {@code Actor} — under a token the two
 * are usually the same person, and keeping them distinct means the audit schema does not change
 * shape depending on how the caller authenticated.
 */
public final class JwtRoleConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final Logger log = LoggerFactory.getLogger(JwtRoleConverter.class);

    private final SecurityConfiguration.ApiClientProperties.Jwt config;

    private JwtRoleConverter(SecurityConfiguration.ApiClientProperties.Jwt config) {
        this.config = config;
    }

    public static JwtRoleConverter of(SecurityConfiguration.ApiClientProperties.Jwt config) {
        return new JwtRoleConverter(config);
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<String> scopes = grantedValues(jwt, config);
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String scope : scopes) {
            String role = config.getScopeRoles().get(scope);
            if (role != null) {
                // ROLE_ prefixed here because hasRole("ADMIN") checks for ROLE_ADMIN. Getting this
                // wrong produces a token that authenticates and is refused everywhere, which reads
                // as a provider misconfiguration rather than as a one-word bug here.
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
        }

        // WARN, on authorities being empty rather than on scopes being non-empty, and the
        // difference is the whole point. The previous guard required a non-empty scope set, so the
        // one case worth catching — a claim name the provider does not use, which yields no values
        // at all — logged NOTHING and presented as an unexplained 403. Naming both the claims that
        // were inspected and the claims the token actually carries turns that into a five-second
        // diagnosis. Not a flood vector: the token has already passed signature validation.
        if (authorities.isEmpty()) {
            log.warn("token for '{}' authenticates but maps to no role, so every route will refuse "
                            + "it. Looked for granted values in {}; the token carries the claims "
                            + "{}; values found: {}. Check uds.consent.security.jwt.roles-claims "
                            + "and scope-roles — the lookup is exact and case-sensitive.",
                    jwt.getSubject(), config.getRolesClaims(), jwt.getClaims().keySet(), scopes);
        }
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    /**
     * Every granted value the token carries, unioned across the configured claims.
     *
     * <p><strong>Shapes and names are different problems and this handles both.</strong> The
     * javadoc here previously described three claim <em>names</em> — {@code scope}, {@code scp} and
     * {@code roles} — while the code read exactly one, configured, and fell back to nothing. It
     * handled two <em>shapes</em>: a space-delimited string and an array. That gap is very likely
     * where the belief that multi-provider support already worked came from, and it cost a silent
     * 403 against a correctly-issued Entra token.
     *
     * <p>Now: each name in {@code roles-claims} is read, each is accepted in either shape, and the
     * results are unioned. Union rather than first-non-empty, because a provider emits several at
     * once and the first is often the one carrying nothing this platform maps. See
     * {@code SecurityConfiguration.ApiClientProperties.Jwt#getRolesClaims()} for the worked example.
     *
     * <p><strong>Public and static because there must be one reader.</strong>
     * {@code EntityAccessGuard} resolves a caller's fiduciary entity from these same values when the
     * issuer cannot carry a dedicated claim, and rules §2 is explicit that the two isolation layers
     * must never be able to disagree about who the caller is. A second parser here would be a second
     * way to disagree.
     */
    public static Set<String> grantedValues(Jwt jwt,
                                            SecurityConfiguration.ApiClientProperties.Jwt config) {
        Set<String> values = new LinkedHashSet<>();
        for (String claim : config.getRolesClaims()) {
            Object raw = jwt.getClaim(claim);
            if (raw == null) {
                continue;
            }
            if (raw instanceof String single) {
                for (String part : single.trim().split("\\s+")) {
                    if (!part.isBlank()) {
                        values.add(part);
                    }
                }
            } else if (raw instanceof Iterable<?> many) {
                many.forEach(value -> {
                    if (value != null) {
                        values.add(value.toString());
                    }
                });
            }
        }
        return values;
    }
}
