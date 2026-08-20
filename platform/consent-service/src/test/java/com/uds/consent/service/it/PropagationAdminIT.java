package com.uds.consent.service.it;

import com.uds.consent.ledger.store.AdminAuditStore;
import com.uds.consent.ledger.store.PropagationSystemStore;
import com.uds.consent.ledger.store.WebhookStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The propagation register, through the routes an operator actually uses.
 *
 * <p>Phase 17 built {@code propagation_target}, its two admin routes and the resolved-subscription
 * field, and shipped all three with <strong>no integration test at all</strong> — the closure
 * record says so rather than claiming otherwise. What existed was {@code PropagationIT}, which
 * drives the stores directly, and {@code OpenApiContractIT}, which proves the mappings render. So
 * the role boundary, the {@code X-UDS-Actor} requirement, the audit row and the one field that
 * shows an operator a {@code system_code} mismatch were all unasserted.
 *
 * <p>The last of those is the reason this suite is worth more than a boundary check. The register
 * joins {@code propagation_target.system_code} to {@code webhook_subscription.system_code} as exact
 * upper-case text, and a mismatch fails <em>quiet</em>: a target for {@code DENCRM} against a
 * subscription labelled {@code DENCRM_PROD} produces a phantom gap every day, permanently, in an
 * append-only table. The GET's per-target subscription id is the only place that is visible before
 * the evidence is written, which makes it a control rather than a convenience.
 */
class PropagationAdminIT extends PostgresIntegrationTest {

    private static final String ENTITY = "DENAVE_IN";
    private static final String TOPIC = "uds.consent.events";
    private static final String TARGETS = "/v1/admin/propagation/targets";

    @Autowired
    private PropagationSystemStore propagationSystems;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AdminAuditStore audit;

    @Autowired
    private WebhookStore webhooks;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("registering a target records who configured it, not just which credential")
    void registeringATargetNamesTheHuman() {
        String system = uniqueSystem();
        int before = audit.recent(ENTITY, 200).size();

        try {
            ResponseEntity<String> response = put(system, true, true, "priya@uds.example");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

            // rules 5: a credential is not a person. compliance-console is one password a team
            // holds, so an audit row naming it alone cannot answer "who decided this system must
            // be told" — which is the question asked when a register turns out to be wrong.
            assertThat(audit.recent(ENTITY, 200)).hasSizeGreaterThan(before)
                    .anySatisfy(entry -> {
                        assertThat(entry.action()).isEqualTo("PROPAGATION_TARGET_CONFIGURED");
                        assertThat(entry.targetId()).isEqualTo(system);
                        // Two separate facts, as rules 5 requires: the person who acted, and the
                        // credential they acted through.
                        assertThat(entry.actorId()).isEqualTo("priya@uds.example");
                        assertThat(entry.clientId()).isEqualTo("compliance-console");
                    });
        } finally {
            cleanUp(system);
        }
    }

    @Test
    @DisplayName("a registration with no named actor is refused, and writes nothing")
    void anUnattributedRegistrationIsRefused() {
        String system = uniqueSystem();
        try {
            ResponseEntity<String> response = put(system, true, true, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody())
                    .withFailMessage("the refusal does not name the header, so an integrator "
                            + "meeting it has to guess or read the source")
                    .contains("X-UDS-Actor");
            // The property, not the status. A refusal thrown after the write would leave the
            // register configured and the evidence plane silent about who did it.
            assertThat(registeredSystems()).doesNotContain(system);
        } finally {
            cleanUp(system);
        }
    }

