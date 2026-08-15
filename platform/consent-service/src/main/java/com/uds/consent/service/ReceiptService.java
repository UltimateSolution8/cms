package com.uds.consent.service;

import com.uds.consent.core.model.ConsentArtefact;
import com.uds.consent.core.model.ConsentReceipt;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.PurposeDefinition;
import com.uds.consent.ledger.store.ConsentArtefactStore;
import com.uds.consent.ledger.store.EntityStore;
import com.uds.consent.ledger.store.NoticeStore;
import com.uds.consent.policy.port.PolicyPorts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues consent receipts to data principals.
 *
 * <p>Shaped along ISO/IEC TS 27560:2023, which descends from the Kantara Consent Receipt
 * specification. Worth stating plainly because the earlier research drafts conflated this with
 * IAB's Transparency and Consent Framework: TCF is an adtech signalling protocol, useful only as
 * an adapter at the edge, and never the record format.
 *
 * <p>A receipt is a statement issued to a person, not internal state. Every purpose gets its own
 * entry — a receipt that says "you agreed to our terms" tells the subject nothing and proves
 * nothing.
 */
@Service
public class ReceiptService {

    private final ConsentArtefactStore artefacts;
    private final EntityStore entities;
    private final NoticeStore notices;
    private final PolicyPorts.PurposeCatalog purposes;

    public ReceiptService(ConsentArtefactStore artefacts, EntityStore entities, NoticeStore notices,
                          PolicyPorts.PurposeCatalog purposes) {
        this.artefacts = artefacts;
        this.entities = entities;
        this.notices = notices;
        this.purposes = purposes;
    }

    /**
     * Builds a receipt covering everything on record for a subject.
     *
     * @param at instant the receipt describes; expiry is evaluated against it so that a receipt
     *           reproduced during an audit shows the state as it was, not as it is now
     */
    @Transactional(readOnly = true)
    public ConsentReceipt issue(String entityId, String subjectId, Instant at) {
        EntityStore.FiduciaryEntity entity = entities.find(entityId)
                .orElseThrow(() -> new IllegalArgumentException("unknown entity: " + entityId));

        List<ConsentArtefact> held = artefacts.findAllForSubject(entityId, subjectId);
        List<ConsentReceipt.Entry> entries = new ArrayList<>(held.size());

        String noticeId = null;
        Integer noticeVersion = null;
        String languageTag = null;
        String evidenceHash = null;

        for (ConsentArtefact artefact : held) {
            Optional<PurposeDefinition> purpose = purposes.find(artefact.purposeCode());

            entries.add(new ConsentReceipt.Entry(
                    artefact.purposeCode(),
                    artefact.purposeVersion(),
                    purpose.map(PurposeDefinition::name).orElse(artefact.purposeCode()),
                    purpose.map(p -> List.copyOf(p.dataCategories())).orElse(List.of()),
                    artefact.legalBasis(),
                    artefact.effectiveStatus(at),
                    artefact.grantedAt(),
                    artefact.expiresAt()));

            // The receipt reports the notice the subject most recently saw. Where different
            // purposes were captured under different notices this picks the latest, and the
            // per-purpose evidence trail remains available through the history endpoint.
            if (artefact.noticeId() != null) {
                noticeId = artefact.noticeId();
                noticeVersion = artefact.noticeVersion();
                languageTag = artefact.languageTag();
                evidenceHash = artefact.lastEventHash();
            }
        }

        Jurisdiction jurisdiction = entity.primaryJurisdiction();
        Optional<NoticeStore.NoticeVersion> notice = noticeId == null
                ? Optional.empty()
                : notices.findVersion(noticeId, noticeVersion == null ? 1 : noticeVersion);

        return new ConsentReceipt(
                UUID.randomUUID().toString(),
                at,
                entity.legalName(),
                entity.entityId(),
                entity.dpoContact(),
                subjectId,
                jurisdiction,
                languageTag,
                noticeId,
                noticeVersion,
                entries,
                // Rule 3 requires a specific link for each of these, not a general contact page.
                notice.map(NoticeStore.NoticeVersion::withdrawalUri).orElse(null),
                notice.map(NoticeStore.NoticeVersion::rightsUri).orElse(null),
                notice.map(NoticeStore.NoticeVersion::grievanceUri).orElse(entity.grievanceUri()),
                evidenceHash);
    }
}
