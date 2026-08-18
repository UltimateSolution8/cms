package com.uds.consent.service;

import com.uds.consent.core.crypto.Hashes;
import com.uds.consent.core.crypto.IdentifierHasher;
import com.uds.consent.core.model.IdentifierType;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.RightsRequestType;
import com.uds.consent.core.model.RightsVerificationMethod;
import com.uds.consent.ledger.store.OutboxStore;
import com.uds.consent.ledger.store.RightsRequestStore;
import com.uds.consent.ledger.store.RightsVerificationStore;
import com.uds.consent.service.config.PlatformProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * The data principal's way in.
 *
 * <p>Every other route on this platform needs a credential, which meant the group's ability to
 * receive a rights request was its ability to answer the phone. DPDP <strong>Rule 14(1)</strong>
 * requires a Data Fiduciary to prominently publish the means of exercising a right, and
 * {@code NoticeStore.rightsUri} has pointed every consent receipt ever issued at a page that did
 * not exist. This is that page's backend.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p><strong>No preference centre.</strong> There is no route here that changes a consent. Letting
 * somebody flip consents from the open internet needs a session model the group does not have, and
 * it would open a write path into the append-only ledger from an unauthenticated surface — the one
 * thing two layers of entity isolation exist to refuse. Withdrawal already has a route, and the
 * capture surface that knows who the person is calls it.
 *
 * <p><strong>No message is sent.</strong> The platform mints the token and hands it to the outbox.
 * Whichever system sends email or SMS picks it up. That boundary has held since V1 and holds here:
 * nothing in this platform has ever been able to reach a person, and the day it can is the day it
 * becomes a marketing system rather than the thing that governs one.
 *
 * <h2>The two properties that matter</h2>
 *
 * <p><strong>Submission cannot reveal whether an identifier is known.</strong> It is not that the
 * responses are carefully matched — it is that this class never looks. Submission hashes what it is
 * given, stores the hash, and enqueues. No subject lookup happens, so there is no branch that could
 * differ and no timing difference to measure. An oracle over the group's contact list, reachable by
 * anyone, is not a thing to be careful about; it is a thing to make unrepresentable.
 *
 * <p><strong>The statutory clock starts at verification.</strong> {@code StatutoryClock} derives a
 * deadline that Rule 14(3) caps at ninety days. If an anonymous submission started it, anyone could
 * burn the group's entire response window on somebody else's behalf, repeatedly, without ever
 * proving they were that person. An unverified submission is not yet a request from the principal.
 */
@Service
public class PrincipalPortalService {

    private static final Logger log = LoggerFactory.getLogger(PrincipalPortalService.class);

    /**
     * The verification window.
     *
     * <p>Long enough that an email read the next morning still works, short enough that a token
     * sitting in an abandoned inbox is not a standing key to somebody's rights channel.
     */
    private static final Duration VALIDITY = Duration.ofHours(24);

    /**
     * How many wrong tokens a reference tolerates.
     *
     * <p>Five, and the cap is what lets the token be short enough to read over the phone. Without
     * it, an eight-character alphabet-32 token is brute-forceable at HTTP speed; with it, an
     * attacker gets five guesses out of a trillion and then the reference is dead.
     */
    private static final int MAX_ATTEMPTS = 5;

    /**
     * Crockford-style: no I, L, O or U. Removes the digit-letter confusions that make a support
     * call about a token longer than the request it was for, and removes the one four-letter
     * combination nobody wants to read out.
     */
    private static final String TOKEN_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int TOKEN_LENGTH = 10;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RightsVerificationStore verifications;
    private final RightsService rights;
    private final IdentifierHasher hasher;
    private final OutboxStore outbox;
    private final TransactionTemplate transactions;
    private final String pepper;

    public PrincipalPortalService(RightsVerificationStore verifications, RightsService rights,
                                  IdentifierHasher hasher, OutboxStore outbox,
                                  PlatformTransactionManager transactionManager,
                                  PlatformProperties properties) {
        this.verifications = verifications;
        this.rights = rights;
        this.hasher = hasher;
        this.outbox = outbox;
        this.transactions = new TransactionTemplate(transactionManager);
        this.pepper = properties.getIdentifierPepper();
    }

    /**
     * Accepts a submission from someone claiming an identifier, and tells them nothing about it.
     *
     * <p>Returns a reference in every case. The caller gets the same shape whether the group holds
     * a file on that person or has never heard of them, because this method does not find out.
     */
    @Transactional
    public Submission submit(String entityId, IdentifierType identifierType, String identifierValue,
                             RightsRequestType type, Jurisdiction jurisdiction) {
        String reference = "PR-" + randomToken(12);
        String token = randomToken(TOKEN_LENGTH);
        String identifierHash = hasher.hash(identifierType, identifierValue);
        Instant expiresAt = Instant.now().plus(VALIDITY);

        verifications.create(reference, entityId, identifierType, identifierHash, type,
                jurisdiction, tokenHash(token), expiresAt);

        // The plaintext token leaves the platform exactly here, exactly once. The sending system
        // resolves identifier_hash to a contact — this platform holds no contact details and could
        // not send to one if it wanted to.
        outbox.enqueue("rights.verification.requested", reference, Map.of(
                "reference", reference,
                "entityId", entityId,
                "identifierType", identifierType.name(),
                "identifierHash", identifierHash,
                "requestType", type.name(),
                "token", token,
                "expiresAt", expiresAt.toString()));

        // Deliberately no identifier, no hash and no token in the log line. This is an
        // unauthenticated route, so its log is the one place an attacker's chosen input most
        // easily ends up somewhere it is kept for a year.
        log.info("rights portal submission {} for entity {} ({})", reference, entityId, type);

        return new Submission(reference, expiresAt);
    }

