package com.uds.consent.service.it;

import com.uds.consent.core.model.ActorType;
import com.uds.consent.core.model.CaptureMethod;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.ConsentEvent;
import com.uds.consent.core.model.ConsentEventType;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.LegalBasis;
import com.uds.consent.ledger.service.ConsentLedger;
import com.uds.consent.ledger.service.LedgerIntegrityVerifier;
import com.uds.consent.ledger.store.StoredEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The evidence plane's central claim, tested against the thing that actually enforces it.
 *
 * <p>Everything the platform says about consent rests on one assertion: the ledger has not been
 * edited. That assertion is only worth as much as its weakest layer, so all three are exercised
 * here — the triggers that reject an UPDATE, the behaviour under a superuser who disables them,
 * and the hash chain that notices afterwards.
 */
class LedgerAppendOnlyIT extends PostgresIntegrationTest {

    private static final String ENTITY = "DENAVE_IN";
    private static final Instant NOW = Instant.parse("2026-08-15T09:00:00Z");

    /**
     * Every table this platform has declared append-only by revoking the application role's
     * {@code UPDATE} and {@code DELETE}.
     *
     * <p>Written out by hand, and that is the point. Until Phase 18 this suite named three tables
     * — {@code consent_event}, {@code notice_version} and {@code admin_audit_event} — while
     * thirteen carried a revoke. So the guarantee rules 1 opens with was proven for three and
     * taken on trust for ten, including every evidence table added since V11:
     * {@code consent_receipt} (V11), {@code subject_age_assertion} (V18), {@code subject_alias}
     * (V24), {@code rights_fulfilment_action} (V26), {@code webhook_delivery} (V27) and
     * {@code propagation_gap} (V31) all arrived without anything asserting their guard held.
     *
     * <p>The list sits beside {@link #everyRevokedTableActuallyRefuses()}, which derives the same
     * question from the catalogue. Two assertions of opposite shape, the arrangement
     * {@code RowLevelSecurityIT} already uses: this one fails when a migration forgets the revoke,
     * that one fails when a later migration hands the privilege back — which is not hypothetical,
     * because V28 had to re-apply V8's revoke after re-creating {@code enforcement_decision} as a
     * partitioned table, and nothing would have noticed had it not.
     *
     * <p>{@code rights_request_verification} (V29) is deliberately absent. Its attempt counter and
     * consumption flag are mutable state, not evidence — the evidence is the {@code rights_request}
     * the verification produces — and V29 says so in its own header. A reader comparing this list
     * against the catalogue needs to find that written down rather than read it as an omission.
     *
     * <p>{@code sweep_run} (V32) is absent for the same reason and V32's header says so: it records
     * when each scheduled job last ran, is upserted one row per sweep, and is operational telemetry
     * rather than evidence about a data principal. The append-only alternative is one row per sweep
     * per tick — the relay alone would write ~43,000 a day to answer what one row answers.
     * {@code propagation_system} (V33) is likewise absent: a vocabulary an operator must be able to
     * retire and re-describe is configuration, and {@code DELETE} is what is withheld there, because
     * a code named by a {@code propagation_gap} row cannot be removed without orphaning evidence.
     */
    private static final List<String> APPEND_ONLY_TABLES = List.of(
            // V2 - the V1-era tables, guarded by trigger functions as well as by the revoke.
            "consent_event", "admin_audit_event", "notice_version", "notice_translation",
            "purpose_version",
            // V8, and enforcement_decision re-revoked by V28 after partitioning.
            "enforcement_decision", "scrub_run",
            // V11 onwards - guarded by the revoke alone, which is the sibling pattern to copy.
            "consent_receipt", "subject_age_assertion", "subject_alias",
            "rights_fulfilment_action", "webhook_delivery", "propagation_gap");

    @Autowired
    private ConsentLedger ledger;

    @Autowired
    private LedgerIntegrityVerifier verifier;

    @Autowired
    private JdbcTemplate jdbc;

