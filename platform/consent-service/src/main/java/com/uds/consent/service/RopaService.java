package com.uds.consent.service;

import com.uds.consent.core.model.PurposeDefinition;
import com.uds.consent.ledger.store.AdminAuditStore;
import com.uds.consent.ledger.store.EntityStore;
import com.uds.consent.ledger.store.ProcessingActivityStore;
import com.uds.consent.ledger.store.VendorStore;
import com.uds.consent.service.adapter.CachingPurposeCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The Record of Processing Activities, and the vendor registry underneath it.
 *
 * <p>The export is assembled from the same purpose registry the decision engine reads, which is
 * the whole reason for holding the RoPA here rather than in a document. A hand-maintained RoPA
 * describes what someone believed the group was doing on the day they wrote it; this one cannot
 * describe a purpose the platform does not enforce, or omit one it does.
 *
 * <p>The export therefore ships its own gaps. An RoPA that lists only what is documented is a
 * document that looks complete because the missing parts are missing from it too.
 */
@Service
public class RopaService {

    private static final Logger log = LoggerFactory.getLogger(RopaService.class);

    private final ProcessingActivityStore activities;
    private final VendorStore vendors;
    private final EntityStore entities;
    private final CachingPurposeCatalog purposes;
    private final AdminAuditStore audit;

    public RopaService(ProcessingActivityStore activities, VendorStore vendors,
                       EntityStore entities, CachingPurposeCatalog purposes,
                       AdminAuditStore audit) {
        this.activities = activities;
        this.vendors = vendors;
        this.entities = entities;
        this.purposes = purposes;
        this.audit = audit;
    }

    /**
     * The full record for one entity, with its gaps stated.
     *
     * <p>Per entity, never per group. The entity is the unit of legal accountability — each one is
     * independently reportable and independently auditable — and a group-level rollup would be a
     * management view offered where a regulator asked a legal question.
     */
    @Transactional(readOnly = true)
    public Ropa forEntity(String entityId) {
        EntityStore.FiduciaryEntity entity = entities.find(entityId).orElseThrow(() ->
                new IllegalArgumentException("no fiduciary entity '" + entityId + "'"));

        List<ProcessingActivityStore.Activity> all = activities.findForEntity(entityId);
        List<VendorStore.Vendor> vendorList = vendors.findForEntity(entityId, false);

        List<ActivityEntry> entries = all.stream()
                .map(activity -> new ActivityEntry(activity, purposeFor(activity.purposeCode())))
                .toList();

        Gaps gaps = new Gaps(
                activities.purposesWithoutActivity(entityId),
                activities.findWithoutRetention(entityId).stream()
                        .map(ProcessingActivityStore.Activity::name).toList(),
                vendors.findWithoutDpa(entityId).stream()
                        .map(VendorStore.Vendor::name).toList());

        return new Ropa(entity, Instant.now(), entries, vendorList,
                activities.findCrossBorder(entityId),
                // Rule 13(4): transfers of a category the Government has prohibited a Significant
                // Data Fiduciary from moving out of India. Separate from crossBorderTransfers,
                // which is the Rule 15 report and binds every fiduciary, because those transfers
                // are lawful and documentable and these are neither — the remediation is to stop,
                // not to paper.
                // Empty on every entity today, because no categories are notified.
                activities.findRestrictedCrossBorder(entityId), gaps);
    }

    /**
     * Produces the export and records that it was produced.
     *
     * <p>Audited because an RoPA export is a snapshot handed to someone outside the platform —
     * a regulator, an auditor, a client's due-diligence team. Knowing which version of the record
     * a given party was shown, and when, is part of being able to stand behind it later.
     */
    // Not readOnly, unlike forEntity above: this one writes the audit row. Marking it read-only
    // would produce a method that reads perfectly, passes review, and fails on the first real
    // export — which is the moment somebody is standing in front of a regulator.
    @Transactional
    public Ropa export(String entityId, String recipient, String actorId) {
        Ropa ropa = forEntity(entityId);
        audit.record(actorId, "ROPA_EXPORTED", entityId, "processing_activity",
                recipient == null ? "unspecified-recipient" : recipient,
                Map.of("activities", String.valueOf(ropa.activities().size()),
                        "vendors", String.valueOf(ropa.vendors().size()),
                        "gaps", String.valueOf(ropa.gaps().total())));
        log.info("RoPA exported for {} to '{}': {} activities, {} vendors, {} gaps", entityId,
                recipient, ropa.activities().size(), ropa.vendors().size(), ropa.gaps().total());
        return ropa;
    }

    @Transactional
    public long createActivity(ProcessingActivityStore.Activity activity, String actorId) {
        long id = activities.create(activity);
        audit.record(actorId, "PROCESSING_ACTIVITY_CREATED", activity.entityId(),
                "processing_activity", String.valueOf(id),
                Map.of("name", activity.name(), "purposeCode", activity.purposeCode()));
        return id;
    }

