package com.uds.consent.service;

import com.uds.consent.core.crypto.IdentifierHasher;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.IdentifierType;
import com.uds.consent.core.model.SuppressionScope;
import com.uds.consent.core.model.SuppressionSource;
import com.uds.consent.ledger.store.AdminAuditStore;
import com.uds.consent.ledger.store.SuppressionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Do-not-contact management, including campaign scrubbing.
 *
 * <p>Suppression is channel-level and sits above consent: it answers "may we contact this person
 * at all on this channel", where a consent record answers "may we contact them for this purpose".
 * Keeping the two separate is what stops a subject who withdrew consent to promotional email from
 * silently losing the transactional messages they still expect.
 */
@Service
public class SuppressionService {

    private static final Logger log = LoggerFactory.getLogger(SuppressionService.class);

    private final SuppressionStore store;
    private final IdentifierHasher hasher;
    private final AdminAuditStore audit;

    public SuppressionService(SuppressionStore store, IdentifierHasher hasher,
                              AdminAuditStore audit) {
        this.store = store;
        this.hasher = hasher;
        this.audit = audit;
    }

    /**
     * Records that a subject asked not to be contacted on a channel.
     *
     * <p>Takes the identifier in the clear and hashes it here, so that no caller has to hold the
     * pepper and no plaintext number reaches the store.
     */
    @Transactional
    public long optOut(String entityId, SuppressionScope scope, SuppressionSource source,
                       Channel channel, IdentifierType identifierType, String identifierValue,
                       String subjectId, String clientId, String campaignId, String reason,
                       String actorId) {
        String hash = hasher.hash(identifierType, identifierValue);
        long id = store.add(entityId, scope, source, channel, identifierType, hash, subjectId,
                clientId, campaignId, Instant.now(), null, reason, actorId);

        audit.record(actorId, "SUPPRESSION_ADDED", entityId, "suppression_entry",
                String.valueOf(id), Map.of("source", source.name(), "scope", scope.name(),
                        "channel", channel.name()));
        return id;
    }

    /**
     * Removes contacts that must not be approached, from a list about to be dialled or messaged.
     *
     * <p>Runs before every campaign, not once at list build. A number added to the national
     * preference register yesterday has to be excluded today, and a list scrubbed at build time
     * would miss it — which is precisely the failing TRAI acts on.
     *
     * <p>Deliberately does not resolve or create subjects. Checking whether a number may be
     * contacted must not itself add that number to the platform's records.
     */
    @Transactional(readOnly = true)
    public ScrubResult scrub(String entityId, Channel channel, IdentifierType identifierType,
                             List<String> identifierValues, String clientId, String campaignId,
                             Instant at) {
        List<String> permitted = new ArrayList<>();
        List<Excluded> excluded = new ArrayList<>();

        for (String value : identifierValues) {
            String hash;
            try {
                hash = hasher.hash(identifierType, value);
            } catch (IllegalArgumentException e) {
                // An unusable number is excluded rather than passed through. Dialling something
                // that could not even be normalised is how a list ends up calling the wrong person.
                excluded.add(new Excluded(value, "UNPARSEABLE_IDENTIFIER"));
                continue;
            }

            store.findForIdentifier(entityId, identifierType, hash, channel, clientId, campaignId, at)
                    .ifPresentOrElse(
                            hit -> excluded.add(new Excluded(value, hit.source().name())),
                            () -> permitted.add(value));
        }

        log.info("scrub for entity={} channel={} campaign={}: {} permitted, {} excluded",
                entityId, channel, campaignId, permitted.size(), excluded.size());
        return new ScrubResult(permitted, excluded);
    }

    /**
     * Loads a statutory registry export.
     *
     * <p>These entries are global and cannot be overridden by a consent record. The identifiers
     * arrive in the clear from the registry and are hashed here.
     */
    @Transactional
    public int loadStatutoryRegistry(SuppressionSource source, Channel channel,
                                     IdentifierType identifierType, List<String> identifierValues,
                                     String actorId) {
        List<String> hashes = identifierValues.stream()
                .map(value -> hasher.hash(identifierType, value))
                .toList();
        int written = store.addStatutoryBatch(source, channel, identifierType, hashes,
                Instant.now(), actorId);

        audit.record(actorId, "STATUTORY_REGISTRY_LOADED", null, "suppression_entry", source.name(),
                Map.of("channel", channel.name(), "entries", String.valueOf(written)));
        log.info("loaded {} entries from {} for channel {}", written, source, channel);
        return written;
    }

    /**
     * @param permitted identifiers that may be contacted
     * @param excluded  identifiers that must not be, each with the reason
     */
    public record ScrubResult(List<String> permitted, List<Excluded> excluded) {

        public ScrubResult {
            permitted = List.copyOf(permitted);
            excluded = List.copyOf(excluded);
        }

        public int permittedCount() {
            return permitted.size();
        }

        public int excludedCount() {
            return excluded.size();
        }
    }

    public record Excluded(String identifier, String reason) {
    }
}
