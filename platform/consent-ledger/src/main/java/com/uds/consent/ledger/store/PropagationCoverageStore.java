package com.uds.consent.ledger.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Current state: which propagation obligations cannot presently be met.
 *
 * <p><strong>This has no table, and that is the design.</strong> The first draft of this phase
 * answered "is this gap still open?" by anti-joining {@code webhook_delivery} for a given
 * {@code outbox_id} — a question that can never become true again. {@code fetchUnpublished} selects
 * {@code where published_at is null} and the relay sets it on the same pass, so a published message
 * is never re-published and no later delivery row can exist for it. Register DENCRM at 09:00,
 * register its subscription at 09:05, and the 300 events in between would be open <em>forever</em>:
 * the gauge never returns to zero, the critical alert fires for the life of the database, and it is
 * muted inside a week.
 *
 * <p>So current state is derived from the register instead — a mandatory active
 * {@code propagation_target} with no active {@code webhook_subscription} carrying that
 * {@code system_code}. Bounded by the number of registered targets rather than by traffic, cheap
 * enough to read on every scrape and every health probe, and <strong>it returns to zero the moment
 * an operator fixes the configuration</strong>. That reachability is the whole point of the split.
 *
 * <p>History is the separate concern of {@link PropagationGapStore}, which is append-only and which
 * <strong>nothing alerts on</strong> — alerting on a count that can only grow is how the unreachable
 * zero comes back through the window.
 *
 * <p>The anti-join is the same shape as
 * {@link RightsFulfilmentStore#outstandingTargets(String, String, String)}, deliberately: that one
 * is already argued, and a second shape would be a second thing to reason about.
 */
@Repository
public class PropagationCoverageStore {

    private final JdbcClient jdbc;

    public PropagationCoverageStore(javax.sql.DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** Mandatory active targets for one entity that no active subscription reaches. */
    public List<Uncovered> uncovered(String entityId) {
        return jdbc.sql(UNCOVERED + " and t.entity_id = :entityId order by t.topic, t.system_code")
                .param("entityId", entityId)
                .query((rs, n) -> new Uncovered(rs.getString("entity_id"), rs.getString("topic"),
                        rs.getString("system_code")))
                .list();
    }

    /**
     * How many mandatory obligations are currently unreachable, across the group.
     *
     * <p>What {@code uds.consent.propagation.uncovered} publishes and what the critical alert reads.
     * Untagged and group-wide: a gauge tagged by entity would grow cardinality with every
     * acquisition, and the question the alert asks — "is any system that must be told unreachable?"
     * — does not need the breakdown. The breakdown is a route.
     *
     * <p>Read group-level, so it is not filtered by an entity claim. That is correct here and would
     * not be on a request path: this runs from the metrics registry and the health indicator, which
     * have no caller to be scoped to.
     */
    public long uncoveredCount() {
        Long count = jdbc.sql("select count(*) from (" + UNCOVERED + ") u")
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }

    private static final String UNCOVERED = """
            select t.entity_id, t.topic, t.system_code
              from propagation_target t
             where t.mandatory and t.active
               and not exists (
                   select 1 from webhook_subscription s
                    where s.entity_id = t.entity_id
                      and s.topic = t.topic
                      and s.system_code = t.system_code
                      and s.active)
            """;

    /** A system that must be told about a consent change and that nothing can currently reach. */
    public record Uncovered(String entityId, String topic, String systemCode) {
    }

    /**
     * Entities with at least one uncovered mandatory target.
     *
     * <p>Bounded by the number of fiduciaries — fifteen — which is why it can be a health detail
     * where a per-entity metric label could not. The uncovered gauge is deliberately group-wide to
     * keep cardinality off a series read on every scrape, and the consequence was that the critical
     * alert named no entity and the responder's first step was fifteen calls. This is the answer
     * that does not cost cardinality.
     */
    public List<String> uncoveredEntities() {
        return jdbc.sql("""
                        select distinct t.entity_id
                          from propagation_target t
                         where t.mandatory and t.active
                           and not exists (
                               select 1 from webhook_subscription s
                                where s.entity_id = t.entity_id and s.topic = t.topic
                                  and s.system_code = t.system_code and s.active)
                         order by t.entity_id
                        """)
                .query(String.class)
                .list();
    }

    /**
     * Mandatory active targets whose delivery the configured publisher can never evidence.
     *
     * <p>Zero unless something is registered. {@code webhook_delivery} is written by the webhook
     * publisher and by nothing else, so under the shipped {@code log} default the register can be
     * fully "covered" while the platform has no way to observe that anyone received anything — a
     * set of instruments indistinguishable from full coverage. The publisher is not this store's
     * business, so the caller supplies whether the channel can evidence delivery.
     */
    public long unobservableTargets(boolean channelEvidencesDelivery) {
        if (channelEvidencesDelivery) {
            return 0;
        }
        return jdbc.sql("select count(*) from propagation_target where mandatory and active")
                .query(Long.class)
                .single();
    }
}
