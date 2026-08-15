package com.uds.consent.ledger.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

/**
 * The surfaces registered to submit consent.
 *
 * <p>One row per application, environment and platform — {@code iSFA Connect / Production /
 * Android} is a different row from the iOS build and from staging. That granularity is the point:
 * a staging build writing into the production ledger is an ordinary accident, and without the
 * environment on the row nothing distinguishes it from real traffic.
 */
@Repository
public class ApplicationRegistryStore {

    private final JdbcClient jdbc;

    public ApplicationRegistryStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public Optional<Application> find(String applicationId) {
        return jdbc.sql(SELECT + " where application_id = :applicationId")
                .param("applicationId", applicationId)
                .query(ApplicationRegistryStore::map)
                .optional();
    }

    public List<Application> findAll() {
        return jdbc.sql(SELECT + " order by entity_id, name, environment")
                .query(ApplicationRegistryStore::map)
                .list();
    }

    public List<Application> findForEntity(String entityId) {
        return jdbc.sql(SELECT + " where entity_id = :entityId order by name, environment")
                .param("entityId", entityId)
                .query(ApplicationRegistryStore::map)
                .list();
    }

    public void upsert(Application application) {
        jdbc.sql("""
                        insert into application_registry (application_id, entity_id, name, platform,
                                                          environment, description, active)
                        values (:applicationId, :entityId, :name, :platform, :environment,
                                :description, :active)
                        on conflict (application_id) do update
                            set entity_id = excluded.entity_id,
                                name = excluded.name,
                                platform = excluded.platform,
                                environment = excluded.environment,
                                description = excluded.description,
                                active = excluded.active
                        """)
                .param("applicationId", application.applicationId())
                .param("entityId", application.entityId())
                .param("name", application.name())
                .param("platform", application.platform())
                .param("environment", application.environment())
                .param("description", application.description())
                .param("active", application.active())
                .update();
    }

    private static final String SELECT = """
            select application_id, entity_id, name, platform, environment, description, active
              from application_registry
            """;

    private static Application map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Application(rs.getString("application_id"), rs.getString("entity_id"),
                rs.getString("name"), rs.getString("platform"), rs.getString("environment"),
                rs.getString("description"), rs.getBoolean("active"));
    }

    public record Application(String applicationId, String entityId, String name, String platform,
                              String environment, String description, boolean active) {
    }
}
