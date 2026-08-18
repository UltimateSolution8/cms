package com.uds.consent.ledger.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The Board's register of Consent Managers, and who is managed through them.
 *
 * <p>Two questions, and they are not the same question. "Is this caller a Consent Manager the Board
 * still recognises" is answered from the register, which is group-wide configuration. "Which
 * principal does this Consent Manager mean by reference X" is answered from the links, which are
 * personal data and entity-scoped. See {@code V14__consent_manager.sql}.
 *
 * <p><strong>The register is writable, narrowly.</strong> It was originally read-only through the
 * application, on the reasoning that a fiduciary able to edit the list of who is registered could
 * authorise its own relays. True, and it produced a control nobody could operate: Rule 4 lets the
 * Board suspend or cancel a registration, and reflecting that needed a DBA with schema rights at
 * whatever hour the notice arrived, left no audit record, and could not be rehearsed. A register
 * that is hard to update is a register that does not get updated, and the failure that produces —
 * honouring relays from a Consent Manager the Board removed last month — is worse than the one the
 * restriction was guarding against. So the writes are here, they are ADMIN-only at the HTTP layer,
 * and every status change is audited. See {@code V17__consent_manager_administration.sql}.
 *
 * <p>There is no delete. A Consent Manager that has ever relayed is referenced by links and by
 * events in the ledger, so removing the row would orphan the provenance of consents that are still
 * live. Deregistration is a status, which is what the Board's own vocabulary calls it.
 */
@Repository
public class ConsentManagerStore {

    private static final String SELECT_CM = """
            select registration_id, name, status, api_client_id, public_key, registered_at,
                   status_changed_at, status_reason, contact_email, last_reconciled_at,
                   last_reconciled_by
              from consent_manager
            """;

    private final JdbcClient jdbc;

    public ConsentManagerStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public Optional<ConsentManager> find(String registrationId) {
        return jdbc.sql(SELECT_CM + " where registration_id = :registrationId")
                .param("registrationId", registrationId)
                .query(ConsentManagerStore::mapManager)
                .optional();
    }

    public List<ConsentManager> findAll() {
        return jdbc.sql(SELECT_CM + " order by name")
                .query(ConsentManagerStore::mapManager)
                .list();
    }

    /**
     * Records a Consent Manager the Board has registered, or corrects an entry already held.
     *
     * <p>Upsert rather than insert-or-fail. Loading the register is a repeated act — the Board
     * publishes, somebody transcribes, and the next publication is transcribed over the top — and
     * an operation that failed on the second pass would be worked around with a delete, which this
     * table does not permit and should not.
     *
     * <p>Deliberately does <em>not</em> touch {@code status}. Registering and suspending are
     * different acts with different authority behind them, and folding both into one call would
     * mean a routine re-transcription could silently restore a Consent Manager the Board suspended
     * last week. Status moves only through {@link #setStatus}.
     */
    public void upsert(ConsentManager manager) {
        jdbc.sql("""
                insert into consent_manager (registration_id, name, status, api_client_id,
                                             public_key, registered_at, contact_email)
                     values (:registrationId, :name, :status, :apiClientId, :publicKey,
                             :registeredAt, :contactEmail)
                on conflict (registration_id) do update
                    set name          = excluded.name,
                        api_client_id = excluded.api_client_id,
                        public_key    = excluded.public_key,
                        registered_at = excluded.registered_at,
                        contact_email = excluded.contact_email
                """)
                .param("registrationId", manager.registrationId())
                .param("name", manager.name())
                .param("status", manager.status().name())
                .param("apiClientId", manager.apiClientId())
                .param("publicKey", manager.publicKey())
                .param("registeredAt", java.sql.Timestamp.from(manager.registeredAt()))
                .param("contactEmail", manager.contactEmail())
                .update();
    }

