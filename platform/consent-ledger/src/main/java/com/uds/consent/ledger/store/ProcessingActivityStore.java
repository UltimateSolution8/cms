package com.uds.consent.ledger.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The Record of Processing Activities.
 *
 * <p>The first artefact a regulator asks for, and the one most often assembled in a spreadsheet
 * the week before an audit. Holding it here instead means it is derived from the same purpose
 * registry the decision engine enforces — so the document describing what the group does and the
 * code deciding what the group may do cannot drift apart, which is the failure mode that makes a
 * hand-maintained RoPA worse than useless.
 */
@Repository
public class ProcessingActivityStore {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ProcessingActivityStore(DataSource dataSource, ObjectMapper json) {
        this.jdbc = JdbcClient.create(dataSource);
        this.json = json;
    }

    public long create(Activity activity) {
        return jdbc.sql("""
                        insert into processing_activity (entity_id, name, description, purpose_code,
                                                         system_name, data_categories, recipients,
                                                         cross_border_countries,
                                                         retention_period_days, retention_basis,
                                                         owner, updated_at)
                        values (:entityId, :name, :description, :purposeCode, :systemName,
                                cast(:dataCategories as jsonb), cast(:recipients as jsonb),
                                cast(:crossBorder as jsonb), :retentionDays, :retentionBasis,
                                :owner, now())
                        returning id
                        """)
                .param("entityId", activity.entityId())
                .param("name", activity.name())
                .param("description", activity.description())
                .param("purposeCode", activity.purposeCode())
                .param("systemName", activity.systemName())
                .param("dataCategories", writeJson(activity.dataCategories()))
                .param("recipients", writeJson(activity.recipients()))
                .param("crossBorder", writeJson(activity.crossBorderCountries()))
                .param("retentionDays", activity.retentionPeriodDays())
                .param("retentionBasis", activity.retentionBasis())
                .param("owner", activity.owner())
                .query(Long.class)
                .single();
    }

    public void update(long id, Activity activity) {
        jdbc.sql("""
                        update processing_activity
                           set name = :name, description = :description,
                               purpose_code = :purposeCode, system_name = :systemName,
                               data_categories = cast(:dataCategories as jsonb),
                               recipients = cast(:recipients as jsonb),
                               cross_border_countries = cast(:crossBorder as jsonb),
                               retention_period_days = :retentionDays,
                               retention_basis = :retentionBasis, owner = :owner, updated_at = now()
                         where id = :id
                        """)
                .param("id", id)
                .param("name", activity.name())
                .param("description", activity.description())
                .param("purposeCode", activity.purposeCode())
                .param("systemName", activity.systemName())
                .param("dataCategories", writeJson(activity.dataCategories()))
                .param("recipients", writeJson(activity.recipients()))
                .param("crossBorder", writeJson(activity.crossBorderCountries()))
                .param("retentionDays", activity.retentionPeriodDays())
                .param("retentionBasis", activity.retentionBasis())
                .param("owner", activity.owner())
                .update();
    }

