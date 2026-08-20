package com.uds.consent.ledger.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * When each scheduled sweep last ran, durably.
 *
 * <p><strong>Why the database and not a field on each sweeper.</strong> {@code SweepLock} runs a
 * sweep on one instance at a time through a PostgreSQL advisory lock, and the shipped deployment
 * runs three replicas. An in-memory timestamp would therefore read "never ran" on the two instances
 * that skipped, permanently — so an alert over the maximum age would fire forever and be muted
 * inside a week. That is the failure the propagation register was redesigned to avoid before it
 * shipped: a control whose alert can never clear is not a control. The record has to live in the
 * thing all three instances share.
 *
 * <p>Current state, one row per sweep, upserted — deliberately not append-only. {@code V32}'s header
 * carries the argument; the short version is that a relay ticking every two seconds would otherwise
 * write ~43,000 rows a day to answer a question one row answers.
 */
@Repository
public class SweepRunStore {

    private final JdbcClient jdbc;

    public SweepRunStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records that {@code sweepName} began on this instance.
     *
     * <p>{@code last_finished_at} is cleared, so a sweep that dies mid-run leaves a started-and-never
     * -finished row rather than looking like one that simply has not been scheduled. Those are
     * different faults and a single "last run" column cannot tell them apart.
     */
    public void started(String sweepName, String instance, Instant at) {
        jdbc.sql("""
                        insert into sweep_run (sweep_name, last_started_at, last_finished_at,
                                               last_ran_on, last_outcome)
                        values (:name, :at, null, :instance, 'OK')
                        on conflict (sweep_name) do update
                           set last_started_at = excluded.last_started_at,
                               last_finished_at = null,
                               last_ran_on = excluded.last_ran_on,
                               last_outcome = 'OK'
                        """)
                .param("name", sweepName)
                .param("at", Timestamp.from(at))
                .param("instance", instance)
                .update();
    }

    /** Records the outcome. {@code ok} false means the sweep body threw; the lock released either way. */
    public void finished(String sweepName, Instant at, boolean ok) {
        jdbc.sql("""
                        update sweep_run
                           set last_finished_at = :at, last_outcome = :outcome
                         where sweep_name = :name
                        """)
                .param("name", sweepName)
                .param("at", Timestamp.from(at))
                .param("outcome", ok ? "OK" : "FAILED")
                .update();
    }

    /** Every sweep the platform has a record for. Empty until the first sweep of each kind runs. */
    public List<Run> all() {
        return jdbc.sql("""
                        select sweep_name, last_started_at, last_finished_at, last_ran_on,
                               last_outcome
                          from sweep_run
                         order by sweep_name
                        """)
                .query((rs, n) -> new Run(rs.getString("sweep_name"),
                        rs.getTimestamp("last_started_at").toInstant(),
                        rs.getTimestamp("last_finished_at") == null
                                ? null : rs.getTimestamp("last_finished_at").toInstant(),
                        rs.getString("last_ran_on"),
                        rs.getString("last_outcome")))
                .list();
    }

    /** One sweep's record, or empty where it has never run on any instance. */
    public Optional<Run> find(String sweepName) {
        return all().stream().filter(r -> r.sweepName().equals(sweepName)).findFirst();
    }

    /**
     * @param lastFinishedAt null while a sweep is in flight, and null after one that died mid-run
     * @param lastOutcome    {@code OK} or {@code FAILED} — a sweep that throws every tick otherwise
     *                       looks identical to one that succeeds every tick
     */
    public record Run(String sweepName, Instant lastStartedAt, Instant lastFinishedAt,
                      String lastRanOn, String lastOutcome) {

        /**
         * How long since this sweep last <em>finished</em>, as at {@code now}.
         *
         * <p>Null where it has never finished — which a caller must render as "unknown" and never
         * as zero. A gauge that reports a sweep which has never run as {@code 0} is
         * indistinguishable from one that just completed, which is the whole defect this table
         * exists to close.
         */
        public Long ageSeconds(Instant now) {
            return lastFinishedAt == null ? null : now.getEpochSecond() - lastFinishedAt.getEpochSecond();
        }
    }
}
