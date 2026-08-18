package com.uds.consent.service;

import com.uds.consent.core.crypto.IdentifierHasher;
import com.uds.consent.core.decision.DenialReason;
import com.uds.consent.core.model.ActorType;
import com.uds.consent.core.model.CaptureMethod;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.ConsentEvent;
import com.uds.consent.core.model.IdentifierType;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.ledger.store.ConsentManagerStore;
import com.uds.consent.ledger.store.SubjectStore;
import com.uds.consent.policy.capture.CaptureSubmission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The inbound and outbound sides of a Consent Manager relay (DPDP Rule 4).
 *
 * <p><strong>UDS does not register as a Consent Manager and cannot.</strong> The First Schedule
 * requires one to be independent of the fiduciaries it intermediates for and to act solely in the
 * principal's interest; UDS is a Data Fiduciary for the same principals, and no internal separation
 * cures that. What the framework obliges UDS to do is transact with a registered one — which,
 * before this class existed, it could not do at all. A principal who manages consent through a CM
 * and withdraws there produced a withdrawal with no way to arrive.
 *
 * <p><strong>No parallel write path.</strong> Everything here ends in
 * {@link ConsentCaptureService#capture} or {@link ConsentCaptureService#withdraw}. That is the
 * single most important decision in the class. A relayed withdrawal <em>is</em> a withdrawal: it
 * must append the same {@code WITHDRAWN} event, update the same projection, hit the same outbox and
 * reach the same downstream systems, or the platform would honour a withdrawal made on its own form
 * and quietly not honour one made through the statutory channel. A second write path would drift
 * from the first within two releases, and the drift would be invisible until somebody was contacted
 * after asking not to be.
 *
 * <p>What differs is evidence, not effect. A relayed event carries
 * {@link ActorType#CONSENT_MANAGER}, {@link CaptureMethod#RELAYED_BY_CONSENT_MANAGER} and the Board
 * registration number as the actor id, so an auditor can tell the two apart and can follow the
 * registration back to the register entry that made the relay legitimate.
 */
@Service
public class ConsentManagerRelayService {

    private static final Logger log = LoggerFactory.getLogger(ConsentManagerRelayService.class);

    private final ConsentManagerStore managers;
    private final ConsentCaptureService capture;
    private final SubjectStore subjects;
    private final IdentifierHasher hasher;
    private final EnforcementRecorder enforcement;

    public ConsentManagerRelayService(ConsentManagerStore managers, ConsentCaptureService capture,
                                      SubjectStore subjects, IdentifierHasher hasher,
                                      EnforcementRecorder enforcement) {
        this.managers = managers;
        this.capture = capture;
        this.subjects = subjects;
        this.hasher = hasher;
        this.enforcement = enforcement;
    }

    /**
     * Checks that this caller may relay under this registration, and records the refusal when it
     * may not.
     *
     * <p>Two questions, and the second one is the reason this method replaced its predecessor.
     *
     * <p><strong>Is the registration active?</strong> On the register, and not suspended or
     * deregistered. That was always checked.
     *
     * <p><strong>Does it belong to the caller?</strong> That was not. The registration number
     * arrived as a path variable and was trusted, so the credential and the number it named were
     * two independent facts with nothing joining them — any credential holding the
     * {@code CONSENT_MANAGER} role could relay grants and withdrawals under any other registration
     * on the register, and the ledger would faithfully record the other Consent Manager as the
     * actor. In an evidence plane, an actor id that can be chosen by the caller is not evidence of
     * anything. {@link ConsentManagerStore#findByClient} was written for this join and was called
     * from nowhere.
     *
     * <p><strong>All three refusals look identical from outside.</strong> Same 403, same body, no
     * detail about which check failed. That is deliberate and it is the reason this method returns
     * refusals rather than distinguishing them in the response: told apart, the endpoint becomes a
     * way of enumerating the Board's register and probing which numbers are live. Told apart only
     * in {@code enforcement_decision}, they are available to the one reader entitled to them.
     *
     * <p>The refusal is evidence, not a log line. An inbound channel that writes consent and
     * authenticates on a claim about who the caller is will be probed, and the question afterwards
     * is how many times and against which entity — which a log that rotates in a fortnight cannot
     * answer.
     */
    public ConsentManagerStore.ConsentManager requireActiveAndBound(String registrationId,
                                                                    Caller caller, String entityId,
                                                                    Instant at) {
        Optional<ConsentManagerStore.ConsentManager> found = managers.find(registrationId);

        if (found.isEmpty()) {
            enforcement.recordConsentManagerRefusal(entityId, registrationId,
                    DenialReason.CONSENT_MANAGER_NOT_REGISTERED,
                    "relay refused: no Consent Manager is registered under this number", at);
            throw new RelayRefusedException(registrationId,
                    "no Consent Manager is registered under this number");
        }

        ConsentManagerStore.ConsentManager manager = found.get();
        if (!manager.status().mayRelay()) {
            enforcement.recordConsentManagerRefusal(entityId, registrationId,
                    DenialReason.CONSENT_MANAGER_NOT_REGISTERED,
                    "relay refused: registration is " + manager.status(), at);
            throw new RelayRefusedException(registrationId,
                    "registration is " + manager.status());
        }

        requireBound(manager, caller, entityId, at);
        return manager;
    }

    /**
     * The join between the credential that authenticated and the registration it named.
     *
     * <p>Administrators pass. That is a real operation rather than a loophole: rehearsing the relay
     * path before 13 November 2026, and reproducing a disputed relay during an investigation, are
     * both things a compliance administrator has to be able to do, and both are already audited as
     * administrative acts. It is written as its own branch with its own comment so that it is a
     * decision somebody took, not a side effect of {@code hasAnyRole('CONSENT_MANAGER','ADMIN')}
     * letting everything through.
     */
    private void requireBound(ConsentManagerStore.ConsentManager manager, Caller caller,
                              String entityId, Instant at) {
        if (caller == null) {
            // No caller means no proof. Refusing is the only safe reading: a call site that forgot
            // to pass one must fail closed, or this check would be optional in practice while
            // looking mandatory in the source.
            enforcement.recordConsentManagerRefusal(entityId, manager.registrationId(),
                    DenialReason.CONSENT_MANAGER_NOT_BOUND,
                    "relay refused: the caller was not identified", at);
            throw new RelayRefusedException(manager.registrationId(),
                    "the caller was not identified");
        }
        if (caller.administrator()) {
            log.info("administrator {} relayed under registration={} at entity={}",
                    caller.clientId(), manager.registrationId(), entityId);
            return;
        }

        String held = manager.apiClientId();
        if (held == null || !held.equals(caller.clientId())) {
            enforcement.recordConsentManagerRefusal(entityId, manager.registrationId(),
                    DenialReason.CONSENT_MANAGER_NOT_BOUND,
                    "relay refused: the calling credential does not hold this registration", at);
            // The claimed registration is on the exception for the response's sake and goes no
            // further; what the caller actually holds is deliberately not logged beside it, since
            // the pair would let anyone with log access map credentials to registrations.
            throw new RelayRefusedException(manager.registrationId(),
                    "the calling credential does not hold this registration");
        }
    }

    /**
     * A grant relayed from a Consent Manager.
     *
     * <p>The link is asserted on every relay rather than established once. A Consent Manager should
     * not have to remember whether it has told UDS about this principal before — requiring it to
     * would make the first relay after any data loss on their side fail for a reason nobody could
     * diagnose from this end, and the failure would look like a refused consent.
     */
    @Transactional
    public ConsentCaptureService.Result relayGrant(Relay relay, Caller caller) {
        requireActiveAndBound(relay.registrationId(), caller, relay.entityId(), relay.occurredAt());

        String subjectId = resolveOrLink(relay);

        return capture.capture(new CaptureSubmission(
                relay.entityId(),
                subjectId,
                relay.jurisdiction(),
                relay.languageTag(),
                // The channel the principal used at their Consent Manager is not knowable here and
                // guessing would put a fact in the ledger that nobody established.
                relay.channel(),
                relay.applicationId(),
                CaptureMethod.RELAYED_BY_CONSENT_MANAGER,
                ActorType.CONSENT_MANAGER,
                // The registration number, so an event can be traced to the register entry.
                relay.registrationId(),
                relay.noticeId(),
                relay.noticeVersion(),
                relay.choices(),
                relay.rejectAllOffered(),
                relay.occurredAt(),
                relay.idempotencyKey(),
                // The CM's own record is the evidence; this is the pointer to it.
                relay.evidenceRef(),
                Map.of("consentManager.registrationId", relay.registrationId(),
                        "consentManager.subjectRef", relay.cmSubjectRef())));
    }

    /**
     * A withdrawal relayed from a Consent Manager.
     *
     * <p>Goes through the same {@code withdraw} every other surface uses, and therefore takes
     * effect the instant it commits. DPDP s.6(4) requires withdrawal to be as easy as giving; a
     * principal who gave consent through a Consent Manager and withdraws there has done the easiest
     * thing available to them, and the platform has no business making it the slowest.
     */
    @Transactional
    public List<ConsentEvent> relayWithdrawal(Relay relay, Caller caller, List<String> purposeCodes,
                                              String reason) {
        requireActiveAndBound(relay.registrationId(), caller, relay.entityId(), relay.occurredAt());

        String subjectId = resolveOrLink(relay);

        return capture.withdraw(relay.entityId(), subjectId, purposeCodes, relay.channel(),
                relay.applicationId(), ActorType.CONSENT_MANAGER, relay.registrationId(),
                relay.jurisdiction(), relay.occurredAt(), relay.idempotencyKey(),
                reason == null || reason.isBlank()
                        ? "withdrawn through Consent Manager " + relay.registrationId()
                        : reason);
    }

    /**
     * Ends a link without touching consent.
     *
     * <p>Unlinking is not withdrawing, and the platform must not conflate them. A principal who
     * stops using a Consent Manager has said nothing about whether they still want to hear from
     * anyone — treating the two as one would silently revoke consents nobody revoked, and the
     * evidence plane would faithfully record a withdrawal that never happened.
     */
    @Transactional
    public boolean unlink(String entityId, String subjectId, String registrationId, Instant at) {
        return managers.unlink(entityId, subjectId, registrationId, at) > 0;
    }

    /** Live and past links for a principal. */
    public List<ConsentManagerStore.Link> linksFor(String entityId, String subjectId) {
        return managers.linksForSubject(entityId, subjectId);
    }

    /**
     * Finds the subject the relay is about, creating and linking as needed.
     *
     * <p>Three ways in, in order of trust. An existing live link is the strongest: the CM has
     * relayed for this principal before and UDS already resolved them. A subject id supplied
     * directly is next. An identifier — a phone number or an email — is the fallback, hashed with
     * the platform's pepper before it goes anywhere near the ledger, exactly as a first-party
     * capture would be.
     */
    private String resolveOrLink(Relay relay) {
        String subjectId = managers
                .resolveSubject(relay.entityId(), relay.registrationId(), relay.cmSubjectRef())
                .orElseGet(() -> resolveFromRelay(relay));

        managers.link(relay.entityId(), subjectId, relay.registrationId(), relay.cmSubjectRef(),
                relay.occurredAt());
        return subjectId;
    }

    private String resolveFromRelay(Relay relay) {
        if (relay.subjectId() != null && !relay.subjectId().isBlank()) {
            return relay.subjectId();
        }
        if (relay.identifierType() == null || relay.identifierValue() == null
                || relay.identifierValue().isBlank()) {
            throw new RelayRefusedException(relay.registrationId(),
                    "the Consent Manager's reference is not linked to a known principal, so the "
                            + "relay must carry either a subjectId or an identifier");
        }
        log.info("linking new principal for consent manager registration={} at entity={}",
                relay.registrationId(), relay.entityId());
        return subjects.resolveOrCreate(relay.entityId(), relay.identifierType(),
                hasher.hash(relay.identifierType(), relay.identifierValue()));
    }

    /**
     * One relayed act, as it arrives.
     *
     * @param cmSubjectRef how the Consent Manager identifies this principal. Required on every
     *                     relay: it is the only stable join between the two systems, and a relay
     *                     without it cannot be resolved on any later request
     */
    /**
     * Who is asking, as established by authentication rather than asserted by the request.
     *
     * <p>A separate parameter from {@link Relay} on purpose. Everything in a {@code Relay} is
     * something the caller said; everything here is something the platform established. Folding the
     * two into one record would put a field the caller controls next to a field it must not, and the
     * next person to build a {@code Relay} would have no way to tell which was which.
     *
     * @param clientId      the authenticated principal's name, matched against
     *                      {@code consent_manager.api_client_id}
     * @param administrator whether the caller holds {@code ROLE_ADMIN}, which permits relaying under
     *                      a registration the caller does not hold — see
     *                      {@link #requireActiveAndBound}
     */
    public record Caller(String clientId, boolean administrator) {
    }

    public record Relay(String registrationId, String entityId, String cmSubjectRef,
                        String subjectId, IdentifierType identifierType, String identifierValue,
                        Jurisdiction jurisdiction, String languageTag, Channel channel,
                        String applicationId, String noticeId, Integer noticeVersion,
                        List<CaptureSubmission.PurposeChoice> choices, boolean rejectAllOffered,
                        Instant occurredAt, String idempotencyKey, String evidenceRef) {
    }

    /** A relay that will not be honoured. Carries no detail the caller could probe with. */
    public static class RelayRefusedException extends RuntimeException {

        private final String registrationId;

        public RelayRefusedException(String registrationId, String message) {
            super(message);
            this.registrationId = registrationId;
        }

        public String registrationId() {
            return registrationId;
        }
    }
}
