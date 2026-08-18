package com.uds.consent.service.adapter;

import com.uds.consent.core.model.PurposeDefinition;
import com.uds.consent.ledger.store.PurposeRegistryStore;
import com.uds.consent.policy.port.PolicyPorts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Holds the purpose registry in memory.
 *
 * <p>The registry is small — hundreds of rows — and changes when a human publishes a version, not
 * with traffic. Reading it from the database on every decision would put a query on the hot path
 * to answer a question whose answer changes a few times a year.
 *
 * <p>Deliberately in-process rather than in Redis. A distributed cache would buy shared
 * invalidation across instances, at the cost of a network hop on the path this platform most
 * needs to be fast, and a new failure mode on it. The cost of the simpler design is bounded and
 * visible: after a publish, instances converge within the refresh interval. When the decision
 * path is split out and scaled independently, revisit this — not before.
 */
@Component
// The constructor reads the registry, so this bean must be built after Flyway has run. Without
// this the service starts fine against an already-migrated database and fails on a fresh one —
// the worst kind of ordering bug, because every environment that matters gets it wrong exactly
// once, on first deployment.
@DependsOnDatabaseInitialization
public class CachingPurposeCatalog implements PolicyPorts.PurposeCatalog {

    private static final Logger log = LoggerFactory.getLogger(CachingPurposeCatalog.class);

    private final PurposeRegistryStore store;

    private volatile Map<String, PurposeDefinition> byCode = Map.of();
    private volatile List<PurposeDefinition> all = List.of();

    public CachingPurposeCatalog(PurposeRegistryStore store) {
        this.store = store;
        refresh();
    }

    @Override
    public Optional<PurposeDefinition> find(String purposeCode) {
        return Optional.ofNullable(byCode.get(purposeCode));
    }

    @Override
    public List<PurposeDefinition> all() {
        return all;
    }

    /**
     * Reloads from the database.
     *
     * <p>Called at startup, on a timer, and explicitly whenever an administrator publishes a
     * purpose version — the explicit call is what makes a publish take effect immediately on the
     * instance that served it, rather than after the next tick.
     *
     * <p>On the same interval as {@link CachingApplicationRegistry}, deliberately. Two
     * independently-timed caches on the capture path would leave a window in which a purpose is
     * known to one and not the other, and the resulting rejection would be intermittent.
     */
    @Scheduled(fixedDelayString = "${uds.consent.registry-refresh-interval:PT5M}")
    public final void refresh() {
        List<PurposeDefinition> loaded = store.loadCurrentVersions();
        // Both fields are replaced with fully-built immutable values, so a concurrent reader sees
        // either the old registry or the new one — never a half-populated map.
        this.byCode = loaded.stream().collect(
                Collectors.toUnmodifiableMap(PurposeDefinition::code, Function.identity()));
        this.all = List.copyOf(loaded);
        log.info("purpose registry loaded: {} purposes", loaded.size());
    }
}