    // -------------------------------------------------------------------------------------------
    // Layer 1 — the database refuses
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("UPDATE on a consent event is rejected by the database, not by convention")
    void updateIsRejected() {
        String subject = newSubject();
        ConsentEvent stored = ledger.record(grant(subject, "MKT_OUTBOUND_CALL", NOW));

        assertThatThrownBy(() -> jdbc.update(
                "update consent_event set event_type = 'GRANTED' where event_id = ?::uuid",
                stored.eventId()))
                .hasStackTraceContaining("append-only");

        // And the row is untouched, which is the part that matters. A guard that raises after the
        // write would be worse than none.
        assertThat(jdbc.queryForObject(
                "select event_type from consent_event where event_id = ?::uuid", String.class,
                stored.eventId()))
                .isEqualTo(ConsentEventType.GRANTED.name());
    }

    @Test
    @DisplayName("DELETE on a consent event is rejected")
    void deleteIsRejected() {
        String subject = newSubject();
        ConsentEvent stored = ledger.record(grant(subject, "MKT_OUTBOUND_CALL", NOW));

        assertThatThrownBy(() -> jdbc.update("delete from consent_event where event_id = ?::uuid",
                stored.eventId()))
                .hasStackTraceContaining("append-only");

        assertThat(ledger.history(ENTITY, subject)).hasSize(1);
    }

    @Test
    @DisplayName("TRUNCATE of the ledger is rejected")
    void truncateIsRejected() {
        assertThatThrownBy(() -> jdbc.execute("truncate table consent_event"))
                .hasStackTraceContaining("append-only");
    }

    @Test
    @DisplayName("a published notice version cannot be edited after the fact")
    void publishedNoticeVersionsAreImmutable() {
        // Reproducing exactly what a subject read in 2026 is the evidence plane's hardest job.
        // An editable notice version would quietly destroy that, and nothing would look wrong.
        assertThatThrownBy(() -> jdbc.update(
                "update notice_version set withdrawal_uri = 'https://example.invalid/edited' "
                        + "where id = (select min(id) from notice_version)"))
                .hasStackTraceContaining("append-only");
    }

    @Test
    @DisplayName("the administrative audit trail is immutable too")
    void adminAuditIsImmutable() {
        jdbc.update("""
                insert into admin_audit_event (actor_id, action, entity_id, target_type, target_id,
                                               detail, occurred_at)
                values ('tester', 'PURPOSE_PUBLISHED', 'DENAVE_IN', 'purpose',
                        'MKT_OUTBOUND_CALL', '{}'::jsonb, now())
                """);

        assertThatThrownBy(() -> jdbc.update(
                "update admin_audit_event set actor_id = 'someone-else' "
                        + "where actor_id = 'tester'"))
                .hasStackTraceContaining("append-only");
    }

    @Test
    @DisplayName("every table declared append-only refuses UPDATE and DELETE as the application role")
    void everyDeclaredAppendOnlyTableRefuses() {
        // As uds_consent_app, which is the only role whose refusal means anything: the owner can
        // edit history by design, so a test running as the owner proves nothing about production.
        //
        // The statements are syntactically valid and semantically harmless - `where false` touches
        // no row - so a table that failed to refuse reports success rather than corrupting the
        // suite's fixtures. That is deliberate: this assertion must be able to fail without
        // damaging the evidence plane it is asserting about.
        JdbcTemplate application = asApplication();
        for (String table : APPEND_ONLY_TABLES) {
            // A column that exists on this table, read from the catalogue rather than assumed.
            // `set c = c` is a no-op assignment, so the statement is meaningful only as a
            // privilege question — and an assumed column name would fail on the parse instead,
            // which passes the assertion for the wrong reason.
            String column = jdbc.queryForObject(
                    "select column_name from information_schema.columns "
                            + "where table_schema = 'public' and table_name = ? "
                            + "order by ordinal_position limit 1", String.class, table);

            assertThatThrownBy(() -> application.update(
                    "update " + table + " set " + column + " = " + column + " where false"))
                    .describedAs("UPDATE on %s must be refused for uds_consent_app", table)
                    .hasStackTraceContaining("permission denied");
            assertThatThrownBy(() -> application.update("delete from " + table + " where false"))
                    .describedAs("DELETE on %s must be refused for uds_consent_app", table)
                    .hasStackTraceContaining("permission denied");
        }
    }

