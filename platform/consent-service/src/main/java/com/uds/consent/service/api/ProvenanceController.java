package com.uds.consent.service.api;

import com.uds.consent.core.model.IdentifierType;
import com.uds.consent.core.model.LegalBasis;
import com.uds.consent.core.model.ProvenanceSourceType;
import com.uds.consent.ledger.store.ProvenanceStore;
import com.uds.consent.service.ProvenanceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Recording where contact data came from.
 *
 * <p>The write path the platform was missing. Without it the prospect-database backfill — the
 * exercise the plan calls the single biggest commercial risk in the programme — had nowhere to
 * put its output.
 *
 * <p>No endpoint here can mark a record substantiated. That is only reachable through
 * {@code POST /v1/admin/provenance/{id}/substantiate}, one record at a time, with a named
 * reviewer and a note describing the evidence. Importing data and clearing it for use are two
 * different acts by two different people, and the API is shaped so they cannot be the same call.
 */
@RestController
@RequestMapping("/v1/provenance")
public class ProvenanceController {

    /**
     * A batch ceiling, because the report is built in memory and returned in one response.
     * Larger files are the importer's job to chunk — and chunking gives the operator a progress
     * signal that a single twelve-minute request would not.
     */
    private static final int MAX_BATCH = 5_000;

    private final ProvenanceService provenance;

    public ProvenanceController(ProvenanceService provenance) {
        this.provenance = provenance;
    }

    /** Records where one contact came from. Lands quarantined. */
    @PostMapping
    @PreAuthorize("hasAnyRole('CAPTURE', 'ADMIN')")
    public ProvenanceService.Result record(@Valid @RequestBody ProvenanceRequest request,
                                           Authentication authentication) {
        return provenance.record(request.toSubmission(request.entityId()), actorOf(authentication));
    }

    /**
     * The backfill endpoint: many records, one report, no all-or-nothing failure.
     *
     * <p>Idempotent per row on entity, subject, source type, source name and acquisition date, so
     * a re-run after a truncated file does not double the quarantine count — and the quarantine
     * count is what a re-permissioning budget is sized against.
     *
     * <p>ADMIN rather than CAPTURE. A bulk assertion about the origin of two hundred thousand
     * people is a compliance act, not a capture surface doing its job.
     */
    @PostMapping("/bulk")
    @PreAuthorize("hasRole('ADMIN')")
    public ProvenanceService.BatchResult recordBatch(@Valid @RequestBody BulkProvenanceRequest req,
                                                     Authentication authentication) {
        if (req.records().size() > MAX_BATCH) {
            throw new IllegalArgumentException("batch of " + req.records().size()
                    + " exceeds the limit of " + MAX_BATCH + "; split the file and resubmit");
        }
        List<ProvenanceService.Submission> submissions = req.records().stream()
                .map(row -> row.toSubmission(req.entityId()))
                .toList();
        return provenance.recordBatch(req.entityId(), submissions, req.batchRef(),
                administratorOf(authentication));
    }

    /**
     * Whether a subject's origin has been substantiated, and what it is.
     *
     * <p>Available to DECISION callers as well: a campaign tool deciding whether to include
     * someone needs to be able to see why they were excluded, or the exclusion looks like a bug
     * and someone routes around it.
     */
    @GetMapping("/{entityId}/{subjectId}")
    @PreAuthorize("hasAnyRole('DECISION', 'CAPTURE', 'ADMIN')")
    public ProvenanceStore.Record forSubject(@PathVariable String entityId,
                                             @PathVariable String subjectId) {
        return provenance.latestForSubject(entityId, subjectId).orElseThrow(() ->
                new IllegalArgumentException("no provenance record for that subject. A subject "
                        + "with none is not quarantined — that is the ordinary case for someone "
                        + "who consented directly, where the consent event is the provenance."));
    }

    /** Quarantine and contactability by source: the leadership report. */
    @GetMapping("/summary")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ProvenanceStore.SourceSummary> summary(@RequestParam String entityId) {
        return provenance.summariseBySource(entityId);
    }

    /**
     * The caller, for routes a machine holds.
     *
     * <p>Deliberately the credential and not the {@code X-UDS-Actor} header. A dialer scrubbing a
     * campaign list, a website recording an opt-out and a CRM asserting provenance are systems,
     * not people, and there is no human behind the call to name. Recording the credential is the
     * accurate answer — and honouring a header on these routes would be worse than useless, since
     * it would let any capture surface write an arbitrary name into evidence about who did
     * something no person did.
     *
     * <p>The administrative routes in this controller use {@link #administratorOf} instead, which
     * requires the header. The split follows the authorisation model rather than the file: this
     * class serves both machine and console callers, and attribution should mean different things
     * for each.
     */
    private static String actorOf(Authentication authentication) {
        return authentication == null ? "anonymous" : authentication.getName();
    }

    /**
     * The person taking an administrative action, refusing when the caller did not say who.
     *
     * <p>Used on the ADMIN-only routes here. {@code compliance-console} is one credential held by
     * a team, so an append-only audit row naming it is permanently ambiguous about who authorised
     * the action — which is the one question an audit trail exists to answer.
     */
    private static String administratorOf(Authentication authentication) {
        return Actor.required(authentication).actorId();
    }

    /**
     * One provenance assertion.
     *
     * <p>{@code sourceName} is required even though {@code sourceType} is already a controlled
     * vocabulary. Knowing a record was purchased is not actionable; knowing which vendor sold it
     * is what lets the group go back to that vendor for evidence, or write off everything from
     * them at once.
     */
    public record ProvenanceRequest(
            String entityId,
            String subjectId,
            IdentifierType identifierType,
            String identifierValue,
            @NotNull ProvenanceSourceType sourceType,
            @NotBlank String sourceName,
            @NotNull Instant acquiredAt,
            LegalBasis originalLegalBasis,
            String evidenceRef,
            String contractRef) {

        ProvenanceService.Submission toSubmission(String defaultEntityId) {
            return new ProvenanceService.Submission(
                    entityId == null || entityId.isBlank() ? defaultEntityId : entityId,
                    subjectId, identifierType, identifierValue, sourceType, sourceName,
                    acquiredAt, originalLegalBasis, evidenceRef, contractRef);
        }
    }

    /**
     * @param batchRef names the file or job. Ends up in the audit trail, which is where someone
     *                 in 2029 asks which import created a record they are looking at
     */
    public record BulkProvenanceRequest(
            @NotBlank String entityId,
            String batchRef,
            @NotEmpty @Size(max = MAX_BATCH) List<@Valid ProvenanceRequest> records) {
    }
}