    /**
     * Suspends, restores or deregisters a registration.
     *
     * <p>The operation that has to work on the day the Board's notice arrives. Separate from
     * {@link #upsert} because it is a different act: this one changes whether relays are honoured,
     * and it is the one whose audit record somebody will read afterwards.
     *
     * @param reason free text, because "why is this one suspended" is asked under time pressure and
     *               the answer is not enumerable
     * @return whether a registration by that number existed to change
     */
    public boolean setStatus(String registrationId, Status status, String reason, Instant at) {
        return jdbc.sql("""
                update consent_manager
                   set status            = :status,
                       status_changed_at = :at,
                       status_reason     = :reason
                 where registration_id = :registrationId
                """)
                .param("registrationId", registrationId)
                .param("status", status.name())
                .param("reason", reason)
                .param("at", java.sql.Timestamp.from(at))
                .update() > 0;
    }

    /**
     * Records that a person checked this entry against the Board's published register.
     *
     * <p>Not a sync — there is no feed to sync with. The Board publishes no API, so the copy UDS
     * holds can only be as fresh as the last time somebody compared the two by eye. Naming who did
     * it is the point: a reconciliation nobody is named for is a reconciliation nobody did.
     */
    public boolean recordReconciliation(String registrationId, String by, Instant at) {
        return jdbc.sql("""
                update consent_manager
                   set last_reconciled_at = :at, last_reconciled_by = :by
                 where registration_id = :registrationId
                """)
                .param("registrationId", registrationId)
                .param("by", by)
                .param("at", java.sql.Timestamp.from(at))
                .update() > 0;
    }

    /**
     * The oldest reconciliation across the live register, or empty if there is none to report.
     *
     * <p>Read together with {@link #neverReconciled()}, never alone. "Never reconciled" is the
     * worse state and it is not a very old date — it is the absence of one — so a health indicator
     * that only reported this value would fall silent on exactly the entries that matter, including
     * the {@code CM-TEST-*} fixtures that are meant to keep showing up until somebody retires them.
     */
    public Optional<Instant> oldestReconciliation() {
        return jdbc.sql("""
                select min(last_reconciled_at) as oldest
                  from consent_manager
                 where status <> 'DEREGISTERED' and last_reconciled_at is not null
                """)
                .query((rs, n) -> rs.getTimestamp("oldest"))
                .optional()
                .map(timestamp -> timestamp == null ? null : timestamp.toInstant());
    }

    /** Live registrations nobody has ever checked against the Board's published list. */
    public List<String> neverReconciled() {
        return jdbc.sql("""
                select registration_id from consent_manager
                 where status <> 'DEREGISTERED' and last_reconciled_at is null
                 order by registration_id
                """)
                .query(String.class)
                .list();
    }

    /**
     * Links a principal to a Consent Manager, or refreshes an existing live link.
     *
     * <p>Idempotent on the live link, because a relay is allowed to assert the link it is acting
     * under on every request — a Consent Manager should not have to remember whether it has told
     * UDS about this principal before, and requiring it to would make the first relay after any
     * data loss on their side fail for a reason nobody could diagnose from here.
     */
    public void link(String entityId, String subjectId, String registrationId, String cmSubjectRef,
                     Instant linkedAt) {
        jdbc.sql("""
                        insert into consent_manager_link (entity_id, subject_id, registration_id,
                                                          cm_subject_ref, linked_at)
                        values (:entityId, :subjectId, :registrationId, :cmSubjectRef, :linkedAt)
                        on conflict (entity_id, subject_id, registration_id)
                            where unlinked_at is null
                            do update set cm_subject_ref = excluded.cm_subject_ref
                        """)
                .param("entityId", entityId)
                .param("subjectId", subjectId)
                .param("registrationId", registrationId)
                .param("cmSubjectRef", cmSubjectRef)
                .param("linkedAt", java.sql.Timestamp.from(linkedAt))
                .update();
    }

