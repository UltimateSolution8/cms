package com.uds.consent.service.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the published HTTP contract.
 *
 * <p>Denave's website, Athena's dialer and DenCRM integrate against these routes and nothing holds
 * them still. springdoc builds {@code /v3/api-docs} from the controllers at runtime, so renaming a
 * field or tightening a required flag changes the contract silently and the discovery event is an
 * integrator's production failure. This test makes it a build failure instead.
 *
 * <p><strong>The snapshot is the reviewable artefact.</strong> {@code docs/openapi.json} is checked
 * in, so a change to the API shows up as a diff in that file in the same change that caused it —
 * which is the point. A reviewer reading "one field renamed" in the source sees, beside it, whether
 * that field was in the published contract.
 *
 * <h2>Regenerating, deliberately</h2>
 *
 * <p>When the change is intended:
 *
 * <pre>{@code
 * mvn -B verify -pl consent-service -am -Dit.test=OpenApiContractIT -Dtest='!*' \
 *     -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false \
 *     -Duds.openapi.snapshot=update
 * }</pre>
 *
 * <p>A flag rather than "delete the file and re-run": an absent snapshot regenerating itself would
 * make a deleted contract indistinguishable from an approved one, and the failure mode of this whole
 * test is somebody making it green without reading the diff.
 */
class OpenApiContractIT extends PostgresIntegrationTest {

    /**
     * Under {@code docs/}, not {@code src/test/resources/}. It is the artefact an integrator is
     * given, and burying the published contract inside a test tree is how it stops being read.
     * Relative to the Maven module directory, which is {@code platform/consent-service}.
     */
    private static final Path SNAPSHOT = Paths.get("..", "..", "docs", "openapi.json");

    private static final String UPDATE_FLAG = "uds.openapi.snapshot";

