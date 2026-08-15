package com.uds.consent.service.adapter;

import com.uds.consent.ledger.store.ApplicationRegistryStore;
import com.uds.consent.policy.port.PolicyPorts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Holds the application registry in memory, alongside the purpose registry.
 *
 * <p>Same reasoning as {@link CachingPurposeCatalog}: a few dozen rows that change when a human
 * registers a surface, read on every capture. What matters more is that the two caches refresh on
 * the same schedule and are refreshed by the same admin call. Two independently-timed caches on
 * the capture path would mean a window in which a newly registered application is known to one and
 * not the other, and the resulting rejection would be intermittent — the hardest kind of bug to
 * get anyone to believe.
 */
@Component
// The constructor reads the registry, so the bean must be built after Flyway has run. Without
// this the service starts against an already-migrated database and fails on a fresh one.
@DependsOnDatabaseInitialization
public class CachingApplicationRegistry implements PolicyPorts.ApplicationRegistry {

    private static final Logger log = LoggerFactory.getLogger(CachingApplicationRegistry.class);

    private final ApplicationRegistryStore store;

    private volatile Map<String, PolicyPorts.RegisteredApplication> byId = Map.of();

    public CachingApplicationRegistry(ApplicationRegistryStore store) {
        this.store = store;
        refresh();
    }

    @Override
    public Optional<PolicyPorts.RegisteredApplication> find(String applicationId) {
        return Optional.ofNullable(byId.get(applicationId));
    }

    /** How many surfaces are currently cached. Reported back by the admin refresh. */
    public int size() {
        return byId.size();
    }

    /**
     * Reloads from the database.
     *
     * <p>On a timer as well as on demand. A surface registered on one instance would otherwise be
     * rejected by the others until a restart — and a capture rejected for a reason that resolves
     * itself later is the kind of failure an integrator routes around rather than reports.
     */
    @Scheduled(fixedDelayString = "${uds.consent.registry-refresh-interval:PT5M}")
    public final void refresh() {
        Map<String, PolicyPorts.RegisteredApplication> loaded = store.findAll().stream()
                .map(application -> new PolicyPorts.RegisteredApplication(
                        application.applicationId(), application.entityId(), application.name(),
                        application.platform(), application.environment(), application.active()))
                .collect(Collectors.toUnmodifiableMap(
                        PolicyPorts.RegisteredApplication::applicationId, Function.identity()));

        this.byId = loaded;
        log.info("application registry loaded: {} application(s), {} active", loaded.size(),
                loaded.values().stream().filter(PolicyPorts.RegisteredApplication::active).count());
    }
}