    public Optional<Activity> find(long id) {
        return jdbc.sql(SELECT + " where id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    public List<Activity> findForEntity(String entityId) {
        return jdbc.sql(SELECT + " where entity_id = :entityId order by name")
                .param("entityId", entityId)
                .query(this::map)
                .list();
    }

    /**
     * The activities an entity has recorded for a purpose.
     *
     * <p>Read when a consent receipt is built. TS 27560 asks the receipt to state, per purpose, how
     * long the data is kept and where it goes — facts the RoPA already holds and which had no route
     * to the artefact the subject is given.
     *
     * <p>Returns a list rather than an optional because nothing stops an entity describing the same
     * purpose in two activities (a call centre and a campaign platform, say). A receipt that showed
     * only the first would be arbitrary; the caller merges them.
     */
    public List<Activity> findForPurpose(String entityId, String purposeCode) {
        return jdbc.sql(SELECT + " where entity_id = :entityId and purpose_code = :purposeCode "
                        + "order by name")
                .param("entityId", entityId)
                .param("purposeCode", purposeCode)
                .query(this::map)
                .list();
    }

    /**
     * Activities with no documented retention rule.
     *
     * <p>DPDP requires erasure once the purpose is served. An activity that cannot say how long it
     * keeps data cannot demonstrate it erases anything, so this list is a compliance gap rather
     * than a tidiness one.
     */
    public List<Activity> findWithoutRetention(String entityId) {
        return jdbc.sql(SELECT + " where entity_id = :entityId "
                        + "and (retention_period_days is null "
                        + "     and (retention_basis is null or retention_basis = '')) "
                        + "order by name")
                .param("entityId", entityId)
                .query(this::map)
                .list();
    }

    /** Activities that move data out of India. Each one is a transfer to document, not to discover. */
    public List<Activity> findCrossBorder(String entityId) {
        return jdbc.sql(SELECT + " where entity_id = :entityId "
                        + "and jsonb_array_length(cross_border_countries) > 0 order by name")
                .param("entityId", entityId)
                .query(this::map)
                .list();
    }

    /**
     * Activities transferring a category the Government has restricted from leaving India.
     *
     * <p>DPDP <strong>Rule 13(4)</strong> lets the Central Government, on the recommendation of the
     * committee constituted under Rule 13(5), name categories of personal data that a Significant
     * Data Fiduciary may not transfer outside India at all. Unlike every other cross-border
     * question, this one has no lawful-transfer answer: the category may not go, and an activity
     * sending it is not a transfer to document but one to stop.
     *
     * <p>Not to be confused with two neighbouring rules. <strong>Rule 15</strong> is the general
     * restriction on transfer outside India and binds <em>every</em> Data Fiduciary, not only
     * Significant ones; {@link #findCrossBorder} is the report for that. <strong>Rule 14</strong>
     * is about rights, publication and grievance redressal and has nothing to do with transfers —
     * it was miscited here until V21.
     *
     * <p><strong>Scope.</strong> Rule 13(4) reaches "the personal data and the traffic data
     * pertaining to its flow". This query covers the personal data half only, by data category.
     * Traffic data — which carrier a message went through, when, to where — is not a data category
     * and is not held by this platform at all: it holds consent evidence, not message logs. That is
     * a boundary rather than a gap, and it is stated so nobody reads this method as covering it.
     *
     * <p><strong>Returns nothing today, and that is the point.</strong> No categories are notified
     * as at August 2026 — checked, not assumed — so {@code data_category.transfer_restricted} is
     * false on every row and this query is empty. It exists now so that honouring a notification
     * is an update statement against one column rather than a release, and so that the report
     * reading it is already in production and already tested when the notification lands.
     */
    public List<Activity> findRestrictedCrossBorder(String entityId) {
        return jdbc.sql(SELECT + """
                         where entity_id = :entityId
                           and jsonb_array_length(cross_border_countries) > 0
                           and exists (select 1 from data_category dc
                                        where dc.transfer_restricted = true
                                          and jsonb_exists(processing_activity.data_categories,
                                                           dc.code))
                         order by name
                        """)
                .param("entityId", entityId)
                .query(this::map)
                .list();
    }

    /**
     * Purposes the entity enforces but has no processing activity for.
     *
     * <p>The gap that matters most in a RoPA, and the one a spreadsheet cannot find: the group is
     * making decisions about a purpose it has never described. Reported as a list of purpose codes
     * so it can be worked through.
     */
    public List<String> purposesWithoutActivity(String entityId) {
        return jdbc.sql("""
                        select p.code from purpose p
                         where exists (select 1 from purpose_version pv
                                        where pv.purpose_code = p.code and pv.retired = false)
                           and not exists (select 1 from processing_activity pa
                                            where pa.purpose_code = p.code
                                              and pa.entity_id = :entityId)
                         order by p.code
                        """)
                .param("entityId", entityId)
                .query(String.class)
                .list();
    }

    private static final String SELECT = """
            select id, entity_id, name, description, purpose_code, system_name, data_categories,
                   recipients, cross_border_countries, retention_period_days, retention_basis,
                   owner, updated_at
              from processing_activity
            """;

    private Activity map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        // wasNull() reports on the most recent column read, so the null check has to happen
        // immediately after the get and not further down the argument list.
        int retentionValue = rs.getInt("retention_period_days");
        Integer retentionDays = rs.wasNull() ? null : retentionValue;

        return new Activity(rs.getLong("id"), rs.getString("entity_id"), rs.getString("name"),
                rs.getString("description"), rs.getString("purpose_code"),
                rs.getString("system_name"), readJson(rs.getString("data_categories")),
                readJson(rs.getString("recipients")),
                readJson(rs.getString("cross_border_countries")),
                retentionDays, rs.getString("retention_basis"),
                rs.getString("owner"), rs.getTimestamp("updated_at").toInstant());
    }

    private String writeJson(List<String> values) {
        try {
            return json.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            throw new IllegalArgumentException("could not serialise processing activity list", e);
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
     * @param retentionBasis why the retention period is what it is. A number without a reason
     *                       cannot be defended when someone asks why it is not shorter
     */
    public record Activity(Long id, String entityId, String name, String description,
                           String purposeCode, String systemName, List<String> dataCategories,
                           List<String> recipients, List<String> crossBorderCountries,
                           Integer retentionPeriodDays, String retentionBasis, String owner,
                           Instant updatedAt) {

        public boolean hasRetentionRule() {
            return retentionPeriodDays != null
                    || (retentionBasis != null && !retentionBasis.isBlank());
        }

        public boolean crossesBorder() {
            return crossBorderCountries != null && !crossBorderCountries.isEmpty();
        }
    }
}
