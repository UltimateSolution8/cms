package com.uds.consent.service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Refuses a request that names an entity the caller is not scoped to.
 *
 * <p>The largest security gap the platform had. Every entity-scoped endpoint takes an
 * {@code entityId} — as a query parameter, a path variable or a body field — and every one of them
 * trusted it. A Matrix {@code ADMIN} credential could read Denave's consent records, its audit
 * trail and its RoPA by changing one string, and nothing anywhere would notice, because every
 * field in the request is individually well-formed.
 *
 * <p><strong>How the entity is found.</strong> From the query string and from the path, which
 * between them cover every entity-scoped endpoint the platform has. Request bodies are deliberately
 * <em>not</em> parsed here: reading the body in a filter consumes the stream, and buffering every
 * request to re-serve it would put a copy of every consent submission in memory to check a field
 * the database is about to check anyway. Bodies are covered by the second layer — the row-level
 * security policies in {@code V13__row_level_security.sql} apply to the statement whatever route
 * the value took to reach it.
 *
 * <p>That division is the point of having two layers. This one is code, and code acquires a new
 * endpoint that somebody forgets to check; the database policy applies to every statement whether
 * or not anybody remembered.
 *
 * <p><strong>What this layer structurally cannot do, said plainly.</strong> The prefix list below
 * is a list, and a list is exactly the failure mode that lost {@code /v1/admin/evidence/subject/}
 * and {@code /v1/consent/} the first time round. Unlike the second layer it cannot be derived —
 * {@code RowLevelSecurityIT} reads the entity-scoped tables out of {@code information_schema},
 * and there is no equivalent catalogue that says which path segment is an entity id. A regex over
 * "anything that looks like an entity id" would eventually match a purpose code and start refusing
 * valid traffic.
 *
 * <p>Two families of route are therefore <em>not</em> covered here and are covered by layer two
 * alone: {@code /v1/admin/reconfirmation/{id}} and {@code /v1/admin/breaches/{breachId}} take an
 * opaque row id rather than an entity, so there is nothing in the path for this filter to compare
 * against a claim. {@code consent_reconfirmation}, {@code personal_data_breach} and — since
 * {@code V22} — {@code breach_notification} all carry an {@code entity_id} and all have an
 * isolation policy, so a scoped session reads nothing it should not.
 *
 * <p>{@code breach_notification} was the standing exception to that and is no longer. It was
 * scoped through {@code breach_id} alone, so a scoped credential that had guessed or been told a
 * breach id could read another entity's notification rows — the party told, the deadline, the
 * method, the reference and the recipient count, which between them describe the shape of another
 * group company's worst week and whether they met the Rule 7 clock. {@code V22} denormalises the
 * entity from the parent breach so layer two has a column to bind, and {@code BreachStore} selects
 * the value from the breach rather than accepting it from a caller, so a row filed under the wrong
 * fiduciary is unrepresentable rather than merely unlikely.
 */
