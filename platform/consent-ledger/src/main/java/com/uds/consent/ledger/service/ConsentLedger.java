package com.uds.consent.ledger.service;

import com.uds.consent.core.model.ConsentArtefact;
import com.uds.consent.core.model.ConsentEvent;
import com.uds.consent.core.model.ConsentEventType;
import com.uds.consent.ledger.store.ConsentArtefactStore;
import com.uds.consent.ledger.store.ConsentEventStore;
import com.uds.consent.ledger.store.OutboxStore;
import com.uds.consent.ledger.store.StoredEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The only way consent state changes.
 *
 * <p>One transaction covers three things that must never come apart: the immutable event, the
 * projection the enforcement plane reads, and the outbox row that tells every downstream system.
 * If any of them fails, none of them happened.
 */
@Service
public class ConsentLedger {

    /**
     * Default topic. Topic names are part of the platform's public contract — downstream teams
     * subscribe to them — which is why this is overridable rather than fixed: a group that already
     * has a naming convention for its event bus should not have to fork the platform to follow it.
     */
    public static final String TOPIC_CONSENT = "uds.consent.events";

    private final ConsentEventStore events;
    private final ConsentArtefactStore artefacts;
    private final ArtefactProjector projector;
    private final OutboxStore outbox;
    private final String topic;

    public ConsentLedger(ConsentEventStore events, ConsentArtefactStore artefacts,
                         ArtefactProjector projector, OutboxStore outbox,
                         @Value("${uds.consent.events.topic:" + TOPIC_CONSENT + "}") String topic) {
        this.events = events;
        this.artefacts = artefacts;
        this.projector = projector;
        this.outbox = outbox;
        this.topic = topic;
    }

    /**
     * Records what a subject did, projects it, and queues it for fan-out.
     *
     * @return the event as written, carrying its assigned sequence number and hash
     */
    @Transactional
    public ConsentEvent record(ConsentEvent candidate) {
        ConsentEventStore.AppendResult result = events.append(candidate);
        ConsentEvent stored = result.event();

        // A replay is already reflected in the projection and has already been published. Doing
        // either again would show downstream consumers the same withdrawal twice.
        if (result.replay()) {
            return stored;
        }

        projector.apply(stored);
        outbox.enqueue(topic, stored.entityId() + '|' + stored.subjectId(),
                publishablePayload(stored));
        return stored;
    }

    /** Current state for one subject and purpose, never null. */
    @Transactional(readOnly = true)
    public ConsentArtefact currentState(String entityId, String subjectId, String purposeCode) {
        return artefacts.find(entityId, subjectId, purposeCode)
                .orElseGet(() -> ConsentArtefact.notAsked(entityId, subjectId, purposeCode));
    }

    /** Everything on record for a subject. */
    @Transactional(readOnly = true)
    public List<ConsentArtefact> currentStateForSubject(String entityId, String subjectId) {
        return artefacts.findAllForSubject(entityId, subjectId);
    }

    /** The full evidence trail for a subject, oldest first. */
    @Transactional(readOnly = true)
    public List<StoredEvent> history(String entityId, String subjectId) {
        return events.findChain(entityId, subjectId);
    }

    /** The evidence trail for one purpose. What an auditor asks for about a specific complaint. */
    @Transactional(readOnly = true)
    public List<StoredEvent> history(String entityId, String subjectId, String purposeCode) {
        return events.findChainForPurpose(entityId, subjectId, purposeCode);
    }

    @Transactional(readOnly = true)
    public Optional<ConsentEvent> findByIdempotencyKey(String entityId, String key) {
        return events.findByIdempotencyKey(entityId, key);
    }

    /**
     * What goes on the wire to downstream systems.
     *
     * <p>Deliberately narrow. Consumers need to know that something changed for a subject and
     * purpose so they can invalidate caches and stop processing; they do not need the notice
     * version, the evidence pointer or the capture method, and sending those would spread
     * consent evidence into systems that have no business holding it.
     */
    private static Map<String, Object> publishablePayload(ConsentEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", event.eventId());
        payload.put("entityId", event.entityId());
        payload.put("subjectId", event.subjectId());
        payload.put("purposeCode", event.purposeCode());
        payload.put("purposeVersion", event.purposeVersion());
        payload.put("eventType", event.type().name());
        payload.put("status", event.resultingStatus().name());
        payload.put("channel", event.channel() == null ? null : event.channel().name());
        payload.put("occurredAt", event.occurredAt().toString());
        payload.put("expiresAt", event.expiresAt() == null ? null : event.expiresAt().toString());
        payload.put("sequenceNumber", event.sequenceNumber());
        // Restrictive changes are what downstream systems must act on immediately; flagged so a
        // consumer can prioritise them without having to know the event-type taxonomy.
        payload.put("restrictive", event.type() != ConsentEventType.GRANTED
                && event.type() != ConsentEventType.MODIFIED);
        return payload;
    }
}