    @Transactional
    public void updateActivity(long id, ProcessingActivityStore.Activity activity, String actorId) {
        activities.find(id).orElseThrow(() ->
                new IllegalArgumentException("no processing activity " + id));
        activities.update(id, activity);
        audit.record(actorId, "PROCESSING_ACTIVITY_UPDATED", activity.entityId(),
                "processing_activity", String.valueOf(id),
                Map.of("name", activity.name(), "purposeCode", activity.purposeCode()));
    }

    @Transactional
    public void upsertVendor(VendorStore.Vendor vendor, List<String> purposeCodes, String actorId) {
        vendors.upsert(vendor);
        if (purposeCodes != null) {
            vendors.setPurposes(vendor.vendorId(), purposeCodes);
        }
        audit.record(actorId, "VENDOR_REGISTERED", vendor.entityId(), "vendor",
                vendor.vendorId(),
                Map.of("name", vendor.name(), "role", vendor.role(),
                        "hasDpa", String.valueOf(vendor.hasDpa()),
                        "countries", String.join(",", vendor.countries())));

        if (!vendor.hasDpa() && vendor.active()) {
            // Not refused — a vendor may legitimately be registered before the agreement is
            // countersigned, and blocking the record would push it into a spreadsheet where
            // nobody tracks it at all. Logged so the gap is visible from the day it opens.
            log.warn("vendor {} ({}) registered for {} with no data processing agreement on record",
                    vendor.vendorId(), vendor.name(), vendor.entityId());
        }
    }

    @Transactional(readOnly = true)
    public List<VendorStore.Vendor> vendorsFor(String entityId, boolean activeOnly) {
        return vendors.findForEntity(entityId, activeOnly);
    }

    @Transactional(readOnly = true)
    public List<ProcessingActivityStore.Activity> activitiesFor(String entityId) {
        return activities.findForEntity(entityId);
    }

    private PurposeDefinition purposeFor(String purposeCode) {
        return purposes.all().stream()
                .filter(purpose -> purpose.code().equals(purposeCode))
                .findFirst()
                .orElse(null);
    }

    /**
     * @param purpose the registry entry the activity claims to serve. Null when the activity names
     *                a purpose the registry does not know — itself a finding, and left visible
     *                rather than dropped from the export
     */
    public record ActivityEntry(ProcessingActivityStore.Activity activity,
                                PurposeDefinition purpose) {

        public boolean purposeIsRegistered() {
            return purpose != null;
        }
    }

    /**
     * What the record does not cover.
     *
     * <p>Shipped as part of the export rather than as a separate report, because a gap in a
     * separate report is a gap in a report nobody opened.
     */
    public record Gaps(List<String> purposesWithoutActivity, List<String> activitiesWithoutRetention,
                       List<String> vendorsWithoutDpa) {

        public int total() {
            return purposesWithoutActivity.size() + activitiesWithoutRetention.size()
                    + vendorsWithoutDpa.size();
        }

        public boolean complete() {
            return total() == 0;
        }
    }

    /**
     * @param crossBorderTransfers transfers out of India to document. Lawful, and listed so they
     *                             can be shown to be. This is the DPDP <strong>Rule 15</strong>
     *                             position, which binds every Data Fiduciary rather than only the
     *                             Significant ones
     * @param prohibitedTransfers  transfers of a category DPDP <strong>Rule 13(4)</strong> forbids
     *                             a Significant Data Fiduciary moving out of India at all. (Cited
     *                             as Rule 14 until V21; Rule 14 is rights and grievance redressal.)
     *                             Not a documentation task: the remediation is to stop. Empty
     *                             on every entity as at August 2026, because the Government has
     *                             notified no categories — an empty list that has been checked
     *                             being a different fact from one that has not
     */
    public record Ropa(EntityStore.FiduciaryEntity entity, Instant generatedAt,
                       List<ActivityEntry> activities, List<VendorStore.Vendor> vendors,
                       List<ProcessingActivityStore.Activity> crossBorderTransfers,
                       List<ProcessingActivityStore.Activity> prohibitedTransfers, Gaps gaps) {

        /** Convenience for the console: does this entity have anything recorded at all. */
        public boolean populated() {
            return !activities.isEmpty();
        }

        /**
         * Whether anything here is unlawful rather than merely undocumented.
         *
         * <p>Distinct from {@code gaps.complete()} on purpose. A gap is work outstanding; this is
         * processing that must cease, and a report that ranked the two together would bury it.
         */
        public boolean hasProhibitedTransfer() {
            return !prohibitedTransfers.isEmpty();
        }
    }
}
