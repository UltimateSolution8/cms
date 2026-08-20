package com.uds.consent.ledger.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Date;
import java.time.LocalDate;

/**
 * Creates the monthly partitions {@code enforcement_decision} needs before the month arrives.
 *
 * <p>DDL from the application, which is unusual here and deliberate. Everything else structural is
 * a Flyway migration, because schema changes are reviewed. A partition is not a schema change: it
 * is the same shape repeated on a calendar, and putting it in migrations would mean shipping a
 * release every quarter whose entire content is four {@code CREATE TABLE} statements — which is a
 * release nobody reviews, and eventually one nobody remembers to cut.
 *
 * <p>The application role cannot do this, and for four phases this class issued the DDL through it
 * anyway. Creating a partition requires <em>ownership of the parent</em> — not {@code CREATE} on
 * the schema, which is what this javadoc used to claim, pointing at a grant in {@code OPERATIONS.md}
 * that has never existed. So {@code PartitionMaintenanceSweeper}
 * failed on every pass in any deployment whose two roles were genuinely separate, and nothing saw
 * it because the Compose stack and every integration test connect as the owner.
 *
 * <p>Creation now goes through {@code uds_ensure_enforcement_partition}, a {@code SECURITY DEFINER}
 * function introduced in {@code V34} with the parent table hardcoded. {@code V34}'s header carries
 * the argument for that shape over the obvious alternative — a second, owner-credentialled data
 * source inside the application, which would bypass every V13 policy for whatever else picked the
 * bean up.
 *
 * <p>The reads below need no such treatment: {@code SELECT} on a partition reaches the application
 * role through the provisioning script's default privileges.
 */
@Repository
public class PartitionStore {

    /**
     * The only parent with a registered maintenance function. Named rather than inlined so the
     * refusal above and any future second function are visibly one decision.
     */
    private static final String ENFORCEMENT_DECISION = "enforcement_decision";

    private final JdbcClient jdbc;

    public PartitionStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * Ensures a partition exists for one month.
     *
     * <p>The function this delegates to also revokes {@code UPDATE}, {@code DELETE} and
     * {@code TRUNCATE} on the partition it creates. The parent's revoke does not reach a child:
     * PostgreSQL checks DML privileges on the relation named in the query, and the provisioning
     * script's {@code alter default privileges} grants all four on anything the owner creates. The
     * row triggers still refuse the write, so this is the second of two layers rather than the only
     * one — {@code V34}'s header records the measurement that established which.
     *
     * @param table the parent. Must be {@code enforcement_decision}: the function hardcodes its
     *              table deliberately, so a second partitioned table needs a second function, and
     *              failing loudly here is how that gets noticed rather than silently skipped
     * @return whether it was created. False means it already existed, which is the ordinary case
     *         on every pass after the first and is not worth logging
     */
    public boolean ensureMonthlyPartition(String table, LocalDate month) {
        if (!ENFORCEMENT_DECISION.equals(table)) {
            throw new IllegalArgumentException(
                    "no partition-maintenance function is registered for '" + table
                            + "'. Partition creation runs through a SECURITY DEFINER function "
                            + "whose parent table is hardcoded (V34); a new partitioned table "
                            + "needs its own function in its own migration.");
        }

        Boolean created = jdbc.sql("select uds_ensure_enforcement_partition(:month)")
                .param("month", Date.valueOf(month.withDayOfMonth(1)))
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(created);
    }

    /**
     * Rows that landed in the default partition.
     *
     * <p>Should always be zero. Anything above it means a month was written without a partition to
     * hold it — the evidence is safe, which is what the default is for, and the pruning the
     * partitioning exists to buy has quietly stopped for those rows.
     */
    public long defaultPartitionRows(String table) {
        String defaultPartition = table + "_default";
        Boolean exists = jdbc.sql("select exists (select 1 from pg_class where relname = :name)")
                .param("name", defaultPartition)
                .query(Boolean.class)
                .single();
        if (!Boolean.TRUE.equals(exists)) {
            return 0;
        }
        Long count = jdbc.sql("select count(*) from " + defaultPartition)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    /**
     * How many whole months of partitions exist beyond the current one.
     *
     * <p>The sweeper provisions three months ahead and creates rather than drops, so this number
     * should sit at three and only ever falls because the job stopped running. That is the failure
     * worth measuring: it is silent for a quarter and then, on the first of some month, every
     * denial fails to record.
     *
     * <p>Compared as strings, which works because the names are zero-padded {@code _YYYY_MM} and
     * therefore sort chronologically. The default partition is excluded by name — {@code _default}
     * sorts after every year and would otherwise be counted as a month of runway that does not
     * exist.
     */
    public int monthsProvisionedAhead(String table, LocalDate asOf) {
        LocalDate current = asOf.withDayOfMonth(1);
        String currentSuffix = table + "_" + current.getYear() + "_"
                + String.format("%02d", current.getMonthValue());

        Integer ahead = jdbc.sql("""
                        select count(*)
                          from pg_class child
                          join pg_inherits on pg_inherits.inhrelid = child.oid
                          join pg_class parent on parent.oid = pg_inherits.inhparent
                         where parent.relname = :table
                           and child.relname > :current
                           and child.relname <> :defaultPartition
                        """)
                .param("table", table)
                .param("current", currentSuffix)
                .param("defaultPartition", table + "_default")
                .query(Integer.class)
                .single();
        return ahead == null ? 0 : ahead;
    }
}
