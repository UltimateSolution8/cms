package com.uds.consent.service.it;

import com.uds.consent.ledger.store.AdminAuditStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The four things no test in this repository had ever exercised.
 *
 * <p>{@link JwtAuthenticationIT} mints tokens in-process against a generated key pair and pins the
 * public key, which is the right test for this platform's scope-to-role mapping, its entity scoping
 * and its attribution — and it deliberately never touches an identity provider. What it therefore
 * cannot cover is everything between the two: <strong>discovery from {@code issuer-uri}, the JWKS
 * fetch, {@code iss} validation and {@code aud} validation.</strong> Those four are configuration
 * against a real provider, they are the four most likely things to be wrong on first contact, and
 * until this suite they were asserted nowhere at all.
 *
 * <p><strong>Excluded from the default build</strong>, by carrying only {@code @Tag("keycloak")}
 * where every other integration suite carries {@code integration}. It pulls a ~450 MB image and
 * starts a JVM inside it; making every {@code mvn verify} pay for that to test Keycloak rather than
 * this platform is the trade {@code JwtAuthenticationIT}'s javadoc already refused. Run it:
 *
 * <pre>
 * mvn -B verify -pl consent-service -am -Dit.test=KeycloakIssuerIT -Dfailsafe.excluded.groups=
 * </pre>
 *
 * <p>It therefore contributes <strong>nothing</strong> to the baseline in
 * {@code .claude/state/test-baseline}, and the delivery record says so rather than letting a count
 * imply coverage that does not run.
 *
 * <p>The realm is {@code platform/docker/keycloak/uds-realm.json} — the same committed artefact a
 * developer starts with {@code docker compose --profile auth up -d keycloak}, not a second one
 * written for the test. A realm the suite configured for itself would prove the suite works.
 */
@Tag("keycloak")
class KeycloakIssuerIT extends PostgresIntegrationTest {

    private static final String AUDIENCE = "uds-consent-api";

