package com.uds.consent.ledger.store;

import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.ExpiryPolicy;
import com.uds.consent.core.model.FailureBehavior;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.LegalBasis;
import com.uds.consent.core.model.PurposeDefinition;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the purpose registry — the controlled vocabulary every decision is made against.
 *
 * <p>Loads are assembled in four queries rather than one join, because a purpose fans out to its
 * jurisdictions, channels and data categories and a single join would multiply rows and need
 * de-duplication in Java anyway. The whole registry is small — hundreds of rows, not millions —
 * and the service layer caches it, so this runs on configuration change rather than per decision.
 */
@Repository
public class PurposeRegistryStore {

    private final JdbcClient jdbc;

    public PurposeRegistryStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * The current version of every purpose, retired ones included.
     *
     * <p>Retired purposes are loaded deliberately: consent given against them still exists in the
     * ledger and still has to be readable, rendered on a receipt and reported on. What retirement
     * stops is new capture, which the decision engine enforces.
     */
    public List<PurposeDefinition> loadCurrentVersions() {
        List<Row> rows = jdbc.sql("""
                        select pv.id, pv.purpose_code, pv.version, pv.name, pv.description,
                               pv.expiry_policy, pv.expiry_days, pv.failure_behavior, pv.notice_id,
                               pv.requires_separate_consent, pv.permitted_for_children, pv.retired
                          from purpose_version pv
                          join (select purpose_code, max(version) as v
                                  from purpose_version group by purpose_code) latest
                            on latest.purpose_code = pv.purpose_code and latest.v = pv.version
                         order by pv.purpose_code
                        """)
                .query(PurposeRegistryStore::mapRow)
                .list();
        return assemble(rows);
    }

    /** One specific purpose version. Used to render receipts and to reproduce past decisions. */
    public Optional<PurposeDefinition> loadVersion(String purposeCode, int version) {
        List<Row> rows = jdbc.sql("""
                        select id, purpose_code, version, name, description, expiry_policy,
                               expiry_days, failure_behavior, notice_id, requires_separate_consent,
                               permitted_for_children, retired
                          from purpose_version
                         where purpose_code = :code and version = :version
                        """)
                .param("code", purposeCode)
                .param("version", version)
                .query(PurposeRegistryStore::mapRow)
                .list();
        return assemble(rows).stream().findFirst();
    }

    private List<PurposeDefinition> assemble(List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(Row::id).toList();

        Map<Long, Map<Jurisdiction, LegalBasis>> bases = new HashMap<>();
        jdbc.sql("""
                        select purpose_version_id, jurisdiction, legal_basis
                          from purpose_legal_basis
                         where purpose_version_id in (:ids)
                        """)
                .param("ids", ids)
                .query((rs, n) -> {
                    bases.computeIfAbsent(rs.getLong("purpose_version_id"), k -> new HashMap<>())
                            .put(Jurisdiction.valueOf(rs.getString("jurisdiction")),
                                    LegalBasis.valueOf(rs.getString("legal_basis")));
                    return null;
                })
                .list();

        Map<Long, Set<Channel>> channels = new HashMap<>();
        jdbc.sql("select purpose_version_id, channel from purpose_channel "
                        + "where purpose_version_id in (:ids)")
                .param("ids", ids)
                .query((rs, n) -> {
                    channels.computeIfAbsent(rs.getLong("purpose_version_id"), k -> new HashSet<>())
                            .add(Channel.valueOf(rs.getString("channel")));
                    return null;
                })
                .list();

        Map<Long, Set<String>> categories = new HashMap<>();
        jdbc.sql("select purpose_version_id, data_category_code from purpose_data_category "
                        + "where purpose_version_id in (:ids)")
                .param("ids", ids)
                .query((rs, n) -> {
                    categories.computeIfAbsent(rs.getLong("purpose_version_id"), k -> new HashSet<>())
                            .add(rs.getString("data_category_code"));
                    return null;
                })
                .list();

        List<PurposeDefinition> definitions = new ArrayList<>(rows.size());
        for (Row row : rows) {
            definitions.add(new PurposeDefinition(
                    row.purposeCode(),
                    row.version(),
                    row.name(),
                    row.description(),
                    bases.getOrDefault(row.id(), Map.of()),
                    categories.getOrDefault(row.id(), Set.of()),
                    channels.getOrDefault(row.id(), Set.of()),
                    row.expiryPolicy(),
                    row.expiryDays(),
                    row.failureBehavior(),
                    row.noticeId(),
                    row.requiresSeparateConsent(),
                    row.permittedForChildren(),
                    row.retired()));
        }
        return definitions;
    }

    private static Row mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Row(
                rs.getLong("id"),
                rs.getString("purpose_code"),
                rs.getInt("version"),
                rs.getString("name"),
                rs.getString("description"),
                ExpiryPolicy.valueOf(rs.getString("expiry_policy")),
                (Integer) rs.getObject("expiry_days"),
                FailureBehavior.valueOf(rs.getString("failure_behavior")),
                rs.getString("notice_id"),
                rs.getBoolean("requires_separate_consent"),
                rs.getBoolean("permitted_for_children"),
                rs.getBoolean("retired"));
    }

    private record Row(long id, String purposeCode, int version, String name, String description,
                       ExpiryPolicy expiryPolicy, Integer expiryDays, FailureBehavior failureBehavior,
                       String noticeId, boolean requiresSeparateConsent, boolean permittedForChildren,
                       boolean retired) {
    }
}
