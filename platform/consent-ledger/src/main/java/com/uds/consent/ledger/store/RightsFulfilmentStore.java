package com.uds.consent.ledger.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;

/**
 * Which systems must act on a rights request, and what each of them did.
 *
 * <p>Exists because {@code FULFILLED} meant nothing. {@code RightsService.transition} let an
 * operator close a request with a sentence of resolution text, and nothing in the platform touches
 * DenCRM, the HRMS or the BGV workflow — so a closure by somebody who had done the work and a
 * closure by somebody who had not were indistinguishable on the record, permanently, because the
 * audit trail is append-only.
 *
 * <p>Two tables, doing two different jobs. {@code fulfilment_target} is the configured set of
 * systems that have to act, so the list is a fact rather than whatever the operator remembered.
 * {@code rights_fulfilment_action} is what each of them did, with a reference a reviewer can follow
 * into a system other than this one.
 *
 * <p><strong>This is not fulfilment.</strong> No connector here erases or exports anything, and
 * writing one against a system nobody on this side can call would be worse than none, because it
 * would look like the real thing. What it buys is that a manual process becomes evidenced and its
 * gaps become enumerable — which is the difference between a defensible SOP and the exposure.
 */
@Repository
public class RightsFulfilmentStore {

    private final JdbcClient jdbc;

    public RightsFulfilmentStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** The systems configured as mandatory for this entity and request type. */
    public List<String> mandatoryTargets(String entityId, String requestType) {
        return jdbc.sql("""
                        select system_code from fulfilment_target
                         where entity_id = :entityId and request_type = :requestType
                           and mandatory and active
                         order by system_code
                        """)
                .param("entityId", entityId)
                .param("requestType", requestType)
                .query(String.class)
                .list();
    }

    /** Every configured target, mandatory or not, for the console. */
    public List<Target> targets(String entityId) {
        return jdbc.sql("""
                        select entity_id, request_type, system_code, mandatory, active, description
                          from fulfilment_target
                         where entity_id = :entityId
                         order by request_type, system_code
                        """)
                .param("entityId", entityId)
                .query((rs, n) -> new Target(rs.getString("entity_id"), rs.getString("request_type"),
                        rs.getString("system_code"), rs.getBoolean("mandatory"),
                        rs.getBoolean("active"), rs.getString("description")))
                .list();
    }

    /** Adds or updates a target. Configuration, so an upsert rather than an append. */
    public void upsertTarget(String entityId, String requestType, String systemCode,
                             boolean mandatory, boolean active, String description) {
        jdbc.sql("""
                        insert into fulfilment_target (entity_id, request_type, system_code,
                                                       mandatory, active, description)
                        values (:entityId, :requestType, :systemCode, :mandatory, :active, :description)
                        on conflict (entity_id, request_type, system_code) do update
                           set mandatory = excluded.mandatory,
                               active = excluded.active,
                               description = excluded.description
                        """)
                .param("entityId", entityId)
                .param("requestType", requestType)
                .param("systemCode", systemCode)
                .param("mandatory", mandatory)
                .param("active", active)
                .param("description", description)
                .update();
    }

    /** Records what one system did. Append-only; there is no correction, only a further action. */
    public long recordAction(String requestId, String entityId, String systemCode,
                             String actionType, String status, String performedBy,
                             String evidenceRef, String detail, Instant at) {
        return jdbc.sql("""
                        insert into rights_fulfilment_action
                            (request_id, entity_id, system_code, action_type, status,
                             performed_by, performed_at, evidence_ref, detail)
                        values (:requestId, :entityId, :systemCode, :actionType, :status,
                                :performedBy, :at, :evidenceRef, :detail)
                        returning action_id
                        """)
                .param("requestId", requestId)
                .param("entityId", entityId)
                .param("systemCode", systemCode)
                .param("actionType", actionType)
                .param("status", status)
                .param("performedBy", performedBy)
                .param("at", java.sql.Timestamp.from(at))
                .param("evidenceRef", evidenceRef)
                .param("detail", detail)
                .query(Long.class)
                .single();
    }

    public List<Action> actions(String requestId) {
        return jdbc.sql("""
                        select action_id, request_id, system_code, action_type, status,
                               performed_by, performed_at, evidence_ref, detail
                          from rights_fulfilment_action
                         where request_id = :requestId
                         order by performed_at, action_id
                        """)
                .param("requestId", requestId)
                .query((rs, n) -> new Action(rs.getLong("action_id"), rs.getString("request_id"),
                        rs.getString("system_code"), rs.getString("action_type"),
                        rs.getString("status"), rs.getString("performed_by"),
                        rs.getTimestamp("performed_at").toInstant(),
                        rs.getString("evidence_ref"), rs.getString("detail")))
                .list();
    }

    /**
     * Mandatory systems that have not completed for this request.
     *
     * <p>Only {@code COMPLETED} counts. A {@code FAILED} attempt is worth recording precisely
     * because it must not satisfy the gate — a request closed on the strength of a failed erasure
     * is the exact outcome this table exists to make impossible.
     */
    public List<String> outstandingTargets(String requestId, String entityId, String requestType) {
        return jdbc.sql("""
                        select t.system_code
                          from fulfilment_target t
                         where t.entity_id = :entityId and t.request_type = :requestType
                           and t.mandatory and t.active
                           and not exists (
                               select 1 from rights_fulfilment_action a
                                where a.request_id = :requestId
                                  and a.system_code = t.system_code
                                  and a.status = 'COMPLETED')
                         order by t.system_code
                        """)
                .param("requestId", requestId)
                .param("entityId", entityId)
                .param("requestType", requestType)
                .query(String.class)
                .list();
    }

    public record Target(String entityId, String requestType, String systemCode, boolean mandatory,
                         boolean active, String description) {
    }

    public record Action(long actionId, String requestId, String systemCode, String actionType,
                         String status, String performedBy, Instant performedAt,
                         String evidenceRef, String detail) {
    }
}
