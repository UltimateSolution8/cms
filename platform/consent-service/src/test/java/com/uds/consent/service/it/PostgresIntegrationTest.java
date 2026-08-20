package com.uds.consent.service.it;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for tests that need the real database.
 *
 * <p>PostgreSQL rather than an in-memory substitute, because the properties under test here are
 * database properties. The ledger's append-only guarantee is enforced by triggers and revoked
 * grants; proving it against H2 would prove that H2 lacks the triggers, which nobody doubts and
 * nobody cares about.
 *
 * <p>One container is shared across every subclass. Testcontainers stops it when the JVM exits, so
 * a suite of ledger tests pays the start-up cost once rather than once per class — which is the
 * difference between a check that runs in CI and one that gets switched off for being slow.
 */
@Tag("integration")
// A real servlet environment rather than a bare context. The security configuration — which roles
// may write consent and which may only ask — is part of what these tests are for, and it does not
// exist outside a web context. Booting the whole thing also means a mistake in the wiring shows up
// here rather than on first deployment.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integrationtest")
// Makes every suite's HTTP client send X-UDS-Actor, which administrative mutations now require.
// See IntegrationTestClient for why it is a RestTemplateBuilder rather than an interceptor added
// after the fact — the short version is that withBasicAuth silently discards the latter.
@Import(IntegrationTestClient.class)
public abstract class PostgresIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("uds_consent")
                    .withUsername("uds_consent_owner")
                    .withPassword("uds_consent_owner")
                    // Creates the application role before Flyway runs, so that V2's privilege
                    // revocation takes its real branch rather than the "role not present" one.
                    .withInitScript("db/testcontainer-init.sql")
                    // PostgreSQL's default is 100 and the suite runs many Spring contexts against
                    // one container, each holding its own pool. The pool is capped in
                    // application-integrationtest.yml, which is the half that matters; this is
                    // headroom, so that adding a suite fails on what the suite asserts rather than
                    // on "sorry, too many clients already" from whichever context happened to boot
                    // last — a failure that reports against a test with nothing wrong with it.
                    .withCommand("postgres", "-c", "max_connections=300");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // Migrations run as the owner in every environment. Pointed at the container explicitly
        // because the defaults in application.yml point at a developer's local database, and a
        // test that quietly migrated that instead would be a very unpleasant surprise.
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }
}
