package com.uds.consent.service.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the published contract has to say beyond its paths.
 *
 * <p>The document listed all 120 routes and was still not enough to generate a client from, in
 * three specific ways that only show up when somebody tries:
 *
 * <ul>
 *   <li><strong>{@code components.securitySchemes} was empty and no operation carried
 *       {@code security}</strong>, so nothing in the contract said the API needs a credential at
 *       all — let alone which of two schemes, or which role. A generated client compiled and was
 *       refused by every route.</li>
 *   <li><strong>Only 200, 201 and 202 were documented.</strong> Every refusal this platform is
 *       careful about — the 409 naming outstanding systems, the 409 that means identity was never
 *       verified, the 429 carrying {@code Retry-After}, the cross-entity 403 — was absent, and
 *       there was no {@code ProblemDetail} schema to describe any of them. A client generated from
 *       that treats an RFC 7807 body as an unparseable surprise.</li>
 *   <li><strong>Six operation ids were auto-suffixed</strong> ({@code withdraw_1},
 *       {@code forSubject_1}, {@code forSubject_2}, {@code summary_1}, {@code receipt_1},
 *       {@code record_1}) because the Java method names collide across controllers. springdoc
 *       resolves that silently and by document order, so the number attached to a route can move
 *       when an unrelated route is added — renaming a generated client's method with no change to
 *       the route it calls.</li>
 * </ul>
 *
 * <p><strong>Documented globally rather than route by route.</strong> Annotating 120 handlers would
 * put the same six annotations on every one of them and go stale the first time a handler was
 * added without them. The customiser below applies to whatever routes exist, which is the same
 * argument {@code RowLevelSecurityIT} makes for deriving its protected set rather than listing it.
 *
 * <p><strong>This does not describe which role a route needs</strong>, and it is worth saying so
 * rather than implying the contract is now complete. That lives in {@code @PreAuthorize} and in the
 * filter chain, and OpenAPI has no vocabulary for "ADMIN or CAPTURE". {@code docs/UI_CONTRACT.md}
 * carries the table; this file carries the fact that a credential is required at all.
 */
@Configuration
public class OpenApiConfiguration {

    /** Where the RFC 7807 body is described once, and referenced from every refusal. */
    private static final String PROBLEM_SCHEMA = "ProblemDetail";

    private static final String PROBLEM_REF = "#/components/schemas/" + PROBLEM_SCHEMA;

