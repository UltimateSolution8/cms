package com.uds.consent.ledger.service;

import com.uds.consent.core.crypto.CanonicalJson;
import com.uds.consent.core.crypto.Hashes;
import com.uds.consent.core.model.ConsentEvent;
import com.uds.consent.ledger.store.ConsentEventStore;
import com.uds.consent.ledger.store.StoredEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Verifies that the evidence plane has not been altered.
 *
 * <p>This is the layer that still works when the others have been defeated. Database triggers and
 * revoked grants stop the application and an ordinary operator from editing history; neither stops
 * someone with superuser rights, who can disable a trigger or set {@code session_replication_role}
 * to bypass it. What they cannot do is change one row without invalidating every hash after it in
 * that subject's chain — so this check, run on a schedule and after every restore, is what makes
 * the ledger's integrity an assertion the group can actually stand behind.
 *
 * <p>Runs nightly across all chains and on demand for a single subject when a dispute arises.
 */
@Service
public class LedgerIntegrityVerifier {

    private final ConsentEventStore events;

    public LedgerIntegrityVerifier(ConsentEventStore events) {
        this.events = events;
    }

    /** Verifies one subject's chain end to end. */
    @Transactional(readOnly = true)
    public ChainVerification verifyChain(String entityId, String subjectId) {
        List<StoredEvent> chain = events.findChain(entityId, subjectId);
        List<Finding> findings = new ArrayList<>();

        String expectedPrevious = ConsentEvent.GENESIS_HASH;
        long expectedSequence = 1;

        for (StoredEvent stored : chain) {
            ConsentEvent event = stored.event();

            if (event.sequenceNumber() != expectedSequence) {
                findings.add(new Finding(event.eventId(), event.sequenceNumber(),
                        FindingType.SEQUENCE_GAP,
                        "expected sequence " + expectedSequence + " but found "
                                + event.sequenceNumber()
                                + " — an event has been removed, or one was written outside the ledger"));
            }

            if (!Hashes.constantTimeEquals(expectedPrevious, event.previousHash())) {
                findings.add(new Finding(event.eventId(), event.sequenceNumber(),
                        FindingType.CHAIN_BREAK,
                        "previous_hash does not match the prior event's hash — history has been "
                                + "altered at or before this point"));
            }

            String recomputed = Hashes.chain(event.previousHash(), stored.canonicalPayload());
            if (!Hashes.constantTimeEquals(recomputed, event.eventHash())) {
                findings.add(new Finding(event.eventId(), event.sequenceNumber(),
                        FindingType.HASH_MISMATCH,
                        "stored event_hash does not match a hash recomputed from the stored payload"));
            }

            // Divergence between the payload and the structured columns means someone edited the
            // columns without being able to forge the payload. Reported separately because a
            // benign cause exists too: a schema change that added a field after this event was
            // written will serialise differently today, and that is not tampering.
            String reserialised = CanonicalJson.serialize(event.hashableBody());
            if (!reserialised.equals(stored.canonicalPayload())) {
                findings.add(new Finding(event.eventId(), event.sequenceNumber(),
                        FindingType.PAYLOAD_DIVERGENCE,
                        "structured columns do not re-serialise to the stored payload — either the "
                                + "columns were edited, or the event predates a schema change"));
            }

            expectedPrevious = event.eventHash();
            expectedSequence = event.sequenceNumber() + 1;
        }

        return new ChainVerification(entityId, subjectId, chain.size(), findings);
    }

    /**
     * Verifies every chain, a page at a time.
     *
     * <p>Paged rather than streamed in one transaction because the sweep will eventually run over
     * tens of millions of events, and holding a single snapshot open for that long would block
     * vacuum and bloat the table it is trying to protect.
     */
    @Transactional(readOnly = true)
    public SweepResult verifyAll(int pageSize) {
        int offset = 0;
        int chainsChecked = 0;
        List<ChainVerification> failures = new ArrayList<>();

        while (true) {
            List<String[]> keys = events.findAllChainKeys(pageSize, offset);
            if (keys.isEmpty()) {
                break;
            }
            for (String[] key : keys) {
                ChainVerification verification = verifyChain(key[0], key[1]);
                chainsChecked++;
                if (!verification.intact()) {
                    failures.add(verification);
                }
            }
            offset += keys.size();
        }

        return new SweepResult(chainsChecked, failures);
    }

    /** What kind of integrity problem was found. */
    public enum FindingType {
        /** An event is missing, or one was inserted without going through the ledger. */
        SEQUENCE_GAP,
        /** The chain does not link — history was altered at or before this event. */
        CHAIN_BREAK,
        /** The stored hash does not match the stored payload. */
        HASH_MISMATCH,
        /** Structured columns disagree with the hashed payload. May be tampering or schema drift. */
        PAYLOAD_DIVERGENCE
    }

    public record Finding(String eventId, long sequenceNumber, FindingType type, String detail) {
    }

    /**
     * @param eventsChecked number of events walked
     * @param findings      empty when the chain is intact
     */
    public record ChainVerification(String entityId, String subjectId, int eventsChecked,
                                    List<Finding> findings) {

        public ChainVerification {
            findings = List.copyOf(findings);
        }

        public boolean intact() {
            return findings.isEmpty();
        }

        /**
         * Whether any finding indicates actual tampering rather than schema drift. Distinguished
         * because a payload divergence alone should raise a ticket, whereas a chain break should
         * wake somebody up.
         */
        public boolean tampered() {
            return findings.stream().anyMatch(f -> f.type() != FindingType.PAYLOAD_DIVERGENCE);
        }
    }

    public record SweepResult(int chainsChecked, List<ChainVerification> failures) {

        public SweepResult {
            failures = List.copyOf(failures);
        }

        public boolean allIntact() {
            return failures.isEmpty();
        }
    }
}
