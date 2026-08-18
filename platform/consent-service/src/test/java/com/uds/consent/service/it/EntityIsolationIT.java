package com.uds.consent.service.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Denave credential cannot read a Matrix row, through any endpoint.
 *
 * <p>The largest security gap the platform had, and the one that grew with every entity onboarded.
 * Every entity-scoped endpoint takes an {@code entityId} and every one of them trusted it — so a
 * Matrix {@code ADMIN} credential could read Denave's consent records, its administrative audit
 * trail and its RoPA by changing one string, with nothing anywhere noticing, because every field
 * in the request is individually well-formed.
 *
 * <p>The suite is written as a sweep rather than as a handful of representative cases, and
 * deliberately so: what matters is not that one endpoint is protected but that none is left out.
 * The failure mode of this control is a route somebody added later, so the test is the list.
 */
class EntityIsolationIT extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    /**
     * Every entity-scoped read the platform offers.
     *
     * <p>Query-parameter form, which is how all but two of them take the entity. The path-variable
     * pair are exercised separately below because they exercise a different branch of the guard.
     */
    private static final List<String> ENTITY_SCOPED_READS = List.of(
            "/v1/admin/applications?entityId=",
            "/v1/admin/provenance/summary?entityId=",
            "/v1/admin/provenance/quarantined?entityId=",
            "/v1/admin/processing-activities?entityId=",
            "/v1/admin/vendors?entityId=",
            "/v1/admin/audit?entityId=",
            "/v1/admin/enforcement/denials?entityId=",
            "/v1/admin/enforcement/scrub-runs?entityId=",
            "/v1/admin/retention/open?entityId=",
            "/v1/admin/breaches?entityId=",
            "/v1/admin/dlt/registrations?entityId=",
            "/v1/admin/dlt/headers?entityId=");

    @Test
    @DisplayName("a Denave credential is refused every Matrix-scoped read")
    void aScopedCredentialCannotReachAnotherEntity() {
        for (String path : ENTITY_SCOPED_READS) {
            ResponseEntity<String> response = asDenave().getForEntity(path + "MATRIX", String.class);

            assertThat(response.getStatusCode())
                    .withFailMessage("%s let a Denave credential ask about MATRIX (status %s)",
                            path, response.getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    @DisplayName("the same credential reaches its own entity through every one of them")
    void aScopedCredentialReachesItsOwnEntity() {
        // The other half, and the half that is easy to forget. A control that refuses everything
        // passes the test above and is useless — worse than useless, because somebody will turn
        // it off rather than debug it.
        for (String path : ENTITY_SCOPED_READS) {
            ResponseEntity<String> response =
                    asDenave().getForEntity(path + "DENAVE_IN", String.class);

            assertThat(response.getStatusCode())
                    .withFailMessage("%s refused a Denave credential its own entity (status %s)",
                            path, response.getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    @DisplayName("the entity in a path variable is checked too, not only a query parameter")
    void pathVariablesAreCheckedAsWell() {
        // Two endpoints take the entity as a path segment. A guard that only read query
        // parameters would look correct on eleven routes and be wide open on these.
        assertThat(asDenave().getForEntity("/v1/admin/ropa/MATRIX", String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(asDenave().getForEntity("/v1/admin/ropa/DENAVE_IN", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(asDenave()
                .getForEntity("/v1/admin/integrity/MATRIX/some-subject", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a Matrix credential is refused Denave, so the rule is not one-directional")
    void theRuleRunsBothWays() {
        // Asserted explicitly because a guard comparing against a hard-coded entity would pass
        // every test above and fail here.
        assertThat(rest.withBasicAuth("matrix-console", "matrix-secret")
                .getForEntity("/v1/admin/audit?entityId=DENAVE_IN", String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(rest.withBasicAuth("matrix-console", "matrix-secret")
                .getForEntity("/v1/admin/audit?entityId=MATRIX", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a group-level credential still sees every entity")
    void groupComplianceIsNotLockedOut() {
        // A grant rather than a gap. Group compliance genuinely has to see everything, and
        // pretending otherwise produces a shared credential nobody can attribute — which is a
        // worse outcome than an explicit, logged, unscoped account.
        for (String entityId : List.of("DENAVE_IN", "MATRIX", "ATHENA")) {
            assertThat(rest.withBasicAuth("compliance-console", "admin-secret")
                    .getForEntity("/v1/admin/audit?entityId=" + entityId, String.class)
                    .getStatusCode())
                    .withFailMessage("group-level credential refused %s", entityId)
                    .isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    @DisplayName("the subject-facing reads are scoped, and this is where the gap was")
    void subjectReadsAreScoped() {
        // The most sensitive routes in the platform: one person's consent state, their whole
        // event history, and their receipt. They take the entity as a path segment, so a guard
        // reading only query parameters would have looked correct on a dozen admin routes while
        // leaving these open. This suite is what found that.
        for (String suffix : List.of("", "/history", "/receipt")) {
            assertThat(rest.withBasicAuth("denave-web", "capture-secret")
                    .getForEntity("/v1/consent/MATRIX/some-subject" + suffix, String.class)
                    .getStatusCode())
                    .withFailMessage("/v1/consent/{entityId}/{subjectId}%s was not scoped", suffix)
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        assertThat(asDenave()
                .getForEntity("/v1/rights/subject/MATRIX/some-subject", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("the routes that carry the entity in the path are all scoped, including the "
            + "three that were not")
    void theRemainingPathScopedRoutesAreCovered() {
        // Three families were missing from the guard's prefix list, all added after it: the SDF
        // register (V20), provenance and the signed snapshot. Each takes {entityId} as the first
        // segment, which is the branch subjectReadsAreScoped() proved was the easy one to miss.
        //
        // The SDF register in particular says which group companies are under a Government
        // designation and how far behind they are on a statutory obligation. Denave reading that
        // about Matrix is not a data-protection breach so much as a corporate-governance one, and
        // it is the sort of thing that surfaces in a due-diligence exercise rather than in a log.
        for (String path : List.of(
                "/v1/admin/sdf/MATRIX",
                "/v1/admin/sdf/MATRIX/systems",
                "/v1/provenance/MATRIX/some-subject",
                "/v1/snapshot/MATRIX/some-subject")) {
            assertThat(asDenave().getForEntity(path, String.class).getStatusCode())
                    .withFailMessage("%s let a Denave credential ask about MATRIX", path)
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        // And the other half. A prefix added without its reserved segments refuses everything
        // underneath it, which passes the assertions above while breaking the platform.
        assertThat(asDenave().getForEntity("/v1/admin/sdf/DENAVE_IN", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(asDenave().getForEntity("/v1/provenance/summary?entityId=DENAVE_IN",
                String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(asDenave().getForEntity("/v1/snapshot/purposes", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a cross-entity refusal is RFC 7807, like every other refusal on the platform")
    void theRefusalIsShapedLikeEveryOtherRefusal() {
        // This filter used to call sendError(403), which returns the container's error page. An
        // integrator writing one error-handling path against the API would find it worked for all
        // nine exceptions ApiExceptionHandler covers and not for the refusal that means "you asked
        // about somebody else's data" — the one most worth reading.
        ResponseEntity<String> response =
                asDenave().getForEntity("/v1/admin/ropa/MATRIX", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getContentType())
                .withFailMessage("expected application/problem+json, got %s",
                        response.getHeaders().getContentType())
                .isNotNull();
        assertThat(response.getHeaders().getContentType().toString())
                .startsWith("application/problem+json");
        assertThat(response.getBody())
                .contains("\"status\":403")
                .contains("Cross-entity request refused");

        // And the entity that was asked about is deliberately absent: echoing it back would let a
        // caller enumerate which entity ids exist by reading its own refusals.
        assertThat(response.getBody())
                .withFailMessage("the refusal echoed the requested entity id back to the caller")
                .doesNotContain("MATRIX");
    }

    @Test
    @DisplayName("a withdrawal is never mistaken for a cross-entity request")
    void withdrawalIsNotBlockedByTheGuard() {
        // /v1/consent/withdraw shares its prefix with /v1/consent/{entityId}/{subjectId}. A guard
        // that read "withdraw" as an entity id would refuse the single most important call the
        // platform serves — and failing closed, which is right almost everywhere, is exactly
        // wrong there.
        ResponseEntity<String> response = rest.withBasicAuth("denave-web", "capture-secret")
                .postForEntity("/v1/consent/withdraw", java.util.Map.of(
                        "entityId", "DENAVE_IN",
                        "subjectId", "iso-nobody",
                        "purposeCodes", List.of("MKT_OUTBOUND_CALL")), String.class);

        // Whatever the request's own merits, it must not be a 403 from the entity guard.
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("an unscoped path is untouched by the guard")
    void nonEntityEndpointsAreUnaffected() {
        // The guard must not become a filter that refuses anything with a path segment it does
        // not recognise. A notice is public, and the purpose registry is group-wide.
        assertThat(rest.getForEntity("/v1/notices/NOTICE_DENAVE_B2B?jurisdiction=IN&lang=en",
                String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(asDenave().getForEntity("/v1/admin/purposes", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(asDenave().getForEntity("/v1/admin/entities", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private TestRestTemplate asDenave() {
        return rest.withBasicAuth("denave-console", "denave-secret");
    }
}
