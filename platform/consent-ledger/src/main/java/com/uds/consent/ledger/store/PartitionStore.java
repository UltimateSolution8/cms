package com.uds.consent.ledger.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
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
 * <p>The application role cannot do this. Partition creation runs as the owner, the same role
 * Flyway uses, so a deployment that has correctly separated the two will find this failing with a
 * permission error rather than silently succeeding. That is the right way round: see
 * {@code OPERATIONS.md} for the grant, which is narrow — {@code CREATE} on the schema, nothing
 * more.
 */
@Repository
public class PartitionStore {

    private final JdbcClient jdbc;

    public PartitionStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * Ensures a partition exists for one month.
     *
     * @return whether it was created. False means it already existed, which is the ordinary case
     *         on every pass after the first and is not worth logging
     */
    public boolean ensureMonthlyPartition(String table, LocalDate month) {
        LocalDate start = month.withDayOfMonth(1);
        String name = table + "_" + start.getYear() + "_"
                + String.format("%02d", start.getMonthValue());

        Boolean exists = jdbc.sql("select exists (select 1 from pg_class where relname = :name)")
                .param("name", name)
                .query(Boolean.class)
                .single();
        if (Boolean.TRUE.equals(exists)) {
            return false;
        }

        // Identifiers are derived from a date, not from any caller input, so there is nothing here
        // an attacker could shape. Written as a formatted statement because PostgreSQL takes no
        // parameters in DDL — and the bound-parameter habit is worth breaking loudly rather than
        // quietly, hence this comment.
        jdbc.sql("create table " + name + " partition of " + table
                        + " for values from ('" + start + "') to ('" + start.plusMonths(1) + "')")
                .update();
        return true;
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
