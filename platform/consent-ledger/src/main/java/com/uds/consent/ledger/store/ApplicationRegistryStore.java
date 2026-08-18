package com.uds.consent.ledger.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /**
     * Entities each application may act for, keyed by application id.
     *
     * <p>Loaded whole rather than per application. The registry is a few dozen rows and the cache
     * in front of it wants the entire picture at once; a per-application query would turn one
     * refresh into one round trip per surface to answer a question about a table that fits on a
     * screen.
     */
    public Map<String, Set<String>> entityScopes() {
        Map<String, Set<String>> scopes = new HashMap<>();
        jdbc.sql("select application_id, entity_id from application_entity_scope")
                .query((rs, n) -> {
                    scopes.computeIfAbsent(rs.getString("application_id"), k -> new HashSet<>())
                            .add(rs.getString("entity_id"));
                    return null;
                })
                .list();
        return scopes;
    }

    /**
     * Grants a surface reach over an entity.
     *
     * <p>Idempotent, so re-registering a shared system does not fail on a grant it already had.
     * The rationale is recorded because "which surfaces could see Denave's data in August" is a
     * question asked after an incident, and a bare join table cannot answer it.
     */
    public void grantEntityScope(String applicationId, String entityId, String rationale) {
        jdbc.sql("""
                        insert into application_entity_scope (application_id, entity_id, rationale)
                        values (:applicationId, :entityId, :rationale)
                        on conflict (application_id, entity_id) do nothing
                        """)
                .param("applicationId", applicationId)
                .param("entityId", entityId)
                .param("rationale", rationale)
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