    @Test
    @DisplayName("the register shows which subscription answers for a target, and null when none does")
    void theResolvedSubscriptionIsVisible() {
        String matched = uniqueSystem();
        String mismatched = uniqueSystem();

        try {
            put(matched, false, true, "ops@uds.example");
            put(mismatched, false, true, "ops@uds.example");

            // The subscription's system_code is derived from its id, so registering it under the
            // matched code is what makes the join meet. The mismatched target has a subscription
            // in the world under a different label — which is the production failure exactly.
            // Distinct URLs: webhook_subscription is unique on (entity_id, topic, url), because
            // two subscriptions pointing at the same endpoint would double-deliver.
            declare(matched.toUpperCase(java.util.Locale.ROOT));
            webhooks.upsert(matched, ENTITY, TOPIC, "http://127.0.0.1:1/" + matched, "s", true,
                    "matched");
            declare(mismatched + "_PROD".toUpperCase(java.util.Locale.ROOT));
            webhooks.upsert(mismatched + "_PROD", ENTITY, TOPIC,
                    "http://127.0.0.1:1/" + mismatched, "s", true, "labelled differently");

            @SuppressWarnings("unchecked")
            Map<String, Object> body = rest.withBasicAuth("compliance-console", "admin-secret")
                    .getForObject(TARGETS + "?entityId=" + ENTITY, Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> targets = (List<Map<String, Object>>) body.get("targets");

            assertThat(targets).anySatisfy(target -> {
                assertThat(target.get("systemCode")).isEqualTo(matched);
                assertThat(target.get("subscriptionId")).isEqualTo(matched);
            });

            // Null here is the finding, and it is the only thing that distinguishes "nobody is
            // registered" from "somebody typed a different name" before a year of phantom gap rows
            // accumulates in an append-only table.
            assertThat(targets).anySatisfy(target -> {
                assertThat(target.get("systemCode")).isEqualTo(mismatched);
                assertThat(target.get("subscriptionId")).isNull();
            });
        } finally {
            cleanUp(matched);
            cleanUp(mismatched);
            // Deleted rather than deactivated: (entity_id, topic, url) is unique across inactive
            // rows too, so a leftover here fails the next suite that registers an endpoint.
            jdbc.update("delete from webhook_subscription where subscription_id in (?, ?)",
                    matched, mismatched + "_PROD");
        }
    }

    @Test
    @DisplayName("uncovered names only the mandatory targets nothing can reach")
    void uncoveredIsMandatoryAndUnreachable() {
        String mandatory = uniqueSystem();
        String optional = uniqueSystem();

        try {
            put(mandatory, true, true, "ops@uds.example");
            put(optional, false, true, "ops@uds.example");

            @SuppressWarnings("unchecked")
            Map<String, Object> body = rest.withBasicAuth("compliance-console", "admin-secret")
                    .getForObject(TARGETS + "?entityId=" + ENTITY, Map.class);

            @SuppressWarnings("unchecked")
            List<String> uncovered = (List<String>) body.get("uncovered");

            assertThat(uncovered).contains(mandatory);
            // Non-mandatory records the relationship without alerting on it. If this ever contained
            // the optional target, the critical alert built on the same predicate would fire for a
            // system nobody said had to be told.
            assertThat(uncovered).doesNotContain(optional);
        } finally {
            cleanUp(mandatory);
            cleanUp(optional);
        }
    }

    @Test
    @DisplayName("a target on a topic the platform never publishes is refused at registration")
    void anUnpublishedTopicIsRefused() {
        String system = uniqueSystem();
        try {
            HttpHeaders headers = headers("ops@uds.example");
            ResponseEntity<String> response = rest.withBasicAuth("compliance-console", "admin-secret")
                    .exchange(TARGETS, HttpMethod.PUT, new HttpEntity<>(Map.of(
                            "entityId", ENTITY,
                            "topic", "uds.consent.invented",
                            "systemCode", system,
                            "mandatory", true,
                            "active", true,
                            "description", "a topic nothing is enqueued to"), headers),
                            String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            // Refused rather than accepted-and-warned, because a target on a topic nothing is
            // enqueued to can never be reconciled: it reads as covered forever while nobody is
            // ever told. A register that fails open looks exactly like success.
            assertThat(registeredSystems()).doesNotContain(system);
        } finally {
            cleanUp(system);
        }
    }

    @Test
    @DisplayName("the gaps route is entity-scoped like every other subject read")
    void gapsAreEntityScoped() {
        // A Denave credential asking about Matrix is refused by EntityAccessGuard reading the
        // query parameter, before RLS is reached. Both layers cover this route and they must agree.
        ResponseEntity<String> response = rest.withBasicAuth("denave-console", "denave-secret")
                .getForEntity("/v1/admin/propagation/gaps?entityId=MATRIX", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // -------------------------------------------------------------------------------------------

    /**
     * Registers a target through the route, declaring its system code first.
     *
     * <p>The declaration is what an operator does before naming a system on either side of the
     * join — {@code V33} refuses a code the entity has not declared, so that a typo is caught at
     * the moment it is typed rather than reported as a daily unmet obligation, permanently, in an
     * append-only table. {@code anUnknownSystemCodeIsRefused} covers the refusal itself; every
     * other test here is about the target, so they declare and move on.
     */
    private ResponseEntity<String> put(String systemCode, boolean mandatory, boolean active,
                                       String actor) {
        declare(systemCode);
        return rest.withBasicAuth("compliance-console", "admin-secret")
                .exchange(TARGETS, HttpMethod.PUT, new HttpEntity<>(Map.of(
                        "entityId", ENTITY,
                        "topic", TOPIC,
                        "systemCode", systemCode,
                        "mandatory", mandatory,
                        "active", active,
                        "description", "propagation admin suite"), headers(actor)), String.class);
    }

    private static HttpHeaders headers(String actor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (actor != null) {
            headers.set("X-UDS-Actor", actor);
        } else {
            // Opt out of the interceptor every suite shares, which otherwise supplies an actor on
            // every call. Without this, a test asserting the refusal asserts nothing — the header
            // it means to omit is added on the way out.
            headers.set(IntegrationTestClient.SUPPRESS_ACTOR, "true");
        }
        return headers;
    }

    private List<String> registeredSystems() {
        return jdbc.queryForList("select system_code from propagation_target where entity_id = ?",
                String.class, ENTITY);
    }

    /**
     * Removes the register rows this suite wrote.
     *
     * <p>{@code propagation_target} is configuration rather than evidence, so it is deleted rather
     * than deactivated: the coverage query reports inactive targets too, and a mandatory one left
     * behind would hold {@code uds_consent_propagation_uncovered} above zero for every suite that
     * runs afterwards — one test class deciding another's gauge.
     */
    private void cleanUp(String systemCode) {
        jdbc.update("delete from propagation_target where entity_id = ? and system_code = ?",
                ENTITY, systemCode);
    }

    /** A system code no other suite will register. */
    private static String uniqueSystem() {
        return "PADMIN_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /**
     * Declares a system code before it is used on either side of the propagation join.
     *
     * <p>{@code V33} put a foreign key on {@code propagation_system} from both
     * {@code propagation_target} and {@code webhook_subscription}, so a code the entity has not
     * declared is refused by the database rather than silently producing a daily gap row for a
     * system that may be perfectly reachable. Fixtures declare theirs the way an operator would.
     */
    private void declare(String systemCode) {
        propagationSystems.upsert(ENTITY, systemCode, "test fixture", true);
    }
}