    private static final ObjectMapper JSON = new ObjectMapper()
            // Deterministic key order and two-space indentation, so that a diff on this file is the
            // diff in the API and not the diff in whatever order Jackson felt like today.
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("the published OpenAPI document has not drifted from the checked-in snapshot")
    void theContractMatchesTheSnapshot() throws IOException {
        // ADMIN, because the specification is not public on this platform: it enumerates every
        // administrative route and the shape of the evidence they return. SecurityConfiguration
        // decided that and this test does not get to disagree with it.
        ResponseEntity<String> response = rest
                .withBasicAuth("compliance-console", "admin-secret")
                .getForEntity("/v3/api-docs", String.class);

        assertThat(response.getStatusCode().value())
                .withFailMessage("/v3/api-docs did not serve, so nothing publishes the contract "
                        + "integrators build against: %s", response.getStatusCode())
                .isEqualTo(200);

        String current = normalise(response.getBody());

        if (Boolean.parseBoolean(System.getProperty(UPDATE_FLAG))
                || "update".equals(System.getProperty(UPDATE_FLAG))) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, current);
            return;
        }

        assertThat(Files.exists(SNAPSHOT))
                .withFailMessage("docs/openapi.json is missing. Regenerate it with "
                        + "-D%s=update and review the result before committing — an absent "
                        + "snapshot is an unpinned contract, not a passing test", UPDATE_FLAG)
                .isTrue();

        assertThat(current)
                .withFailMessage("""
                        The published API contract has changed.

                        If the change is intended, regenerate the snapshot with -D%s=update and \
                        review the diff — every removed or renamed field in it is an integration \
                        somebody has to be told about before it is deployed.

                        If it is not intended, this is the defect: a controller or DTO change has \
                        altered the contract Denave's website, Athena's dialer and DenCRM build \
                        against.""".formatted(UPDATE_FLAG))
                .isEqualTo(Files.readString(SNAPSHOT));
    }

    @Test
    @DisplayName("no two operations share an id, and none is an auto-numbered collision")
    void operationIdsAreStable() throws IOException {
        JsonNode paths = document().get("paths");

        List<String> ids = new ArrayList<>();
        paths.fields().forEachRemaining(path -> path.getValue().fields().forEachRemaining(
                operation -> {
                    JsonNode id = operation.getValue().get("operationId");
                    if (id != null) {
                        ids.add(id.asText());
                    }
                }));

        assertThat(ids).isNotEmpty();
        assertThat(ids).doesNotHaveDuplicates();

        // The property that matters, and the one the drift check cannot see. springdoc resolves a
        // collision between two Java method names silently, by appending _1, _2 — numbered in
        // DOCUMENT ORDER. So adding an unrelated route renumbers them, and a generated client's
        // method is renamed by a change that did not touch the route it calls. The snapshot
        // comparison would report that as ordinary drift and somebody would regenerate it.
        assertThat(ids)
                .withFailMessage("operation ids auto-numbered by springdoc: %s. Two handler "
                        + "methods share a name; give them stable ids in OpenApiConfiguration "
                        + "rather than letting document order decide.",
                        ids.stream().filter(id -> id.matches(".*_\\d+$")).toList())
                .noneMatch(id -> id.matches(".*_\\d+$"));
    }

    @Test
    @DisplayName("the six sweeper reports are six schemas, not one wearing six routes' clothes")
    void schemaNamesDoNotCollide() throws IOException {
        // The ROADMAP item this closes, and the first version of it asserted the wrong thing.
        //
        // The defect: six sweeper classes are all called `Report`, springdoc collapsed them onto a
        // single schema named `Report`, and the contract described five routes with a sixth one's
        // fields for as long as the pin has existed. `theContractMatchesTheSnapshot` could not have
        // caught it — it compares the document against itself, so an aliased schema is perfectly
        // stable and perfectly wrong.
        //
        // The first detector looked for names springdoc had DISAMBIGUATED — a fully-qualified name
        // or a numeric suffix. That is the opposite of the failure: a silent collapse produces one
        // clean `Report` and no marker at all, so it would have passed. It is asserted here as an
        // outcome instead: each of the six must exist under its own name, and the bare `Report`
        // that a collapse produces must not exist.
        JsonNode schemas = document().get("components").get("schemas");

        // Five, not six. RightsSlaSweeper.Report carries the same explicit name and is exposed on
        // no route at all — the rights SLA sweep runs on a schedule and reports through logs and a
        // gauge — so it never reaches the document. Asserting six failed here, which is the test
        // being wrong rather than the platform: worth recording, because "six sweeper Reports
        // collided" is true of the Java classes and only five of them are published.
        List<String> reports = List.of("BreachSlaReport", "LedgerIntegrityReport",
                "ProjectionReconciliationReport", "ReconfirmationReport", "RetentionReport");
        for (String name : reports) {
            assertThat(schemas.has(name))
                    .withFailMessage("schema %s is missing — a sweeper report has lost its explicit "
                            + "@Schema(name = ...) and springdoc has collapsed it onto another "
                            + "type's schema, which publishes one report's fields under another "
                            + "report's route", name)
                    .isTrue();
        }

        assertThat(schemas.has("Report"))
                .withFailMessage("a schema named exactly `Report` exists. That is what springdoc "
                        + "emits when two classes share the simple name and neither carries an "
                        + "explicit @Schema(name = ...) — one schema, several routes, silently "
                        + "describing whichever type it resolved first.")
                .isFalse();
    }

    @Test
    @DisplayName("exactly the six genuinely public routes are published as needing no credential")
    void onlyPublicRoutesAreUnsecured() throws IOException {
        // The security half of the contract, pinned because the first version got it wrong in the
        // dangerous direction: `/v1/notices/` was matched as a PREFIX, so
        // /v1/notices/{noticeId}/versions — ADMIN, and the evidence of what a principal was shown
        // at capture — was published as world-readable.
        JsonNode paths = document().get("paths");
        List<String> unsecured = new ArrayList<>();
        paths.fields().forEachRemaining(path -> path.getValue().fields().forEachRemaining(
                operation -> {
                    JsonNode security = operation.getValue().get("security");
                    if (security != null && security.isArray() && security.isEmpty()) {
                        unsecured.add(path.getKey());
                    }
                }));

        assertThat(unsecured).containsExactlyInAnyOrder(
                "/v1/portal/requests",
                "/v1/portal/requests/{reference}",
                "/v1/portal/requests/{reference}/verify",
                "/v1/notices/{noticeId}",
                "/v1/notices/{noticeId}/languages",
                "/v1/keys");
    }

    @Test
    @DisplayName("the two schemes are alternatives, not a requirement to present both")
    void theSchemesAreAlternatives() throws IOException {
        // Entries within one Security Requirement Object are ANDed by the specification. A single
        // object naming both schemes states that every route needs Basic AND Bearer at once —
        // which is the opposite of what this platform does, and would send an integrator building
        // from the contract to construct a request that cannot work.
        JsonNode security = document().get("security");

        assertThat(security).hasSize(2);
        assertThat(security.get(0).has("basicAuth")).isTrue();
        assertThat(security.get(0).has("bearerAuth")).isFalse();
        assertThat(security.get(1).has("bearerAuth")).isTrue();
    }

    private JsonNode document() throws IOException {
        assertThat(Files.exists(SNAPSHOT))
                .withFailMessage("docs/openapi.json is missing; regenerate it with -D%s=update",
                        UPDATE_FLAG)
                .isTrue();
        return JSON.readTree(Files.readString(SNAPSHOT));
    }

    /**
     * Strips what changes without the API changing.
     *
     * <p>{@code servers} carries the random port every {@code @SpringBootTest} run picks, so leaving
     * it in would make this test fail on every run for a reason that has nothing to do with the
     * contract — and a test that fails always is a test somebody switches off. {@code info.version}
     * is the build version: it moves at release time, when the contract may be identical.
     */
    private static String normalise(String body) throws IOException {
        ObjectNode document = (ObjectNode) JSON.readTree(body);
        document.remove("servers");
        JsonNode info = document.get("info");
        if (info instanceof ObjectNode object) {
            object.remove("version");
        }
        return JSON.writeValueAsString(document) + "\n";
    }
}
