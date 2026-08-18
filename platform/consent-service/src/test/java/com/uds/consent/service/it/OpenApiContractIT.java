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
