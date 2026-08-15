package com.uds.consent.ledger.store;

import com.uds.consent.core.model.Jurisdiction;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The group's entity structure.
 *
 * <p>Entities are configuration, never hard-coded structure. UDS acquires, merges and divests —
 * Tangy Supplies and Stanworth Management were absorbed in May 2025 — and each such change has to
 * cost an insert rather than a release. Onboarding an acquisition means adding a row and pointing
 * it at a parent whose policy it inherits.
 */
@Repository
public class EntityStore {

    private static final String COLUMNS = """
            entity_id, legal_name, short_name, parent_entity_id, uds_stake_percent,
            primary_jurisdiction, data_residency_region, dpo_contact, grievance_uri,
            significant_fiduciary, active
            """;

    private final JdbcClient jdbc;

    public EntityStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public Optional<FiduciaryEntity> find(String entityId) {
        return jdbc.sql("select " + COLUMNS + " from fiduciary_entity where entity_id = :id")
                .param("id", entityId)
                .query(EntityStore::map)
                .optional();
    }

    public List<FiduciaryEntity> findAll() {
        return jdbc.sql("select " + COLUMNS + " from fiduciary_entity order by entity_id")
                .query(EntityStore::map)
                .list();
    }

    /**
     * The entity and each of its ancestors, nearest first.
     *
     * <p>Policy inheritance walks this list and takes the first configuration it finds, so that a
     * newly onboarded subsidiary is governed by its parent's rules from the moment it exists
     * rather than by nothing at all until someone configures it.
     */
    public List<FiduciaryEntity> inheritanceChain(String entityId) {
        List<FiduciaryEntity> chain = new ArrayList<>();
        Optional<FiduciaryEntity> current = find(entityId);
        // Bounded so that a cycle introduced by a bad edit cannot hang a decision. The group
        // hierarchy is three levels deep; ten is generous and still finite.
        int guard = 0;
        while (current.isPresent() && guard++ < 10) {
            chain.add(current.get());
            String parentId = current.get().parentEntityId();
            current = parentId == null ? Optional.empty() : find(parentId);
        }
        return chain;
    }

    private static FiduciaryEntity map(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new FiduciaryEntity(
                rs.getString("entity_id"),
                rs.getString("legal_name"),
                rs.getString("short_name"),
                rs.getString("parent_entity_id"),
                rs.getBigDecimal("uds_stake_percent"),
                Jurisdiction.valueOf(rs.getString("primary_jurisdiction")),
                rs.getString("data_residency_region"),
                rs.getString("dpo_contact"),
                rs.getString("grievance_uri"),
                rs.getBoolean("significant_fiduciary"),
                rs.getBoolean("active"));
    }

    /**
     * @param significantFiduciary set by government notification only — there is no threshold to
     *                             self-assess against. The platform is built to this grade
     *                             regardless, so that a designation arriving mid-programme is a
     *                             flag change rather than a re-architecture.
     */
    public record FiduciaryEntity(String entityId, String legalName, String shortName,
                                  String parentEntityId, BigDecimal udsStakePercent,
                                  Jurisdiction primaryJurisdiction, String dataResidencyRegion,
                                  String dpoContact, String grievanceUri,
                                  boolean significantFiduciary, boolean active) {
    }
}
