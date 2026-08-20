package com.uds.consent.service.it;

import com.uds.consent.ledger.store.PartitionStore;
import com.uds.consent.service.sweeper.PartitionMaintenanceSweeper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The evidence table that grows with traffic, and the guarantees that had to survive partitioning.
 *
 * <p>{@code enforcement_decision} takes a row per dialer pre-flight refusal, is append-only, and
 * nothing ever removes a row. It is the only table in the schema whose growth is bounded by traffic
 * rather than by population, and {@code V28} range-partitions it by month — while it is empty,
 * because retro-fitting partitioning onto a live append-only table carrying triggers, RLS policies
 * and revoked grants is a maintenance window rather than a migration.
 *
 * <p><strong>The risk in that change is not the partitioning. It is everything the partitioning
 * silently drops.</strong> A partitioned table is a new table: it inherits none of the original's
 * indexes, triggers, policies or grants. A migration that recreated four of those five would leave
 * a hole that looks exactly like a working system — the decisions would still be recorded, and one
 * of the three layers protecting them would be gone. That is what this suite is for.
 *
 * <p>{@code consent_event} is deliberately <em>not</em> partitioned. PostgreSQL requires every
 * unique constraint on a partitioned table to include the partition key, and its two — the chain
 * having no forks, and idempotent offline replay — would both have had to admit
 * {@code recorded_at}. At that point two events could share a subject and a sequence number in
 * different months. {@code V28}'s header carries the full argument; the assertion below pins the
 * outcome so nobody completes the job later without meeting it.
 */
