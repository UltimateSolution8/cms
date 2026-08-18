package com.uds.consent.service.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The second isolation layer, executed.
 *
 * <p>This suite exists because of a gap in the shape of the test coverage rather than in the code.
 * {@code EntityAccessGuard} refuses cross-entity requests and its javadoc says plainly that it does
 * <strong>not</strong> parse request bodies — reading the body in a filter consumes the stream, and
 * buffering every submission to check a field the database is about to check anyway is a poor trade.
 * Body-carried {@code entityId} is therefore delegated, by design and in writing, to the row-level
 * security policies in {@code V13__row_level_security.sql}.
 *
 * <p>Those policies had never once run under test. {@code V13} deliberately does not
 * {@code FORCE ROW LEVEL SECURITY}, so the policies do not bind a table's owner — and
 * {@code PostgresIntegrationTest} connects the application as {@code uds_consent_owner}. Every
 * integration test in the tree, including all eight cases in {@code EntityIsolationIT}, therefore
 * ran with the policies bypassed. They prove layer one. Layer two, the one the design leans on for
 * exactly the case layer one declines to cover, was asserted nowhere.
 *
 * <p>So this suite connects as {@code uds_consent_app} — the role
 * {@code src/test/resources/db/testcontainer-init.sql} already creates — which is the role that
 * serves traffic in production and the role the policies actually bind. It is a test fixture and
 * changes nothing about how the application connects.
 *
 * <p><strong>The write direction matters more than the read.</strong> A cross-entity read is a
 * disclosure; a cross-entity write puts one fiduciary's consent record inside another's evidence
 * plane, where it will be produced to a regulator as though it belonged there. And the write is
 * precisely the body-carried case: {@code POST} routes carry {@code entityId} in the body, so the
 * filter never sees it.
 */
class RowLevelSecurityIT extends PostgresIntegrationTest {

    /**
     * Every table {@code V13} names, plus the one {@code V14} added afterwards.
     *
     * <p>Listed here rather than read back out of the database on purpose. Reading the list from
     * {@code pg_class} would make the test assert that whatever is protected is protected, which is
     * true of an empty set. The point of writing it down twice is that adding a table to one list
     * and not the other is a build failure rather than a silent hole.
     *
     * <p>That reasoning still holds for <em>this</em> assertion and it is not the whole control.
     * It only ever checks the tables somebody remembered to add, so
     * {@link #theProtectedSetIsDerivedRatherThanRemembered()} sits beside it and derives the
     * question from {@code information_schema} instead — with an {@code isNotEmpty} guard against
     * precisely the vacuous pass this comment warns about. Two assertions of opposite shape: this
     * one names what must be protected, that one finds what was forgotten.
     */
    private static final List<String> PROTECTED_TABLES = List.of(
            "consent_event", "consent_artefact", "subject", "suppression_entry",
            "provenance_record", "processing_activity", "vendor", "rights_request",
            "admin_audit_event", "enforcement_decision", "scrub_run", "personal_data_breach",
            "retention_action", "consent_receipt", "notice", "dlt_header", "dlt_template",
            // V14.
            "consent_manager_link",
            // V16, and these two are why this suite was worth writing. Both carry an entity_id
            // and both were missing from V13's hand-written list — subject_identifier sits
            // fifteen lines below `subject` in V1 and reads as part of the same thing, and
            // consent_chain_head looks like plumbing rather than personal data. Neither was
            // caught by anything, because until this suite existed nothing had ever connected as
            // the role the policies bind.
            "subject_identifier", "consent_chain_head",
            // V18. A table naming which of an entity's subjects are children, which makes it about
            // as sensitive as anything the platform holds.
            "subject_age_assertion",
            // V19 and V20. The Korean re-confirmation queue names subjects; the SDF register
            // discloses which group companies are under a Government designation and how far
            // behind they are on it, which is not something one entity should read about another.
            "consent_reconfirmation", "sdf_obligation", "algorithmic_system",
            // V22, and the last of EntityAccessGuard's own documented gaps. The guard's javadoc
            // named this table as the one case neither layer constrained: it hung off breach_id
            // with no entity of its own, so a scoped credential holding a breach id could read
            // another entity's notification rows — party, deadline, method, reference, recipient
            // count. The entity is denormalised from the parent breach and written by the insert
            // rather than by a caller, so the policy has something to bind and nothing can file a
            // notification under the wrong fiduciary.
            "breach_notification",
            // V24, V26 and V27. subject_alias says which subjects turned out to be the same
            // person; rights_fulfilment_action says which of the group's systems erased what,
            // with a reference; webhook_delivery says whose consent changes were pushed where.
            // Each of the three discloses something about another entity's operations that the
            // entity itself would not hand over.
            "subject_alias", "rights_fulfilment_action", "fulfilment_target",
            "webhook_subscription", "webhook_delivery");

