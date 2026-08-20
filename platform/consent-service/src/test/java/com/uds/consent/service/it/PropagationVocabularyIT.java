package com.uds.consent.service.it;

import com.uds.consent.ledger.store.PropagationSystemStore;
import com.uds.consent.ledger.store.PropagationTargetStore;
import com.uds.consent.ledger.store.WebhookStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A system code the database cannot resolve is refused, rather than written as a daily lie.
 *
 * <p>V31 joined {@code propagation_target.system_code} to {@code webhook_subscription.system_code}
 * as free text. A target for {@code DENCRM} against a subscription an operator named
 * {@code DENCRM_PROD} never joins — so the reconciler reports a mandatory obligation as uncovered,
 * every day, for a system that is receiving every event perfectly well. {@code propagation_gap} is
 * <strong>append-only</strong>, so those rows become permanent evidence of a failure that never
 * happened, in the artefact the platform would hand a regulator.
 *
 * <p>{@code fulfilment_target} gets the same class of error right by failing loud and closed — a
 * 409 naming the system, at the moment somebody tries to close a request. Propagation failed quiet,
 * and wrote. V33 keys both sides to a declared vocabulary, so the database refuses the typo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "uds.consent.events.relay-interval=PT1H")
class PropagationVocabularyIT extends PostgresIntegrationTest {

    private static final String ENTITY = "DENAVE_IN";
    private static final String TOPIC = "uds.consent.events";
    private static final String SYSTEMS = "/v1/admin/propagation/systems";
    private static final String TARGETS = "/v1/admin/propagation/targets";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private PropagationSystemStore systems;

    @Autowired
    private PropagationTargetStore targets;

    @Autowired
    private WebhookStore webhooks;

    @Test
    @DisplayName("a target naming an undeclared system is refused, and the refusal names what is declared")
    void anUndeclaredSystemIsRefused() {
        String typo = "DENCRM_TYPO_" + suffix();

        ResponseEntity<String> response = putTarget(typo);

        // Loud and closed, at the moment the operator types it. The alternative — what shipped in
        // V31 — is that the code is accepted, never joins, and is reported as an unmet obligation
        // every day thereafter in a table nothing can edit.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("unknown system code").contains(typo);
        // Naming the declared set is what makes the refusal actionable rather than merely correct:
        // the operator sees DENCRM beside their DENCRM_TYPO and fixes it in one step.
        assertThat(response.getBody()).contains("recognises");
    }

    @Test
    @DisplayName("the database refuses an undeclared code, not only the controller")
    void theConstraintIsInTheDatabase() {
        // The refusal above comes from AdminController.requireKnownSystem — application-level
        // validation, which is the branch V33's plan explicitly rejected in favour of a key the
        // database enforces. Every existing assertion in this suite passes with
        // fk_propagation_target_system dropped, so the constraint the whole argument rests on was
        // asserted by nothing.
        //
        // As uds_consent_app, because that is the role the application actually writes with and
        // the only role whose refusal means anything — the same reasoning LedgerAppendOnlyIT and
        // RowLevelSecurityIT are built on.
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                POSTGRES.getJdbcUrl(), "uds_consent_app", "uds_consent_app", true);
        dataSource.setDriverClassName(org.postgresql.Driver.class.getName());
        JdbcTemplate asApplication = new JdbcTemplate(dataSource);
        asApplication.queryForObject("select set_config('uds.entity_id', ?, false)",
                String.class, ENTITY);

