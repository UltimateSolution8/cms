package com.uds.consent.service.api;

import com.uds.consent.core.model.PurposeDefinition;
import com.uds.consent.ledger.service.BlastRadiusService;
import com.uds.consent.ledger.service.LedgerIntegrityVerifier;
import com.uds.consent.ledger.store.AdminAuditStore;
import com.uds.consent.ledger.store.ApplicationRegistryStore;
import com.uds.consent.ledger.store.EntityStore;
import com.uds.consent.ledger.store.ProcessingActivityStore;
import com.uds.consent.ledger.store.ProvenanceStore;
import com.uds.consent.ledger.store.VendorStore;
import com.uds.consent.service.ProvenanceService;
import com.uds.consent.service.RopaService;
import com.uds.consent.service.adapter.CachingApplicationRegistry;
import com.uds.consent.service.adapter.CachingPurposeCatalog;
import com.uds.consent.service.sweeper.IntegritySweeper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The compliance console's API.
 *
 * <p>Everything here is administrative and every caller is attributable. Note what is absent:
 * there is no endpoint that edits a consent record. Corrections are appended as events with an
 * actor and a reason, which is why the ledger can be offered as evidence at all.
 */
@RestController
@RequestMapping("/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final CachingPurposeCatalog purposes;
    private final EntityStore entities;
    private final BlastRadiusService blastRadius;
    private final LedgerIntegrityVerifier verifier;
    private final IntegritySweeper integritySweeper;
    private final ProvenanceService provenance;
    private final RopaService ropa;
    private final AdminAuditStore auditStore;
    private final CachingApplicationRegistry applications;
    private final ApplicationRegistryStore applicationStore;

    public AdminController(CachingPurposeCatalog purposes, EntityStore entities,
                           BlastRadiusService blastRadius, LedgerIntegrityVerifier verifier,
                           IntegritySweeper integritySweeper, ProvenanceService provenance,
                           RopaService ropa, AdminAuditStore auditStore,
                           CachingApplicationRegistry applications,
                           ApplicationRegistryStore applicationStore) {
        this.applications = applications;
        this.applicationStore = applicationStore;
        this.purposes = purposes;
        this.entities = entities;
        this.blastRadius = blastRadius;
        this.verifier = verifier;
        this.integritySweeper = integritySweeper;
        this.provenance = provenance;
        this.ropa = ropa;
        this.auditStore = auditStore;
    }

    /** The purpose registry as the decision engine currently sees it. */
    @GetMapping("/purposes")
    public List<PurposeDefinition> purposes() {
        return purposes.all();
    }

    /**
     * Reloads the in-memory registries after a publish, without waiting for the refresh interval.
     *
     * <p>Refreshes purposes and applications together. They are both read on the capture path, and
     * refreshing one without the other leaves a window in which a submission is checked against a
     * new purpose registry and an old application registry — producing a rejection that resolves
     * itself minutes later, which is the hardest kind of failure to get anyone to believe.
     */
    @PostMapping("/purposes/refresh")
    public Map<String, Object> refreshRegistries() {
        purposes.refresh();
        applications.refresh();
        return Map.of("purposes", purposes.all().size(),
                "applications", applications.size(),
                "refreshed", true);
    }

    /** Surfaces registered to submit consent. */
    @GetMapping("/applications")
    public List<ApplicationRegistryStore.Application> applications(
            @RequestParam(required = false) String entityId) {
        return entityId == null ? applicationStore.findAll()
                : applicationStore.findForEntity(entityId);
    }

    /**
     * Registers a surface, or updates one.
     *
     * <p>Refreshes the cache in the same call. Registering an application and then having it
     * rejected for five minutes would teach every integrator that the registry is unreliable, and
     * a control people work around is worse than no control.
     */
    @PutMapping("/applications/{applicationId}")
    public ApplicationRegistryStore.Application registerApplication(
            @PathVariable String applicationId,
            @Valid @RequestBody ApplicationRequest request,
            Authentication authentication) {
        ApplicationRegistryStore.Application application = new ApplicationRegistryStore.Application(
                applicationId, request.entityId(), request.name(), request.platform(),
                request.environment(), request.description(), request.active());
        applicationStore.upsert(application);
        applications.refresh();

        auditStore.record(actorOf(authentication), "APPLICATION_REGISTERED", request.entityId(),
                "application_registry", applicationId,
                Map.of("name", request.name(), "platform", request.platform(),
                        "environment", request.environment(),
                        "active", String.valueOf(request.active())));
        return application;
    }

    public record ApplicationRequest(
            @NotBlank String entityId,
            @NotBlank String name,
            @NotBlank String platform,
            @NotBlank String environment,
            String description,
            boolean active) {
    }

    /** The group's entity structure. */
    @GetMapping("/entities")
    public List<EntityStore.FiduciaryEntity> entities() {
        return entities.findAll();
    }

    /**
     * Who is affected by a purpose change, and what has to happen to them.
     *
     * <p>Run this <em>before</em> publishing. It is the difference between knowing that eleven
     * thousand subjects need re-consent and finding out when someone complains — and the number
     * it returns is the size of a re-permissioning campaign, which is a budget line, not a
     * technical detail.
     */
    @GetMapping("/blast-radius/purpose/{purposeCode}")
    public BlastRadiusService.Impact purposeBlastRadius(@PathVariable String purposeCode,
                                                        @RequestParam int newVersion) {
        return blastRadius.forPurposeChange(purposeCode, newVersion);
    }

    /** Who is affected by a notice change, per purpose that points at it. */
    @GetMapping("/blast-radius/notice/{noticeId}")
    public List<BlastRadiusService.Impact> noticeBlastRadius(@PathVariable String noticeId,
                                                              @RequestParam int newVersion) {
        return blastRadius.forNoticeChange(noticeId, newVersion);
    }

    /** Verifies one subject's hash chain, on demand. What a dispute about one person needs. */
    @GetMapping("/integrity/{entityId}/{subjectId}")
    public LedgerIntegrityVerifier.ChainVerification verifyChain(@PathVariable String entityId,
                                                                  @PathVariable String subjectId) {
        return verifier.verifyChain(entityId, subjectId);
    }

    /** Runs the full integrity sweep now, rather than waiting for the nightly schedule. */
    @PostMapping("/integrity/sweep")
    public IntegritySweeper.Report sweepIntegrity() {
        return integritySweeper.run();
    }

    /** The most recent sweep result. */
    @GetMapping("/integrity/last")
    public IntegritySweeper.Report lastIntegrityReport() {
        return integritySweeper.lastReport();
    }

    /**
     * Records that cannot be contacted because their origin is unsubstantiated.
     *
     * <p>The Phase 0 triage queue for Denave's prospect database. Every record here is one the
     * group holds and cannot lawfully use until someone produces the evidence behind it.
     */
    @GetMapping("/provenance/quarantined")
    public List<ProvenanceStore.Record> quarantined(@RequestParam String entityId,
                                                     @RequestParam(defaultValue = "100") int limit,
                                                     @RequestParam(defaultValue = "0") int offset) {
        return provenance.quarantined(entityId, limit, offset);
    }

    /**
     * Quarantine and contactability by source.
     *
     * <p>The report that turns a vague worry about purchased data into a number leadership can
     * act on — which is why the plan puts this in Phase 0 rather than letting it surface
     * mid-pilot.
     */
    @GetMapping("/provenance/summary")
    public List<ProvenanceStore.SourceSummary> provenanceSummary(@RequestParam String entityId) {
        return provenance.summariseBySource(entityId);
    }

    /**
     * Releases a record from quarantine.
     *
     * <p>Requires a note describing the evidence, and records who accepted it. Substantiation is
     * a judgement someone makes and stands behind, not a flag that gets flipped in bulk.
     */
    @PostMapping("/provenance/{id}/substantiate")
    public ProvenanceStore.Record substantiate(@PathVariable long id,
                                                @Valid @RequestBody SubstantiateRequest request,
                                                Authentication authentication) {
        String actor = authentication == null ? "anonymous" : authentication.getName();
        return provenance.substantiate(id, request.evidenceNote(), actor);
    }

    public record SubstantiateRequest(@NotBlank String evidenceNote) {
    }

    // ---------------------------------------------------------------------------------------
    // Record of Processing Activities
    // ---------------------------------------------------------------------------------------

    /**
     * The full RoPA for one entity, gaps included.
     *
     * <p>Per entity, never per group: each entity is independently reportable and independently
     * auditable, and a group rollup would answer a management question where a regulator asked a
     * legal one.
     */
    @GetMapping("/ropa/{entityId}")
    public RopaService.Ropa ropa(@PathVariable String entityId) {
        return ropa.forEntity(entityId);
    }

    /**
     * The same record, marked as having been handed to someone.
     *
     * <p>Separate from the read above so that a console refresh does not fill the audit trail with
     * exports that never left the building. What this records is that a named party was given the
     * record on a date — which is what makes it possible to stand behind it later.
     */
    @PostMapping("/ropa/{entityId}/export")
    public RopaService.Ropa exportRopa(@PathVariable String entityId,
                                        @RequestParam(required = false) String recipient,
                                        Authentication authentication) {
        return ropa.export(entityId, recipient, actorOf(authentication));
    }

    @GetMapping("/processing-activities")
    public List<ProcessingActivityStore.Activity> processingActivities(
            @RequestParam String entityId) {
        return ropa.activitiesFor(entityId);
    }

    @PostMapping("/processing-activities")
    public Map<String, Object> createProcessingActivity(
            @Valid @RequestBody ProcessingActivityStore.Activity activity,
            Authentication authentication) {
        long id = ropa.createActivity(activity, actorOf(authentication));
        return Map.of("id", id, "created", true);
    }

    @PutMapping("/processing-activities/{id}")
    public Map<String, Object> updateProcessingActivity(
            @PathVariable long id,
            @Valid @RequestBody ProcessingActivityStore.Activity activity,
            Authentication authentication) {
        ropa.updateActivity(id, activity, actorOf(authentication));
        return Map.of("id", id, "updated", true);
    }

    // ---------------------------------------------------------------------------------------
    // Vendors and processors
    // ---------------------------------------------------------------------------------------

    @GetMapping("/vendors")
    public List<VendorStore.Vendor> vendors(@RequestParam String entityId,
                                             @RequestParam(defaultValue = "false")
                                             boolean activeOnly) {
        return ropa.vendorsFor(entityId, activeOnly);
    }

    /**
     * Registers or updates a processor.
     *
     * <p>A vendor with no data processing agreement is accepted and logged rather than refused.
     * Refusing it would push the relationship into a spreadsheet where nothing tracks it; the
     * missing agreement instead shows up as a gap on every RoPA export until it is closed.
     */
    @PutMapping("/vendors/{vendorId}")
    public Map<String, Object> upsertVendor(@PathVariable String vendorId,
                                             @Valid @RequestBody VendorRequest request,
                                             Authentication authentication) {
        VendorStore.Vendor vendor = new VendorStore.Vendor(vendorId, request.entityId(),
                request.name(), request.role(), request.countries(), request.dpaReference(),
                request.dpaSignedAt(), request.active());
        ropa.upsertVendor(vendor, request.purposeCodes(), actorOf(authentication));
        return Map.of("vendorId", vendorId, "registered", true, "hasDpa", vendor.hasDpa());
    }

    public record VendorRequest(
            @NotBlank String entityId,
            @NotBlank String name,
            @NotBlank String role,
            List<String> countries,
            String dpaReference,
            java.time.LocalDate dpaSignedAt,
            boolean active,
            List<String> purposeCodes) {

        public VendorRequest {
            countries = countries == null ? List.of() : List.copyOf(countries);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Administrative audit trail
    // ---------------------------------------------------------------------------------------

    /**
     * Who did what in the control plane.
     *
     * <p>The trail is append-only and enforced as such by trigger, and until now nothing could
     * read it. An immutable audit trail nobody can see is an audit trail nobody checks — which
     * makes it a control in name and a cost in practice.
     *
     * <p>{@code entityId} is optional: group-level actions such as loading a statutory suppression
     * registry belong to no single entity, and omitting the filter is how they are found.
     */
    @GetMapping("/audit")
    public List<AdminAuditStore.Entry> auditTrail(
            @RequestParam(required = false) String entityId,
            @RequestParam(defaultValue = "100") int limit) {
        return auditStore.recent(entityId, Math.min(limit, 1000));
    }

    private static String actorOf(Authentication authentication) {
        return authentication == null ? "anonymous" : authentication.getName();
    }
}