    /**
     * The tables that carry an {@code entity_id} and are open on purpose.
     *
     * <p>Every other entity-scoped table must have a policy, and the test below derives the "every
     * other" from {@code information_schema} rather than from a list somebody remembered to
     * update. This map is the exceptions, and the reasons are copied out of {@code V16} on purpose
     * — a reader deciding whether to add a table here should not have to go and find the migration
     * that argued it the first time.
     *
     * <p>{@code consent_manager} is <em>not</em> here, though {@code V16}'s prose names it beside
     * these three. It carries no {@code entity_id} at all — a Consent Manager is registered with
     * the Board, not with a fiduciary — so it is out of this test's scope by construction rather
     * than by exception. The guard below caught it on its first run: an allow-list entry for a
     * column that does not exist is an excuse waiting to be inherited by a table that does.
     */
    private static final Map<String, String> DELIBERATELY_OPEN = Map.of(
            "fiduciary_entity",
            "the group's own structure. Configuration, not personal data — and hiding it would "
                    + "break the decision path for every entity while protecting nobody.",
            "application_registry",
            "the list of registered surfaces. Same reasoning as fiduciary_entity: the decision "
                    + "path reads it on every call and it describes systems, not people.",
            "application_entity_scope",
            "which application may act for which entity. It is the authorisation table itself; "
                    + "scoping it by the claim it exists to establish would be circular.");

    private static final String DENAVE = "DENAVE_IN";
    private static final String MATRIX = "MATRIX";

    /** As the migration role: sets up rows the policies are then asked about. Bypasses policies. */
    @Autowired
    private JdbcTemplate asOwner;

    /** The application's own data source, for the per-checkout test. */
    @Autowired
    private DataSource applicationDataSource;

    @Test
    @DisplayName("the policies are enabled and the application role does not own the tables")
    void theEnvironmentIsAsTheRunbookRequires() {
        // The way this control fails in production is not a wrong policy. It is a deployment where
        // the application connects as the owner, because V13 deliberately does not FORCE — at which
        // point every policy in the list is bypassed in complete silence and the platform looks
        // exactly as it does when everything is correct.
        //
        // OPERATIONS.md §8 asks an operator to verify this by hand, once. A check that lives only
        // in a runbook is a check that gets done once, by the person who wrote it.
        for (String table : PROTECTED_TABLES) {
            Boolean enabled = asOwner.queryForObject(
                    "select relrowsecurity from pg_class where relname = ?", Boolean.class, table);
            assertThat(enabled)
                    .withFailMessage("row-level security is not enabled on %s", table)
                    .isTrue();

            String owner = asOwner.queryForObject(
                    "select tableowner from pg_tables where tablename = ?", String.class, table);
            assertThat(owner)
                    .withFailMessage("%s is owned by the application role, so its policy is "
                            + "bypassed and this whole suite would pass while protecting nothing",
                            table)
                    .isNotEqualTo("uds_consent_app");
        }
    }

