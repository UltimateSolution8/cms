package com.uds.consent.service.it;

import com.uds.consent.ledger.store.AdminAuditStore;
import com.uds.consent.ledger.store.ProcessingActivityStore;
import com.uds.consent.ledger.store.VendorStore;
import com.uds.consent.service.RopaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Record of Processing Activities.
 *
 * <p>The first artefact a regulator asks for, and the one most often assembled in a spreadsheet
 * the week before an audit. The property worth testing is not that the export renders — it is that
 * the export <strong>ships its own gaps</strong>. An RoPA listing only what somebody remembered to
 * document looks complete precisely because the missing parts are missing from it too.
 */
class RopaIT extends PostgresIntegrationTest {

    private static final String ENTITY = "DENAVE_IN";

    @Autowired
    private RopaService ropa;

    @Autowired
    private ProcessingActivityStore activities;

    @Autowired
    private VendorStore vendors;

    @Autowired
    private AdminAuditStore audit;

    @Test
    @DisplayName("an activity joins to its purpose in the registry the engine actually enforces")
    void activityResolvesToARegisteredPurpose() {
        // This is why the RoPA lives here rather than in a document. The record describing what
        // the group does and the code deciding what the group may do read the same registry, so
        // they cannot drift apart.
        long id = ropa.createActivity(activity("Outbound telemarketing " + UUID.randomUUID(),
                "MKT_OUTBOUND_CALL", 730, "TRAI record-keeping"), "compliance-console");

        RopaService.Ropa record = ropa.forEntity(ENTITY);

        assertThat(record.activities())
                .filteredOn(entry -> entry.activity().id() == id)
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.purposeIsRegistered()).isTrue();
                    assertThat(entry.purpose().code()).isEqualTo("MKT_OUTBOUND_CALL");
                });
    }

    @Test
    @DisplayName("the export names purposes the platform enforces but nobody has documented")
    void gapsIncludePurposesWithNoActivity() {
        // The gap a spreadsheet cannot find: the group is making decisions about a purpose it has
        // never described. Seeded purposes far outnumber seeded activities, so this reads long.
        RopaService.Ropa record = ropa.forEntity(ENTITY);

        assertThat(record.gaps().purposesWithoutActivity()).isNotEmpty();
        assertThat(record.gaps().complete()).isFalse();
    }

    @Test
    @DisplayName("an activity with no retention rule is reported as a gap, not omitted")
    void activityWithoutRetentionIsAGap() {
        String name = "Undocumented retention " + UUID.randomUUID();
        ropa.createActivity(activity(name, "MKT_OUTBOUND_EMAIL", null, null),
                "compliance-console");

        // DPDP requires erasure once the purpose is served. An activity that cannot say how long
        // it keeps data cannot demonstrate it erases anything.
        assertThat(ropa.forEntity(ENTITY).gaps().activitiesWithoutRetention()).contains(name);
    }

    @Test
    @DisplayName("a vendor with no signed DPA is registered and reported, not refused")
    void vendorWithoutDpaIsRegisteredAndFlagged() {
        // Refusing the registration would push the relationship into a spreadsheet where nothing
        // tracks it at all. Accepting it and reporting the gap keeps it visible from day one.
        String vendorId = "VEN-" + UUID.randomUUID();
        ropa.upsertVendor(new VendorStore.Vendor(vendorId, ENTITY, "Unpapered Telecoms Ltd",
                        "PROCESSOR", List.of("IN"), null, null, true),
                List.of("MKT_OUTBOUND_CALL"), "compliance-console");

        RopaService.Ropa record = ropa.forEntity(ENTITY);

        assertThat(record.vendors()).extracting(VendorStore.Vendor::vendorId).contains(vendorId);
        assertThat(record.gaps().vendorsWithoutDpa()).contains("Unpapered Telecoms Ltd");
    }

    @Test
    @DisplayName("a vendor with a signed DPA does not appear in the gap list")
    void vendorWithDpaIsNotAGap() {
        String vendorId = "VEN-" + UUID.randomUUID();
        String name = "Papered Cloud " + UUID.randomUUID();
        ropa.upsertVendor(new VendorStore.Vendor(vendorId, ENTITY, name, "PROCESSOR",
                        List.of("IN", "SG"), "DPA-2026-014", LocalDate.of(2026, 2, 11), true),
                List.of("MKT_OUTBOUND_EMAIL"), "compliance-console");

        assertThat(ropa.forEntity(ENTITY).gaps().vendorsWithoutDpa()).doesNotContain(name);
        assertThat(vendors.find(vendorId)).get()
                .satisfies(vendor -> {
                    assertThat(vendor.hasDpa()).isTrue();
                    assertThat(vendor.countries()).containsExactly("IN", "SG");
                });
    }

    @Test
    @DisplayName("vendor authorisation is per purpose, not blanket")
    void vendorAuthorisationIsPerPurpose() {
        // A vendor authorised for telemarketing is not thereby authorised for profiling. The
        // registry is what makes that distinction enforceable rather than merely contractual.
        String vendorId = "VEN-" + UUID.randomUUID();
        ropa.upsertVendor(new VendorStore.Vendor(vendorId, ENTITY, "Narrow Scope Ltd", "PROCESSOR",
                        List.of("IN"), "DPA-2026-021", LocalDate.of(2026, 1, 5), true),
                List.of("MKT_OUTBOUND_CALL"), "compliance-console");

        assertThat(vendors.isAuthorisedFor(vendorId, "MKT_OUTBOUND_CALL")).isTrue();
        assertThat(vendors.isAuthorisedFor(vendorId, "WEB_ADVERTISING")).isFalse();
    }

    @Test
    @DisplayName("deactivating a vendor withdraws its authorisation")
    void inactiveVendorLosesAuthorisation() {
        String vendorId = "VEN-" + UUID.randomUUID();
        VendorStore.Vendor vendor = new VendorStore.Vendor(vendorId, ENTITY, "Ends Soon Ltd",
                "PROCESSOR", List.of("IN"), "DPA-2026-030", LocalDate.of(2026, 3, 1), true);

        ropa.upsertVendor(vendor, List.of("MKT_OUTBOUND_CALL"), "compliance-console");
        assertThat(vendors.isAuthorisedFor(vendorId, "MKT_OUTBOUND_CALL")).isTrue();

        ropa.upsertVendor(new VendorStore.Vendor(vendorId, ENTITY, "Ends Soon Ltd", "PROCESSOR",
                        List.of("IN"), "DPA-2026-030", LocalDate.of(2026, 3, 1), false),
                List.of("MKT_OUTBOUND_CALL"), "compliance-console");

        assertThat(vendors.isAuthorisedFor(vendorId, "MKT_OUTBOUND_CALL")).isFalse();
    }

    @Test
    @DisplayName("cross-border activities are listed separately, because each is a transfer")
    void crossBorderTransfersAreListed() {
        String name = "Denave UK demand gen " + UUID.randomUUID();
        ProcessingActivityStore.Activity offshore = new ProcessingActivityStore.Activity(null,
                ENTITY, name, "Prospect outreach run from the UK entity", "MKT_OUTBOUND_EMAIL",
                "DenCRM", List.of("CONTACT_BUSINESS"), List.of("Denave UK"), List.of("GB", "SG"),
                365, "Client contract term plus one year", "denave-dpo", null);

        ropa.createActivity(offshore, "compliance-console");

        assertThat(ropa.forEntity(ENTITY).crossBorderTransfers())
                .extracting(ProcessingActivityStore.Activity::name)
                .contains(name);
    }

    @Test
    @DisplayName("an activity naming a purpose the registry does not know stays visible")
    void unregisteredPurposeIsNotSilentlyDropped() {
        // Dropping it from the export would hide a real finding — the group describing processing
        // that the enforcement plane knows nothing about.
        String name = "Orphan activity " + UUID.randomUUID();
        activities.create(new ProcessingActivityStore.Activity(null, ENTITY, name, null,
                "MKT_OUTBOUND_CALL", "LegacySystem", List.of(), List.of(), List.of(), 90,
                "ninety days", null, null));

        assertThat(ropa.forEntity(ENTITY).activities())
                .extracting(entry -> entry.activity().name())
                .contains(name);
    }

    @Test
    @DisplayName("exporting is audited with who received it")
    void exportIsAudited() {
        String recipient = "DPB-inspection-" + UUID.randomUUID();
        ropa.export(ENTITY, recipient, "compliance-console");

        assertThat(audit.recent(ENTITY, 200))
                .filteredOn(entry -> "ROPA_EXPORTED".equals(entry.action())
                        && recipient.equals(entry.targetId()))
                .singleElement()
                .satisfies(entry -> assertThat(entry.actorId()).isEqualTo("compliance-console"));
    }

    @Test
    @DisplayName("reading the record does not fill the audit trail with exports that never left")
    void plainReadIsNotAudited() {
        int before = audit.recent(ENTITY, 500).size();
        ropa.forEntity(ENTITY);
        ropa.forEntity(ENTITY);

        assertThat(audit.recent(ENTITY, 500)).hasSize(before);
    }

    @Test
    @DisplayName("the record is per entity, because the entity is the unit of accountability")
    void ropaIsScopedToOneEntity() {
        String name = "Matrix BGV screening " + UUID.randomUUID();
        ropa.createActivity(new ProcessingActivityStore.Activity(null, "MATRIX", name, null,
                        "BGV_CRIMINAL_RECORD", "BGV workflow", List.of(), List.of(),
                        List.of(), 1095, "Client contract", null, null),
                "compliance-console");

        assertThat(ropa.forEntity("MATRIX").activities())
                .extracting(entry -> entry.activity().name()).contains(name);
        assertThat(ropa.forEntity(ENTITY).activities())
                .extracting(entry -> entry.activity().name()).doesNotContain(name);
    }

    // -------------------------------------------------------------------------------------------

    private static ProcessingActivityStore.Activity activity(String name, String purposeCode,
                                                             Integer retentionDays,
                                                             String retentionBasis) {
        return new ProcessingActivityStore.Activity(null, ENTITY, name, null, purposeCode,
                "DenCRM", List.of("CONTACT_BUSINESS"), List.of(), List.of(), retentionDays,
                retentionBasis, "denave-dpo", null);
    }
}
