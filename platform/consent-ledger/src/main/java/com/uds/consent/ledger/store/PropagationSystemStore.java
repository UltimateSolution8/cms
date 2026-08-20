package com.uds.consent.ledger.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * The system codes an entity recognises for propagation.
 *
 * <p><strong>Why this exists.</strong> {@code propagation_target.system_code} and
 * {@code webhook_subscription.system_code} were joined as free text. A target for {@code DENCRM}
 * against a subscription an operator named {@code DENCRM_PROD} never joins, so the reconciler
 * reports a mandatory obligation as uncovered — every day, permanently, in an
 * <strong>append-only</strong> table — for a system that is in fact reachable and receiving
 * everything. Those rows are then evidence of a failure that never happened, in the artefact the
 * platform would hand a regulator.
 *
 * <p>{@code fulfilment_target} gets the same class of error right by failing loud and closed: a 409
 * naming the system, at the moment somebody tries to close a request. Propagation failed quiet, and
 * wrote. Both sides now reference this table by foreign key, so the database refuses the typo.
 *
 * <p><strong>A code is never deleted.</strong> A {@code propagation_gap} row naming a
 * decommissioned system has to stay readable — "this system was not told, on these days" is the
 * whole point of that table — so retirement is {@code active = false} and {@code DELETE} is not
 * granted to the application role at all.
 */
@Repository
public class PropagationSystemStore {

    private final JdbcClient jdbc;

    public PropagationSystemStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every code this entity recognises, retired ones included. */
    public List<System> forEntity(String entityId) {
        return jdbc.sql("""
                        select entity_id, system_code, description, active
                          from propagation_system
                         where entity_id = :entityId
                         order by system_code
                        """)
                .param("entityId", entityId)
                .query((rs, n) -> new System(rs.getString("entity_id"), rs.getString("system_code"),
                        rs.getString("description"), rs.getBoolean("active")))
                .list();
    }

    /** Whether this entity recognises the code at all. Retired codes still count as known. */
    public boolean isKnown(String entityId, String systemCode) {
        return jdbc.sql("""
                        select count(*) from propagation_system
                         where entity_id = :entityId and system_code = :systemCode
                        """)
                .param("entityId", entityId)
                .param("systemCode", systemCode)
                .query(Integer.class)
                .single() > 0;
    }

    /** Declares or updates a code. Upper-cased by the caller; the database checks it either way. */
    public void upsert(String entityId, String systemCode, String description, boolean active) {
        jdbc.sql("""
                        insert into propagation_system (entity_id, system_code, description, active)
                        values (:entityId, :systemCode, :description, :active)
                        on conflict (entity_id, system_code) do update
                           set description = excluded.description, active = excluded.active
                        """)
                .param("entityId", entityId)
                .param("systemCode", systemCode)
                .param("description", description)
                .param("active", active)
                .update();
    }

    /** @param active a retired code stays in the vocabulary so the gap rows naming it stay readable */
    public record System(String entityId, String systemCode, String description, boolean active) { }
}
