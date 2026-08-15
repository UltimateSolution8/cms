package com.uds.consent.ledger.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;

/**
 * A real PostgreSQL, and nothing else.
 *
 * <p>No Spring context here, unlike the service module's integration base. These tests exercise
 * SQL — {@code jsonb} handling, partial indexes, {@code distinct on}, the append-only triggers —
 * and every one of those properties belongs to the database rather than to the framework in front
 * of it. Booting an application context to reach them would add thirty seconds and prove nothing
 * extra.
 *
 * <p>The container is started once for the module and reused. Migrations run as the owner; the
 * application role is created first so V2's revocation branch actually executes, exactly as it
 * does in the service suite and in the compose file.
 */
@Tag("integration")
public abstract class StoreTestBase {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("uds_consent")
                    .withUsername("uds_consent_owner")
                    .withPassword("uds_consent_owner");

    private static final DataSource DATA_SOURCE;

    static {
        POSTGRES.start();

        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        DATA_SOURCE = dataSource;

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    protected static DataSource dataSource() {
        return DATA_SOURCE;
    }

    /** The same mapper configuration the service uses for the jsonb columns. */
    protected static ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
