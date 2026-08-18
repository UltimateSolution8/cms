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

    /**
     * The contact points to publish for an entity, resolved up the parent chain.
     *
     * <p>The first caller {@link #inheritanceChain} has ever had, and it closes a live defect
     * rather than filling in a design. {@code V3} seeds fifteen entities and gives not one of them
     * a {@code dpo_contact} or a {@code grievance_uri}, while {@code ReceiptService} puts
     * {@code dpoContact()} straight into the ISO/IEC TS 27560 receipt and falls back to
     * {@code grievanceUri()} when the notice carries none. Every receipt issued so far has named a
     * null contact point and a null grievance route — the two things DPDP Rule 3 requires a data
     * principal be given a way to reach.
     *
     * <p>Resolved per field rather than per entity, deliberately. A subsidiary that publishes its
     * own grievance route and shares the group DPO is the ordinary case, and taking the nearest
     * ancestor that has <em>both</em> would silently discard the one it set.
     *
     * <p>Either field may still come back null — when nothing in the chain has been configured,
     * which is the state the platform is in today. That is reported by {@code EntityContactCheck}
     * at start-up rather than papered over with an invented address: a receipt naming an inbox
     * nobody reads is worse than one naming none, because it looks discharged.
     */
    public Contacts resolveContacts(String entityId) {
        String dpoContact = null;
        String grievanceUri = null;
        for (FiduciaryEntity ancestor : inheritanceChain(entityId)) {
            if (dpoContact == null && isPresent(ancestor.dpoContact())) {
                dpoContact = ancestor.dpoContact();
            }
            if (grievanceUri == null && isPresent(ancestor.grievanceUri())) {
                grievanceUri = ancestor.grievanceUri();
            }
            if (dpoContact != null && grievanceUri != null) {
                break;
            }
        }
        return new Contacts(dpoContact, grievanceUri);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * An entity's published contact points after inheritance.
     *
     * @param dpoContact   the nearest configured Data Protection Officer contact, or null when
     *                     nothing in the chain has one
     * @param grievanceUri the nearest configured grievance route, or null on the same terms
     */
    public record Contacts(String dpoContact, String grievanceUri) {

        /** Whether both are answerable. False is a Rule 3 gap, not a cosmetic one. */
        public boolean complete() {
            return dpoContact != null && grievanceUri != null;
        }
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
