package com.uds.consent.ledger.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Processors and other third parties that receive personal data.
 *
 * <p>The registry answers two questions an auditor asks in the first ten minutes: who else has
 * this data, and is there a signed data processing agreement with each of them. A group that can
 * only answer the first has a finding.
 *
 * <p>It also carries weight the DPDP Act does not: Malaysia's PDPA (Amendment) 2024 extends direct
 * statutory liability to processors, so for the Malaysian flows a vendor row is not an
 * administrative record of a supplier — it names a party with obligations of its own.
 */
@Repository
public class VendorStore {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public VendorStore(DataSource dataSource, ObjectMapper json) {
        this.jdbc = JdbcClient.create(dataSource);
        this.json = json;
    }

    public void upsert(Vendor vendor) {
        jdbc.sql("""
                        insert into vendor (vendor_id, entity_id, name, role, countries,
                                            dpa_reference, dpa_signed_at, active)
                        values (:vendorId, :entityId, :name, :role, cast(:countries as jsonb),
                                :dpaReference, :dpaSignedAt, :active)
                        on conflict (vendor_id) do update
                            set entity_id = excluded.entity_id,
                                name = excluded.name,
                                role = excluded.role,
                                countries = excluded.countries,
                                dpa_reference = excluded.dpa_reference,
                                dpa_signed_at = excluded.dpa_signed_at,
                                active = excluded.active
                        """)
                .param("vendorId", vendor.vendorId())
                .param("entityId", vendor.entityId())
                .param("name", vendor.name())
                .param("role", vendor.role())
                .param("countries", writeJson(vendor.countries()))
                .param("dpaReference", vendor.dpaReference())
                .param("dpaSignedAt", vendor.dpaSignedAt() == null ? null
                        : Date.valueOf(vendor.dpaSignedAt()))
                .param("active", vendor.active())
                .update();
    }

    public Optional<Vendor> find(String vendorId) {
        return jdbc.sql(SELECT + " where vendor_id = :vendorId")
                .param("vendorId", vendorId)
                .query(this::map)
                .optional();
    }

    public List<Vendor> findForEntity(String entityId, boolean activeOnly) {
        return jdbc.sql(SELECT + " where entity_id = :entityId"
                        + (activeOnly ? " and active = true" : "") + " order by name")
                .param("entityId", entityId)
                .query(this::map)
                .list();
    }

    /**
     * Active vendors with no data processing agreement on record.
     *
     * <p>The report worth running before an audit rather than during one. A vendor receiving
     * personal data without a DPA is a finding on its own, and it is the kind that accumulates
     * quietly — nobody adds a supplier intending to skip the paperwork.
     */
    public List<Vendor> findWithoutDpa(String entityId) {
        return jdbc.sql(SELECT + " where entity_id = :entityId and active = true "
                        + "and (dpa_reference is null or dpa_reference = '' "
                        + "     or dpa_signed_at is null) order by name")
                .param("entityId", entityId)
                .query(this::map)
                .list();
    }

    public void setPurposes(String vendorId, List<String> purposeCodes) {
        jdbc.sql("delete from vendor_purpose where vendor_id = :vendorId")
                .param("vendorId", vendorId)
                .update();
        for (String purposeCode : purposeCodes) {
            jdbc.sql("""
                            insert into vendor_purpose (vendor_id, purpose_code)
                            values (:vendorId, :purposeCode)
                            on conflict do nothing
                            """)
                    .param("vendorId", vendorId)
                    .param("purposeCode", purposeCode)
                    .update();
        }
    }

    public List<String> purposesFor(String vendorId) {
        return jdbc.sql("select purpose_code from vendor_purpose where vendor_id = :vendorId "
                        + "order by purpose_code")
                .param("vendorId", vendorId)
                .query(String.class)
                .list();
    }

    /**
     * Whether a vendor is authorised for a purpose.
     *
     * <p>Read on the decision path when a request names a vendor. A vendor authorised for
     * telemarketing is not thereby authorised for profiling, and the registry is what makes that
     * distinction enforceable rather than contractual.
     */
    public boolean isAuthorisedFor(String vendorId, String purposeCode) {
        return jdbc.sql("""
                        select count(*) from vendor v
                          join vendor_purpose vp on vp.vendor_id = v.vendor_id
                         where v.vendor_id = :vendorId and vp.purpose_code = :purposeCode
                           and v.active = true
                        """)
                .param("vendorId", vendorId)
                .param("purposeCode", purposeCode)
                .query(Integer.class)
                .single() > 0;
    }

    private static final String SELECT = """
            select vendor_id, entity_id, name, role, countries, dpa_reference, dpa_signed_at, active
              from vendor
            """;

    private Vendor map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Date signedAt = rs.getDate("dpa_signed_at");
        return new Vendor(rs.getString("vendor_id"), rs.getString("entity_id"),
                rs.getString("name"), rs.getString("role"), readJson(rs.getString("countries")),
                rs.getString("dpa_reference"), signedAt == null ? null : signedAt.toLocalDate(),
                rs.getBoolean("active"));
    }

    private String writeJson(List<String> values) {
        try {
            return json.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            throw new IllegalArgumentException("could not serialise vendor countries", e);
        }
    }

    private List<String> readJson(String value) {
        try {
            return value == null ? List.of() : json.readValue(value, STRING_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * @param role      PROCESSOR, SUB_PROCESSOR, JOINT_CONTROLLER or RECIPIENT. Determines whose
     *                  obligations attach, which is why it is not free text in the RoPA export
     * @param countries where the vendor processes. Each one outside India is a cross-border
     *                  transfer to be documented, not an implementation detail
     */
    public record Vendor(String vendorId, String entityId, String name, String role,
                         List<String> countries, String dpaReference, LocalDate dpaSignedAt,
                         boolean active) {

        /** Whether a data processing agreement is on record at all. */
        public boolean hasDpa() {
            return dpaReference != null && !dpaReference.isBlank() && dpaSignedAt != null;
        }
    }
}
