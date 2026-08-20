package com.uds.consent.service.sweeper;

import com.uds.consent.ledger.store.SweepRunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Runs a sweep on one instance at a time.
 *
 * <p>Every replica of this service schedules every sweeper, which is fine for exactly one replica
 * and wrong for two. The failure modes differ by sweep and none of them is loud: the expiry sweeper
 * survives duplication on its idempotency key and merely does the work twice; the integrity sweep
 * verifies the whole chain once per replica, which is the most expensive job the platform runs; and
 * the rights-SLA sweep pages the on-call rota once per replica for the same breach, which is how a
 * genuine alert gets muted as noise.
 *
 * <p>Implemented with a PostgreSQL session-level advisory lock rather than a scheduler-lock
 * library. The database is already the thing every instance shares, the lock is released
 * automatically if the instance holding it dies mid-sweep, and it adds no dependency — which
 * matters here, because the build resolves offline.
 *
 * <p><strong>The connection is held open for the duration of the sweep, deliberately.</strong>
 * Advisory locks taken by {@code pg_try_advisory_lock} are scoped to the session, so acquiring on
 * one pooled connection and releasing on another would leave the lock held forever by a connection
 * nobody can identify — and every subsequent sweep on every instance would skip, silently, until
 * someone restarted the database. Borrowing one connection and doing both on it is the only way to
 * make the pairing true. The transactional variant would avoid this, but it releases at commit,
 * which would mean wrapping the whole integrity sweep in a single long transaction.
 *
 * <p>A sweep that cannot take the lock does not queue and does not retry. It returns, and the next
 * tick tries again. These are periodic reconciliations rather than tasks: skipping one because
 * another instance is already doing it is the correct outcome, not a deferral.
 *
 * <p><strong>Every sweep that runs here is recorded in {@code sweep_run}.</strong> That is one
 * change covering all eight jobs, and a ninth the day somebody writes it — which is the reason it
 * lives here rather than in each sweeper. Until it did, a silently dead sweeper was invisible: it
 * writes nothing, fails nothing and alerts nothing, and the evidence plane goes quietly incomplete
 * while every decision stays correct.
 *
 * <p>The record is written by whichever instance won the lock, into the database all three share.
 * Holding it in memory would have read "never ran" on the two that skipped, permanently. See
 * {@link SweepRunStore}.
 */
@Component
public class SweepLock {

    private static final Logger log = LoggerFactory.getLogger(SweepLock.class);

    /**
     * Namespace for the platform's advisory locks.
     *
     * <p>Advisory lock keys are global to the database and shared with anything else connected to
     * it. The two-argument form partitions the space, so a lock taken here cannot collide with one
     * taken by an unrelated tool pointed at the same instance.
     */
    private static final int NAMESPACE = 0x554453; // 'UDS'

    private final DataSource dataSource;
    private final SweepRunStore runs;
    private final String instance = hostname();

    public SweepLock(DataSource dataSource, SweepRunStore runs) {
        this.dataSource = dataSource;
        this.runs = runs;
    }

    /**
     * Runs {@code sweep} if no other instance holds the lock for {@code name}.
     *
     * @return whether the sweep ran here
     */
    public boolean runExclusively(String name, Runnable sweep) {
        int key = name.hashCode();
        try (Connection connection = dataSource.getConnection()) {
            if (!tryLock(connection, key)) {
                log.debug("{} sweep skipped: another instance holds the lock", name);
                return false;
            }
            record(() -> runs.started(name, instance, Instant.now()), name);
            boolean ok = false;
            try {
                sweep.run();
                ok = true;
                return true;
            } finally {
                // Before the unlock, so a sweep that threw is still recorded as having run and
                // failed. A FAILED row is a different fault from a missing one: one says the job
                // is scheduled and broken, the other says nothing is scheduling it at all.
                boolean outcome = ok;
                record(() -> runs.finished(name, Instant.now(), outcome), name);
                // In a finally block rather than after the call: an unlock that only runs on the
                // happy path stops running the first time a sweep throws, and closing the
                // connection would return it to the pool still holding the lock.
                unlock(connection, key);
            }
        } catch (SQLException e) {
            // Deliberately not rethrown. A sweeper that cannot reach the database has nothing to
            // sweep, and letting this propagate out of a @Scheduled method logs a stack trace that
            // says nothing the next tick will not say better.
            log.error("could not acquire the {} sweep lock: {}", name, e.getMessage());
            return false;
        }
    }

    /**
     * Runs a bookkeeping write, swallowing any failure.
     *
     * <p>Deliberately not allowed to propagate. This table is telemetry about the sweep, not the
     * sweep's own work, and letting a failed write abort a retention pass or an integrity sweep
     * would trade the thing that matters for the thing that describes it. Copied from
     * {@code EnforcementRecorder}'s posture on failed evidence writes, and visible the same way:
     * at WARN, and as a stale age on the gauge, which is what the alert is watching anyway.
     */
    private void record(Runnable write, String name) {
        try {
            write.run();
        } catch (RuntimeException e) {
            log.warn("could not record the {} sweep run: {}", name, e.getMessage());
        }
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            // Not worth failing over. The column answers "which replica is wedged", and an
            // unknown answer there is better than a sweeper that will not start.
            return "unknown";
        }
    }

    private static boolean tryLock(Connection connection, int key) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("select pg_try_advisory_lock(?, ?)")) {
            statement.setInt(1, NAMESPACE);
            statement.setInt(2, key);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static void unlock(Connection connection, int key) {
        try (PreparedStatement statement =
                     connection.prepareStatement("select pg_advisory_unlock(?, ?)")) {
            statement.setInt(1, NAMESPACE);
            statement.setInt(2, key);
            statement.execute();
        } catch (SQLException e) {
            // Worth an ERROR rather than a warning. The connection is about to go back to the pool
            // still holding the lock, and every later sweep of this kind will skip until it is
            // closed — which looks exactly like a sweeper that has quietly stopped working.
            log.error("failed to release the advisory lock for key {}; sweeps of this kind will "
                    + "skip until the holding connection is closed", key, e);
        }
    }
}
