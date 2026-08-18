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
import java.util.Arrays;
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
        Set<String> scopes = scopes(jwt);
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

        if (authorities.isEmpty() && !scopes.isEmpty()) {
            log.debug("token for '{}' carries no scope this platform maps to a role; it will "
                    + "authenticate and be refused authorisation. Scopes: {}", jwt.getSubject(),
                    scopes);
        }
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    /**
     * The granted scopes, from whichever shape the provider uses.
     *
     * <p>Both are common and providers disagree: {@code scope} is conventionally a single
     * space-delimited string, {@code roles} and {@code scp} are conventionally arrays, and several
     * providers emit the opposite of what the convention says. Handling both here costs six lines
     * and removes a class of integration failure that presents as a 403 with a token that looks
     * entirely correct in a debugger.
     */
    private Set<String> scopes(Jwt jwt) {
        Object raw = jwt.getClaim(config.getRolesClaim());
        if (raw == null) {
            return Set.of();
        }
        if (raw instanceof String single) {
            return new LinkedHashSet<>(Arrays.asList(single.trim().split("\\s+")));
        }
        if (raw instanceof Iterable<?> many) {
            Set<String> values = new LinkedHashSet<>();
            many.forEach(value -> {
                if (value != null) {
                    values.add(value.toString());
                }
            });
            return values;
        }
        return Set.of();
    }
}