        String undeclared = "NEVER_DECLARED_" + suffix();
        try {
            assertThatThrownBy(() -> asApplication.update(
                    "insert into propagation_target (entity_id, topic, system_code, mandatory, "
                            + "active) values (?, ?, ?, true, true)",
                    ENTITY, TOPIC, undeclared))
                    .withFailMessage("the database accepted a system code nobody declared, so the "
                            + "foreign key is doing nothing and a typo can still write permanent "
                            + "false evidence into propagation_gap")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("fk_propagation_target_system");
        } finally {
            dataSource.destroy();
        }
    }

    @Test
    @DisplayName("a declared system may be targeted, and the declaration is audited")
    void aDeclaredSystemIsAccepted() {
        String system = "VOCAB_" + suffix();

        assertThat(putSystem(system).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(putTarget(system).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(targets.coverage(ENTITY))
                .anyMatch(c -> c.systemCode().equals(system));
    }

    @Test
    @DisplayName("a subscription's system code can be corrected without discarding its delivery history")
    void aSubscriptionCodeIsCorrectableInPlace() {
        String wrong = "MISMATCH_" + suffix();
        String right = "CORRECT_" + suffix();
        String subscriptionId = "sub-" + suffix();

        systems.upsert(ENTITY, wrong, "as first registered", true);
        systems.upsert(ENTITY, right, "what the target names", true);
        targets.upsert(ENTITY, TOPIC, right, true, true, "vocabulary fixture");

        webhooks.upsert(subscriptionId, ENTITY, TOPIC, "http://127.0.0.1:1/" + subscriptionId,
                "s", true, "first registration", wrong);
        webhooks.recordDelivery(subscriptionId, ENTITY, "vocab-subject", 1L, 1, "DELIVERED",
                200, null, Instant.now().truncatedTo(ChronoUnit.SECONDS));

        // Before: the target is mandatory, active, and nothing joins it — a phantom gap.
        assertThat(targets.mandatoryFor(ENTITY, TOPIC))
                .filteredOn(c -> c.systemCode().equals(right))
                .allMatch(PropagationTargetStore.Coverage::uncovered);

        // The correction. Before this was settable an operator's only option was to delete the
        // subscription and recreate it, which discards webhook_delivery — the append-only evidence
        // that withdrawals reached that system. Fixing a configuration mistake must not cost the
        // proof that the system was working.
        webhooks.upsert(subscriptionId, ENTITY, TOPIC, "http://127.0.0.1:1/" + subscriptionId,
                "s", true, "corrected", right);

        assertThat(targets.mandatoryFor(ENTITY, TOPIC))
                .filteredOn(c -> c.systemCode().equals(right))
                .noneMatch(PropagationTargetStore.Coverage::uncovered);

        // The property that makes this worth doing in place: the delivery evidence survived.
        assertThat(webhooks.deliveriesFor(1L))
                .anyMatch(d -> d.subscriptionId().equals(subscriptionId)
                        && d.status().equals("DELIVERED"));
    }

    @Test
    @DisplayName("the health read answers coverage and reachability together, not one or the other")
    void healthJoinsCoverageAndDelivery() {
        String system = "HEALTH_" + suffix();
        String subscriptionId = "sub-" + suffix();

        systems.upsert(ENTITY, system, "health fixture", true);
        targets.upsert(ENTITY, TOPIC, system, true, true, "health fixture");
        webhooks.upsert(subscriptionId, ENTITY, TOPIC, "http://127.0.0.1:1/" + subscriptionId,
                "s", true, "health fixture", system);
        webhooks.recordDelivery(subscriptionId, ENTITY, "health-subject", 2L, 1, "FAILED",
                500, "boom", Instant.now().truncatedTo(ChronoUnit.SECONDS));

        // Covered — a subscription exists — and NOT current, because the last attempt failed. That
        // combination is invisible in propagation_gap: a failing delivery throws, the message stays
        // unpublished, the relay breaks, and the reconciler never runs for it. Before this read an
        // operator had to consult two artefacts and reconcile them by hand.
        assertThat(targets.healthFor(ENTITY))
                .filteredOn(h -> h.coverage().systemCode().equals(system))
                .singleElement()
                .satisfies(h -> {
                    assertThat(h.coverage().uncovered()).isFalse();
                    assertThat(h.lastStatus()).isEqualTo("FAILED");
                    assertThat(h.consecutiveFailures()).isGreaterThan(0);
                    assertThat(h.attention()).isTrue();
                });
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private ResponseEntity<String> putSystem(String systemCode) {
        return rest.withBasicAuth("compliance-console", "admin-secret")
                .exchange(SYSTEMS, HttpMethod.PUT, new HttpEntity<>(Map.of(
                        "entityId", ENTITY,
                        "systemCode", systemCode,
                        "description", "vocabulary suite",
                        "active", true), headers()), String.class);
    }

    private ResponseEntity<String> putTarget(String systemCode) {
        return rest.withBasicAuth("compliance-console", "admin-secret")
                .exchange(TARGETS, HttpMethod.PUT, new HttpEntity<>(Map.of(
                        "entityId", ENTITY,
                        "topic", TOPIC,
                        "systemCode", systemCode,
                        "mandatory", true,
                        "active", true,
                        "description", "vocabulary suite"), headers()), String.class);
    }

    private static HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-UDS-Actor", "priya@uds.example");
        return headers;
    }
}