    @Test
    @DisplayName("every table that carries an entity_id has a policy, or is open on the record")
    void theProtectedSetIsDerivedRatherThanRemembered() {
        // The list above is the failure this test exists to make impossible. V13 wrote seventeen
        // table names into an array; V14, V16, V18, V19 and V20 each added an entity-scoped table
        // afterwards, and each had to remember to add a policy in a file other than the one that
        // holds the list. Five times out of five somebody did remember — which is luck with a good
        // record, not a control.
        //
        // So derive the question from the catalogue. Any table with an entity_id column is
        // entity-scoped by construction, and the only ones allowed to go without a policy are the
        // four named above, each with its reason. A migration adding a sixth entity-scoped table
        // and forgetting the policy fails here, in the module that ships it, without anybody
        // having to notice.
        // Partitions excluded, and the reason is a property of PostgreSQL rather than a
        // convenience. A policy created on a partitioned parent applies to every partition; the
        // partition itself has no row in pg_policies and never will. Without this exclusion the
        // guard would report fourteen unprotected tables that are one protected table, gain a new
        // false positive every month as the maintenance sweeper provisions ahead, and be switched
        // off by whoever got tired of it — losing the real control it exists to be.
        //
        // The parent is still checked, by the same query, because it is not a partition.
        List<String> entityScoped = asOwner.queryForList(
                "select c.table_name from information_schema.columns c "
                        + "join information_schema.tables t on t.table_schema = c.table_schema "
                        + "  and t.table_name = c.table_name "
                        + "join pg_class pc on pc.relname = c.table_name "
                        + "where c.table_schema = 'public' and c.column_name = 'entity_id' "
                        + "  and t.table_type = 'BASE TABLE' "
                        + "  and not pc.relispartition "
                        + "order by c.table_name",
                String.class);

        assertThat(entityScoped)
                .withFailMessage("no entity_id columns found at all — the query is wrong, not the "
                        + "schema, and a guard that finds nothing passes forever")
                .isNotEmpty();

        List<String> unprotected = entityScoped.stream()
                .filter(table -> !DELIBERATELY_OPEN.containsKey(table))
                .filter(table -> !hasIsolationPolicy(table))
                .toList();

        assertThat(unprotected)
                .withFailMessage("""
                        these tables carry an entity_id and have no row-level security policy: %s.

                        One entity can read and write another's rows in them. Add the policy in a \
                        new migration following V16's shape — one policy covering all commands, \
                        guarded by a uds_consent_app presence check — or, if the table is \
                        genuinely group-wide, add it to DELIBERATELY_OPEN in this class with the \
                        reason written out. Do not delete this assertion.""", unprotected)
                .isEmpty();

        // And the exceptions have to still exist. An allow-list outlives the table it excuses, and
        // a stale entry is how the next gap gets excused by accident.
        assertThat(entityScoped)
                .withFailMessage("DELIBERATELY_OPEN names a table that no longer carries an "
                        + "entity_id; remove the stale entry rather than leaving a standing excuse")
                .containsAll(DELIBERATELY_OPEN.keySet());
    }

    private boolean hasIsolationPolicy(String table) {
        Integer policies = asOwner.queryForObject(
                "select count(*) from pg_policies where schemaname = 'public' and tablename = ?",
                Integer.class, table);
        Boolean enabled = asOwner.queryForObject(
                "select relrowsecurity from pg_class where relname = ?", Boolean.class, table);
        // Both halves, because either alone is silently useless: a policy on a table without RLS
        // enabled is never consulted, and RLS enabled without a policy denies everything, which
        // would be caught by the first test to run rather than by this one.
        return policies != null && policies > 0 && Boolean.TRUE.equals(enabled);
    }

    @Test
    @DisplayName("a scoped session cannot read another entity's rows")
    void aScopedSessionSeesOnlyItsOwnEntity() {
        String denaveSubject = seedSubject(DENAVE);
        String matrixSubject = seedSubject(MATRIX);

        JdbcTemplate scoped = asApplication(DENAVE);

        assertThat(subjectsVisibleTo(scoped, denaveSubject)).isEqualTo(1);
        assertThat(subjectsVisibleTo(scoped, matrixSubject))
                .withFailMessage("a Denave-scoped session read a Matrix row")
                .isZero();

        // And the identifier mapping, which is the sharper of the two. A hash is deterministic
        // under one pepper, so an open subject_identifier lets a session take a phone number,
        // hash it, and learn whether that person is another entity's data principal and under
        // which subject id — the key to every table whose policy would have refused a direct read.
        assertThat(identifiersVisibleTo(scoped, matrixSubject))
                .withFailMessage("a Denave-scoped session read Matrix's identifier mapping")
                .isZero();
        assertThat(identifiersVisibleTo(scoped, denaveSubject)).isEqualTo(1);
    }

