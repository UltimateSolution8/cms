package com.uds.consent.ledger.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * The register of systems that must be told about a consent change.
 *
 * <p>The gap this closes is the one {@code /phase-gate} step 5 asks about and the platform could not
 * answer: <em>a principal withdrew — prove it reached every consuming system.</em> Until this table
 * existed, a downstream system nobody registered received nothing and left no trace of not having
 * received it. {@code webhook_delivery} proves arrival <strong>at subscribers that exist</strong>,
 * and a row there is structurally impossible for a system that was never subscribed.
 *
 * <p><strong>Keyed on {@code (entity_id, topic, system_code)} and deliberately not hung off a
 * subscription.</strong> A target is <em>who must hear</em>; a subscription is <em>how they are
 * reached</em>. A target that hangs off a subscription cannot express "DENCRM must be told and
 * nobody registered it", which is the entire finding. Putting {@code mandatory} on the subscription
 * instead has exactly the same defect.
 *
 * <p><strong>An empty register reports nothing, and that is deliberate</strong> — the same no-op as
 * {@link RightsFulfilmentStore}'s empty {@code fulfilment_target}, for the same reason. The platform
 * cannot know which of the group's systems hold a principal's data and will not invent them.
 * {@code REGULATORY_HANDOFF.md} §8.7 is the statement UDS signs.
 *
 * <p><strong>Targets do not inherit down the entity hierarchy.</strong> Stated here as well as in
 * the migration because {@code .claude/rules/consent-management.md} §3 is the section most often
 * misread, and Phase 16's C6 established the cost: a false claim that purposes inherit by recursive
 * CTE propagated into seven documents over three phases.
 */
@Repository
public class PropagationTargetStore {

    private final JdbcClient jdbc;

    public PropagationTargetStore(javax.sql.DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * The register for one entity, each row carrying the subscription that currently reaches it.
     *
     * <p>The resolved subscription is the point of this query rather than a convenience. The join
     * key is free text on both sides, so a target for {@code DENCRM} and a subscription labelled
     * {@code DenCRM-prod} do not meet — and unlike {@code fulfilment_target}, where a mismatch
     * fails loud and closed as a 409 naming the system, a mismatch here fails <em>quiet</em> and
     * writes a gap row every day forever. Returning the resolution puts that on the page the
     * operator is already looking at.
     */
    public List<Coverage> coverage(String entityId) {
        return jdbc.sql("""
                        select t.entity_id, t.topic, t.system_code, t.mandatory, t.active,
                               t.description,
                               (select s.subscription_id
                                  from webhook_subscription s
                                 where s.entity_id = t.entity_id
                                   and s.topic = t.topic
                                   and s.system_code = t.system_code
                                   and s.active
                                 order by s.subscription_id
                                 limit 1) as subscription_id
                          from propagation_target t
                         where t.entity_id = :entityId
                         order by t.topic, t.system_code
                        """)
                .param("entityId", entityId)
                .query((rs, n) -> new Coverage(rs.getString("entity_id"), rs.getString("topic"),
                        rs.getString("system_code"), rs.getBoolean("mandatory"),
                        rs.getBoolean("active"), rs.getString("description"),
                        rs.getString("subscription_id")))
                .list();
    }

    /**
     * Mandatory active targets for one entity and topic, with their resolved subscription.
     *
     * <p>What the reconciler reads, once per drained message. Bounded by the register — tens of
     * rows — which is why this runs in the relay rather than over the traffic-sized tables the
     * first design of this phase would have anti-joined.
     */
    public List<Coverage> mandatoryFor(String entityId, String topic) {
        return jdbc.sql("""
                        select t.entity_id, t.topic, t.system_code, t.mandatory, t.active,
                               t.description,
                               (select s.subscription_id
                                  from webhook_subscription s
                                 where s.entity_id = t.entity_id
                                   and s.topic = t.topic
                                   and s.system_code = t.system_code
                                   and s.active
                                 order by s.subscription_id
                                 limit 1) as subscription_id
                          from propagation_target t
                         where t.entity_id = :entityId and t.topic = :topic
                           and t.mandatory and t.active
                         order by t.system_code
                        """)
                .param("entityId", entityId)
                .param("topic", topic)
                .query((rs, n) -> new Coverage(rs.getString("entity_id"), rs.getString("topic"),
                        rs.getString("system_code"), rs.getBoolean("mandatory"),
                        rs.getBoolean("active"), rs.getString("description"),
                        rs.getString("subscription_id")))
                .list();
    }

    public void upsert(String entityId, String topic, String systemCode, boolean mandatory,
                       boolean active, String description) {
        jdbc.sql("""
                        insert into propagation_target (entity_id, topic, system_code, mandatory,
                                                        active, description)
                        values (:entityId, :topic, :systemCode, :mandatory, :active, :description)
                        on conflict (entity_id, topic, system_code) do update
                           set mandatory = excluded.mandatory,
                               active = excluded.active,
                               description = excluded.description
                        """)
                .param("entityId", entityId)
                .param("topic", topic)
                .param("systemCode", systemCode)
                .param("mandatory", mandatory)
                .param("active", active)
                .param("description", description)
                .update();
    }

    /** Every distinct topic named by any target. What the start-up check validates. */
    public List<String> distinctTopics() {
        return jdbc.sql("select distinct topic from propagation_target where active order by topic")
                .query(String.class)
                .list();
    }

    /**
     * A target and the subscription that currently reaches it, or null if none does.
     *
     * @param subscriptionId null means <strong>nobody is registered to be told</strong>, which is
     *                       the finding this whole table exists to make expressible — not a missing
     *                       join
     */
    public record Coverage(String entityId, String topic, String systemCode, boolean mandatory,
                           boolean active, String description, String subscriptionId) {

        /** Whether a mandatory obligation currently has no way of being met. */
        public boolean uncovered() {
            return mandatory && active && subscriptionId == null;
        }

        public Optional<String> subscription() {
            return Optional.ofNullable(subscriptionId);
        }
    }
}