    /**
     * Ends a link.
     *
     * <p>An update rather than a delete, and one of the few updates in the schema. The link table
     * is not the ledger — it is a current-state mapping, and the evidence that a principal once
     * managed their consent through a given CM is in the consent events themselves, which carry the
     * registration number on every relayed one. What matters here is that unlinking does not
     * withdraw anything: a principal leaving a Consent Manager has not changed their mind about
     * being contacted, and treating the two as the same would silently revoke consents nobody
     * revoked.
     */
    public int unlink(String entityId, String subjectId, String registrationId, Instant at) {
        return jdbc.sql("""
                        update consent_manager_link
                           set unlinked_at = :at
                         where entity_id = :entityId
                           and subject_id = :subjectId
                           and registration_id = :registrationId
                           and unlinked_at is null
                        """)
                .param("entityId", entityId)
                .param("subjectId", subjectId)
                .param("registrationId", registrationId)
                .param("at", java.sql.Timestamp.from(at))
                .update();
    }

    /** The subject a Consent Manager means by its own reference, if the link is live. */
    public Optional<String> resolveSubject(String entityId, String registrationId,
                                           String cmSubjectRef) {
        return jdbc.sql("""
                        select subject_id
                          from consent_manager_link
                         where entity_id = :entityId
                           and registration_id = :registrationId
                           and cm_subject_ref = :cmSubjectRef
                           and unlinked_at is null
                        """)
                .param("entityId", entityId)
                .param("registrationId", registrationId)
                .param("cmSubjectRef", cmSubjectRef)
                .query(String.class)
                .optional();
    }

    /** Live links for a principal — what the evidence bundle and a preference centre both show. */
    public List<Link> linksForSubject(String entityId, String subjectId) {
        return jdbc.sql("""
                        select entity_id, subject_id, registration_id, cm_subject_ref, linked_at,
                               unlinked_at
                          from consent_manager_link
                         where entity_id = :entityId and subject_id = :subjectId
                         order by linked_at desc
                        """)
                .param("entityId", entityId)
                .param("subjectId", subjectId)
                .query((rs, n) -> new Link(
                        rs.getString("entity_id"), rs.getString("subject_id"),
                        rs.getString("registration_id"), rs.getString("cm_subject_ref"),
                        rs.getTimestamp("linked_at").toInstant(),
                        rs.getTimestamp("unlinked_at") == null
                                ? null : rs.getTimestamp("unlinked_at").toInstant()))
                .list();
    }

    private static ConsentManager mapManager(ResultSet rs, int rowNum) throws SQLException {
        return new ConsentManager(
                rs.getString("registration_id"),
                rs.getString("name"),
                Status.valueOf(rs.getString("status")),
                rs.getString("api_client_id"),
                rs.getString("public_key"),
                rs.getTimestamp("registered_at").toInstant(),
                rs.getTimestamp("status_changed_at") == null
                        ? null : rs.getTimestamp("status_changed_at").toInstant(),
                rs.getString("status_reason"),
                rs.getString("contact_email"),
                rs.getTimestamp("last_reconciled_at") == null
                        ? null : rs.getTimestamp("last_reconciled_at").toInstant(),
                rs.getString("last_reconciled_by"));
    }

    /**
     * Registration status.
     *
     * <p>Three values rather than a boolean because the Board can suspend without deregistering,
     * and the consequences differ: a suspended CM's links survive and its relays do not.
     */
    public enum Status {
        REGISTERED,
        SUSPENDED,
        DEREGISTERED;

        /** Whether a relay from this registration may be honoured. */
        public boolean mayRelay() {
            return this == REGISTERED;
        }
    }

    /**
     * One entry on the register, as UDS holds it.
     *
     * @param lastReconciledAt when a person last compared this entry against the Board's published
     *                         register. Null means never, which is the state every entry starts in
     *                         and the state the fixtures deliberately stay in until somebody
     *                         retires them
     */
    public record ConsentManager(String registrationId, String name, Status status,
                                 String apiClientId, String publicKey, Instant registeredAt,
                                 Instant statusChangedAt, String statusReason,
                                 String contactEmail, Instant lastReconciledAt,
                                 String lastReconciledBy) {
    }

    public record Link(String entityId, String subjectId, String registrationId,
                       String cmSubjectRef, Instant linkedAt, Instant unlinkedAt) {

        public boolean isLive() {
            return unlinkedAt == null;
        }
    }
}