    /**
     * Turns a verified submission into a real rights request, with a clock.
     *
     * <p>Every refusal below returns the same {@link VerificationFailed}, and the message names no
     * distinction between "no such reference", "wrong token", "already used" and "expired". A
     * caller who guesses a reference should not be told that they guessed one correctly.
     *
     * <p><strong>Deliberately not {@code @Transactional} as a whole.</strong> It was, and the
     * attempt counter did not work: {@code recordFailedAttempt} incremented it and the
     * {@link VerificationFailed} thrown immediately afterwards rolled the increment back with
     * everything else. Five wrong guesses left the counter at zero, so the cap that makes a
     * ten-character code safe against an online attack existed only in the source. Found by
     * {@code PrincipalPortalIT.guessingIsBounded} asserting the cap rather than the increment —
     * asserting the increment would have passed.
     *
     * <p>So the counter commits on its own, and only the part that must be atomic — filing the
     * request and consuming the reference — runs in a transaction, through an explicit
     * {@link TransactionTemplate} rather than an annotation, because a {@code @Transactional} call
     * from inside this class would bypass the proxy and silently do nothing at all.
     */
    public RightsRequestStore.Request verify(String reference, String token) {
        RightsVerificationStore.Pending pending = verifications.find(reference)
                .orElseThrow(VerificationFailed::new);

        if (pending.verified() || pending.expired(Instant.now())
                || pending.attempts() >= MAX_ATTEMPTS) {
            throw new VerificationFailed();
        }

        if (!Hashes.constantTimeEquals(pending.tokenHash(), tokenHash(token))) {
            int attempts = verifications.recordFailedAttempt(reference);
            if (attempts >= MAX_ATTEMPTS) {
                log.warn("rights portal reference {} exhausted its verification attempts",
                        reference);
            }
            throw new VerificationFailed();
        }

        RightsRequestStore.Request request = transactions.execute(status -> {
            // Now, and not when they submitted. See the class comment: the clock is the thing an
            // anonymous caller must not be able to start.
            // received_at and verified_at are the same instant here, and by construction rather
            // than by an assurance that they agree: this path files the request at the moment the
            // token comes back, so there is no interval in which they could diverge.
            Instant verifiedAt = Instant.now();
            RightsRequestStore.Request filed = rights.intake(new RightsService.Intake(
                    pending.entityId(), null, pending.identifierType(), null,
                    pending.identifierHash(), pending.requestType(), pending.jurisdiction(),
                    verifiedAt,
                    "Filed by the data principal through the rights portal; identifier verified by "
                            + "single-use token. Portal reference " + reference + ".",
                    "data-principal",
                    RightsVerificationMethod.PORTAL_TOKEN, verifiedAt, null));

            if (!verifications.consume(reference, filed.requestId(), Instant.now())) {
                // Lost a race with a simultaneous verification of the same code. The single-use
                // predicate lives in the UPDATE rather than in a check above precisely so this is
                // detectable, and the rollback discards the request that would otherwise be the
                // second one filed — each with its own statutory deadline — for one person.
                throw new VerificationFailed();
            }
            return filed;
        });

        log.info("rights portal reference {} verified; filed as {}", reference,
                request.requestId());
        return request;
    }

    /**
     * What the principal is told about their own request.
     *
     * <p>Status and dates. Never the evidence bundle, and never the subject id: a token delivered
     * to an email address is not the authentication standard on which to hand over a person's
     * complete file, and that route stays behind ADMIN where it belongs.
     */
    @Transactional(readOnly = true)
    public Optional<Status> status(String reference, String token) {
        return verifications.find(reference)
                .filter(pending -> Hashes.constantTimeEquals(pending.tokenHash(), tokenHash(token)))
                .map(pending -> {
                    if (!pending.verified()) {
                        return new Status(reference, "AWAITING_VERIFICATION",
                                pending.requestType().name(), null, null);
                    }
                    RightsRequestStore.Request request = rights.find(pending.requestId());
                    return new Status(reference, request.status().name(),
                            request.type().name(), request.receivedAt(), request.dueAt());
                });
    }

    /**
     * Peppered, so that a leaked copy of the table is useless.
     *
     * <p>The pepper lives in the secret manager and never in the database, which is the same
     * property {@code IdentifierHasher} relies on and the reason {@code RUNBOOK_DR.md} insists the
     * pepper is backed up separately. A plain digest here would let anyone holding a backup verify
     * anybody's pending request by trying the small token space offline.
     */
    private String tokenHash(String token) {
        return Hashes.sha256Hex(pepper + ":rights-portal:" + token.trim().toUpperCase());
    }

    private static String randomToken(int length) {
        StringBuilder token = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            token.append(TOKEN_ALPHABET.charAt(RANDOM.nextInt(TOKEN_ALPHABET.length())));
        }
        return token.toString();
    }

    /** What a submitter is told: a reference, and how long they have. Never anything else. */
    public record Submission(String reference, Instant expiresAt) {
    }

    /** What a verified principal may read back about their own request. */
    public record Status(String reference, String status, String requestType,
                         Instant receivedAt, Instant dueAt) {
    }

    /**
     * One refusal for every way verification can fail.
     *
     * <p>Deliberately undifferentiated. Distinguishing "no such reference" from "wrong token" tells
     * a caller which half of a guess was right, and distinguishing "already used" from "expired"
     * confirms that a reference existed at all.
     */
    public static class VerificationFailed extends RuntimeException {

        public VerificationFailed() {
            super("that reference and code do not match an open request. Codes are single-use and "
                    + "expire after 24 hours — submit a new request if yours has expired.");
        }
    }
}
