package com.uds.consent.service.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Who made a request: the credential, and the person behind it.
 *
 * <p>Every controller in this package used to carry a private {@code actorOf(Authentication)} that
 * returned {@code authentication.getName()} — the API client. Six identical copies, and all six
 * were answering the wrong question. {@code compliance-console} is one credential held by a
 * compliance team, so an audit row saying it retired a purpose, invalidated a consent or assembled
 * an evidence bundle for a named data principal identifies a service account. The append-only
 * guarantee then makes that permanent: the record is immutable and therefore unfixably ambiguous.
 *
 * <p>So the caller asserts the human in {@code X-UDS-Actor}, and mutations refuse without it.
 *
 * <p><strong>How much a header is worth, stated plainly.</strong> A header the console sets is
 * weaker than a signed claim, and it is not pretending otherwise — it is trustworthy exactly as far
 * as the console is, which is a system inside the group's network already holding an ADMIN
 * credential. What it buys is that the console has to know who is driving it and the ledger has
 * somewhere to put the answer.
 *
 * <p><strong>Under a bearer token it is not used at all.</strong> When the caller authenticated
 * with a JWT, the human comes from the token's {@code preferred_username} — falling back to
 * {@code sub} — and {@code X-UDS-Actor} is ignored entirely, present or not. Preferring the header
 * when it happens to be set would leave the spoofable path open under the very scheme adopted to
 * close it: a token proves the provider authenticated somebody, and letting the request body of
 * that same call overwrite the name would make the proof decorative. The header therefore stays
 * required for basic-auth administrators and becomes dead weight under a token, which is the
 * cheapest possible incentive to migrate.
 *
 * <p>The schema did not change for either. {@code admin_audit_event} has carried {@code actor_id}
 * and {@code client_id} separately since {@code V23}, and both schemes fill the same two columns.
 *
 * @param actorId  the human, or the client name where none was asserted on a read
 * @param clientId the authenticated API credential
 */
public record Actor(String actorId, String clientId) {

    private static final Logger log = LoggerFactory.getLogger(Actor.class);

    /** The header a calling console sets to name the person driving it. */
    public static final String HEADER = "X-UDS-Actor";

    /**
     * Longest actor this will accept.
     *
     * <p>Bounded because the value is written to an append-only table and echoed in log lines. An
     * unbounded header is a way to put a megabyte of anything into evidence that cannot be
     * deleted.
     */
    private static final int MAX_LENGTH = 128;

    /**
     * Reads the actor for a request.
     *
     * <p>Falls back to the credential name when no header is present, which is right for reads —
     * a query attributed to the credential that ran it is accurate, and requiring the header on
     * every GET would break every integration for no attribution gain. Mutations go through
     * {@link #required} instead.
     */
    public static Actor of(Authentication authentication) {
        String clientId = authentication == null ? "anonymous" : authentication.getName();
        String signed = signedActor(authentication);
        if (signed != null) {
            return new Actor(sanitise(signed, clientId), clientId);
        }
        return new Actor(sanitise(assertedActor(), clientId), clientId);
    }

    /**
     * The actor for a mutating request, refusing when the caller did not say who is acting.
     *
     * <p>An {@link IllegalArgumentException} so it lands on the existing 400 handler in
     * {@code ApiExceptionHandler} rather than needing a tenth exception type. The message names the
     * header, because the person who meets this is an integrator who has not read the change note.
     */
    public static Actor required(Authentication authentication) {
        String clientIdOrAnonymous =
                authentication == null ? "anonymous" : authentication.getName();
        String signed = signedActor(authentication);
        if (signed != null) {
            // A token already names the person and the provider vouched for it. Demanding a header
            // as well would refuse a request that carries strictly better attribution than the one
            // the header was invented to supply.
            return new Actor(sanitise(signed, clientIdOrAnonymous), clientIdOrAnonymous);
        }

        String asserted = assertedActor();
        if (asserted == null || asserted.isBlank()) {
            throw new IllegalArgumentException(
                    HEADER + " is required on administrative changes. Send the identity of the "
                            + "person taking the action — a username or work email, not a team "
                            + "name and not the client id. It is written to the append-only audit "
                            + "trail, which cannot answer 'who authorised this' from a shared "
                            + "credential alone.");
        }
        String clientId = authentication == null ? "anonymous" : authentication.getName();
        return new Actor(sanitise(asserted, clientId), clientId);
    }

