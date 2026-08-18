package com.uds.consent.ledger.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Headers and templates registered on the DLT platform.
 *
 * <p>What this makes possible is the join a TRAI investigation actually asks about: given an
 * outbound message, show the registered template it went under, the live consent behind it, and
 * the preference check that preceded it. Before the registry the decision API returned the
 * obligations "use-dlt-registered-header" and "use-dlt-registered-template" — both of which tell a
 * sender something it already knew, and neither of which says which.
 */
@Repository
public class DltRegistryStore {

    private final JdbcClient jdbc;

    public DltRegistryStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * The active registration for an entity's purpose.
     *
     * <p>Empty means the purpose has no registered template, which is a finding rather than a
     * fallback: an A2P message with no registered template is refused by the operator, so
     * discovering it at decision time is strictly better than discovering it mid-campaign.
     */
    public Optional<Registration> find(String entityId, String purposeCode) {
        return jdbc.sql("""
                        select t.template_id, t.template_ref, t.purpose_code, t.entity_id,
                               h.header_id, h.header, h.category, h.series
                          from dlt_template t
                          join dlt_header h on h.header_id = t.header_id
                         where t.entity_id = :entityId and t.purpose_code = :purposeCode
                           and t.active = true and h.active = true
                         order by t.template_id
                         limit 1
                        """)
                .param("entityId", entityId)
                .param("purposeCode", purposeCode)
                .query(DltRegistryStore::mapRegistration)
                .optional();
    }

    public List<Registration> findForEntity(String entityId) {
        return jdbc.sql("""
                        select t.template_id, t.template_ref, t.purpose_code, t.entity_id,
                               h.header_id, h.header, h.category, h.series
                          from dlt_template t
                          join dlt_header h on h.header_id = t.header_id
                         where t.entity_id = :entityId
                         order by t.purpose_code, t.template_id
                        """)
                .param("entityId", entityId)
                .query(DltRegistryStore::mapRegistration)
                .list();
    }

    public void upsertHeader(Header header) {
        jdbc.sql("""
                        insert into dlt_header (header_id, entity_id, header, category, series,
                                                registered_at, active)
                        values (:headerId, :entityId, :header, :category, :series, :registeredAt,
                                :active)
                        on conflict (header_id) do update
                            set header = excluded.header, category = excluded.category,
                                series = excluded.series, registered_at = excluded.registered_at,
                                active = excluded.active
                        """)
                .param("headerId", header.headerId())
                .param("entityId", header.entityId())
                .param("header", header.header())
                .param("category", header.category())
                .param("series", header.series())
                .param("registeredAt", header.registeredAt())
                .param("active", header.active())
                .update();
    }

    public void upsertTemplate(String templateId, String entityId, String headerId,
                               String purposeCode, String templateRef, String description,
                               LocalDate registeredAt, boolean active) {
        jdbc.sql("""
                        insert into dlt_template (template_id, entity_id, header_id, purpose_code,
                                                  template_ref, description, registered_at, active)
                        values (:templateId, :entityId, :headerId, :purposeCode, :templateRef,
                                :description, :registeredAt, :active)
                        on conflict (template_id) do update
                            set header_id = excluded.header_id,
                                purpose_code = excluded.purpose_code,
                                template_ref = excluded.template_ref,
                                description = excluded.description,
                                registered_at = excluded.registered_at,
                                active = excluded.active
                        """)
                .param("templateId", templateId)
                .param("entityId", entityId)
                .param("headerId", headerId)
                .param("purposeCode", purposeCode)
                .param("templateRef", templateRef)
                .param("description", description)
                .param("registeredAt", registeredAt)
                .param("active", active)
                .update();
    }

    public List<Header> headers(String entityId) {
        return jdbc.sql("""
                        select header_id, entity_id, header, category, series, registered_at, active
                          from dlt_header where entity_id = :entityId order by category, header
                        """)
                .param("entityId", entityId)
                .query((rs, n) -> new Header(rs.getString("header_id"), rs.getString("entity_id"),
                        rs.getString("header"), rs.getString("category"), rs.getString("series"),
                        rs.getDate("registered_at") == null ? null
                                : rs.getDate("registered_at").toLocalDate(),
                        rs.getBoolean("active")))
                .list();
    }

    private static Registration mapRegistration(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new Registration(rs.getString("entity_id"), rs.getString("purpose_code"),
                rs.getString("header_id"), rs.getString("header"), rs.getString("category"),
                rs.getString("series"), rs.getString("template_id"), rs.getString("template_ref"));
    }

    /**
     * @param category  P, S, T or G. A promotional message under a service header is the mis-send
     *                  TRAI acts on, so the category travels with the answer rather than being
     *                  something the sender has to look up separately
     * @param series    140 for promotional, 1600 for transactional; null where it does not apply
     */
    public record Header(String headerId, String entityId, String header, String category,
                         String series, LocalDate registeredAt, boolean active) {
    }

    /**
     * @param templateRef the id the DLT platform issued, which is the value that has to appear on
     *                    the wire. {@code PENDING_REGISTRATION} means no real template exists yet
     *                    and any send will be rejected by the operator
     */
    public record Registration(String entityId, String purposeCode, String headerId, String header,
                               String category, String series, String templateId,
                               String templateRef) {

        /** Whether this registration can actually carry traffic. */
        public boolean usable() {
            return templateRef != null && !"PENDING_REGISTRATION".equals(templateRef);
        }
    }
}
