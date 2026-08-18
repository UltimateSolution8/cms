package com.uds.consent.ledger.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The algorithmic systems that process personal data, per entity.
 *
 * <p>DPDP Rule 13 asks a Significant Data Fiduciary to verify that these do not pose a risk to
 * data principals' rights. A register is the precondition for verifying anything: a group that
 * cannot list its scoring, ranking and automated-decision systems cannot assert they are safe, and
 * an assurance covering "our algorithms" covers whichever ones the person writing it remembered.
 *
 * <p>{@code automatedDecisionMaking} does double duty. It marks the systems Rule 13's diligence is
 * most pointed at, and it is the source PIPA's separate-consent rule never had — Korea requires
 * its own consent step for automated decision-making, and until now the platform enforced that
 * from a flag on the purpose with nothing behind it saying which systems made the decision.
 */
@Repository
public class AlgorithmicSystemStore {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AlgorithmicSystemStore(DataSource dataSource, ObjectMapper json) {
        this.jdbc = JdbcClient.create(dataSource);
        this.json = json;
    }

    public long upsert(AlgorithmicSystem system) {
        return jdbc.sql("""
                        insert into algorithmic_system (entity_id, name, decides, purpose_codes,
                                                        automated_decision_making, owner, active)
                        values (:entityId, :name, :decides, cast(:purposeCodes as jsonb),
                                :automated, :owner, :active)
                        on conflict (entity_id, name) do update
                            set decides = excluded.decides,
                                purpose_codes = excluded.purpose_codes,
                                automated_decision_making = excluded.automated_decision_making,
                                owner = excluded.owner,
                                active = excluded.active
                        returning id
                        """)
                .param("entityId", system.entityId())
                .param("name", system.name())
                .param("decides", system.decides())
                .param("purposeCodes", writeJson(system.purposeCodes()))
                .param("automated", system.automatedDecisionMaking())
                .param("owner", system.owner())
                .param("active", system.active())
                .query(Long.class)
                .single();
    }

    public List<AlgorithmicSystem> forEntity(String entityId, boolean activeOnly) {
        return jdbc.sql(SELECT + " where entity_id = :entityId"
                        + (activeOnly ? " and active = true" : "") + " order by name")
                .param("entityId", entityId)
                .query(this::map)
                .list();
    }

    public void recordDiligence(long id, Instant at) {
        jdbc.sql("update algorithmic_system set last_diligence_at = :at where id = :id")
                .param("id", id)
                .param("at", Timestamp.from(at))
                .update();
    }

    private static final String SELECT = """
            select id, entity_id, name, decides, purpose_codes, automated_decision_making,
                   last_diligence_at, owner, active
              from algorithmic_system
            """;

    private AlgorithmicSystem map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AlgorithmicSystem(rs.getLong("id"), rs.getString("entity_id"),
                rs.getString("name"), rs.getString("decides"),
                readJson(rs.getString("purpose_codes")),
                rs.getBoolean("automated_decision_making"),
                rs.getTimestamp("last_diligence_at") == null ? null
                        : rs.getTimestamp("last_diligence_at").toInstant(),
                rs.getString("owner"), rs.getBoolean("active"));
    }

    private String writeJson(List<String> values) {
        try {
            return json.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            throw new IllegalArgumentException("could not serialise purpose codes", e);
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
     * @param decides what it decides about people, in a sentence. Free text because the useful
     *                answer is never a category — "which prospects the dialer calls first" is what
     *                an auditor needs and "RANKING" is not
     * @param automatedDecisionMaking whether it makes decisions PIPA treats as automated
     *                decision-making, which carries a separate consent requirement of its own
     */
    public record AlgorithmicSystem(Long id, String entityId, String name, String decides,
                                    List<String> purposeCodes, boolean automatedDecisionMaking,
                                    Instant lastDiligenceAt, String owner, boolean active) {

        public AlgorithmicSystem {
            purposeCodes = purposeCodes == null ? List.of() : List.copyOf(purposeCodes);
        }

        public AlgorithmicSystem(String entityId, String name, String decides,
                                 List<String> purposeCodes, boolean automatedDecisionMaking,
                                 String owner) {
            this(null, entityId, name, decides, purposeCodes, automatedDecisionMaking, null, owner,
                    true);
        }
    }
}
