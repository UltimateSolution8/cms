package com.uds.consent.service.it;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.uds.consent.ledger.store.AdminAuditStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bearer tokens, alongside the credentials that were already there.
 *
 * <p>The platform authenticated machine callers with HTTP Basic against an in-memory user list.
 * That was the oldest open blocker: the credential is shared, it is a static secret in an
 * environment variable, and the audit trail's answer to "who authorised this" was a service
 * account's name plus whatever the caller typed into a header. This suite covers the resource
 * server that closes it.
 *
 * <p><strong>The most important test here is {@link #basicAuthenticationStillWorks}.</strong>
 * Everything else proves a new capability; that one proves the change is additive. A cutover that
 * required the Athena dialer, DenCRM and every capture surface to move to a token in the same
 * maintenance window — against a provider none of them has been pointed at yet — is not a security
 * improvement anybody would be allowed to deploy.
 *
 * <p><strong>No identity provider, on purpose.</strong> The tokens below are minted in-process
 * against a generated RSA pair and validated through {@code NimbusJwtDecoder.withPublicKey}. A
 * Keycloak container would add most of a minute to every build to test Keycloak; what is under test
 * here is this platform's scope-to-role mapping, its entity scoping and its attribution.
 */
class JwtAuthenticationIT extends PostgresIntegrationTest {

    private static final KeyPair KEYS = generateRsaKeyPair();

    @DynamicPropertySource
    static void oidc(DynamicPropertyRegistry registry) {
        // A pinned key rather than issuer discovery: there is no issuer to discover in a test, and
        // pinning exercises the same decoder path a network-isolated environment would use.
        registry.add("uds.consent.security.jwt.public-key",
                () -> Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()));
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AdminAuditStore audit;

    @Test
    @DisplayName("basic authentication still works once bearer tokens are accepted")
    void basicAuthenticationStillWorks() {
        // The no-flag-day assertion. If this ever fails, the change stopped being additive and
        // every existing integration breaks on the deploy that introduces OIDC.
        assertThat(rest.withBasicAuth("compliance-console", "admin-secret")
                .getForEntity("/v1/admin/purposes", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a token carrying the decision scope reaches the decision API")
    void aScopedTokenIsAuthorised() {
        ResponseEntity<String> response = rest.exchange("/v1/evaluate", HttpMethod.POST,
                new HttpEntity<>(decisionRequest(), bearer(token(claims -> claims
                        .subject("athena-dialer-sa")
                        .claim("scope", "consent.decision")
                        .claim("entity_id", "DENAVE_IN")))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a token whose scopes map to no role authenticates and is then refused")
    void anUnmappedScopeGrantsNothing() {
        // Not a 401. The token is valid and the provider vouched for it — the caller simply has no
        // authority here, and saying "unauthenticated" would send an integrator to debug their
        // credential rather than their scopes. A group provider fronting several services will
        // routinely issue tokens carrying scopes this platform knows nothing about.
        ResponseEntity<String> response = rest.exchange("/v1/evaluate", HttpMethod.POST,
                new HttpEntity<>(decisionRequest(), bearer(token(claims -> claims
                        .subject("expenses-sa")
                        .claim("scope", "expenses.submit openid profile")))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("an expired token is refused")
    void anExpiredTokenIsRefused() {
        String expired = token(claims -> claims
                .subject("athena-dialer-sa")
                .claim("scope", "consent.decision")
                .issueTime(Date.from(Instant.now().minus(2, ChronoUnit.HOURS)))
                .expirationTime(Date.from(Instant.now().minus(1, ChronoUnit.HOURS))));

        ResponseEntity<String> response = rest.exchange("/v1/evaluate", HttpMethod.POST,
                new HttpEntity<>(decisionRequest(), bearer(expired)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a token signed by the wrong key is refused")
    void aForgedTokenIsRefused() {
        KeyPair rogue = generateRsaKeyPair();
        String forged = signWith(rogue, new JWTClaimsSet.Builder()
                .subject("attacker")
                .claim("scope", "consent.admin")
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plus(5, ChronoUnit.MINUTES)))
                .build());

        assertThat(rest.exchange("/v1/admin/purposes", HttpMethod.GET,
                new HttpEntity<>(bearer(forged)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("the audit row names the human from the token, with no header sent")
    void theSignedClaimSuppliesTheActor() {
        String applicationId = "jwt-test-" + UUID.randomUUID();
        HttpHeaders headers = bearer(token(claims -> claims
                .subject("8f14e45f-ce9a-4c1e-91d0-2f6a1b3c4d5e")
                .claim("preferred_username", "priya.sharma@uds.example")
                .claim("scope", "consent.admin")));
        // Explicitly suppressed, so this proves the token supplied the name rather than the shared
        // interceptor having quietly supplied one.
        headers.add(IntegrationTestClient.SUPPRESS_ACTOR, "true");

        assertThat(rest.exchange("/v1/admin/applications/" + applicationId, HttpMethod.PUT,
                new HttpEntity<>(application(), headers), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        AdminAuditStore.Entry entry = latestFor(applicationId);

        // The whole point of B1. Under Basic this row would have read actor_id=<whatever header
        // the console sent>, client_id=compliance-console. Now the human is a claim the provider
        // signed, and sub is kept as the credential — an opaque uuid identifies a person only to
        // whoever still has the directory, which years into an inquiry is not a safe assumption.
        assertThat(entry.actorId()).isEqualTo("priya.sharma@uds.example");
        assertThat(entry.clientId()).isEqualTo("8f14e45f-ce9a-4c1e-91d0-2f6a1b3c4d5e");
    }

    @Test
    @DisplayName("an asserted header cannot override the signed claim")
    void theHeaderIsIgnoredUnderAToken() {
        // If the header won here, adopting OIDC would leave the spoofable attribution path open
        // under the very scheme adopted to close it — a caller holding a token could write any
        // name it liked into an append-only table, and the signature would make it look verified.
        String applicationId = "jwt-test-" + UUID.randomUUID();
        HttpHeaders headers = bearer(token(claims -> claims
                .subject("real-person")
                .claim("preferred_username", "priya.sharma@uds.example")
                .claim("scope", "consent.admin")));
        headers.add("X-UDS-Actor", "mallory@elsewhere.example");

        assertThat(rest.exchange("/v1/admin/applications/" + applicationId, HttpMethod.PUT,
                new HttpEntity<>(application(), headers), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(latestFor(applicationId).actorId()).isEqualTo("priya.sharma@uds.example");
    }

    @Test
    @DisplayName("a token scoped to one entity cannot read another's records")
    void theEntityClaimScopesTheToken() {
        // The defect this suite exists to catch. Entity isolation resolved a caller's scope by
        // looking its name up in the configured client map — and a token's subject is not in that
        // map, so it resolved to "no claim", which the platform reads as GROUP LEVEL. Every JWT
        // caller would silently have been granted every entity in the group, through both
        // isolation layers at once, with nothing logged.
        HttpHeaders headers = bearer(token(claims -> claims
                .subject("denave-admin-sa")
                .claim("preferred_username", "denave.admin@uds.example")
                .claim("scope", "consent.admin")
                .claim("entity_id", "DENAVE_IN")));

        assertThat(rest.exchange("/v1/admin/ropa/DENAVE_IN", HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(rest.exchange("/v1/admin/ropa/MATRIX_IN", HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---------------------------------------------------------------------------------------

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private String token(java.util.function.Consumer<JWTClaimsSet.Builder> customiser) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plus(5, ChronoUnit.MINUTES)));
        customiser.accept(claims);
        return signWith(KEYS, claims.build());
    }

    private static String signWith(KeyPair keys, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims);
            jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("could not mint a test token", e);
        }
    }

    private static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            // Asserted rather than assumed: NimbusJwtDecoder.withPublicKey takes an RSAPublicKey,
            // and a cast failing inside the context start-up produces an error message that says
            // nothing about why.
            assertThat(pair.getPublic()).isInstanceOf(RSAPublicKey.class);
            return pair;
        } catch (Exception e) {
            throw new IllegalStateException("RSA unavailable on this JVM", e);
        }
    }

    private static Map<String, Object> decisionRequest() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("entityId", "DENAVE_IN");
        request.put("subjectId", "jwt-suite-subject");
        request.put("purposeCode", "MKT_OUTBOUND_CALL");
        request.put("channel", "VOICE_CALL");
        request.put("jurisdiction", "IN");
        return request;
    }

    private static Map<String, Object> application() {
        return Map.of(
                "entityId", "DENAVE_IN",
                "name", "JWT fixture " + UUID.randomUUID(),
                "platform", "WEB",
                "environment", "TEST",
                "active", true);
    }

    private AdminAuditStore.Entry latestFor(String targetId) {
        List<AdminAuditStore.Entry> entries = audit.recent("DENAVE_IN", 200);
        return entries.stream()
                .filter(entry -> targetId.equals(entry.targetId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no audit row was written for " + targetId));
    }
}
