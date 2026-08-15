package com.uds.consent.service;

import com.uds.consent.core.model.ConsentArtefact;
import com.uds.consent.core.model.ConsentStatus;
import com.uds.consent.core.model.PurposeDefinition;
import com.uds.consent.core.snapshot.ConsentSnapshot;
import com.uds.consent.core.snapshot.PurposeState;
import com.uds.consent.core.snapshot.SignedSnapshot;
import com.uds.consent.ledger.store.ConsentArtefactStore;
import com.uds.consent.ledger.store.SuppressionStore;
import com.uds.consent.policy.port.PolicyPorts;
import com.uds.consent.service.config.PlatformProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Issues signed consent snapshots.
 *
 * <p>This is what makes the offline story real rather than aspirational. Denave's field force runs
 * iSFA and retail-audit tools in places with no usable connectivity; a design that needs a round
 * trip before each action either blocks the work or gets routed around. A snapshot lets the device
 * answer in microseconds and stay accountable, because the signature ties its answer to a policy
 * version the server can reproduce.
 *
 * <p>Snapshots are small on purpose. They carry a subject's purpose states and nothing else — no
 * identifiers, no notice text, no evidence pointers. A device that is lost should yield nothing
 * beyond the fact that some opaque subject id agreed to some purposes.
 */
@Service
public class SnapshotService {

    private final ConsentArtefactStore artefacts;
    private final SuppressionStore suppression;
    private final PolicyPorts.PurposeCatalog purposes;
    private final SigningKeys keys;
    private final PlatformProperties properties;

    public SnapshotService(ConsentArtefactStore artefacts, SuppressionStore suppression,
                           PolicyPorts.PurposeCatalog purposes, SigningKeys keys,
                           PlatformProperties properties) {
        this.artefacts = artefacts;
        this.suppression = suppression;
        this.purposes = purposes;
        this.keys = keys;
        this.properties = properties;
    }

    /**
     * Builds and signs a snapshot for a subject.
     *
     * @param at evaluation instant, injected so that a snapshot can be reproduced exactly during
     *           an audit rather than depending on when the reproduction happens to run
     */
    @Transactional(readOnly = true)
    public SignedSnapshot issue(String entityId, String subjectId, Instant at) {
        Map<String, PurposeState> states = new HashMap<>();

        Map<String, ConsentArtefact> held = artefacts.findAllForSubject(entityId, subjectId)
                .stream()
                .collect(Collectors.toMap(ConsentArtefact::purposeCode, a -> a, (a, b) -> b));

        for (PurposeDefinition purpose : purposes.all()) {
            if (purpose.retired()) {
                continue;
            }
            ConsentArtefact artefact = held.get(purpose.code());

            ConsentStatus status = artefact == null
                    ? ConsentStatus.NOT_ASKED
                    : artefact.effectiveStatus(at);

            // Suppression is resolved here, on the server, and baked into the snapshot as a flag.
            // A device cannot check the national preference register, so if this were left out the
            // offline answer would be systematically more permissive than the online one.
            boolean suppressed = purpose.channels().stream()
                    .filter(channel -> channel.isCommercialCommunication())
                    .anyMatch(channel -> suppression
                            .findForSubject(entityId, subjectId, channel, null, null, at)
                            .isPresent());

            states.put(purpose.code(), new PurposeState(
                    status,
                    artefact == null ? null : artefact.legalBasis(),
                    artefact == null ? purpose.version() : artefact.purposeVersion(),
                    artefact == null ? null : artefact.expiresAt(),
                    purpose.failureBehavior(),
                    purpose.channels().stream().map(Enum::name).collect(Collectors.toSet()),
                    suppressed));
        }

        ConsentSnapshot snapshot = new ConsentSnapshot(
                UUID.randomUUID().toString(),
                entityId,
                subjectId,
                at,
                at.plus(properties.getSnapshot().getValidity()),
                properties.getPolicyVersion(),
                states);

        return keys.signer().sign(snapshot);
    }

    /** The purposes a snapshot will carry, for clients that want to size their local store. */
    public Set<String> snapshotPurposes() {
        return purposes.all().stream()
                .filter(purpose -> !purpose.retired())
                .map(PurposeDefinition::code)
                .collect(Collectors.toSet());
    }
}