    @Test
    @DisplayName("a scoped session cannot write a row belonging to another entity")
    void aScopedSessionCannotWriteAcrossEntities() {
        // The case that justifies having this layer at all. EntityAccessGuard does not parse
        // bodies, and every write route carries entityId in the body — so for a POST, this policy
        // is not the second line of defence, it is the only one.
        JdbcTemplate scoped = asApplication(DENAVE);

        assertThatThrownBy(() -> scoped.update(
                "insert into subject (entity_id, subject_id) values (?, ?)",
                MATRIX, "rls-" + UUID.randomUUID()))
                .rootCause()
                // PostgreSQL's own wording for a WITH CHECK failure. Asserted on the message
                // because the alternative — asserting the row is absent afterwards — passes just as
                // well if the insert silently succeeded somewhere nobody looked.
                .hasMessageContaining("row-level security");
    }

    @Test
    @DisplayName("a scoped session cannot read another entity's breach notifications")
    void breachNotificationsAreScopedNow() {
        // EntityAccessGuard's javadoc named this table as the one case NEITHER layer constrained.
        // Layer one structurally cannot: GET /v1/admin/breaches/{breachId}/… carries an opaque row
        // id, so there is nothing in the path to compare against a claim. Layer two had nothing to
        // bind, because the table hung off breach_id with no entity of its own.
        //
        // What that disclosed is not abstract. A notification row names the party told, the
        // deadline, the method, the reference and the recipient count — the shape and scale of
        // another group company's worst week, and whether they met the Rule 7 clock.
        String matrixBreach = seedBreachWithNotification(MATRIX);
        String denaveBreach = seedBreachWithNotification(DENAVE);

        JdbcTemplate scoped = asApplication(DENAVE);

        assertThat(notificationsVisibleTo(scoped, matrixBreach))
                .withFailMessage("a Denave-scoped session read Matrix's breach notifications")
                .isZero();
        assertThat(notificationsVisibleTo(scoped, denaveBreach)).isEqualTo(1);

        // And the write direction, which is the sharper one: a notification filed under another
        // fiduciary is an obligation that entity cannot see and did not discharge. BreachStore
        // selects entity_id from the parent breach rather than accepting it, so this is belt and
        // braces — and the belt is the part that survives somebody adding a second writer.
        assertThatThrownBy(() -> scoped.update(
                "insert into breach_notification (breach_id, entity_id, party, immediate, basis) "
                        + "values (?, ?, 'REGULATOR', true, 'rls-probe')",
                matrixBreach, MATRIX))
                .rootCause()
                .hasMessageContaining("row-level security");
    }

    private String seedBreachWithNotification(String entityId) {
        String breachId = "rls-breach-" + UUID.randomUUID();
        asOwner.update("insert into personal_data_breach (breach_id, entity_id, jurisdiction, "
                        + "occurred_at, aware_at, description, reported_by) "
                        + "values (?, ?, 'IN', now(), now(), 'rls fixture', 'test')",
                breachId, entityId);
        asOwner.update("insert into breach_notification (breach_id, entity_id, party, immediate, "
                        + "basis) values (?, ?, 'REGULATOR', true, 'rls fixture')",
                breachId, entityId);
        return breachId;
    }

    private static int notificationsVisibleTo(JdbcTemplate jdbc, String breachId) {
        return count(jdbc, "select count(*) from breach_notification where breach_id = ?", breachId);
    }

    @Test
    @DisplayName("a session with no claim reads group-wide, which is the documented fallback")
    void anUnscopedSessionIsGroupLevel() {
        String denaveSubject = seedSubject(DENAVE);
        String matrixSubject = seedSubject(MATRIX);

        // Group compliance genuinely needs this, and V13 calls it a grant rather than a gap. It is
        // asserted so that the fallback stays a decision: current_setting(..., true) returns NULL
        // on a connection where nothing was set, and if that ever started denying instead, every
        // sweeper and the outbox relay — none of which authenticate — would stop silently.
        JdbcTemplate unscoped = asApplication(null);

        assertThat(subjectsVisibleTo(unscoped, denaveSubject)).isEqualTo(1);
        assertThat(subjectsVisibleTo(unscoped, matrixSubject)).isEqualTo(1);
    }

