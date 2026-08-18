package com.uds.consent.service.config;

import com.uds.consent.core.audit.CallerContext;
import com.uds.consent.service.api.Actor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Binds the calling credential to the thread so the audit trail can record it.
 *
 * <p>{@code admin_audit_event} recorded one identifier and it was the wrong one: the API client.
 * {@code compliance-console} is a single credential held by a compliance team, so a row saying it
 * retired a purpose or assembled an evidence bundle for a named data principal identifies a service
 * account — and because the table is append-only, that ambiguity can never be corrected.
 *
 * <p>The human now arrives in {@code X-UDS-Actor} and is passed explicitly by the controllers. The
 * credential arrives here, through {@link CallerContext}, because threading it as a second argument
 * would have touched some forty call sites across seven services and buried the change worth
 * reviewing under mechanical edits.
 *
 * <p>Also puts both into the MDC, so every log line for a request carries them alongside the
 * correlation id that {@code CorrelationIdFilter} already sets. Under the {@code json-logging}
 * profile they become fields rather than prose, which is the difference between "find every action
 * this person took last Tuesday" being a query and being a grep.
 *
 * <p>Cleared in a {@code finally}, and that is the whole safety argument. A value surviving into
 * the next request on a reused container thread would attribute one caller's action to another —
 * an audit trail that is confidently wrong, which is worse than one that is vague.
 */
@Component
// After authentication, since there is no credential to bind before the caller is known, and
// before EntityAccessGuard so a refused cross-entity request is still attributed in the log.
@Order(Ordered.LOWEST_PRECEDENCE - 110)
public class CallerAttributionFilter extends OncePerRequestFilter {

    private static final String MDC_CLIENT = "clientId";
    private static final String MDC_ACTOR = "actor";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String clientId = authentication == null || !authentication.isAuthenticated()
                ? null
                : authentication.getName();

        CallerContext.setClientId(clientId);
        if (clientId != null) {
            MDC.put(MDC_CLIENT, clientId);
        }
        // Read through Actor so the same bounding and control-character stripping applies here as
        // on the path that writes it to the ledger. A header with a newline in it would otherwise
        // forge log lines in exactly the log an incident is reconstructed from.
        String actor = Actor.of(authentication).actorId();
        if (actor != null) {
            MDC.put(MDC_ACTOR, actor);
        }

        try {
            chain.doFilter(request, response);
        } finally {
            CallerContext.clear();
            MDC.remove(MDC_CLIENT);
            MDC.remove(MDC_ACTOR);
        }
    }
}
