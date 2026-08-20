package com.uds.consent.service.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

/**
 * Pushes the caller's entity claim into the database session.
 *
 * <p>This is what makes the row-level security policies in {@code V13__row_level_security.sql}
 * mean anything: without it the session variable is never set, every query reads as group level,
 * and the policies pass everything.
 *
 * <p><strong>Set on every checkout, without exception.</strong> That is the load-bearing decision
 * here and it is worth being explicit about why. The pool hands the same physical connection to
 * different requests, so a variable set once per session would be a Denave claim answering a
 * Matrix request within minutes of start-up — and that is strictly worse than no isolation,
 * because it would look like isolation and would fail intermittently, on traffic patterns nobody
 * can reproduce. Setting it on every checkout, including setting it back to empty for a
 * group-level caller, makes the connection's state a function of the current request alone.
 *
 * <p>The cost is one extra round trip per connection acquisition. Measured against the alternative
 * — a cross-entity disclosure that depends on pool scheduling — that is not a close call.
 *
 * <p>Background threads (the sweepers, the outbox relay) have no authentication and therefore no
 * claim, so they run as group level. Correct: a sweeper that could only see one entity would
 * silently stop expiring consent for every other one.
 */
public class EntityScopedDataSource extends DelegatingDataSource {

    private static final Logger log = LoggerFactory.getLogger(EntityScopedDataSource.class);

    private final Map<String, SecurityConfiguration.ApiClientProperties.Client> clients;
    private final SecurityConfiguration.ApiClientProperties.Jwt jwt;

    public EntityScopedDataSource(
            DataSource delegate,
            Map<String, SecurityConfiguration.ApiClientProperties.Client> clients,
            SecurityConfiguration.ApiClientProperties.Jwt jwt) {
        super(delegate);
        this.clients = clients;
        this.jwt = jwt;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return scope(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return scope(super.getConnection(username, password));
    }

    private Connection scope(Connection connection) throws SQLException {
        String claim;
        try {
            claim = EntityAccessGuard.currentEntityClaim(clients, jwt).orElse("");
        } catch (RuntimeException e) {
            // The connection is already checked out. currentEntityClaim can now throw — a token
            // naming two fiduciary entities is refused rather than resolved — and an unchecked
            // exception escaping here would leave the connection neither closed nor returned to
            // the pool. Rare, because the guard refuses such a token before any store runs; a
            // permanent leak on an error or async dispatch if it were not caught.
            //
            // Closed rather than served, for the same reason the SQLException branch below closes:
            // a connection whose scope cannot be determined is one that answers as group level.
            log.error("could not resolve the entity scope for a connection; closing it rather "
                    + "than serving an unscoped one: {}", e.toString());
            connection.close();
            throw e;
        }
        try (PreparedStatement statement =
                     connection.prepareStatement("select set_config('uds.entity_id', ?, false)")) {
            statement.setString(1, claim);
            statement.execute();
        } catch (SQLException e) {
            // Closed rather than handed back. A connection whose scope could not be set is one
            // that would answer the next query as group level, and returning it to the caller
            // would turn a database hiccup into a cross-entity disclosure.
            log.error("could not set the entity scope on a connection; closing it rather than "
                    + "serving an unscoped one: {}", e.toString());
            connection.close();
            throw e;
        }
        return connection;
    }
}