    @Test
    @DisplayName("the claim is re-applied on every checkout, not once per physical connection")
    void theClaimDoesNotSurviveIntoTheNextRequest() throws SQLException {
        // The failure V13's own header warns about, and the one that would be worst: a pooled
        // connection carrying the previous request's claim answers the next request as the wrong
        // entity. It would look like isolation, fail intermittently, and depend on pool scheduling
        // — which is to say it would be unreproducible on the day somebody reported it.
        String underDenave = claimOnAConnectionScopedTo("denave-console");
        assertThat(underDenave).isEqualTo(DENAVE);

        // Same pool, almost certainly the same physical connection, different caller.
        String underGroup = claimOnAConnectionScopedTo("compliance-console");
        assertThat(underGroup)
                .withFailMessage("a connection carried the previous caller's claim into the next "
                        + "request; this is the pooling failure EntityScopedDataSource exists to "
                        + "prevent")
                .isEmpty();
    }

    /**
     * A {@code JdbcTemplate} connected as the role that serves production traffic.
     *
     * <p>{@code SingleConnectionDataSource} rather than a pool, and that is not a shortcut — the
     * claim is a <em>session</em> variable, so every statement in one of these tests has to run on
     * the same physical connection or the claim set by the first would be invisible to the second.
     * A pool would also hand back a connection scoped by whatever ran before it, which would make
     * these assertions depend on execution order.
     *
     * @param claim the entity to scope to, or null for a group-level session
     */
    private JdbcTemplate asApplication(String claim) {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                POSTGRES.getJdbcUrl(), "uds_consent_app", "uds_consent_app", true);
        dataSource.setDriverClassName(org.postgresql.Driver.class.getName());

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // Set the same way EntityScopedDataSource sets it, so the test exercises the real
        // mechanism rather than a lookalike. An empty string and an unset variable both read as
        // group level, which is the point of nullif() in uds_entity_claim(). queryForObject rather
        // than update because set_config returns the value it set.
        jdbc.queryForObject("select set_config('uds.entity_id', ?, false)", String.class,
                claim == null ? "" : claim);
        return jdbc;
    }

    /** The claim a connection from the application's own pool carries for a given credential. */
    private String claimOnAConnectionScopedTo(String clientId) throws SQLException {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(clientId, "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        try (Connection connection = applicationDataSource.getConnection();
             var statement = connection.prepareStatement(
                     "select current_setting('uds.entity_id', true)");
             var results = statement.executeQuery()) {
            results.next();
            String claim = results.getString(1);
            return claim == null ? "" : claim;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private String seedSubject(String entityId) {
        String subjectId = "rls-" + UUID.randomUUID();
        asOwner.update("insert into subject (entity_id, subject_id) values (?, ?)",
                entityId, subjectId);
        // The identifier row too, because subject_identifier is one of the two tables V16 closed
        // and the read assertion should cover the table that actually held the key.
        asOwner.update("insert into subject_identifier (entity_id, identifier_type, "
                        + "identifier_hash, subject_id) values (?, 'PHONE', ?, ?)",
                entityId, "rls-hash-" + UUID.randomUUID(), subjectId);
        return subjectId;
    }

    private static int subjectsVisibleTo(JdbcTemplate jdbc, String subjectId) {
        return count(jdbc, "select count(*) from subject where subject_id = ?", subjectId);
    }

    private static int identifiersVisibleTo(JdbcTemplate jdbc, String subjectId) {
        return count(jdbc, "select count(*) from subject_identifier where subject_id = ?",
                subjectId);
    }

    private static int count(JdbcTemplate jdbc, String sql, String argument) {
        Integer count = jdbc.queryForObject(sql, Integer.class, argument);
        return count == null ? 0 : count;
    }
}