    @Test
    @DisplayName("nothing has quietly handed the privilege back to the application role")
    void everyRevokedTableActuallyRefuses() {
        // The other half, derived rather than remembered. The list above catches a migration that
        // forgets to revoke; this catches one that re-grants. V28 re-created enforcement_decision
        // as a partitioned table and had to re-apply V8's revoke - had it not, the hand-written
        // list would still have named the table and nothing would have failed.
        //
        // The isNotEmpty guard is in front of it because a query returning nothing would pass
        // vacuously, which is the failure this assertion shape is otherwise prone to.
        List<String> revoked = jdbc.queryForList(
                "select c.relname from pg_class c "
                        + "join pg_namespace n on n.oid = c.relnamespace "
                        + "where n.nspname = 'public' and c.relkind in ('r', 'p') "
                        + "and not has_table_privilege('uds_consent_app', c.oid, 'UPDATE')",
                String.class);

        assertThat(revoked)
                .describedAs("the catalogue must report revoked tables, or this passes vacuously")
                .isNotEmpty()
                .containsAll(APPEND_ONLY_TABLES);
    }

    // -------------------------------------------------------------------------------------------
    // Layer 3 — the chain notices what got past the others
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a subject's chain verifies end to end across several events")
    void chainVerifiesEndToEnd() {
        String subject = newSubject();
        ledger.record(grant(subject, "MKT_OUTBOUND_CALL", NOW));
        ledger.record(grant(subject, "MKT_OUTBOUND_EMAIL", NOW.plus(1, ChronoUnit.MINUTES)));
        ledger.record(withdraw(subject, "MKT_OUTBOUND_CALL", NOW.plus(2, ChronoUnit.MINUTES)));

        LedgerIntegrityVerifier.ChainVerification verification =
                verifier.verifyChain(ENTITY, subject);

        assertThat(verification.eventsChecked()).isEqualTo(3);
        assertThat(verification.intact()).isTrue();

        List<StoredEvent> chain = ledger.history(ENTITY, subject);
        assertThat(chain.get(0).event().previousHash()).isEqualTo(ConsentEvent.GENESIS_HASH);
        assertThat(chain.get(1).event().previousHash()).isEqualTo(chain.get(0).event().eventHash());
        assertThat(chain.get(2).event().previousHash()).isEqualTo(chain.get(1).event().eventHash());
        assertThat(chain).extracting(e -> e.event().sequenceNumber()).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("an event captured at nanosecond precision still re-serialises to its payload")
    void subMicrosecondPrecisionDoesNotLookLikeTampering() {
        // Found by running the platform by hand, and it could not have been found any other way
        // here: every other test in this suite passes a fixed instant or one truncated to seconds,
        // so nothing in the build had ever carried a nanosecond component into the ledger. In
        // production every event does — `Instant.now()` on a modern JVM has nanosecond resolution
        // and PostgreSQL's timestamptz stores microseconds.
        //
        // The result was not a broken chain. The chain hashes the stored payload and the payload
        // is stored verbatim, so `intact()` stayed true — which is precisely why nobody noticed.
        // What broke was the PAYLOAD_DIVERGENCE check, the one that exists to catch somebody
        // editing the structured columns without being able to forge the payload. It fired on
        // every event ever written, and a detector that fires on everything detects nothing.
        // Two sources of sub-microsecond precision, and both had to be closed. `occurredAt` comes
        // from the caller, so the fixture supplies one with nanoseconds. `recordedAt` is
        // `Instant.now()` inside the store, which nothing here controls — hence ten events rather
        // than one: the store's value used to be *rounded* into the column and *truncated* into
        // the payload, so any single event had roughly even odds of agreeing by luck. Ten leaves
        // about one chance in a thousand of this test passing for the wrong reason.
        String subject = newSubject();
        Instant withNanos = Instant.parse("2026-08-15T09:00:00Z").plusNanos(123_456_789);
        assertThat(withNanos.getNano() % 1_000)
                .withFailMessage("the fixture lost its sub-microsecond digits; the test is vacuous")
                .isNotZero();

        for (int i = 0; i < 10; i++) {
            ledger.record(grant(subject, "MKT_OUTBOUND_CALL",
                    withNanos.plus(i, ChronoUnit.MINUTES)));
        }

        LedgerIntegrityVerifier.ChainVerification verification =
                verifier.verifyChain(ENTITY, subject);

        assertThat(verification.findings())
                .withFailMessage("""
                        a freshly written, untampered event was reported as diverging from its \
                        own payload: %s. What is hashed must be what the evidence plane can \
                        store, or this check is noise forever.""", verification.findings())
                .isEmpty();
        assertThat(verification.intact()).isTrue();

        // And the truncation is visible rather than silent, so a reader comparing the receipt
        // against the ledger sees the same instant in both.
        assertThat(ledger.history(ENTITY, subject).get(0).event().occurredAt())
                .isEqualTo(withNanos.truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    @DisplayName("a superuser who disables the triggers and rewrites history is still caught")
    void tamperingBehindTheTriggersIsDetected() {
        // This is the scenario the hash chain exists for. Triggers and grants stop the application
        // and an ordinary operator; they do not stop someone who can set session_replication_role.
        // What that person cannot do is rewrite one row without invalidating every hash after it.
        String subject = newSubject();
        ledger.record(grant(subject, "MKT_OUTBOUND_CALL", NOW));
        ConsentEvent withdrawal =
                ledger.record(withdraw(subject, "MKT_OUTBOUND_CALL", NOW.plus(1, ChronoUnit.HOURS)));

        assertThat(verifier.verifyChain(ENTITY, subject).intact()).isTrue();

        // The most valuable single edit an attacker could make: turn a withdrawal into a grant so
        // the dialer starts calling again.
        asSuperuserBypassingTriggers("update consent_event set canonical_payload = "
                        + "replace(canonical_payload, '\"WITHDRAWN\"', '\"GRANTED\"') "
                        + "where event_id = ?::uuid",
                withdrawal.eventId());

        LedgerIntegrityVerifier.ChainVerification verification =
                verifier.verifyChain(ENTITY, subject);

        assertThat(verification.intact()).isFalse();
        assertThat(verification.tampered()).isTrue();
        assertThat(verification.findings())
                .extracting(LedgerIntegrityVerifier.Finding::type)
                .contains(LedgerIntegrityVerifier.FindingType.HASH_MISMATCH);
    }

    @Test
    @DisplayName("a removed event leaves a sequence gap the sweep reports")
    void removedEventIsDetected() {
        String subject = newSubject();
        ledger.record(grant(subject, "MKT_OUTBOUND_CALL", NOW));
        ConsentEvent second =
                ledger.record(grant(subject, "MKT_OUTBOUND_EMAIL", NOW.plus(1, ChronoUnit.MINUTES)));
        ledger.record(withdraw(subject, "MKT_OUTBOUND_CALL", NOW.plus(2, ChronoUnit.MINUTES)));

        asSuperuserBypassingTriggers("delete from consent_event where event_id = ?::uuid",
                second.eventId());

        LedgerIntegrityVerifier.ChainVerification verification =
                verifier.verifyChain(ENTITY, subject);

        assertThat(verification.intact()).isFalse();
        assertThat(verification.findings())
                .extracting(LedgerIntegrityVerifier.Finding::type)
                .contains(LedgerIntegrityVerifier.FindingType.SEQUENCE_GAP,
                        LedgerIntegrityVerifier.FindingType.CHAIN_BREAK);
    }

    @Test
    @DisplayName("the sweep over every chain reports the damaged one and nothing else")
    void sweepFindsTheDamagedChain() {
        String healthy = newSubject();
        ledger.record(grant(healthy, "MKT_OUTBOUND_CALL", NOW));

        LedgerIntegrityVerifier.SweepResult before = verifier.verifyAll(50);

        String damaged = newSubject();
        ConsentEvent victim = ledger.record(grant(damaged, "MKT_OUTBOUND_CALL", NOW));

        asSuperuserBypassingTriggers("update consent_event set event_hash = repeat('a', 64) "
                + "where event_id = ?::uuid", victim.eventId());

        LedgerIntegrityVerifier.SweepResult after = verifier.verifyAll(50);

        assertThat(after.chainsChecked()).isGreaterThan(before.chainsChecked());
        assertThat(after.failures()).extracting(
                        LedgerIntegrityVerifier.ChainVerification::subjectId)
                .contains(damaged)
                .doesNotContain(healthy);
    }

    // -------------------------------------------------------------------------------------------

    /**
     * A {@code JdbcTemplate} connected as {@code uds_consent_app}, the role that serves production
     * traffic.
     *
     * <p>This distinction is the whole of what the append-only privilege assertion is worth.
     * Migrations and the suite's own fixtures run as {@code uds_consent_owner}, which can edit
     * history by design — so a revoke test running on the default template would be stopped by the
     * V2 triggers on five tables and by nothing at all on the other eight, and would report
     * success for both reasons. Connecting as the application role is what makes "the privilege was
     * removed" a fact rather than an assumption.
     *
     * <p>{@code SingleConnectionDataSource} for the same reason {@code RowLevelSecurityIT} uses
     * one: a pooled connection would carry whatever session state ran before it.
     */
    private JdbcTemplate asApplication() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                POSTGRES.getJdbcUrl(), "uds_consent_app", "uds_consent_app", true);
        dataSource.setDriverClassName(org.postgresql.Driver.class.getName());
        return new JdbcTemplate(dataSource);
    }

    /**
     * Runs a statement the triggers would otherwise reject, standing in for an attacker or a
     * careless DBA with superuser rights.
     *
     * <p>Done on one borrowed connection deliberately: {@code session_replication_role} is a
     * session setting, and issuing it through the pool would leave the bypass and the edit on
     * different connections — the test would then pass for the wrong reason, having proved only
     * that the trigger still fires.
     */
    private void asSuperuserBypassingTriggers(String sql, Object... args) {
        jdbc.execute((java.sql.Connection connection) -> {
            try (var statement = connection.createStatement()) {
                statement.execute("set session_replication_role = replica");
            }
            try (var prepared = connection.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    prepared.setObject(i + 1, args[i]);
                }
                prepared.executeUpdate();
            } finally {
                try (var statement = connection.createStatement()) {
                    statement.execute("set session_replication_role = origin");
                }
            }
            return null;
        });
    }

    /** A subject id nothing else in the suite will touch, so tests stay order-independent. */
    private static String newSubject() {
        return "it-" + UUID.randomUUID();
    }

    private static ConsentEvent grant(String subjectId, String purposeCode, Instant occurredAt) {
        return event(subjectId, purposeCode, ConsentEventType.GRANTED, occurredAt, null);
    }

    private static ConsentEvent withdraw(String subjectId, String purposeCode, Instant occurredAt) {
        return event(subjectId, purposeCode, ConsentEventType.WITHDRAWN, occurredAt, null);
    }

    private static ConsentEvent event(String subjectId, String purposeCode, ConsentEventType type,
                                      Instant occurredAt, String idempotencyKey) {
        return new ConsentEvent(UUID.randomUUID().toString(), ENTITY, subjectId, purposeCode, 1,
                type, LegalBasis.CONSENT, "NOTICE_DENAVE_B2B", 1, "en",
                CaptureMethod.CHECKBOX_OPT_IN, Channel.WEB, "DENAVE_WEB", Jurisdiction.IN,
                occurredAt, null, null, ActorType.SUBJECT, subjectId, null, null, idempotencyKey,
                Map.of(), 0L, null, null);
    }
}