@Component
// After authentication — there is no claim to check before the caller is known — and before the
// controllers.
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class EntityAccessGuard extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(EntityAccessGuard.class);

    /**
     * Paths whose first segment after the prefix is an entity id.
     *
     * <p>Listed rather than pattern-matched. A regex over "anything that looks like an entity id"
     * would eventually match a purpose code or a notice id and start refusing valid requests, and
     * the failure would look like a permissions problem rather than a parsing one.
     */
    private static final Set<String> ENTITY_PATH_PREFIXES = Set.of(
            "/v1/admin/ropa/",
            "/v1/admin/integrity/",
            // The evidence bundle composes six stores' worth of personal data behind one path,
            // which makes it the most valuable single route to get this wrong on. It was added
            // after this list existed and was missing from it — which is the failure this class's
            // own javadoc predicts, caught by EvidenceBundleIT asserting the scope rather than by
            // anybody remembering.
            "/v1/admin/evidence/subject/",
            // The subject-facing reads, and the most sensitive of the set: these return one
            // person's consent state, their whole event history and their receipt. They were the
            // gap the isolation suite found — a guard reading only query parameters would have
            // looked correct on a dozen admin routes while leaving these wide open.
            "/v1/consent/",
            "/v1/rights/subject/",
            // The SDF register and the algorithmic-system inventory. Added in V20 and, like the
            // evidence bundle before them, added after this list existed: the register discloses
            // which group companies are under a Government designation and how far behind they
            // are on a statutory obligation, which is not something one entity should read about
            // another. /v1/admin/sdf/obligations/{id}/* takes an obligation id rather than an
            // entity and is handled by RESERVED_SEGMENTS below.
            "/v1/admin/sdf/",
            // Provenance and the signed snapshot. Both take {entityId}/{subjectId} and neither
            // was in this list — the same shape of gap as /v1/consent/, and found the same way.
            // Provenance says where a contact record came from, which is the evidence that the
            // consent behind an outbound call was lawfully obtained; the snapshot is a signed
            // assertion about a named person that a downstream system will honour.
            "/v1/provenance/",
            "/v1/snapshot/");

    private final SecurityConfiguration.ApiClientProperties clients;
    private final ObjectMapper json;

    public EntityAccessGuard(SecurityConfiguration.ApiClientProperties clients, ObjectMapper json) {
        this.clients = clients;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Optional<String> claim;
        try {
            claim = entityClaim();
        } catch (AmbiguousEntityClaimException e) {
            // Refused here rather than allowed through to be refused by layer two, because layer
            // two would not refuse it: an unresolvable claim reaches the session as NULL and V13's
            // policy passes a NULL claim on purpose. This is the only layer that can see the
            // ambiguity at all.
            refuseAmbiguous(response);
            return;
        }

        // Group level, or unauthenticated (in which case Spring Security is about to refuse it
        // anyway and a second opinion here would only confuse the response).
        if (claim.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        String requested = requestedEntity(request);
        if (requested != null && !requested.equals(claim.get())) {
            log.warn("refused cross-entity request: credential scoped to {} asked about {} at {}",
                    claim.get(), requested, request.getRequestURI());
            refuse(response);
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * Refuses in the same shape as every other refusal on the platform.
     *
     * <p>This used to call {@code sendError(403)}, which hands the request to the servlet
     * container's error page and comes back as {@code text/html} or as Spring Boot's default error
     * map — either way, a different body from the RFC 7807 {@code ProblemDetail} that
     * {@code ApiExceptionHandler} returns for all nine of the exceptions it covers. An integrator
     * writing one error-handling path against the API would find it worked everywhere except on
     * the one refusal that means "you asked about somebody else's data", which is the refusal most
     * worth surfacing clearly.
     *
     * <p>Serialised here by hand rather than by throwing, because a filter's exception does not
     * reach {@code @RestControllerAdvice} — the advice is inside the {@code DispatcherServlet} and
     * this filter runs before it.
     *
     * <p>The detail deliberately does not name the entity that was asked about. It is already in
     * the log line above, attributed to the credential; putting it in the response would let a
     * caller enumerate which entity ids exist by reading back the ones it is refused.
     */
    private void refuse(HttpServletResponse response) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                "credential is not authorised for that fiduciary entity");
        problem.setTitle("Cross-entity request refused");

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        json.writeValue(response.getOutputStream(), problem);
    }

    /**
     * Refuses a token that names more than one fiduciary entity.
     *
     * <p>Separate from {@link #refuse} because the two say different things and an operator acting
     * on them does different work: one is a credential reaching outside its scope, which is a
     * caller problem; this is a credential whose scope cannot be determined, which is an issuer
     * configuration problem. Collapsing them would send somebody to read the wrong logs.
     */
    private void refuseAmbiguous(HttpServletResponse response) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                "this token names more than one fiduciary entity; a credential is scoped to one "
                        + "entity or to the group, never to a subset");
        problem.setTitle("Ambiguous entity scope");

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        json.writeValue(response.getOutputStream(), problem);
    }

    /** The entity this credential is scoped to, or empty for a group-level credential. */
    private Optional<String> entityClaim() {
        return currentEntityClaim(clients.getClients(), clients.getJwt());
    }

    /** The entity the request names, from the query string or the path. Null if it names none. */
    private static String requestedEntity(HttpServletRequest request) {
        String parameter = request.getParameter("entityId");
        if (parameter != null && !parameter.isBlank()) {
            return parameter;
        }

        String path = request.getRequestURI();
        for (String prefix : ENTITY_PATH_PREFIXES) {
            if (path.startsWith(prefix)) {
                String remainder = path.substring(prefix.length());
                int slash = remainder.indexOf('/');
                String candidate = slash < 0 ? remainder : remainder.substring(0, slash);
                if (candidate.isBlank() || RESERVED_SEGMENTS.contains(candidate)) {
                    return null;
                }
                return candidate;
            }
        }
        return null;
    }

    /**
     * Path segments that sit where an entity id would and are not one.
     *
     * <p>{@code POST /v1/consent/withdraw} and {@code POST /v1/consent/notice-served} share a
     * prefix with {@code GET /v1/consent/{entityId}/{subjectId}}. Without this list the guard
     * would read "withdraw" as an entity, find it does not match any claim, and refuse the single
     * most important call the platform serves — a subject withdrawing consent. Failing closed is
     * the right instinct almost everywhere and is exactly wrong there.
     *
     * <p>An explicit list rather than a shape rule, because the shape rules all have the same
     * flaw: they work until somebody adds a route, and the failure is a refusal that looks like a
     * permissions problem.
     */
    private static final Set<String> RESERVED_SEGMENTS = Set.of(
            "withdraw", "notice-served",
            // POST /v1/admin/sdf/obligations/{id}/complete and .../reported take an obligation id.
            "obligations",
            // GET /v1/provenance/summary and POST /v1/provenance/bulk.
            "summary", "bulk",
            // GET /v1/snapshot/purposes.
            "purposes");

    /**
     * The claim, for this filter and for the connection preparer that pushes it into the database
     * session. One resolution used by both layers, deliberately: two layers of isolation are only
     * two layers if they agree about who the caller is.
     *
     * <p><strong>Two sources, and the second one is why this method exists.</strong> A basic
     * credential is looked up in the configured client map. A bearer token is not in that map —
     * its subject is a person or a service principal at the group's OIDC provider — so resolving a
     * token this way would find no client, return empty, and the platform reads empty as
     * <em>group level</em>. A JWT caller intended to be scoped to Denave would silently have been
     * given every entity in the group, through both layers at once, and nothing would have logged
     * a thing. So a token's scope comes from a claim on the token.
     *
     * <p>An absent claim is still group level, which keeps one rule for both schemes rather than
     * two: a credential without a scope sees everything, and the deployment is responsible for not
     * issuing one carelessly. What has changed is who that responsibility sits with — for tokens it
     * is the issuer's client registration, not this file.
     */
    public static Optional<String> currentEntityClaim(
            Map<String, SecurityConfiguration.ApiClientProperties.Client> clients,
            SecurityConfiguration.ApiClientProperties.Jwt jwt) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }

        if (authentication.getPrincipal() instanceof Jwt token) {
            String claim = token.getClaimAsString(jwt.getEntityClaim());
            if (claim != null && !claim.isBlank()) {
                return Optional.of(claim);
            }
            return entityFromRoles(token, jwt);
        }

        SecurityConfiguration.ApiClientProperties.Client client =
                clients.get(authentication.getName());
        if (client == null || client.getEntityId() == null || client.getEntityId().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(client.getEntityId());
    }

    /**
     * The fiduciary entity named by an {@code entity.*} role, where the issuer cannot carry a
     * dedicated claim.
     *
     * <p>Entra will not put an arbitrary claim into an access token for a custom API without a
     * claims-mapping policy and a custom signing key; app roles need neither and are assigned to
     * people rather than to applications. So {@code entity.DENAVE_IN} arriving in {@code roles} is
     * how a token from that issuer says which fiduciary its holder may act for.
     *
     * <p><strong>Read through {@link JwtRoleConverter#grantedValues} rather than parsed here.</strong>
     * That is the same reader the authorities go through, so a claim shape this method understood
     * and the converter did not — or the reverse — is unrepresentable. Rules §2: one resolver.
     *
     * <p><strong>Two entity roles are refused.</strong> Not first-wins, because the set's iteration
     * order would then decide which fiduciary's records a caller reads, and both isolation layers
     * would agree on the wrong answer. The refusal is an exception rather than an empty Optional
     * deliberately: empty means <em>group level</em> here, so returning it on an ambiguous token
     * would widen the grant at precisely the moment the platform is least sure what it should be.
     */
    private static Optional<String> entityFromRoles(
            Jwt token, SecurityConfiguration.ApiClientProperties.Jwt jwt) {
        String prefix = jwt.getEntityRolePrefix();
        if (prefix == null || prefix.isBlank()) {
            return Optional.empty();
        }
        List<String> named = JwtRoleConverter.grantedValues(token, jwt).stream()
                .filter(value -> value.startsWith(prefix))
                .map(value -> value.substring(prefix.length()))
                .filter(value -> !value.isBlank())
                // Upper-cased on the way in, the way propagation_system is upper-cased on both
                // sides for the same reason. Entity ids are upper case throughout the seed and the
                // schema; a role written entity.denave_in would otherwise resolve to a value that
                // matches no entity, fail layer one as a CROSS-ENTITY refusal, and push a claim
                // into the session that quietly matches nothing on a route with no entity in its
                // path. It fails closed either way — but the operator is sent to the wrong problem.
                .map(value -> value.toUpperCase(java.util.Locale.ROOT))
                .distinct()
                .toList();
        if (named.isEmpty()) {
            return Optional.empty();
        }
        if (named.size() > 1) {
            log.error("token for '{}' names {} fiduciary entities ({}); refusing rather than "
                            + "choosing one. A credential is scoped to one entity or to the group, "
                            + "never to a subset.", token.getSubject(), named.size(), named);
            throw new AmbiguousEntityClaimException(named);
        }
        return Optional.of(named.get(0));
    }

    /**
     * Raised when a token names more than one fiduciary entity.
     *
     * <p>Fails the request closed wherever it surfaces: this filter answers 403, and
     * {@code EntityScopedDataSource} lets it abort the connection rather than serving an unscoped
     * one. Both are the same decision — the platform cannot tell which entity was meant, and every
     * available guess is a disclosure.
     */
    public static class AmbiguousEntityClaimException extends IllegalStateException {

        private final transient List<String> entities;

        AmbiguousEntityClaimException(List<String> entities) {
            super("token names more than one fiduciary entity: " + entities);
            this.entities = List.copyOf(entities);
        }

        public List<String> entities() {
            return entities;
        }
    }
}