    @SuppressWarnings("resource")
    static final GenericContainer<?> KEYCLOAK =
            new GenericContainer<>("quay.io/keycloak/keycloak:26.0")
                    .withCommand("start-dev", "--import-realm")
                    .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
                    .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(
                                    Path.of("..", "docker", "keycloak", "uds-realm.json")
                                            .toAbsolutePath().normalize()),
                            "/opt/keycloak/data/import/uds-realm.json")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(
                                    Path.of("..", "docker", "keycloak", "other-realm.json")
                                            .toAbsolutePath().normalize()),
                            "/opt/keycloak/data/import/other-realm.json")
                    .withExposedPorts(8080)
                    // Waits for the REALM, not the server. A realm import that fails leaves
                    // Keycloak perfectly healthy and the realm absent, and the symptom would then
                    // be six confusing token failures rather than one clear start-up failure.
                    .waitingFor(Wait.forHttp("/realms/uds").forPort(8080).forStatusCode(200)
                            .withStartupTimeout(Duration.ofMinutes(4)));

    static {
        KEYCLOAK.start();
    }

    @DynamicPropertySource
    static void oidc(DynamicPropertyRegistry registry) {
        // Discovery, not a pinned key. That is the whole point of this suite: issuer-uri sends
        // Spring to /.well-known/openid-configuration and then to the JWKS, and registers a
        // JwtIssuerValidator comparing `iss` verbatim. Keycloak in dev mode derives the issuer it
        // stamps from the request's Host header, so the mapped port appears on both sides and they
        // agree — which is also why pointing the platform at a Keycloak behind a proxy without
        // KC_HOSTNAME set produces a token every validator refuses.
        registry.add("uds.consent.security.jwt.issuer-uri", KeycloakIssuerIT::issuerUri);
        registry.add("uds.consent.security.jwt.audience", () -> AUDIENCE);
    }

    private static String issuerUri() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080) + "/realms/uds";
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private javax.sql.DataSource dataSource;

    @Autowired
    private AdminAuditStore audit;

    @Test
    @DisplayName("a token this platform never minted reaches /v1/evaluate — discovery, JWKS, iss "
            + "and aud all hold")
    void aRealIssuersTokenIsAccepted() {
        // Every one of the four is load-bearing and none had a test. If discovery fails the context
        // does not start; if the JWKS fetch fails, iss disagrees, or aud is absent from the token,
        // this is 401. A 200 is the only outcome that requires all four to be right.
        ResponseEntity<String> response = rest.exchange("/v1/evaluate", HttpMethod.POST,
                new HttpEntity<>(decisionRequest(), bearer(serviceAccountToken())), String.class);

        assertThat(response.getStatusCode())
                .withFailMessage("a bearer token from a real issuer was refused: %s\n%s\n"
                                + "401 means one of discovery, JWKS, iss or aud; 403 means the "
                                + "scope did not map to ROLE_DECISION",
                        response.getStatusCode(), response.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("the entity claim from a real issuer reaches the database session, not only the "
            + "filter")
    void theEntityClaimReachesTheDatabaseSession() throws Exception {
        // Rules section 2: the two layers must never be able to disagree about who the caller is,
        // and Phase 11's worst defect was exactly that. An HTTP assertion cannot tell "layer two
        // received DENAVE_IN" from "layer two received nothing and passed everything", so this
        // reads the session GUC the RLS policy actually consults.
        assertThat(entityOnTheSession(serviceAccountToken())).isEqualTo("DENAVE_IN");
    }

    @Test
    @DisplayName("an app role from a real issuer scopes the token, with no custom claim in it")
    void anAppRoleScopesARealToken() {
        // The branch Entra needs. It cannot mint a custom entity_id claim for a resource API
        // without a claims-mapping policy and a custom signing key, so Phase 21 taught
        // EntityAccessGuard to read entity.<ID> out of the roles claim through
        // JwtRoleConverter.grantedValues — the same parser the authorities go through. That branch
        // had never seen a token an issuer signed.
        HttpHeaders headers = bearer(passwordToken("matrix.operator"));

        assertThat(rest.exchange("/v1/admin/ropa/MATRIX", HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(rest.exchange("/v1/admin/ropa/DENAVE_IN", HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getStatusCode())
                .withFailMessage("a token scoped to MATRIX read DENAVE_IN. This is the ROADMAP "
                        + "criterion's second half and the defect rules section 2 exists to refuse")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a token naming two entities is refused rather than resolved to one")
    void twoEntityRolesAreRefused() {
        // Asserted in JwtAuthenticationIT against a token this repository minted. Here the
        // over-assignment is a real directory state — a user granted two roles, which is one
        // mis-click in an admin console — and the refusal has to survive whatever order the
        // provider happens to serialise them in.
        assertThat(rest.exchange("/v1/admin/ropa/MATRIX", HttpMethod.GET,
                new HttpEntity<>(bearer(passwordToken("over.assigned"))), String.class)
                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("the human in the audit row comes from the provider's claim, with no header sent")
    void theSignedUsernameSuppliesTheActor() {
        String applicationId = "kc-test-" + UUID.randomUUID();
        HttpHeaders headers = bearer(passwordToken("denave.operator"));
        // Suppressed, so this proves the token supplied the name rather than the shared test
        // interceptor having quietly supplied one.
        headers.add(IntegrationTestClient.SUPPRESS_ACTOR, "true");

        assertThat(rest.exchange("/v1/admin/applications/" + applicationId, HttpMethod.PUT,
                new HttpEntity<>(application(), headers), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // ROADMAP's IdP criterion, verbatim, and the half that is easiest to lose: the realm has
        // an explicit preferred_username mapper because naming clientScopes in an import REPLACES
        // Keycloak's built-in profile scope, silently. Without it this reads a uuid — which is
        // exactly what Entra does by default, and why Actor's fallback to `sub` is worth a WARN.
        AdminAuditStore.Entry entry = audit.recent("DENAVE_IN", 200).stream()
                .filter(row -> applicationId.equals(row.targetId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no audit row for " + applicationId));

        assertThat(entry.actorId()).isEqualTo("denave.operator");

        // BOTH halves, and the second one is here because the first realm this suite ran against
        // failed it. Naming clientScopes in a realm import replaces Keycloak's built-in set —
        // including `basic`, which is what carries `sub` — so the tokens had no subject at all.
        // They authenticated, they authorised correctly, and every audit row they produced named
        // the human and recorded NULL for the credential. Rules section 5 is that a credential is
        // not a person; it is also that both are recorded. No in-process test could catch this,
        // because every token JwtAuthenticationIT builds is given an explicit subject.
        assertThat(entry.clientId())
                .withFailMessage("the audit row recorded no credential. The token most likely "
                        + "carries no `sub` claim — check the realm's subject mapper")
                .isNotBlank();
    }

    @Test
    @DisplayName("a token from another realm of the same provider is refused")
    void aTokenFromAnotherIssuerIsRefused() {
        // JwtIssuerValidator, which is unreachable without an issuer to validate against. The
        // `other` realm is the same Keycloak on the same host and port, signing with a different
        // key under a different iss — the closest thing to the real failure, which is a provider
        // fronting several services and a token minted for one of the others.
        //
        // It claims the SAME audience on purpose. Otherwise the audience check would refuse the
        // token first and this would pass without JwtIssuerValidator ever running, which is the
        // mechanism-instead-of-property trap. The first draft used Keycloak's `master` realm and
        // could not get a token out of it at all — its default sslRequired refuses plain HTTP.
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", "other-client");
        form.add("username", "outsider");
        form.add("password", "dev");

        String foreign = tokenFrom("other", form);

        assertThat(rest.exchange("/v1/admin/ropa/DENAVE_IN", HttpMethod.GET,
                new HttpEntity<>(bearer(foreign)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // -----------------------------------------------------------------------------------------

    private String serviceAccountToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", "athena-dialer");
        form.add("client_secret", "dev-athena-secret");
        return tokenFrom("uds", form);
    }

    private String passwordToken(String username) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", "compliance-console");
        form.add("username", username);
        form.add("password", "dev");
        return tokenFrom("uds", form);
    }

    @SuppressWarnings("unchecked")
    private String tokenFrom(String realm, MultiValueMap<String, String> form) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        String url = "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080)
                + "/realms/" + realm + "/protocol/openid-connect/token";

        ResponseEntity<Map> response = new org.springframework.web.client.RestTemplate()
                .postForEntity(url, new HttpEntity<>(form, headers), Map.class);

        Object token = response.getBody() == null ? null : response.getBody().get("access_token");
        assertThat(token)
                .withFailMessage("the provider issued no token for realm %s: %s", realm,
                        response.getBody())
                .isNotNull();
        return (String) token;
    }

    private String entityOnTheSession(String jwtToken) throws Exception {
        Jwt decoded = jwtDecoder.decode(jwtToken);
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(decoded, List.of(), decoded.getSubject()));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select coalesce(current_setting('uds.entity_id', true), '')")) {
            ResultSet rows = statement.executeQuery();
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static Map<String, Object> decisionRequest() {
        return Map.of(
                "entityId", "DENAVE_IN",
                "subjectId", "keycloak-suite-subject",
                "purposeCode", "MKT_OUTBOUND_CALL",
                "channel", "VOICE_CALL",
                "jurisdiction", "IN");
    }

    private static Map<String, Object> application() {
        return Map.of(
                "entityId", "DENAVE_IN",
                "name", "Keycloak fixture " + UUID.randomUUID(),
                "platform", "WEB",
                "environment", "TEST",
                "active", true);
    }
}
