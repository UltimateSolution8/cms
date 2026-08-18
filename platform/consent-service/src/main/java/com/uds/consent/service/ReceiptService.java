package com.uds.consent.service;

import com.uds.consent.core.crypto.CanonicalJson;
import com.uds.consent.core.crypto.Hashes;
import com.uds.consent.core.model.ConsentArtefact;
import com.uds.consent.core.model.ConsentEvent;
import com.uds.consent.core.model.ConsentReceipt;
import com.uds.consent.core.model.ConsentStatus;
import com.uds.consent.core.model.GuardianVerification;
import com.uds.consent.core.model.GuardianVerificationMethod;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.PurposeDefinition;
import com.uds.consent.ledger.store.ConsentArtefactStore;
import com.uds.consent.ledger.store.ConsentEventStore;
import com.uds.consent.ledger.store.ConsentManagerStore;
import com.uds.consent.ledger.store.EntityStore;
import com.uds.consent.ledger.store.NoticeStore;
import com.uds.consent.ledger.store.ProcessingActivityStore;
import com.uds.consent.ledger.store.PurposeRegistryStore;
import com.uds.consent.ledger.store.ReceiptStore;
import com.uds.consent.ledger.store.StoredEvent;
import com.uds.consent.ledger.store.VendorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
    /**
     * The registry, read by version rather than the cached current-version catalogue.
     *
     * <p>{@code PolicyPorts.PurposeCatalog} answers "what does this purpose mean now", which is the
     * right question on the decision path and the wrong one here. A receipt is a statement about
     * what a person agreed to on a particular day. Rendering it from the current catalogue produced
     * a document that reported {@code purposeVersion: 3} in one field and described version 5's
     * name, data categories and sensitivity flag in the next — internally contradictory, and
     * contradictory in the direction that flatters the fiduciary, since the whole reason a purpose
     * is re-published is that its scope changed.
     */
    private final PurposeRegistryStore registry;
    private final ReceiptStore store;
    private final VendorStore vendors;
    private final ProcessingActivityStore activities;
    private final ConsentManagerStore managers;
    private final ConsentEventStore events;

    public ReceiptService(ConsentArtefactStore artefacts, EntityStore entities, NoticeStore notices,
                          PurposeRegistryStore registry, ReceiptStore store,
                          VendorStore vendors, ProcessingActivityStore activities,
                          ConsentManagerStore managers, ConsentEventStore events) {
        this.artefacts = artefacts;
        this.entities = entities;
        this.notices = notices;
        this.registry = registry;
        this.store = store;
        this.vendors = vendors;
        this.activities = activities;
        this.managers = managers;
        this.events = events;
    }

    /**
     * Issues a receipt and keeps it.
     *
     * <p>Persisting is what makes {@code receiptId} the thing its javadoc always claimed it was —
     * "a stable identifier the subject can quote in a grievance". Before this, {@code issue} minted
     * a fresh UUID on every call and stored nothing, so two requests a second apart produced two
     * identifiers for the same facts and neither could be looked up. A principal quoting their
     * receipt number to the grievance officer was quoting a number that existed nowhere.
     *
     * <p>The canonical payload is stored verbatim, using the same {@code CanonicalJson} the ledger
     * hashes its chain with. So a receipt can be verified by exactly the code path that verifies
     * an event, and {@link #reproduce} returns what the subject was given rather than what the same
     * query would produce today.
     */
    @Transactional
    public ConsentReceipt issue(String entityId, String subjectId, Instant at) {
        ConsentReceipt receipt = build(entityId, subjectId, at);
        String payload = CanonicalJson.serialize(receipt);

        store.save(new ReceiptStore.StoredReceipt(receipt.receiptId(), entityId, subjectId, at,
                payload, Hashes.sha256Hex(payload), receipt.evidenceHash(), receipt.noticeId(),
                receipt.noticeVersion(), receipt.languageTag(), receipt.entries().size()));
        return receipt;
    }

    /**
     * Returns a receipt exactly as it was issued.
     *
     * <p>Parsed back from the stored payload rather than rebuilt. Rebuilding would answer today's
     * version of a question asked last year — the registry moves, the DPO contact changes, a
     * consent live in March has since expired — and every one of those would rewrite a document
     * the subject is holding a copy of.
     */
    @Transactional(readOnly = true)
    public ConsentReceipt reproduce(String receiptId) {
        ReceiptStore.StoredReceipt stored = store.find(receiptId)
                .orElseThrow(() -> new ReceiptNotFoundException(receiptId));
        return CanonicalJson.parse(stored.payload(), ConsentReceipt.class);
    }

    /** The stored form, including the hash a holder can check their copy against. */
    @Transactional(readOnly = true)
    public ReceiptStore.StoredReceipt findStored(String receiptId) {
        return store.find(receiptId).orElseThrow(() -> new ReceiptNotFoundException(receiptId));
    }

    /** Every receipt issued to a subject, newest first. What a preference centre lists. */
    @Transactional(readOnly = true)
    public List<ReceiptStore.StoredReceipt> forSubject(String entityId, String subjectId,
                                                       int limit) {
        return forSubject(entityId, subjectId, limit, 0);
    }

    /** A page of them. The evidence bundle's truncation notice points a reader at this. */
    public List<ReceiptStore.StoredReceipt> forSubject(String entityId, String subjectId,
                                                       int limit, int offset) {
        return store.findForSubject(entityId, subjectId, limit, offset);
    }

    /** A receipt id that names nothing. Distinguished so the API answers 404 rather than 400. */
    public static class ReceiptNotFoundException extends RuntimeException {
        public ReceiptNotFoundException(String receiptId) {
            super("no receipt " + receiptId);
        }
    }

    /**
     * Builds a receipt covering everything on record for a subject.
     *
     * @param at instant the receipt describes; expiry is evaluated against it so that a receipt
     *           reproduced during an audit shows the state as it was, not as it is now
     */
    private ConsentReceipt build(String entityId, String subjectId, Instant at) {
        EntityStore.FiduciaryEntity entity = entities.find(entityId)
                .orElseThrow(() -> new IllegalArgumentException("unknown entity: " + entityId));

        List<ConsentArtefact> held = artefacts.findAllForSubject(entityId, subjectId);
        List<ConsentReceipt.Entry> entries = new ArrayList<>(held.size());

        String noticeId = null;
        Integer noticeVersion = null;
        String languageTag = null;
        String evidenceHash = null;

        for (ConsentArtefact artefact : held) {
            // The version on the artefact, not the current one. See the field's javadoc.
            //
            // Where that version row has gone — a hard delete against purpose_version, which
            // nothing in the platform does but which a DBA might — this degrades to the purpose
            // code alone rather than to the current definition. Falling back to the catalogue
            // would put the defect straight back, silently and only for the receipts nobody can
            // check. A receipt that names a purpose it cannot describe is visibly incomplete; one
            // that describes the wrong version is not, and that is the whole difference.
            Optional<PurposeDefinition> purpose =
                    registry.loadVersion(artefact.purposeCode(), artefact.purposeVersion());
            List<VendorStore.Vendor> processors =
                    vendors.vendorsForPurpose(entityId, artefact.purposeCode());
            List<ProcessingActivityStore.Activity> described =
                    activities.findForPurpose(entityId, artefact.purposeCode());

            ConsentStatus status = artefact.effectiveStatus(at);

            entries.add(new ConsentReceipt.Entry(
                    artefact.purposeCode(),
                    artefact.purposeVersion(),
                    purpose.map(PurposeDefinition::name).orElse(artefact.purposeCode()),
                    purpose.map(p -> List.copyOf(p.dataCategories())).orElse(List.of()),
                    artefact.legalBasis(),
                    status,
                    artefact.grantedAt(),
                    artefact.expiresAt(),
                    // Already on the projection and never carried onto the receipt until now. See
                    // the field's javadoc: the receipt a principal is most likely to ask for is the
                    // one issued after they withdrew, and it did not say when that was.
                    //
                    // Only where the entry is *currently* withdrawn. ArtefactProjector carries
                    // withdrawnAt forward across a later GRANTED, which is right for a projection
                    // and wrong for a document: a person who withdrew in March and consented again
                    // in July would otherwise hold a receipt reading GRANTED with a March
                    // withdrawal date, and neither they nor the Board could tell from it whether
                    // consent was live in between. That is the exact fact a grievance turns on.
                    status == ConsentStatus.WITHDRAWN ? artefact.withdrawnAt() : null,
                    recipients(processors, described),
                    crossBorderCountries(processors, described),
                    retentionPeriod(described),
                    purpose.map(PurposeDefinition::touchesSensitiveData).orElse(null)));

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

        if (noticeId == null) {
            // No artefact carries a notice, which is the ordinary state of a subject who has
            // withdrawn: the artefact is a projection of *current* state, a withdrawal is served
            // under no notice, and so the notice the person was actually shown is no longer on it.
            //
            // Reading it back off the chain instead. Found by walking the platform by hand — the
            // receipt a data principal is most likely to ask for is the one issued after they
            // withdrew, and that was precisely the one arriving with no noticeId, no language, no
            // evidence hash and none of the three links Rule 3 requires to be specific rather than
            // a general contact page. The document was at its least useful exactly when somebody
            // needed it most.
            for (StoredEvent stored : events.findChain(entityId, subjectId)) {
                ConsentEvent event = stored.event();
                if (event.noticeId() != null) {
                    // Not breaking: the chain is in sequence order, so running to the end leaves
                    // the most recent notice the subject was served, which is the same rule the
                    // artefact loop above applies.
                    noticeId = event.noticeId();
                    noticeVersion = event.noticeVersion();
                    languageTag = event.languageTag();
                    evidenceHash = event.eventHash();
                }
            }
        }

        Jurisdiction jurisdiction = entity.primaryJurisdiction();
        Optional<NoticeStore.NoticeVersion> notice = noticeId == null
                ? Optional.empty()
                : notices.findVersion(noticeId, noticeVersion == null ? 1 : noticeVersion);

        // Resolved up the group hierarchy rather than read off this entity's own row. V3 seeds
        // fifteen entities with neither field set, so every receipt issued until now named a null
        // DPO and — where the notice carried no grievance route either — a null grievance route.
        // Rule 3 requires the principal be given both. Setting them once on UDS now answers for
        // every subsidiary that has not set its own.
        EntityStore.Contacts contacts = entities.resolveContacts(entityId);

        return new ConsentReceipt(
                ConsentReceipt.SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                // Stable across every receipt issued to this principal by this fiduciary, which is
                // what makes it a record identifier rather than a second document identifier. It
                // discloses nothing further: subjectId is already a surrogate, and the entity is
                // named in full two fields below.
                entityId + ":" + subjectId,
                consentManagerFor(entityId, subjectId),
                at,
                entity.legalName(),
                entity.entityId(),
                contacts.dpoContact(),
                subjectId,
                jurisdiction,
                languageTag,
                noticeId,
                noticeVersion,
                entries,
                // Rule 3 requires a specific link for each of these, not a general contact page.
                notice.map(NoticeStore.NoticeVersion::withdrawalUri).orElse(null),
                notice.map(NoticeStore.NoticeVersion::rightsUri).orElse(null),
                notice.map(NoticeStore.NoticeVersion::grievanceUri)
                        .orElse(contacts.grievanceUri()),
                evidenceHash,
                parentalVerification(entityId, subjectId));
    }

    /**
     * The route by which a guardian was verified, if this subject's consent came through one.
     *
     * <p>Read back out of the ledger rather than passed down from the capture, so that a receipt
     * reproduced years later says the same thing as the one issued on the day. Null where no
     * capture on this subject's chain ever carried a verification — which for the overwhelming
     * majority of subjects means "this is an adult", and for a child capture made before this
     * field existed means "we do not know", and the receipt does not pretend to distinguish them.
     * It states what is on record and nothing more.
     */
    private GuardianVerificationMethod parentalVerification(String entityId, String subjectId) {
        return events.latestAttribute(entityId, subjectId, GuardianVerification.ATTR_METHOD)
                .map(GuardianVerificationMethod::valueOf)
                .orElse(null);
    }

    /**
     * Who else receives the data for a purpose.
     *
     * <p>Two sources, merged, because they answer the same question from different directions and
     * either alone would understate it. The vendor registry knows who is contractually authorised
     * and is enforced on the decision path; the processing activity's {@code recipients} is the
     * RoPA's free-text list, which is where a recipient that is not a vendor — a regulator, a
     * client the leads are delivered to — is written down.
     *
     * @return null where neither source holds anything for this purpose. Not an empty list: see the
     *         class javadoc on {@link ConsentReceipt}
     */
    private static List<String> recipients(List<VendorStore.Vendor> processors,
                                           List<ProcessingActivityStore.Activity> described) {
        if (processors.isEmpty() && described.isEmpty()) {
            return null;
        }
        Set<String> named = new LinkedHashSet<>();
        for (VendorStore.Vendor vendor : processors) {
            // The role, because "shared with Acme" and "shared with Acme, who is a joint
            // controller" are materially different statements to a data principal: in the second
            // Acme has its own obligations to them.
            named.add(vendor.role() == null || vendor.role().isBlank()
                    ? vendor.name() : vendor.name() + " (" + vendor.role() + ")");
        }
        for (ProcessingActivityStore.Activity activity : described) {
            if (activity.recipients() != null) {
                named.addAll(activity.recipients());
            }
        }
        return List.copyOf(named);
    }

    /**
     * Where the data goes outside India for a purpose.
     *
     * <p>The vendor registry's {@code countries} column is the place a transfer is most likely to
     * be recorded truthfully, because it is filled in when the vendor is onboarded rather than when
     * the RoPA is written. India is filtered out of it: a domestic processor is not a transfer, and
     * listing IN here would tell a principal their data left the country when it did not.
     */
    private static List<String> crossBorderCountries(
            List<VendorStore.Vendor> processors,
            List<ProcessingActivityStore.Activity> described) {

        if (processors.isEmpty() && described.isEmpty()) {
            return null;
        }
        // Sorted rather than insertion-ordered. This one is a set of facts with no natural
        // sequence, and a stable order keeps two receipts over the same state byte-identical.
        Set<String> countries = new TreeSet<>();
        for (VendorStore.Vendor vendor : processors) {
            if (vendor.countries() == null) {
                continue;
            }
            for (String country : vendor.countries()) {
                if (!"IN".equalsIgnoreCase(country) && !"India".equalsIgnoreCase(country)) {
                    countries.add(country);
                }
            }
        }
        for (ProcessingActivityStore.Activity activity : described) {
            if (activity.crossBorderCountries() != null) {
                countries.addAll(activity.crossBorderCountries());
            }
        }
        return List.copyOf(countries);
    }

    /**
     * How long the data is kept, as an ISO-8601 duration.
     *
     * <p>The longest of the recorded periods where a purpose is described by more than one
     * activity, because that is the one that answers the principal's actual question — when will
     * this be gone. Null where no activity records a period at all; the RoPA reports that gap as a
     * compliance finding, and inventing a default here would hide it behind a number.
     */
    private static String retentionPeriod(List<ProcessingActivityStore.Activity> described) {
        Integer longest = null;
        for (ProcessingActivityStore.Activity activity : described) {
            Integer days = activity.retentionPeriodDays();
            if (days != null && (longest == null || days > longest)) {
                longest = days;
            }
        }
        // Period, not Duration. Duration.ofDays(365) renders as "PT8760H" — correct, and
        // unreadable on a document handed to a member of the public. Period gives "P365D",
        // which is the same fact in the form a person can check against what they were told.
        return longest == null ? null : Period.ofDays(longest).toString();
    }

    /**
     * The Consent Manager a principal's consents currently arrive through, if any.
     *
     * <p>The most recent live link. A link that has been unlinked is deliberately not reported: the
     * principal has since taken their consent management elsewhere, and a receipt issued today
     * naming the intermediary they left would misdescribe the arrangement they are actually in.
     */
    private String consentManagerFor(String entityId, String subjectId) {
        return managers.linksForSubject(entityId, subjectId).stream()
                .filter(link -> link.unlinkedAt() == null)
                .map(ConsentManagerStore.Link::registrationId)
                .findFirst()
                .orElse(null);
    }
}