    /**
     * The human named by a bearer token, or null when the caller did not present one.
     *
     * <p>Fixed precedence rather than a configurable claim name, and that is a deliberate
     * simplification: {@code preferred_username} is what OIDC defines for a human-readable
     * identifier, {@code email} is what a provider that omits it almost always carries, and
     * {@code sub} always exists. A knob here would be one more thing to get wrong in a provider
     * configuration for a result the three-step fallback already reaches.
     *
     * <p>{@code sub} last, not first. A subject claim is frequently an opaque uuid, and an audit
     * row reading {@code 8f14e45f-ce...} identifies a person only to whoever still has access to
     * the provider's directory — which, years later during an inquiry, is not a safe assumption.
     */
    private static String signedActor(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt token)) {
            return null;
        }
        for (String claim : new String[] {"preferred_username", "email"}) {
            String value = token.getClaimAsString(claim);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }

        // The fallback is correct and is also the thing most worth knowing about, so it says so.
        //
        // Entra does not put preferred_username in an access token for a custom resource API
        // unless somebody adds it as an optional claim on the resource registration, and Keycloak
        // drops it too if a realm import names clientScopes (which replaces the built-in `profile`
        // scope, silently — reproduced in Phase 23 on the first import of this project's own
        // realm). Either way the first admin mutation writes a pairwise identifier into a table
        // that cannot be corrected: meaningless outside the directory, different per application.
        //
        // WARN rather than a refusal, on EntityContactCheck's reasoning — it makes the evidence
        // thin, not the decision wrong, and refusing would take the console down over a claim.
        // Logged per request rather than once at start-up because the platform never sees a token
        // until one arrives; the claims present are named so the fix is the configuration change
        // rather than an investigation. OPERATIONS.md section 2.4 is the checklist.
        log.warn("bearer token carries neither preferred_username nor email; falling back to sub, "
                        + "which writes an opaque identifier into admin_audit_event permanently. "
                        + "Claims present: {}. See OPERATIONS.md 2.4",
                token.getClaims().keySet());
        return token.getSubject();
    }

    /**
     * The asserted actor for the request in flight, or null outside one.
     *
     * <p>Read from the request-bound thread local rather than an injected
     * {@code HttpServletRequest}, so that adding attribution did not mean adding a parameter to
     * roughly sixty handler signatures across six controllers. That is a real trade — a thread
     * local is less visible than an argument — and it is the right one here: the alternative was a
     * change large enough that the interesting part of it, the refusal on mutations, would have
     * been invisible in the diff.
     *
     * <p>Null outside a request, which is correct: sweepers and the outbox relay call the audit
     * store with their own literal actor and never come through here.
     */
    private static String assertedActor() {
        if (!(RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        return request == null ? null : request.getHeader(HEADER);
    }

    /**
     * Trims, bounds, and strips anything that would forge a log line.
     *
     * <p>Newlines and control characters are removed rather than rejected. The value reaches
     * {@code log.info("... by {}", actor)} on several paths, and a header containing a newline plus
     * a plausible-looking timestamp lets a caller write whatever they like into the service log —
     * which is the log an incident is reconstructed from.
     */
    private static String sanitise(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String cleaned = value.replaceAll("[\\p{Cntrl}]", "").trim();
        if (cleaned.isEmpty()) {
            return fallback;
        }
        return cleaned.length() > MAX_LENGTH ? cleaned.substring(0, MAX_LENGTH) : cleaned;
    }
}