class PartitionMaintenanceIT extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PartitionMaintenanceSweeper sweeper;

    @Test
    @DisplayName("a decision lands in the partition for the month it was recorded")
    void rowsRouteToTheirMonth() {
        Instant now = Instant.now();
        insertDecision(now);

        String partition = "enforcement_decision_"
                + now.atZone(java.time.ZoneOffset.UTC).getYear() + "_"
                + String.format("%02d", now.atZone(java.time.ZoneOffset.UTC).getMonthValue());

        assertThat(count("select count(*) from " + partition))
                .withFailMessage("the decision did not land in %s; either the partition is missing "
                        + "or the bounds are wrong, and either way the row is in the default "
                        + "partition where no pruning will reach it", partition)
                .isPositive();

        // Nothing in the default. A default partition quietly accumulating traffic is a
        // partitioned table with extra steps, and the whole point of provisioning ahead is that it
        // stays empty.
        assertThat(count("select count(*) from enforcement_decision_default")).isZero();
    }

    @Test
    @DisplayName("append-only survives partitioning — on a partition, not just the parent")
    void theAppendOnlyGuaranteeSurvives() {
        // The assertion that justifies this suite. V8's triggers were created on a table that no
        // longer exists; V28 recreates them on the partitioned parent, and PostgreSQL propagates
        // row triggers to every partition — including ones the sweeper adds next year. If that
        // propagation ever stopped, decisions would still be written and would quietly become
        // editable, which is the failure that looks most like everything working.
        insertDecision(Instant.now());

        assertThatThrownBy(() -> jdbc.update(
                "update enforcement_decision set explanation = 'rewritten'"))
                .rootCause()
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbc.update("delete from enforcement_decision"))
                .rootCause()
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("the application role still cannot update or delete after the rename")
    void theRevokedGrantsSurvive() {
        // Layer two, which the rename dropped along with the table. Asserted as the role that
        // serves traffic, because that is the only role the grants bind — the owner would pass
        // this whatever the grants said.
        JdbcTemplate asApplication = new JdbcTemplate(new SingleConnectionDataSource(
                POSTGRES.getJdbcUrl(), "uds_consent_app", "uds_consent_app", true));

        assertThatThrownBy(() -> asApplication.update(
                "update enforcement_decision set explanation = 'rewritten'"))
                .rootCause()
                .hasMessageMatching("(?s).*(permission denied|append-only).*");
    }

    @Test
    @DisplayName("a partition can be provisioned by the role that will actually provision it")
    void theSweeperWorksAsTheApplicationRole() {
        // The assertion this suite existed for four phases without. PartitionStore issued
        // `create table ... partition of` through the @Primary EntityScopedDataSource — the
        // application role — and creating a partition requires OWNERSHIP of the parent, so the
        // sweeper failed on every pass in any deployment whose two roles were genuinely separate.
        // Every other test here connects as the owner, which is exactly why nobody saw it.
        //
        // PartitionStore is constructed here against an application-role connection rather than
        // injected, because the injected one is wired to the owner and would pass whatever the
        // code did. The FIRST draft of this test called uds_ensure_enforcement_partition directly
        // and passed with PartitionStore reverted to raw DDL — asserting that the function works
        // rather than that the platform uses it, which is the defect class this programme keeps
        // finding. Going through the store is the whole point.
        PartitionStore asApplication = new PartitionStore(new SingleConnectionDataSource(
                POSTGRES.getJdbcUrl(), "uds_consent_app", "uds_consent_app", true));

        // Far enough out that no other test in this suite has provisioned it.
        LocalDate month = LocalDate.of(2031, 7, 1);
        String partition = "enforcement_decision_2031_07";

        assertThat(asApplication.ensureMonthlyPartition("enforcement_decision", month))
                .withFailMessage("the application role could not provision a partition. If this is "
                        + "a permission error, PartitionStore is issuing DDL directly again and "
                        + "the sweeper is broken in every correctly-separated deployment")
                .isTrue();

        assertThat(jdbc.queryForObject(
                "select count(*) from pg_class where relname = ?", Long.class, partition))
                .isEqualTo(1L);

        // Idempotent, because the sweeper runs nightly on every replica.
        assertThat(asApplication.ensureMonthlyPartition("enforcement_decision", month)).isFalse();

        // A second partitioned table needs a second function. Failing loudly here is how that gets
        // noticed rather than silently creating nothing.
        assertThatThrownBy(() -> asApplication.ensureMonthlyPartition("consent_event", month))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no partition-maintenance function is registered");
    }

    @Test
    @DisplayName("no partition grants the application role update or delete")
    void everyPartitionIsRevokedNotJustTheParent() {
        // Derived from pg_inherits rather than listed, because the set grows by one a month and a
        // hand-written list would be stale before the migration that wrote it shipped.
        //
        // V28 revoked update/delete/truncate on the PARENT and granted nothing on the children —
        // because it did not need to. The provisioning scripts' `alter default privileges` grant
        // all four on anything the owner creates, and PostgreSQL checks DML privileges on the
        // relation NAMED IN THE QUERY. So `update enforcement_decision` was refused and
        // `update enforcement_decision_2026_08` was permitted, and only the row trigger stopped
        // the write. One of two layers, not none — and the reason two exist is that either can be
        // got wrong.
        List<String> writable = jdbc.queryForList("""
                        select c.relname
                          from pg_inherits i
                          join pg_class c on c.oid = i.inhrelid
                          join pg_class p on p.oid = i.inhparent
                          join pg_namespace n on n.oid = p.relnamespace
                         where p.relname = 'enforcement_decision'
                           and n.nspname = 'public'
                           and (has_table_privilege('uds_consent_app', c.oid, 'UPDATE')
                             or has_table_privilege('uds_consent_app', c.oid, 'DELETE'))
                         order by c.relname
                        """, String.class);

        assertThat(writable)
                .withFailMessage("""
                        the application role holds UPDATE or DELETE on these partitions of \
                        enforcement_decision: %s.

                        Only the row trigger is refusing the write. The revoke belongs in \
                        uds_ensure_enforcement_partition (V34) so a partition arrives with it, \
                        not in a list somebody maintains monthly.""", writable)
                .isEmpty();
    }

    @Test
    @DisplayName("the sweeper provisions months ahead, and is idempotent")
    void partitionsAreProvisionedAhead() {
        // Three months of headroom is what turns "the sweeper stopped" from a silent degradation
        // into something visible for a quarter. At one month, a job broken for five weeks is
        // already writing to the default partition with nothing reporting it.
        sweeper.run(Instant.now());

        List<String> partitions = jdbc.queryForList(
                "select relname from pg_class where relname like 'enforcement_decision_2%' "
                        + "order by relname", String.class);

        assertThat(partitions).hasSizeGreaterThanOrEqualTo(4);

        // Running twice must not error. This fires nightly on every replica; an exception on the
        // second pass would be a nightly ERROR line that people learn to ignore, which is how the
        // real one gets ignored too.
        assertThat(sweeper.run(Instant.now())).isEmpty();

        // And it reaches forward, not just around today.
        long future = Instant.now().plus(60, ChronoUnit.DAYS).atZone(java.time.ZoneOffset.UTC)
                .getMonthValue();
        assertThat(partitions.stream()
                .anyMatch(name -> name.endsWith(String.format("_%02d", future))))
                .withFailMessage("no partition exists for two months out; the headroom that makes "
                        + "a stalled sweeper visible is not there")
                .isTrue();
    }

    @Test
    @DisplayName("consent_event is deliberately not partitioned, and its constraints are intact")
    void theLedgerKeptItsGuarantees() {
        // Pinned so that nobody finishes the job later without meeting V28's argument. Partitioning
        // consent_event by recorded_at would force both of these uniques to admit the partition
        // key — at which point the chain could fork across a month boundary and a retried offline
        // capture crossing midnight on the first could be recorded twice.
        assertThat(jdbc.queryForObject(
                "select relkind = 'p' from pg_class where relname = 'consent_event'", Boolean.class))
                .withFailMessage("consent_event has been partitioned. Check that "
                        + "uq_consent_event_chain and uq_consent_event_idempotency still mean what "
                        + "they meant — see V28's header before assuming they do")
                .isFalse();

        List<String> uniques = jdbc.queryForList(
                "select conname from pg_constraint where conrelid = 'consent_event'::regclass "
                        + "and contype = 'u' order by conname", String.class);

        assertThat(uniques)
                .contains("uq_consent_event_chain", "uq_consent_event_idempotency");
    }

    private void insertDecision(Instant recordedAt) {
        jdbc.update("""
                        insert into enforcement_decision
                            (entity_id, subject_id, purpose_code, purpose_version, jurisdiction,
                             outcome, reason, policy_version, decided_at, recorded_at)
                        values ('DENAVE_IN', ?, 'MKT_OUTBOUND_CALL', 1, 'IN', 'DENY',
                                'CONSENT_WITHDRAWN', 'partition-suite', ?, ?)
                        """,
                "part-" + UUID.randomUUID(),
                java.sql.Timestamp.from(recordedAt),
                java.sql.Timestamp.from(recordedAt));
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
