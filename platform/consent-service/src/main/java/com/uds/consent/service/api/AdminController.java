package com.uds.consent.service.api;

import com.uds.consent.core.model.PurposeDefinition;
import com.uds.consent.ledger.service.BlastRadiusService;
import com.uds.consent.ledger.service.LedgerIntegrityVerifier;
import com.uds.consent.ledger.store.EntityStore;
import com.uds.consent.ledger.store.ProvenanceStore;
import com.uds.consent.service.adapter.CachingPurposeCatalog;
import com.uds.consent.service.sweeper.IntegritySweeper;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final ProvenanceStore provenance;

    public AdminController(CachingPurposeCatalog purposes, EntityStore entities,
                           BlastRadiusService blastRadius, LedgerIntegrityVerifier verifier,
                           IntegritySweeper integritySweeper, ProvenanceStore provenance) {
        this.purposes = purposes;
        this.entities = entities;
        this.blastRadius = blastRadius;
        this.verifier = verifier;
        this.integritySweeper = integritySweeper;
        this.provenance = provenance;
    }

    /** The purpose registry as the decision engine currently sees it. */
    @GetMapping("/purposes")
    public List<PurposeDefinition> purposes() {
        return purposes.all();
    }

    /** Reloads the registry after a publish, without waiting for the refresh interval. */
    @PostMapping("/purposes/refresh")
    public Map<String, Object> refreshPurposes() {
        purposes.refresh();
        return Map.of("purposes", purposes.all().size(), "refreshed", true);
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
        return provenance.findQuarantined(entityId, limit, offset);
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
    public Map<String, Object> substantiate(@PathVariable long id,
                                             @RequestBody SubstantiateRequest request,
                                             Authentication authentication) {
        String actor = authentication == null ? "anonymous" : authentication.getName();
        provenance.substantiate(id, request.evidenceNote(), actor);
        return Map.of("id", id, "substantiated", true, "reviewedBy", actor);
    }

    public record SubstantiateRequest(@NotBlank String evidenceNote) {
    }
}
