package com.uds.consent.service.config;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Wraps the pooled {@code DataSource} so every connection carries the caller's entity claim.
 *
 * <p>A separate configuration class rather than a {@code BeanPostProcessor}, because the wrapping
 * has to be visible. A post-processor that silently replaced the application's data source would
 * be the kind of thing that takes a day to find when somebody wonders why their query returns
 * nothing — and "returns nothing" is exactly the symptom row-level security produces.
 *
 * <p>Flyway is unaffected: it has its own data source, configured with the owning role, and
 * policies do not apply to a table's owner. That is deliberate — see
 * {@code V13__row_level_security.sql} for why the migration deliberately does not FORCE them.
 */
@Configuration
@EnableConfigurationProperties(DataSourceProperties.class)
public class DataSourceScopingConfiguration {

    /**
     * The data source every store reads through.
     *
     * <p>Marked primary so it replaces the auto-configured one everywhere at once. Wrapping some
     * callers and not others would produce a platform where isolation depended on which bean a
     * store happened to be given, which is the worst of both designs.
     */
    @Bean
    @Primary
    public DataSource entityScopedDataSource(
            DataSourceProperties properties,
            SecurityConfiguration.ApiClientProperties clients) {
        return new EntityScopedDataSource(
                properties.initializeDataSourceBuilder().build(), clients.getClients(),
                clients.getJwt());
    }
}
