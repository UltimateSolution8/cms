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

    // -------------------------------------------------------------------------------------
    // Publishing
    // -------------------------------------------------------------------------------------

    /** Whether the purpose itself is registered. Versions attach to a purpose, not to a string. */
    public boolean purposeExists(String purposeCode) {
        return jdbc.sql("select 1 from purpose where code = :code")
                .param("code", purposeCode)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /** Registers a purpose, so its first version has something to attach to. */
    public void createPurpose(String purposeCode, String name, String owner) {
        jdbc.sql("insert into purpose (code, name, owner) values (:code, :name, :owner)")
                .param("code", purposeCode)
                .param("name", name)
                .param("owner", owner)
                .update();
    }

    /** One past the highest published version; 1 for a purpose with no versions yet. */
    public int nextVersion(String purposeCode) {
        return jdbc.sql("select coalesce(max(version), 0) + 1 from purpose_version "
                        + "where purpose_code = :code")
                .param("code", purposeCode)
                .query(Integer.class)
                .single();
    }

    /**
     * Appends a purpose version with its jurisdictions, channels and data categories.
     *
     * <p>The four inserts are one unit of work or none of them are. A purpose version with no
     * legal-basis rows is not an incomplete record — it is a purpose that is <em>denied in every
     * jurisdiction</em>, because the engine treats a missing jurisdiction row as "not permitted
     * here". A partial publish would therefore not look broken; it would look like a deliberate
     * prohibition, and would silently stop lawful processing until somebody worked out why.
     *
     * <p>Transaction management belongs to the caller — {@code PublishingService} is annotated
     * {@code @Transactional} — so that the blast-radius read and these writes share one snapshot.
     *
     * @return the generated {@code purpose_version.id}
     */
    public long publishVersion(NewPurposeVersion version, String publishedBy) {
        long id = jdbc.sql("""
                        insert into purpose_version (purpose_code, version, name, description,
                                                     expiry_policy, expiry_days, failure_behavior,
                                                     notice_id, requires_separate_consent,
                                                     permitted_for_children, material_change,
                                                     retired, published_by)
                        values (:code, :version, :name, :description, :expiryPolicy, :expiryDays,
                                :failureBehavior, :noticeId, :requiresSeparateConsent,
                                :permittedForChildren, :materialChange, :retired, :publishedBy)
                        returning id
                        """)
                .param("code", version.purposeCode())
                .param("version", version.version())
                .param("name", version.name())
                .param("description", version.description())
                .param("expiryPolicy", version.expiryPolicy().name())
                .param("expiryDays", version.expiryDays())
                .param("failureBehavior", version.failureBehavior().name())
                .param("noticeId", version.noticeId())
                .param("requiresSeparateConsent", version.requiresSeparateConsent())
                .param("permittedForChildren", version.permittedForChildren())
                .param("materialChange", version.materialChange())
                .param("retired", version.retired())
                .param("publishedBy", publishedBy)
                .query(Long.class)
                .single();

        version.legalBases().forEach((jurisdiction, basis) ->
                jdbc.sql("""
                                insert into purpose_legal_basis (purpose_version_id, jurisdiction,
                                                                 legal_basis, assessment_ref, notes)
                                values (:id, :jurisdiction, :basis, :assessmentRef, :notes)
                                """)
                        .param("id", id)
                        .param("jurisdiction", jurisdiction.name())
                        .param("basis", basis.legalBasis().name())
                        .param("assessmentRef", basis.assessmentRef())
                        .param("notes", basis.notes())
                        .update());

        for (Channel channel : version.channels()) {
            jdbc.sql("insert into purpose_channel (purpose_version_id, channel) "
                            + "values (:id, :channel)")
                    .param("id", id)
                    .param("channel", channel.name())
                    .update();
        }

        for (String category : version.dataCategories()) {
            jdbc.sql("insert into purpose_data_category (purpose_version_id, data_category_code) "
                            + "values (:id, :code)")
                    .param("id", id)
                    .param("code", category)
                    .update();
        }
        return id;
    }

    /** Data category codes that are not in the controlled vocabulary. Empty means all known. */
    public List<String> unknownDataCategories(Set<String> codes) {
        if (codes.isEmpty()) {
            return List.of();
        }
        Set<String> known = new HashSet<>(jdbc
                .sql("select code from data_category where code in (:codes)")
                .param("codes", List.copyOf(codes))
                .query(String.class)
                .list());
        return codes.stream().filter(code -> !known.contains(code)).sorted().toList();
    }

    /**
     * A version to publish, before it has an identity.
     *
     * @param materialChange the human judgement about whether this alters what subjects agreed to.
     *                       Not inferable from the diff — a reworded description can be cosmetic or
     *                       can change the deal — which is why it is supplied rather than computed
     */
    public record NewPurposeVersion(String purposeCode, int version, String name, String description,
                                    Map<Jurisdiction, BasisEntry> legalBases,
                                    Set<String> dataCategories, Set<Channel> channels,
                                    ExpiryPolicy expiryPolicy, Integer expiryDays,
                                    FailureBehavior failureBehavior, String noticeId,
                                    boolean requiresSeparateConsent, boolean permittedForChildren,
                                    boolean materialChange, boolean retired) {

        public NewPurposeVersion {
            legalBases = Map.copyOf(legalBases);
            dataCategories = Set.copyOf(dataCategories);
            channels = Set.copyOf(channels);
        }
    }

    /**
     * @param assessmentRef the Legitimate Interests Assessment. Mandatory for
     *                      {@code LEGITIMATE_INTEREST} and enforced by {@code ck_lia_present}
     */
    public record BasisEntry(LegalBasis legalBasis, String assessmentRef, String notes) {
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

        // Joined to data_category rather than reading the codes alone. The sensitive and biometric
        // flags live there and were previously read by nobody, which left the jurisdiction modules
        // inferring sensitivity from the shape of a category code.
        Map<Long, Set<String>> categories = new HashMap<>();
        Map<Long, Set<String>> sensitive = new HashMap<>();
        Map<Long, Set<String>> biometric = new HashMap<>();
        jdbc.sql("""
                        select pdc.purpose_version_id, pdc.data_category_code,
                               coalesce(dc.sensitive, false) as sensitive,
                               coalesce(dc.biometric, false) as biometric
                          from purpose_data_category pdc
                          left join data_category dc on dc.code = pdc.data_category_code
                         where pdc.purpose_version_id in (:ids)
                        """)
                .param("ids", ids)
                .query((rs, n) -> {
                    long versionId = rs.getLong("purpose_version_id");
                    String code = rs.getString("data_category_code");
                    categories.computeIfAbsent(versionId, k -> new HashSet<>()).add(code);
                    if (rs.getBoolean("sensitive")) {
                        sensitive.computeIfAbsent(versionId, k -> new HashSet<>()).add(code);
                    }
                    if (rs.getBoolean("biometric")) {
                        biometric.computeIfAbsent(versionId, k -> new HashSet<>()).add(code);
                    }
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
                    sensitive.getOrDefault(row.id(), Set.of()),
                    biometric.getOrDefault(row.id(), Set.of()),
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