    @Bean
    public OpenAPI udsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("UDS Consent & Privacy Control Plane")
                        .version("1")
                        .description("""
                                Consent, notice and enforcement for the UDS Group's fiduciary \
                                entities under the DPDP Act 2023 and the DPDP Rules 2025, with \
                                jurisdiction modules for TRAI TCCCPR, GDPR/UK GDPR, Korea PIPA, \
                                Singapore and Malaysia PDPA and the US state statutes.

                                Two authentication schemes run side by side: HTTP Basic for \
                                integrators that have not moved, and OIDC bearer tokens. Roles are \
                                DECISION, CAPTURE, ADMIN and CONSENT_MANAGER; which role a route \
                                needs is in docs/UI_CONTRACT.md, because OpenAPI cannot express \
                                "ADMIN or CAPTURE".

                                Mutating administrative routes require an X-UDS-Actor header under \
                                Basic auth, naming the person behind the credential. Under a bearer \
                                token the header is ignored and the human comes from the token — a \
                                claim an identity provider signed beats a header a client asserted.\
                                """))
                .components(new Components()
                        .addSecuritySchemes("basicAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("basic")
                                .description("Per-integrator credentials. Interim; every route "
                                        + "also accepts a bearer token."))
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("OIDC access token. Scopes map to roles; an "
                                        + "entity_id claim, or an entity.<ID> app role, scopes the "
                                        + "token to one fiduciary. Absent means group level."))
                        .addSchemas(PROBLEM_SCHEMA, problemDetail()))
                // TWO requirement objects, not one carrying two schemes. Entries WITHIN a
                // Security Requirement Object are ANDed by the specification, so
                // `[{basicAuth: [], bearerAuth: []}]` states that every route needs both
                // simultaneously — the opposite of what this platform does, and the opposite of
                // what info.description above says. Alternatives are separate objects.
                //
                // Applied at the document level and then cleared from the genuinely public routes,
                // rather than added to 113 of 120 operations by hand.
                .addSecurityItem(new SecurityRequirement().addList("basicAuth"))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }

    /**
     * RFC 7807, plus the properties this platform's handlers actually set.
     *
     * <p>Each of the extras exists because a client needs to act on it rather than merely log it:
     * {@code outstandingSystems} is the list of systems that have not acted, which is what an
     * operator has to chase before a rights request can be closed; {@code availableLanguages} is
     * what a notice can be re-requested in; {@code feature} names the flag a dark route is behind,
     * so a 404 that means "not enabled here" is distinguishable from one that means "no such
     * route".
     */
    private Schema<?> problemDetail() {
        Schema<?> problem = new Schema<>()
                .type("object")
                .description("RFC 7807 problem detail. Returned as application/problem+json for "
                        + "every refusal except the 422 on consent capture and Consent Manager "
                        + "relay, which are domain responses carrying a violations array rather "
                        + "than errors.");
        problem.addProperty("type", new StringSchema().format("uri"));
        problem.addProperty("title", new StringSchema());
        problem.addProperty("status", new Schema<>().type("integer").format("int32"));
        problem.addProperty("detail", new StringSchema());
        problem.addProperty("instance", new StringSchema().format("uri"));
        problem.addProperty("outstandingSystems",
                new Schema<>().type("array").items(new StringSchema())
                        .description("409 on rights fulfilment: systems with no terminal action."));
        problem.addProperty("requestType", new StringSchema()
                .description("409 on rights fulfilment: the request type that is gated."));
        problem.addProperty("feature", new StringSchema()
                .description("404 on a dark route: the property that would enable it."));
        problem.addProperty("availableLanguages",
                new Schema<>().type("array").items(new StringSchema())
                        .description("404 on a notice: the languages it is published in."));
        problem.addProperty("noticeId", new StringSchema());
        problem.addProperty("noticeVersion", new Schema<>().type("integer").format("int32"));
        problem.addProperty("requestedLanguage", new StringSchema());
        return problem;
    }

    /**
     * The refusals every route can produce, added to every route.
     *
     * <p>Deliberately the same set everywhere rather than a hand-curated list per operation. A
     * per-route list would be more precise and would be wrong within a phase — the 429 in
     * particular can arrive on any path from either limiter, and the cross-entity 403 from a filter
     * that reads {@code entityId} on any path at all.
     */
    @Bean
    public OperationCustomizer udsErrorResponses() {
        return (operation, handlerMethod) -> {
            ApiResponses responses = operation.getResponses();
            if (responses == null) {
                return operation;
            }
            // A LinkedHashMap, and that is not a style choice. Map.of randomises its iteration
            // order per JVM, so building the response map from one produced a document whose keys
            // shuffled between builds — and OpenApiContractIT compares the rendered document
            // byte for byte, so the pin would have failed on roughly every other run for a reason
            // that had nothing to do with the API. Caught by the pin doing its job on the first
            // regeneration.
            Map<String, String> refusals = new LinkedHashMap<>();
            ordered(refusals,
                    "400", "The request was malformed, or a required header such as X-UDS-Actor "
                            + "was absent on an administrative mutation.",
                    "401", "No credential, or one the platform could not validate.",
                    "403", "The credential's role does not permit this, or it is scoped to a "
                            + "different fiduciary entity, or its token names more than one.",
                    "404", "No such record — or a route behind a feature flag that is off, in "
                            + "which case the body names the flag.",
                    "409", "Refused by a gate: fulfilment is not evidenced, identity was never "
                            + "verified, or a write-once record already exists.",
                    "429", "Rate limited. Retry-After names the delay; the ceiling is per "
                            + "instance and per route class.",
                    "500", "Deliberately opaque. Quote the correlation id from X-Correlation-Id.");
            refusals.forEach((status, description) -> responses.addApiResponse(status,
                    new ApiResponse().description(description).content(new Content()
                            .addMediaType("application/problem+json",
                                    new MediaType().schema(new Schema<>().$ref(PROBLEM_REF))))));
            return operation;
        };
    }

    /** Fills a map in argument order, so the rendered document is byte-stable across builds. */
    private static void ordered(Map<String, String> target, String... pairs) {
        for (int i = 0; i < pairs.length; i += 2) {
            target.put(pairs[i], pairs[i + 1]);
        }
    }

    /**
     * Clears the global security requirement from the routes that genuinely have none.
     *
     * <p>The requirement is declared at the document level and removed here rather than added to
     * 113 operations individually — same reasoning as the error responses. What matters is that the
     * exemptions are a short, readable list: a data principal reaching the portal has no credential
     * by design (DPDP Rule 14(1)), a notice must be readable before anyone can consent to it, and a
     * verification key is public or it is useless.
     *
     * <p>Getting this wrong in the safe direction — leaving the requirement on a public route —
     * would make a generated client demand a credential the route does not want. Getting it wrong
     * the other way would advertise a route as public when it is not, which is why the list is
     * exact prefixes rather than a pattern.
     */
    /**
     * The routes that genuinely require no credential, by exact path.
     *
     * <p><strong>Exact templates, not prefixes, and the first version got this wrong in precisely
     * the direction its own comment warned about.</strong> {@code "/v1/notices/"} as a prefix also
     * matches {@code /v1/notices/{noticeId}/versions} and
     * {@code /v1/notices/{noticeId}/versions/{version}} — both {@code hasRole('ADMIN')}, because a
     * superseded notice version is evidence of what a principal was actually shown at capture, not
     * public information. The contract published to the group's own front-end team said they were
     * world-readable.
     *
     * <p>Getting it wrong the safe way — leaving the requirement on a public route — makes a
     * generated client demand a credential the route does not want. Getting it wrong this way
     * makes a published artefact state something false about the evidence plane. Hence a literal
     * set, and a test that pins it.
     */
    static final List<String> PUBLIC_PATHS = List.of(
            "/v1/portal/requests",
            "/v1/portal/requests/{reference}",
            "/v1/portal/requests/{reference}/verify",
            "/v1/notices/{noticeId}",
            "/v1/notices/{noticeId}/languages",
            "/v1/keys");

    @Bean
    public OpenApiCustomizer udsPublicRoutes() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, item) -> {
                if (PUBLIC_PATHS.contains(path)) {
                    item.readOperations().forEach(operation -> operation.setSecurity(List.of()));
                }
            });
        };
    }

    /**
     * Names the operations whose Java method names collide across controllers.
     *
     * <p>Left to springdoc these become {@code withdraw_1}, {@code forSubject_2} and so on,
     * numbered by document order — so adding an unrelated route can renumber them, and a generated
     * client's method is renamed by a change that did not touch the route it calls.
     *
     * <p><strong>Keyed on the handler, not on the id springdoc would have generated.</strong>
     * Keying on {@code forSubject_2} would mean this mapping silently stops applying the moment
     * that numbering shifts — which is the same fragility it exists to remove. A class and a method
     * name are what the developer actually chose.
     */
    @Bean
    public OperationCustomizer udsStableOperationIds() {
        Map<String, String> byHandler = Map.of(
                "ConsentManagerController#withdraw", "relayConsentManagerWithdrawal",
                "ConsentManagerController#record", "consentManagerRecord",
                "ReceiptController#forSubject", "listReceipts",
                "ProvenanceController#forSubject", "provenanceForSubject",
                "ProvenanceController#summary", "provenanceSourceSummary",
                "ConsentController#receipt", "currentConsentReceipt");
        return (operation, handlerMethod) -> {
            String key = handlerMethod.getBeanType().getSimpleName()
                    + "#" + handlerMethod.getMethod().getName();
            String replacement = byHandler.get(key);
            if (replacement != null) {
                operation.setOperationId(replacement);
            }
            return operation;
        };
    }
}
