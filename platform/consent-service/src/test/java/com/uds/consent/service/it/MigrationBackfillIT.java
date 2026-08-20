package com.uds.consent.service.it;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code V33}'s backfill, run against rows that existed before it.
 *
 * <p>The migration's two {@code insert … select … on conflict do nothing} statements are the only
 * thing in Phase 19 whose failure breaks a production deployment, and they had never matched a
 * single row. No migration seeds {@code propagation_target} or {@code webhook_subscription}, so
 * every test run applied them against empty tables; and Phase 19 then changed the fixtures to
 * declare their system codes <em>first</em>, which exercises the foreign key and not the backfill.
 * The statement that matters ran hundreds of times and did nothing, every time, and everything was
 * green.
 *
 * <p><strong>Its own container, and there is no way around that.</strong> The shared
 * {@link PostgresIntegrationTest#POSTGRES} is migrated to head before the first suite runs, so a
 * pre-{@code V33} state cannot be observed on it. This one migrates to {@code 32}, seeds what a
 * real deployment would already hold — including the {@code DENCRM} / {@code DENCRM_PROD} mismatch
 * that motivated the vocabulary in the first place — and then applies {@code 33}.
 *
 * <p>Plain JDBC and no Spring context, so it costs one connection rather than a pool.
 */
@Tag("integration")
class MigrationBackfillIT {

    private static final String ENTITY = "DENAVE_IN";
    private static final String TOPIC = "uds.consent.events";

    private static PostgreSQLContainer<?> postgres;
    private static SingleConnectionDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateToThePreviousVersion() {
        postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("uds_consent")
                .withUsername("uds_consent_owner")
                .withPassword("uds_consent_owner")
                // V2's revocation branch needs the application role to exist, exactly as the
                // shared container does — otherwise the migrations under test take a path
                // production never takes.
                .withInitScript("db/testcontainer-init.sql");
        postgres.start();

        dataSource = new SingleConnectionDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), true);
        dataSource.setDriverClassName(org.postgresql.Driver.class.getName());
        jdbc = new JdbcTemplate(dataSource);

        flyway("32").migrate();
    }

    @AfterAll
    static void stop() {
        if (dataSource != null) {
            dataSource.destroy();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    @DisplayName("V33 backfills the vocabulary from rows that already existed, and every pair still joins")
    void theBackfillCoversWhatWasAlreadyThere() {
        // Pre-V33 state: the column exists (V31 added it) and there is no vocabulary to check
        // against, which is exactly the condition an upgrade meets.
        assertThat(tableExists("propagation_system"))
                .withFailMessage("propagation_system already exists at V32, so this test is not "
                        + "observing a pre-V33 state and proves nothing about the backfill")
                .isFalse();

        jdbc.update("insert into propagation_target (entity_id, topic, system_code, mandatory, "
                + "active, description) values (?, ?, 'DENCRM', true, true, 'pre-existing')",
                ENTITY, TOPIC);
        jdbc.update("insert into propagation_target (entity_id, topic, system_code, mandatory, "
                + "active, description) values (?, ?, 'ATHENA_DIALER', true, true, 'pre-existing')",
                ENTITY, TOPIC);

        // The mismatch the vocabulary exists to make visible: a target for DENCRM against a
        // subscription somebody named DENCRM_PROD. Both codes must survive the backfill — the
        // migration records what is in use, it does not correct it, because silently
        // reinterpreting an operator's label is how a phantom gap becomes an invisible one.
        insertSubscription("sub-dencrm", "DENCRM_PROD");
        insertSubscription("sub-athena", "ATHENA_DIALER");

        List<String> before = jdbc.queryForList(
                "select system_code from propagation_target where entity_id = ? order by 1",
                String.class, ENTITY);

        flyway("33").migrate();

        assertThat(jdbc.queryForList(
                "select system_code from propagation_system where entity_id = ? order by 1",
                String.class, ENTITY))
                .containsExactly("ATHENA_DIALER", "DENCRM", "DENCRM_PROD");

        // The assertion the acceptance criterion actually asks for: nothing that already joined
        // stopped joining. A migration that added the constraint before the backfill would fail
        // outright; one that backfilled only from a single side would leave the other orphaned.
        assertThat(jdbc.queryForList(
                "select system_code from propagation_target where entity_id = ? order by 1",
                String.class, ENTITY))
                .isEqualTo(before);
        assertThat(jdbc.queryForObject(
                "select count(*) from webhook_subscription s "
                        + "join propagation_system p on p.entity_id = s.entity_id "
                        + "and p.system_code = s.system_code where s.entity_id = ?",
                Integer.class, ENTITY))
                .isEqualTo(2);

        // And both foreign keys are in place, so the refusal is the database's from here on.
        assertThat(jdbc.queryForList(
                "select constraint_name from information_schema.table_constraints "
                        + "where constraint_type = 'FOREIGN KEY' "
                        + "and constraint_name like 'fk_%_system' order by 1",
                String.class))
                .contains("fk_propagation_target_system", "fk_webhook_subscription_system");
    }

    private void insertSubscription(String id, String systemCode) {
        jdbc.update("insert into webhook_subscription (subscription_id, entity_id, topic, url, "
                        + "secret, active, system_code) "
                        + "values (?, ?, ?, ?, 'x', true, ?)",
                id, ENTITY, TOPIC, "http://127.0.0.1:1/" + id, systemCode);
    }

    private boolean tableExists(String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from information_schema.tables "
                        + "where table_schema = 'public' and table_name = ?)",
                Boolean.class, table));
    }

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
