package com.uds.consent.service.api;

import com.uds.consent.core.model.ActorType;
import com.uds.consent.core.model.ConsentEvent;
import com.uds.consent.core.model.PurposeDefinition;
import com.uds.consent.ledger.service.BlastRadiusService;
import com.uds.consent.ledger.service.LedgerIntegrityVerifier;
import com.uds.consent.ledger.service.ProjectionReconciler;
import com.uds.consent.ledger.store.AdminAuditStore;
import com.uds.consent.ledger.store.AlgorithmicSystemStore;
import com.uds.consent.ledger.store.ApplicationRegistryStore;
import com.uds.consent.ledger.store.ConsentManagerStore;
import com.uds.consent.ledger.store.DltRegistryStore;
import com.uds.consent.ledger.store.EnforcementEvidenceStore;
import com.uds.consent.ledger.store.EntityStore;
import com.uds.consent.ledger.store.ProcessingActivityStore;
import com.uds.consent.ledger.store.ProvenanceStore;
import com.uds.consent.ledger.store.ReconfirmationStore;
import com.uds.consent.ledger.store.RetentionStore;
import com.uds.consent.ledger.store.PropagationTargetStore;
import com.uds.consent.ledger.store.PropagationCoverageStore;
import com.uds.consent.ledger.store.PropagationGapStore;
import com.uds.consent.ledger.store.PropagationSystemStore;
import com.uds.consent.ledger.store.RightsFulfilmentStore;
import com.uds.consent.ledger.store.SdfObligationStore;
import com.uds.consent.ledger.store.SigningKeyStore;
import com.uds.consent.ledger.store.SweepRunStore;
import com.uds.consent.ledger.store.SubjectStore;
import com.uds.consent.ledger.store.VendorStore;
import com.uds.consent.ledger.store.WebhookStore;
import com.uds.consent.service.ConsentCaptureService;
import com.uds.consent.service.EnforcementRecorder;
import com.uds.consent.service.EvidenceBundleService;
import com.uds.consent.service.ProvenanceService;
import com.uds.consent.service.RopaService;
import com.uds.consent.service.SdfObligationService;
import com.uds.consent.service.api.dto.ConsentApi;
import com.uds.consent.service.api.dto.ConsentManagerApi;
import com.uds.consent.service.adapter.CachingApplicationRegistry;
import com.uds.consent.service.adapter.CachingPurposeCatalog;
import com.uds.consent.service.config.FeatureDisabledException;
import com.uds.consent.service.config.PlatformProperties;
import com.uds.consent.service.sweeper.IntegritySweeper;
import com.uds.consent.service.sweeper.ProjectionReconciliationSweeper;
import com.uds.consent.service.sweeper.ReconfirmationSweeper;
import com.uds.consent.service.sweeper.RetentionSweeper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
    private final ConsentCaptureService capture;
    private final EnforcementEvidenceStore evidence;
    private final EnforcementRecorder recorder;
    private final DltRegistryStore dltRegistry;
    private final RetentionStore retentionStore;
    private final RetentionSweeper retentionSweeper;
    private final EvidenceBundleService evidenceBundle;
    private final ConsentManagerStore managers;
    private final ReconfirmationStore reconfirmationStore;
    private final ReconfirmationSweeper reconfirmationSweeper;
    private final SdfObligationService sdf;
    private final SdfObligationStore sdfObligations;
    private final AlgorithmicSystemStore algorithmicSystems;
    private final PlatformProperties properties;
    private final SubjectStore subjectStore;
    private final SigningKeyStore signingKeys;
    private final RightsFulfilmentStore fulfilmentStore;
    private final WebhookStore webhooks;

    private final PropagationTargetStore propagationTargets;
    private final PropagationCoverageStore propagationCoverage;
    private final PropagationGapStore propagationGaps;
    private final PropagationSystemStore propagationSystems;
    private final ProjectionReconciliationSweeper projectionSweeper;
    private final SweepRunStore sweepRuns;

    public AdminController(CachingPurposeCatalog purposes, EntityStore entities,
                           BlastRadiusService blastRadius, LedgerIntegrityVerifier verifier,
                           IntegritySweeper integritySweeper, ProvenanceService provenance,
                           RopaService ropa, AdminAuditStore auditStore,
                           CachingApplicationRegistry applications,
                           ApplicationRegistryStore applicationStore,
                           ConsentCaptureService capture,
                           EnforcementEvidenceStore evidence,
                           EnforcementRecorder recorder,
                           DltRegistryStore dltRegistry,
                           RetentionStore retentionStore,
                           RetentionSweeper retentionSweeper,
                           EvidenceBundleService evidenceBundle,
                           ConsentManagerStore managers,
                           ReconfirmationStore reconfirmationStore,
                           ReconfirmationSweeper reconfirmationSweeper,
                           SdfObligationService sdf,
                           SdfObligationStore sdfObligations,
                           AlgorithmicSystemStore algorithmicSystems,
                           PlatformProperties properties,
                           SubjectStore subjectStore,
                           SigningKeyStore signingKeys,
                           RightsFulfilmentStore fulfilmentStore,
                           WebhookStore webhooks,
                           PropagationTargetStore propagationTargets,
                           PropagationCoverageStore propagationCoverage,
                           PropagationGapStore propagationGaps,
                           PropagationSystemStore propagationSystems,
                           ProjectionReconciliationSweeper projectionSweeper,
                           SweepRunStore sweepRuns) {
        this.propagationTargets = propagationTargets;
        this.propagationCoverage = propagationCoverage;
        this.propagationGaps = propagationGaps;
        this.propagationSystems = propagationSystems;
        this.projectionSweeper = projectionSweeper;
        this.sweepRuns = sweepRuns;
        this.properties = properties;
        this.subjectStore = subjectStore;
        this.signingKeys = signingKeys;
        this.fulfilmentStore = fulfilmentStore;
        this.webhooks = webhooks;
        this.managers = managers;
        this.reconfirmationStore = reconfirmationStore;
        this.reconfirmationSweeper = reconfirmationSweeper;
        this.sdf = sdf;
        this.sdfObligations = sdfObligations;
        this.algorithmicSystems = algorithmicSystems;
        this.applications = applications;
        this.applicationStore = applicationStore;
        this.capture = capture;
        this.evidence = evidence;
        this.recorder = recorder;
        this.dltRegistry = dltRegistry;
        this.retentionStore = retentionStore;
        this.retentionSweeper = retentionSweeper;
        this.evidenceBundle = evidenceBundle;
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

        // The owning entity is always in scope; anything further is granted explicitly. A surface
        // one entity operates for another — Athena's dialer placing Denave's calls — is ordinary
        // in an outsourced-services group, and it has to be recorded rather than inferred, because
        // "which surfaces could reach Denave's data" is asked after an incident and answered from
        // this table.
        applicationStore.grantEntityScope(applicationId, request.entityId(), "Owning entity");
        for (String served : request.servesEntityIds()) {
            applicationStore.grantEntityScope(applicationId, served,
                    "Granted at registration by " + actorOf(authentication));
        }
        applications.refresh();

        auditStore.record(actorOf(authentication), "APPLICATION_REGISTERED", request.entityId(),
                "application_registry", applicationId,
                Map.of("name", request.name(), "platform", request.platform(),
                        "environment", request.environment(),
                        "active", String.valueOf(request.active()),
                        "servesEntityIds", String.join(",", request.servesEntityIds())));
        return application;
    }

    /**
     * @param servesEntityIds other entities this surface may act for, beyond the one that owns it.
     *                        Enumerated rather than wildcarded — an intra-group services agreement
     *                        names the entities it covers, and so does this
     */
    public record ApplicationRequest(
            @NotBlank String entityId,
            @NotBlank String name,
            @NotBlank String platform,
            @NotBlank String environment,
            String description,
            boolean active,
            List<String> servesEntityIds) {

        public ApplicationRequest {
            servesEntityIds = servesEntityIds == null ? List.of() : List.copyOf(servesEntityIds);
        }
    }

    /**
     * Records that two subjects are the same person.
     *
     * <p>The platform resolves one identifier to one subject, so somebody the group knows by a
     * mobile number and by an email address is two subjects with two consent records and two hash
     * chains. A principal who withdraws by email leaves their phone contactable, and an evidence
     * bundle answers a Board complaint with half a person. {@code alsoKnownAs} at capture prevents
     * the split going forward; this repairs the ones already in the database.
     *
     * <p><strong>What it does and does not move.</strong> Identifiers are re-pointed, so the very
     * next decision for either of them lands on the surviving subject — the withdrawal reaches the
     * whole person from the next call onwards. Events are not moved and cannot be: the ledger is
     * append-only, and rewriting a subject id would break the hash chain that makes it evidence.
     * The old id becomes an alias, and every read that assembles a person unions across it.
     *
     * <p><strong>Not reversible.</strong> {@code subject_alias} has {@code UPDATE} and
     * {@code DELETE} revoked from the application role, like the ledger itself. That is deliberate
     * and it is why {@code reason} is required: a merge that turns out to be wrong has joined two
     * people's records, and the only useful thing afterwards is a permanent record of who said
     * they were the same person and on what basis. Ask before merging, not after.
     */
    @PostMapping("/subjects/merge")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, Object> mergeSubjects(@Valid @RequestBody SubjectMergeRequest request,
                                             Authentication authentication) {
        String actor = actorOf(authentication);
        int identifiersMoved = subjectStore.merge(request.entityId(), request.supersededSubjectId(),
                request.canonicalSubjectId(), actor, request.reason());

        auditStore.record(actor, "SUBJECTS_MERGED", request.entityId(), "subject_alias",
                request.supersededSubjectId(),
                Map.of("canonicalSubjectId", request.canonicalSubjectId(),
                        "identifiersMoved", String.valueOf(identifiersMoved),
                        "reason", request.reason()));

        return Map.of(
                "canonicalSubjectId", request.canonicalSubjectId(),
                "supersededSubjectId", request.supersededSubjectId(),
                "identifiersMoved", identifiersMoved,
                "merged", true);
    }

    /**
     * @param supersededSubjectId the id that stops being used. Its events stay where they are
     * @param canonicalSubjectId  the surviving subject. Every identifier moves here
     * @param reason              why these are the same person. Required, and free text on
     *                            purpose: the real answer is "the CRM id matched" or "the
     *                            principal told us on a grievance call", and a code list would
     *                            lose the only part a reviewer needs
     */
    public record SubjectMergeRequest(@NotBlank String entityId,
                                      @NotBlank String supersededSubjectId,
                                      @NotBlank String canonicalSubjectId,
                                      @NotBlank String reason) {
    }

    /**
     * The snapshot signing keys and their states.
     *
     * <p>Includes compromised keys, which {@code GET /v1/keys} deliberately does not. A device
     * must never be told to trust one; an administrator investigating why a snapshot stopped
     * verifying needs to see that it exists and when it was pulled.
     */
    @GetMapping("/signing-keys")
    public List<SigningKeyStore.Key> signingKeys() {
        return signingKeys.all();
    }

    /**
     * Takes a signing key out of service.
     *
     * <p>Two states and the difference is the entire point of having both.
     *
     * <p>{@code RETIRED} stops the key signing and leaves it verifying. This is the ordinary
     * rotation: a device holding a snapshot issued minutes before the change keeps working until
     * that snapshot expires, which is what makes a rotation a non-event instead of a silent
     * enforcement outage across a field force mid-shift. Retire the outgoing key only after every
     * instance has been restarted onto the new one — an instance still signing with a retired key
     * produces snapshots no device will accept.
     *
     * <p>{@code COMPROMISED} stops it verifying too, and is not a tidier retirement. It says the
     * private half may be in someone else's hands, so every snapshot it ever signed is now
     * something an attacker could have manufactured. Devices reject them immediately, which is
     * correct and is also an enforcement outage for anyone offline — expect that, rather than
     * discovering it.
     */
    @PostMapping("/signing-keys/{keyId}/retire")
    public Map<String, Object> retireSigningKey(@PathVariable String keyId,
                                                @Valid @RequestBody SigningKeyRetirementRequest request,
                                                Authentication authentication) {
        if (!List.of("RETIRED", "COMPROMISED").contains(request.state())) {
            throw new IllegalArgumentException(
                    "state must be RETIRED (stops signing, still verifies) or COMPROMISED (stops "
                            + "verifying — every snapshot it ever signed becomes untrustworthy)");
        }
        String actor = actorOf(authentication);
        boolean changed = signingKeys.changeState(keyId, request.state(), actor, request.reason(),
                Instant.now());
        if (!changed) {
            throw new IllegalArgumentException(
                    "signing key " + keyId + " is not active; it does not exist, or it has already "
                            + "been retired or marked compromised");
        }

        auditStore.record(actor, "SIGNING_KEY_" + request.state(), null, "signing_key", keyId,
                Map.of("reason", request.reason()));
        return Map.of("keyId", keyId, "state", request.state(), "recorded", true);
    }

    /**
     * @param state  {@code RETIRED} or {@code COMPROMISED}
     * @param reason why. Required: "we rotated on schedule" and "the key was in a leaked backup"
     *               lead to different incident responses, and six months later the state column
     *               alone cannot tell them apart
     */
    public record SigningKeyRetirementRequest(@NotBlank String state, @NotBlank String reason) {
    }

    /**
     * The systems that must act to fulfil a rights request, per request type.
     *
     * <p>Empty for every entity until UDS configures it, and that emptiness is load-bearing: an
     * unconfigured register blocks nothing, so a request can still be closed as {@code FULFILLED}
     * on an operator's word alone. That is the state the platform is in today, and it is why the
     * scope statement in {@code REGULATORY_HANDOFF.md} §8.5 needs a signature — the register is a
     * statement by UDS about which of its systems hold a principal's data, and the platform cannot
     * know it and will not invent it.
     */
    @GetMapping("/fulfilment-targets")
    public List<RightsFulfilmentStore.Target> fulfilmentTargets(@RequestParam String entityId) {
        return fulfilmentStore.targets(entityId);
    }

    /**
     * Registers or updates a system that has to act on a rights request.
     *
     * <p>Adding a mandatory target immediately blocks closure of every open request of that type
     * until somebody records what the system did. That is the intended effect and it is worth
     * knowing before adding one at four o'clock on a Friday: the register is how "which systems
     * hold this person's data" stops being institutional memory, and switching it on mid-queue
     * makes the outstanding work visible all at once.
     */
    @PutMapping("/fulfilment-targets")
    public Map<String, Object> upsertFulfilmentTarget(
            @Valid @RequestBody FulfilmentTargetRequest request, Authentication authentication) {
        String actor = actorOf(authentication);
        fulfilmentStore.upsertTarget(request.entityId(), request.requestType(),
                request.systemCode(), request.mandatory(), request.active(), request.description());

        auditStore.record(actor, "FULFILMENT_TARGET_CONFIGURED", request.entityId(),
                "fulfilment_target", request.systemCode(),
                Map.of("requestType", request.requestType(),
                        "mandatory", String.valueOf(request.mandatory()),
                        "active", String.valueOf(request.active())));
        return Map.of("systemCode", request.systemCode(), "recorded", true);
    }

    /**
     * @param requestType ERASURE, ACCESS, CORRECTION … Per type because they genuinely differ: an
     *                    erasure reaches every system holding the person's data, an access request
     *                    reaches whichever can produce an export
     * @param mandatory   whether FULFILLED is blocked until this system has acted
     */
    public record FulfilmentTargetRequest(@NotBlank String entityId,
                                          @NotBlank String requestType,
                                          @NotBlank String systemCode,
                                          boolean mandatory,
                                          boolean active,
                                          String description) {
    }

    /**
     * The systems that must be told about this entity's consent changes, and whether anything can
     * currently reach them.
     *
     * <p>The structural sibling of {@code fulfilment-targets} and a deliberately different question.
     * That register is about the <em>act</em> of erasure or export for a rights request; this one is
     * about <em>notification</em> of a consent-state change — DPDP s.6(6)'s duty to "cease and cause
     * its Data Processors to cease processing", and GDPR Art. 19's duty to communicate to each
     * recipient. Two registers because they are two obligations; one register would be filled for
     * neither.
     *
     * <p><strong>Each row carries the subscription that currently reaches it, or null.</strong> That
     * is the half of this route that earns it. The join key is free text on both sides, so a target
     * for {@code DENCRM} and a subscription labelled {@code DENCRM_PROD} silently do not meet — and
     * unlike a fulfilment mismatch, which fails loud as a 409 naming the system, this one fails
     * quiet and writes a gap row every day forever. A null here is either "nobody is registered",
     * which is the finding, or "somebody typed a different name", which is a typo the operator can
     * only see if it is shown to them.
     */
    @GetMapping("/propagation/targets")
    public Map<String, Object> propagationTargets(@RequestParam String entityId) {
        List<PropagationTargetStore.Coverage> coverage = propagationTargets.coverage(entityId);
        return Map.of(
                "entityId", entityId,
                "targets", coverage,
                // Coverage AND what happened on the wire, in one read. The two failure modes land
                // in different artefacts — a configuration error in propagation_gap, a system that
                // is merely down in webhook_delivery's FAILED rows, because a failing delivery
                // leaves the message unpublished and the reconciler never runs for it. An operator
                // asking "is DENCRM current?" had to consult both and reconcile them by hand.
                "health", propagationTargets.healthFor(entityId),
                "needsAttention", propagationTargets.healthFor(entityId).stream()
                        .filter(PropagationTargetStore.Health::attention)
                        .map(h -> h.coverage().systemCode()).toList(),
                // Named separately rather than left to the reader to filter. "How many obligations
                // can this entity not currently meet" is the number an operator is here for.
                "uncovered", coverage.stream().filter(PropagationTargetStore.Coverage::uncovered)
                        .map(PropagationTargetStore.Coverage::systemCode).toList());
    }

    /**
     * Registers or updates a system that must be told about a consent change.
     *
     * <p><strong>Adding a mandatory target does not block anything.</strong> Unlike a fulfilment
     * target, which immediately blocks closure of every open request of that type, this one only
     * makes a gap visible: the uncovered gauge rises, the alert fires after thirty minutes, and the
     * relay starts recording a daily gap row until a subscription carries that {@code system_code}.
     * Propagation is evidence, not a gate — refusing to publish a withdrawal because nobody
     * registered to hear it would punish the principal for a configuration error.
     *
     * <p>The topic is validated against the set the platform actually publishes. A target on a topic
     * nothing is enqueued to would sit at "covered" forever while nobody was ever told, which is a
     * register that fails open and looks exactly like success.
     */
    @PutMapping("/propagation/targets")
    public Map<String, Object> upsertPropagationTarget(
            @Valid @RequestBody PropagationTargetRequest request, Authentication authentication) {
        String actor = actorOf(authentication);
        String topic = request.topic();
        if (!registrableTopics().contains(topic)) {
            throw new IllegalArgumentException("unknown topic: " + topic
                    + "; the platform publishes to " + registrableTopics()
                    + ". A target on a topic nothing is enqueued to can never be covered and would "
                    + "read as satisfied forever.");
        }

        String systemCode = request.systemCode().toUpperCase(java.util.Locale.ROOT);
        requireKnownSystem(request.entityId(), systemCode);

        propagationTargets.upsert(request.entityId(), topic, systemCode, request.mandatory(),
                request.active(), request.description());

        auditStore.record(actor, "PROPAGATION_TARGET_CONFIGURED", request.entityId(),
                "propagation_target", systemCode,
                Map.of("topic", topic,
                        "mandatory", String.valueOf(request.mandatory()),
                        "active", String.valueOf(request.active())));
        return Map.of("systemCode", systemCode, "recorded", true);
    }

    /**
     * Obligations that went unmet, one row per system per day.
     *
     * <p>History, not current state — and the distinction is why there are two things to read.
     * {@code /propagation/targets} answers "what is broken now" and returns to zero when somebody
     * fixes it; this answers "what did we fail to show on which day" and never shrinks, because it
     * is append-only evidence. Nothing alerts on this route: an alert on a monotonic count is one
     * that fires forever and is muted inside a week.
     *
     * <p>Read {@code reason} before drawing a conclusion. {@code NO_DELIVERY_CHANNEL} means the
     * configured publisher writes no delivery evidence — it is a fact about this deployment, not
     * about the downstream system, which may be consuming everything perfectly over Kafka.
     */
    @GetMapping("/propagation/gaps")
    public List<PropagationGapStore.Gap> propagationGaps(
            @RequestParam String entityId,
            @RequestParam(required = false) String topic,
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return propagationGaps.forEntity(entityId, topic, Math.min(limit, 1000), Math.max(offset, 0));
    }

    /**
     * The topics a propagation target may name.
     *
     * <p>**Two, not one.** The consent-events stream, and {@code uds.consent.retention} — which
     * {@code RetentionSweeper} enqueues keyed {@code entityId:subjectId}, a shape {@code OutboxKey}
     * parses, so it is genuinely routable and genuinely registrable. Validating against the events
     * topic alone refused it at this route while {@code PropagationTopicCheck} would have warned
     * that it "is not published by this platform" — a false statement about the stream that carries
     * pre-erasure and retention actions concerning a named principal.
     *
     * <p>{@code rights.verification.requested} is deliberately absent: it is keyed on the request
     * reference alone, carries no entity, and so can never route to a subscription.
     * {@code REGULATORY_HANDOFF.md} §8.7 records it as structurally uncoverable.
     */
    private List<String> registrableTopics() {
        return List.of(properties.getEvents().getTopic(), RetentionSweeper.TOPIC_RETENTION);
    }

    /**
     * @param topic      the stream, validated against what the platform publishes
     * @param systemCode DENCRM, ATHENA_DIALER, HRMS. Upper-cased on the way in, because the join
     *                   against {@code webhook_subscription.system_code} is exact and a case
     *                   mismatch produces a permanent phantom gap rather than an error
     * @param mandatory  whether an unreachable target is a finding. Non-mandatory records the
     *                   relationship without alerting on it
     */
    public record PropagationTargetRequest(@NotBlank String entityId,
                                           @NotBlank String topic,
                                           @NotBlank String systemCode,
                                           boolean mandatory,
                                           boolean active,
                                           String description) {
    }

    /**
     * The system codes this entity recognises for propagation.
     *
     * <p>The register's vocabulary. Both {@code propagation_target} and
     * {@code webhook_subscription} reference it, so a code that is not here cannot be used on
     * either side — which is what stops a typo producing a daily gap row, permanently, in an
     * append-only table, for a system that is in fact reachable.
     */
    @GetMapping("/propagation/systems")
    public List<PropagationSystemStore.System> propagationSystems(@RequestParam String entityId) {
        return propagationSystems.forEntity(entityId);
    }

    /**
     * Declares or retires a system code.
     *
     * <p>Retiring is {@code active = false} and never a delete: a {@code propagation_gap} row
     * naming a decommissioned system has to stay readable, because "this system was not told, on
     * these days" is the entire content of that table.
     */
    @PutMapping("/propagation/systems")
    public Map<String, Object> upsertPropagationSystem(
            @Valid @RequestBody PropagationSystemRequest request, Authentication authentication) {
        String actor = actorOf(authentication);
        String systemCode = request.systemCode().toUpperCase(java.util.Locale.ROOT);

        propagationSystems.upsert(request.entityId(), systemCode, request.description(),
                request.active());

        auditStore.record(actor, "PROPAGATION_SYSTEM_CONFIGURED", request.entityId(),
                "propagation_system", systemCode,
                Map.of("active", String.valueOf(request.active())));
        return Map.of("systemCode", systemCode, "recorded", true);
    }

    /** @param active false retires a code without removing it; see the route javadoc */
    public record PropagationSystemRequest(@NotBlank String entityId,
                                           @NotBlank String systemCode,
                                           String description,
                                           boolean active) {
    }

    /**
     * Refuses a system code this entity has not declared, naming what it has.
     *
     * <p>Loud and closed, at the moment the operator types it — the posture
     * {@code fulfilment_target} already takes and the one propagation lacked. The alternative,
     * which is what shipped in V31, is that a mistyped code is accepted, never joins, and is
     * reported as an unmet obligation every day thereafter in a table nothing can edit.
     */
    private void requireKnownSystem(String entityId, String systemCode) {
        if (!propagationSystems.isKnown(entityId, systemCode)) {
            throw new IllegalArgumentException("unknown system code: " + systemCode
                    + "; " + entityId + " recognises "
                    + propagationSystems.forEntity(entityId).stream()
                            .map(PropagationSystemStore.System::systemCode).toList()
                    + ". Declare it at PUT /v1/admin/propagation/systems first — a code the "
                    + "register cannot resolve produces a daily gap row for a system that may be "
                    + "perfectly reachable, and propagation_gap is append-only.");
        }
    }

    /**
     * Where this entity's consent changes are pushed.
     *
     * <p>The secret is returned as configured. That is deliberate and it is the reason this route
     * is ADMIN-only and entity-scoped: an operator re-onboarding a downstream system needs the
     * value to give them, and forcing a rotation every time somebody has to look it up would mean
     * the rotation happens carelessly and often rather than deliberately and rarely.
     */
    @GetMapping("/subscriptions")
    public List<WebhookStore.Subscription> subscriptions(@RequestParam String entityId) {
        return webhooks.forEntity(entityId);
    }

    /**
     * Registers or updates a downstream system that should be told about consent changes.
     *
     * <p>This is the route that turns the platform from something systems must ask into something
     * that tells them. Until an entity has one, every downstream system is pull-only — correct, and
     * silently dependent on each of them remembering to ask before every campaign.
     *
     * <p>The {@code url} should be https in any real deployment: the payload names a data
     * principal's subject reference and what they decided, which is personal data in transit. The
     * platform does not refuse http, because a developer machine legitimately uses it and a refusal
     * here would be enforced in the one environment where it does not matter.
     */
    @PutMapping("/subscriptions")
    public Map<String, Object> upsertSubscription(@Valid @RequestBody SubscriptionRequest request,
                                                  Authentication authentication) {
        String actor = actorOf(authentication);
        String systemCode = request.systemCode() == null || request.systemCode().isBlank()
                ? request.subscriptionId().toUpperCase(java.util.Locale.ROOT)
                : request.systemCode().toUpperCase(java.util.Locale.ROOT);
        requireKnownSystem(request.entityId(), systemCode);

        webhooks.upsert(request.subscriptionId(), request.entityId(), request.topic(),
                request.url(), request.secret(), request.active(), request.description(),
                systemCode);

        // The secret is deliberately absent from the audit detail. An append-only table is exactly
        // where a shared secret should not end up, because it cannot afterwards be removed.
        auditStore.record(actor, "WEBHOOK_SUBSCRIPTION_CONFIGURED", request.entityId(),
                "webhook_subscription", request.subscriptionId(),
                Map.of("topic", request.topic(), "url", request.url(),
                        "active", String.valueOf(request.active())));
        return Map.of("subscriptionId", request.subscriptionId(), "recorded", true);
    }

    /** Delivery attempts for one outbox message: what answers "did the withdrawal reach DenCRM". */
    @GetMapping("/subscriptions/deliveries/{outboxId}")
    public List<WebhookStore.Delivery> deliveries(@PathVariable long outboxId) {
        return webhooks.deliveriesFor(outboxId);
    }

    /**
     * @param secret HMAC-SHA256 shared secret. Given to the receiving team out of band; every
     *               request carries {@code X-UDS-Signature} over the body under this key, so the
     *               receiver can tell a real withdrawal from anyone who can reach their endpoint
     */
    public record SubscriptionRequest(@NotBlank String subscriptionId,
                                      @NotBlank String entityId,
                                      @NotBlank String topic,
                                      @NotBlank String url,
                                      @NotBlank String secret,
                                      boolean active,
                                      String description,
                                      /*
                                       * Optional. Null keeps the historical behaviour of deriving
                                       * the code from the subscription id — which is why it is not
                                       * @NotBlank. It exists because an operator whose subscription
                                       * is named DENCRM_PROD previously had no way at all to make
                                       * it join a DENCRM propagation target except by deleting the
                                       * subscription, which discards its delivery evidence.
                                       */
                                      String systemCode) {
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
     *
     * <p>Before publishing there is no version row to read the material-change judgement from, so
     * pass {@code materialChange} explicitly. Omit it only for a version that already exists, where
     * the stored flag is authoritative. Asking about an unpublished version without it is refused
     * rather than answered — the previous behaviour returned a confident "consent still stands"
     * for exactly the case this calculation exists to catch.
     */
    @GetMapping("/blast-radius/purpose/{purposeCode}")
    public BlastRadiusService.Impact purposeBlastRadius(
            @PathVariable String purposeCode,
            @RequestParam int newVersion,
            @RequestParam(required = false) Boolean materialChange) {
        return materialChange == null
                ? blastRadius.forPurposeChange(purposeCode, newVersion)
                : blastRadius.forPurposeChange(purposeCode, newVersion, materialChange);
    }

    /** Who is affected by a notice change, per purpose that points at it. */
    @GetMapping("/blast-radius/notice/{noticeId}")
    public List<BlastRadiusService.Impact> noticeBlastRadius(
            @PathVariable String noticeId,
            @RequestParam int newVersion,
            @RequestParam(required = false) Boolean materialChange) {
        return materialChange == null
                ? blastRadius.forNoticeChange(noticeId, newVersion)
                : blastRadius.forNoticeChange(noticeId, newVersion, materialChange);
    }

    /**
     * Strikes down consent the group can no longer rely on.
     *
     * <p>What {@link BlastRadiusService.Action#RE_CONSENT_REQUIRED} is for: a material change means
     * standing consent no longer covers what will be done, and continuing to rely on it would be
     * relying on consent to something else. Also the path for an imported record whose provenance
     * cannot be substantiated.
     *
     * <p>ADMIN only, and the reason is mandatory. This is the fiduciary revoking its own permission
     * over a subject who did nothing wrong, and an auditor will ask who decided that.
     */
    @PostMapping("/consent/invalidate")
    public ConsentApi.EventSummary invalidateConsent(
            @Valid @RequestBody ConsentApi.InvalidateRequest request,
            Authentication authentication) {
        ConsentEvent event = capture.invalidate(request.entityId(), request.subjectId(),
                request.purposeCode(), request.purposeVersion(), ActorType.ADMIN,
                actorOf(authentication), request.reason(),
                request.occurredAt() == null ? Instant.now() : request.occurredAt());

        auditStore.record(actorOf(authentication), "CONSENT_INVALIDATED", request.entityId(),
                "consent_event", event.eventId(),
                Map.of("purposeCode", request.purposeCode(),
                        "purposeVersion", String.valueOf(request.purposeVersion()),
                        "reason", request.reason()));

        return new ConsentApi.EventSummary(event.eventId(), event.purposeCode(),
                event.purposeVersion(), event.type().name(), event.resultingStatus(),
                event.occurredAt(), event.expiresAt(), event.sequenceNumber(), event.eventHash());
    }

    /** Verifies one subject's hash chain, on demand. What a dispute about one person needs. */
    @GetMapping("/integrity/{entityId}/{subjectId}")
    public LedgerIntegrityVerifier.ChainVerification verifyChain(@PathVariable String entityId,
                                                                  @PathVariable String subjectId) {
        return verifier.verifyChain(entityId, subjectId);
    }

    /**
     * Everything the platform holds about one principal, in one call.
     *
     * <p>The artefact a complaint is answered with. The Data Protection Board has been constituted
     * since 6 June 2026 and its grievance portal is live, so this is no longer a hypothetical
     * document — and assembling it by hand across six endpoints at the moment it is needed is how
     * a detail gets missed.
     *
     * <p>Audited as a write would be. Assembling a named person's entire file is itself an event
     * worth recording: it is the most concentrated disclosure the platform can produce, and an
     * administrative read that leaves no trace is one nobody can review afterwards.
     */
    @GetMapping("/evidence/subject/{entityId}/{subjectId}")
    public EvidenceBundleService.Bundle evidenceBundle(@PathVariable String entityId,
                                                        @PathVariable String subjectId,
                                                        Authentication authentication) {
        EvidenceBundleService.Bundle bundle =
                evidenceBundle.assemble(entityId, subjectId, Instant.now());

        auditStore.record(actorOf(authentication), "EVIDENCE_BUNDLE_ASSEMBLED", entityId,
                "SUBJECT", subjectId, Map.of(
                        "events", String.valueOf(bundle.events().size()),
                        "receipts", String.valueOf(bundle.receipts().size()),
                        "noticesServed", String.valueOf(bundle.noticesServed().size()),
                        // Recorded on the audit row itself so that a later question about whether
                        // the file was sound when it was handed over does not depend on whoever
                        // exported it having kept a copy.
                        "chainIntact", String.valueOf(bundle.integrity().intact())));

        return bundle;
    }

    // -------------------------------------------------------------------------------------
    // The Consent Manager register.
    //
    // Group-wide configuration rather than an entity's data — a Consent Manager is registered
    // with the Board, not with Denave — so these routes take no entityId and the audit rows
    // carry none.
    //
    // They exist because Rule 4 lets the Board suspend or cancel a registration, and until now
    // reflecting that needed a DBA with schema rights at whatever hour the notice arrived. That
    // is not a safer register; it is one nobody updates, and the failure it produces is honouring
    // relays from a Consent Manager the Board removed last month.
    // -------------------------------------------------------------------------------------

    /** The register as UDS holds it, including how stale each entry is. */
    @GetMapping("/consent-managers")
    public List<ConsentManagerApi.RegisterEntryDto> consentManagers() {
        return managers.findAll().stream().map(ConsentManagerApi.RegisterEntryDto::from).toList();
    }

    /**
     * Records a Consent Manager the Board has registered, or corrects an entry already held.
     *
     * <p>Status is not settable here. Registering and suspending are different acts with different
     * authority behind them, and one call that did both would mean a routine re-transcription of
     * the Board's list could silently restore a registration suspended last week.
     */
    @PostMapping("/consent-managers")
    @ResponseStatus(HttpStatus.CREATED)
    public ConsentManagerApi.RegisterEntryDto registerConsentManager(
            @Valid @RequestBody ConsentManagerApi.RegisterRequest request,
            Authentication authentication) {

        managers.upsert(new ConsentManagerStore.ConsentManager(
                request.registrationId(), request.name(),
                ConsentManagerStore.Status.REGISTERED, request.apiClientId(), request.publicKey(),
                request.registeredAt() == null ? Instant.now() : request.registeredAt(),
                null, null, request.contactEmail(), null, null));

        auditStore.record(actorOf(authentication), "CONSENT_MANAGER_REGISTERED", null,
                "CONSENT_MANAGER", request.registrationId(), Map.of(
                        "name", String.valueOf(request.name()),
                        "apiClientId", String.valueOf(request.apiClientId()),
                        // Whether a signing key was supplied, not the key. Recorded because the day
                        // the Board publishes a signing standard, the first question is which
                        // registrations already have one on file.
                        "signingKeyOnFile", String.valueOf(
                                request.publicKey() != null && !request.publicKey().isBlank())));

        return ConsentManagerApi.RegisterEntryDto.from(managers.find(request.registrationId())
                .orElseThrow(() -> new IllegalStateException("the registration did not persist")));
    }

    /**
     * Suspends, restores or deregisters a registration.
     *
     * <p>The operation that has to work on the day the Board's notice arrives, which is why it is
     * an endpoint and not a runbook step involving psql. Audited with the reason, because "who
     * suspended this one, when, and on what authority" is exactly the question asked after a relay
     * that should not have been honoured.
     */
    @PutMapping("/consent-managers/{registrationId}/status")
    public ConsentManagerApi.RegisterEntryDto setConsentManagerStatus(
            @PathVariable String registrationId,
            @Valid @RequestBody ConsentManagerApi.StatusRequest request,
            Authentication authentication) {

        ConsentManagerStore.Status status = ConsentManagerStore.Status.valueOf(request.status());
        if (!managers.setStatus(registrationId, status, request.reason(), Instant.now())) {
            throw new IllegalArgumentException("no Consent Manager is registered as "
                    + registrationId);
        }

        auditStore.record(actorOf(authentication), "CONSENT_MANAGER_STATUS_CHANGED", null,
                "CONSENT_MANAGER", registrationId, Map.of(
                        "status", status.name(),
                        "reason", String.valueOf(request.reason())));

        return ConsentManagerApi.RegisterEntryDto.from(managers.find(registrationId)
                .orElseThrow(() -> new IllegalStateException("the status change did not persist")));
    }

    /**
     * Records that a person compared this entry against the Board's published register.
     *
     * <p>Not a sync. The Board publishes no feed, so the copy UDS holds is only as fresh as the
     * last time somebody looked at both — and a copy with no staleness signal rots in a way that
     * looks exactly like normal operation. The health indicator reads what this writes.
     */
    @PostMapping("/consent-managers/{registrationId}/reconciled")
    public ConsentManagerApi.RegisterEntryDto reconcileConsentManager(
            @PathVariable String registrationId, Authentication authentication) {

        String actor = actorOf(authentication);
        if (!managers.recordReconciliation(registrationId, actor, Instant.now())) {
            throw new IllegalArgumentException("no Consent Manager is registered as "
                    + registrationId);
        }

        auditStore.record(actor, "CONSENT_MANAGER_RECONCILED", null,
                "CONSENT_MANAGER", registrationId, Map.of());

        return ConsentManagerApi.RegisterEntryDto.from(managers.find(registrationId)
                .orElseThrow(() -> new IllegalStateException("the reconciliation did not persist")));
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
     * Re-derives every artefact from its chain and reports what disagrees.
     *
     * <p>The integrity sweep proves the ledger; this proves the projection every decision is taken
     * against. They are separate calls because they answer separate questions and because a broken
     * chain makes this one's answer meaningless — run {@code /integrity/sweep} first if both are
     * red.
     *
     * <p><strong>It reports and does not repair.</strong> A projector defect and a direct edit of
     * {@code consent_artefact} produce an identical divergence, and only one of them is a security
     * incident; re-projecting automatically would erase the distinction before anybody saw it.
     * {@code docs/OPERATIONS.md} §3 carries what to do with a finding.
     *
     * <p><strong>Counts only.</strong> The sweep is group-wide by necessity; the answer must not
     * be. Subject identifiers are on {@code /projection/divergences}, which carries an entity.
     */
    @PostMapping("/projection/sweep")
    public ProjectionReconciliationSweeper.Report sweepProjection() {
        return projectionSweeper.run();
    }

    /** The most recent projection reconciliation, as counts. */
    @GetMapping("/projection/last")
    public ProjectionReconciliationSweeper.Report lastProjectionReport() {
        return projectionSweeper.lastReport();
    }

    /**
     * The divergences the last sweep found for one entity.
     *
     * <p>Scoped by the {@code entityId} query parameter, which
     * {@code EntityAccessGuard.requestedEntity} reads on <em>any</em> path — so this route needs no
     * {@code ENTITY_PATH_PREFIXES} entry, and adding one that matched nothing would teach the next
     * reader the wrong rule. A caller scoped to one fiduciary cannot read another's.
     *
     * <p>Paged, and it says when it is not complete: the page cap and, separately, the cap on what
     * the <em>sweep</em> retained. The second matters because a systemic projector defect produces
     * one divergence per artefact, and the honest answer is the exact count on
     * {@code /projection/last} beside a bounded sample here.
     */
    @GetMapping("/projection/divergences")
    public EntityDivergences projectionDivergences(
            @RequestParam String entityId,
            @RequestParam(defaultValue = "0") int offset) {

        int cap = properties.getSweeper().getProjectionDivergencePageSize();
        List<ProjectionReconciler.Divergence> forEntity = projectionSweeper.retainedFor(entityId);

        List<ProjectionReconciler.Divergence> window = forEntity.stream()
                .skip(Math.max(offset, 0))
                .limit(cap + 1L)
                .toList();
        boolean morePages = window.size() > cap;
        List<ProjectionReconciler.Divergence> page =
                morePages ? window.subList(0, cap) : window;

        List<EvidenceBundleService.Truncation> truncation = new ArrayList<>();

        // The sample lives in the memory of whichever replica swept, and SweepLock means that is
        // one of three. A replica that has not swept holds nothing, and an empty list is otherwise
        // indistinguishable from "this entity is clean" — a well-formed 200 making a complete
        // statement that is empty for a reason it did not disclose, which is the exact failure the
        // evidence bundle's truncation notice exists to prevent.
        if (projectionSweeper.lastReport().finishedAt() == null) {
            truncation.add(new EvidenceBundleService.Truncation("sweep", 0, 0,
                    "POST /v1/admin/projection/sweep — this instance has not swept, so it holds no "
                            + "sample. The nightly sweep runs on one replica and keeps the result "
                            + "in memory; an empty list here is not a statement that this entity "
                            + "is clean. GET /v1/admin/projection/last carries the counts."));
        }
        if (morePages) {
            truncation.add(new EvidenceBundleService.Truncation("divergences", page.size(), cap,
                    "GET /v1/admin/projection/divergences?entityId=" + entityId
                            + "&offset=" + (Math.max(offset, 0) + cap)));
        }
        if (projectionSweeper.retentionTruncated()) {
            // The remainder genuinely cannot be paged to: the sweep discarded it rather than
            // holding it. So the request named is the one that WOULD return more — re-run the
            // sweep with a larger cap — rather than a page pointer that could never resolve.
            // rules §9: a pointer is only honest if the route can deliver it.
            truncation.add(new EvidenceBundleService.Truncation("retained", forEntity.size(),
                    projectionSweeper.lastReport().retentionCap(),
                    "POST /v1/admin/projection/sweep with uds.consent.sweeper."
                            + "projection-divergence-cap raised above "
                            + projectionSweeper.lastReport().retentionCap()
                            + "; the exact total is GET /v1/admin/projection/last"));
        }
        return new EntityDivergences(entityId, projectionSweeper.lastReport().finishedAt(),
                Math.max(offset, 0), page.size(), page, truncation);
    }

    /**
     * One entity's divergences, and what this answer leaves out.
     *
     * @param sweptAt    when the instance answering last swept, or <strong>null</strong> if it
     *                   never has. Load-bearing rather than informational: the sample is per
     *                   instance and the sweep runs on one replica, so without this a reader
     *                   cannot tell a clean entity from a replica that was not asked to look
     * @param truncation empty when the answer is complete, which is the common case
     */
    public record EntityDivergences(String entityId, java.time.Instant sweptAt, int offset,
                                    int returned,
                                    List<ProjectionReconciler.Divergence> divergences,
                                    List<EvidenceBundleService.Truncation> truncation) {
    }


    /**
     * When each scheduled sweep last ran, and where.
     *
     * <p>The answer to "has anything quietly stopped". A silently dead {@code ExpirySweeper} writes
     * no {@code EXPIRED} events and the evidence plane goes incomplete while every decision stays
     * correct — no error, no failed request, only a growing absence. Until {@code sweep_run}
     * existed nothing recorded that any sweep had run at all.
     */
    @GetMapping("/sweeps")
    public List<SweepRunStore.Run> sweepRuns() {
        return sweepRuns.all();
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
    // Enforcement evidence
    // ---------------------------------------------------------------------------------------

    /**
     * Decisions the platform refused.
     *
     * <p>The answer to "why was this person not contacted" and, more usefully, to "show us that
     * you checked". Only denials are here; allowances are counted on the scrub runs below rather
     * than enumerated, because a dialer at a hundred thousand calls a day would otherwise write a
     * hundred thousand rows a day to prove that nothing happened.
     *
     * <p>Filter by subject or by campaign. An unfiltered scan of every refusal is a report rather
     * than an investigation, and the indexes are built for the latter.
     */
    @GetMapping("/enforcement/denials")
    public List<EnforcementEvidenceStore.Denial> denials(
            @RequestParam String entityId,
            @RequestParam(required = false) String subjectId,
            @RequestParam(required = false) String campaignId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return evidence.denials(entityId, subjectId, campaignId, Math.min(limit, 1000), offset);
    }

    /**
     * Campaign scrubs, one row per call.
     *
     * <p>This is the artefact TRAI asks for. Its question is not whether a particular number was
     * suppressed but whether the list was screened at all before it was used, and that is a
     * property of the run rather than of any subject in it.
     */
    @GetMapping("/enforcement/scrub-runs")
    public List<EnforcementEvidenceStore.ScrubRun> scrubRuns(
            @RequestParam String entityId,
            @RequestParam(required = false) String campaignId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return evidence.scrubRuns(entityId, campaignId, Math.min(limit, 1000), offset);
    }

    /**
     * Whether the platform is currently able to prove the decisions it is taking.
     *
     * <p>Evidence writes are best-effort so that a logging fault cannot stop lawful processing.
     * The cost of that choice is that failures are silent unless somebody counts them, which is
     * what this exposes. Anything above zero is a compliance incident on a delay.
     */
    @GetMapping("/enforcement/health")
    public Map<String, Object> enforcementHealth() {
        return Map.of("failedWrites", recorder.failedWrites(),
                "recordedDenials", recorder.recordedDenials(),
                "healthy", recorder.failedWrites() == 0);
    }

    // ---------------------------------------------------------------------------------------
    // TRAI — the DLT registry
    // ---------------------------------------------------------------------------------------

    /**
     * Sender headers and message templates registered on the DLT platform.
     *
     * <p>The report that answers "which of our purposes can actually send" before a campaign
     * rather than during one. A template still reading {@code PENDING_REGISTRATION} is one the
     * operator will refuse.
     */
    @GetMapping("/dlt/registrations")
    public List<DltRegistryStore.Registration> dltRegistrations(@RequestParam String entityId) {
        return dltRegistry.findForEntity(entityId);
    }

    @GetMapping("/dlt/headers")
    public List<DltRegistryStore.Header> dltHeaders(@RequestParam String entityId) {
        return dltRegistry.headers(entityId);
    }

    /**
     * Registers or updates a sender header.
     *
     * <p>The category is mandatory and is not decoration: it decides which traffic the header may
     * carry and which numbering series it originates from — 140 for promotional, 1600 for
     * transactional. A promotional message sent under a service header is the mis-send TRAI acts
     * on.
     */
    @PutMapping("/dlt/headers/{headerId}")
    public Map<String, Object> upsertDltHeader(@PathVariable String headerId,
                                               @Valid @RequestBody DltHeaderRequest request,
                                               Authentication authentication) {
        dltRegistry.upsertHeader(new DltRegistryStore.Header(headerId, request.entityId(),
                request.header(), request.category(), request.series(), request.registeredAt(),
                request.active()));

        auditStore.record(actorOf(authentication), "DLT_HEADER_REGISTERED", request.entityId(),
                "dlt_header", headerId,
                Map.of("header", request.header(), "category", request.category(),
                        "series", request.series() == null ? "" : request.series()));
        return Map.of("headerId", headerId, "registered", true);
    }

    /** Registers or updates a template, tying it to the purpose it may carry. */
    @PutMapping("/dlt/templates/{templateId}")
    public Map<String, Object> upsertDltTemplate(@PathVariable String templateId,
                                                 @Valid @RequestBody DltTemplateRequest request,
                                                 Authentication authentication) {
        dltRegistry.upsertTemplate(templateId, request.entityId(), request.headerId(),
                request.purposeCode(), request.templateRef(), request.description(),
                request.registeredAt(), request.active());

        auditStore.record(actorOf(authentication), "DLT_TEMPLATE_REGISTERED", request.entityId(),
                "dlt_template", templateId,
                Map.of("purposeCode", request.purposeCode(),
                        "templateRef", request.templateRef(),
                        "headerId", request.headerId()));
        return Map.of("templateId", templateId, "registered", true);
    }

    /**
     * @param category P, S, T or G
     * @param series   140 for promotional, 1600 for transactional; null where inapplicable
     */
    public record DltHeaderRequest(
            @NotBlank String entityId,
            @NotBlank String header,
            @NotBlank String category,
            String series,
            java.time.LocalDate registeredAt,
            boolean active) {
    }

    public record DltTemplateRequest(
            @NotBlank String entityId,
            @NotBlank String headerId,
            @NotBlank String purposeCode,
            @NotBlank String templateRef,
            String description,
            java.time.LocalDate registeredAt,
            boolean active) {
    }

    // ---------------------------------------------------------------------------------------
    // Retention
    // ---------------------------------------------------------------------------------------

    /**
     * Erasures the platform has proposed and nobody has confirmed.
     *
     * <p>The compliance position, stated as a list. The platform proposes and the system holding
     * the data disposes; what is open here is the set of records the group is obliged under DPDP
     * s.8(7) to have erased and cannot show it has.
     */
    @GetMapping("/retention/open")
    public List<RetentionStore.Action> openRetentionActions(
            @RequestParam String entityId,
            @RequestParam(defaultValue = "200") int limit) {
        return retentionStore.open(entityId, Math.min(limit, 1000));
    }

    /**
     * Records what the owning system did about a proposal.
     *
     * <p>{@code RETAINED} is a legitimate outcome and not a failure — a legal hold or a live
     * contract is a basis to keep data that the expired one no longer supplies. What it must not
     * be is indistinguishable from a proposal nobody read, which is why it takes a note.
     */
    @PostMapping("/retention/{id}/complete")
    public Map<String, Object> completeRetentionAction(
            @PathVariable long id,
            @Valid @RequestBody RetentionCompletionRequest request,
            Authentication authentication) {
        int updated = retentionStore.complete(id, request.status(), actorOf(authentication),
                request.note(), Instant.now());
        if (updated == 0) {
            throw new IllegalArgumentException("retention action " + id + " is not open; it does "
                    + "not exist or has already been completed");
        }
        auditStore.record(actorOf(authentication), "RETENTION_ACTION_COMPLETED", null,
                "retention_action", String.valueOf(id),
                Map.of("status", request.status(), "note", request.note()));
        return Map.of("id", id, "status", request.status(), "recorded", true);
    }

    /** Runs the retention sweep now rather than waiting for the schedule. */
    @PostMapping("/retention/sweep")
    public RetentionSweeper.Report sweepRetention() {
        return retentionSweeper.run(Instant.now());
    }

    /**
     * @param status ERASED, RETAINED or CANCELLED
     * @param note   why. A RETAINED with no reason is an undocumented retention wearing a label
     */
    public record RetentionCompletionRequest(@NotBlank String status, @NotBlank String note) {
    }

    // ---------------------------------------------------------------------------------------
    // Significant Data Fiduciary obligations (DPDP Rule 13)
    // ---------------------------------------------------------------------------------------

    /**
     * What this entity owes under Rule 13, what is late, and what has not reached the Board.
     *
     * <p>Answers for every entity, not only notified ones. An entity the Government has not
     * designated gets {@code significantFiduciary: false} and three empty lists, which is the
     * correct answer rather than a 404 — "this entity is not an SDF" is information, and an error
     * would read as "we could not tell you".
     */
    @GetMapping("/sdf/{entityId}")
    public SdfObligationService.Register sdfRegister(@PathVariable String entityId) {
        return sdf.register(entityId, Instant.now());
    }

    /**
     * Raises whatever this entity now owes.
     *
     * <p>{@code designatedFrom} is supplied by the caller rather than read from the database
     * because the Rules attach the annual cycle to a Government notification the platform has no
     * feed for — the same honesty {@code last_reconciled_at} applies to the Consent Manager
     * register. A date invented here would produce a due date nobody could defend.
     */
    @PostMapping("/sdf/{entityId}/raise")
    public Map<String, Object> raiseSdfObligations(
            @PathVariable String entityId,
            @RequestParam LocalDate designatedFrom,
            Authentication authentication) {
        int raised = sdf.raiseDue(entityId, designatedFrom, Instant.now());
        auditStore.record(actorOf(authentication), "SDF_OBLIGATIONS_RAISED", entityId,
                "sdf_obligation", entityId,
                Map.of("designatedFrom", designatedFrom.toString(), "raised", raised));
        return Map.of("entityId", entityId, "raised", raised);
    }

    /** Records that an assessment, audit or diligence check was carried out. */
    @PostMapping("/sdf/obligations/{id}/complete")
    public Map<String, Object> completeSdfObligation(
            @PathVariable long id,
            @Valid @RequestBody SdfCompletionRequest request,
            Authentication authentication) {
        sdf.complete(id, request.conductedBy(), request.artefactRef(), request.artefactSha256(),
                request.findings(), Instant.now());
        auditStore.record(actorOf(authentication), "SDF_OBLIGATION_COMPLETED", null,
                "sdf_obligation", String.valueOf(id),
                Map.of("conductedBy", request.conductedBy(),
                        "artefactSha256", request.artefactSha256()));
        return Map.of("id", id, "completed", true);
    }

    /** Records that the observations were furnished to the Board, which Rule 13 asks for. */
    @PostMapping("/sdf/obligations/{id}/reported")
    public Map<String, Object> reportSdfObligationToBoard(@PathVariable long id,
                                                          Authentication authentication) {
        int updated = sdfObligations.markReportedToBoard(id, Instant.now());
        if (updated == 0) {
            throw new IllegalArgumentException("SDF obligation " + id + " cannot be reported; it "
                    + "does not exist or has not been completed");
        }
        auditStore.record(actorOf(authentication), "SDF_OBLIGATION_REPORTED_TO_BOARD", null,
                "sdf_obligation", String.valueOf(id), Map.of());
        return Map.of("id", id, "reported", true);
    }

    /** The algorithmic systems Rule 13's due diligence is about. */
    @GetMapping("/sdf/{entityId}/systems")
    public List<AlgorithmicSystemStore.AlgorithmicSystem> algorithmicSystems(
            @PathVariable String entityId) {
        return algorithmicSystems.forEntity(entityId, false);
    }

    /** Registers or updates one. A group that cannot list its systems cannot assure them. */
    @PutMapping("/sdf/{entityId}/systems")
    public Map<String, Object> registerAlgorithmicSystem(
            @PathVariable String entityId,
            @Valid @RequestBody AlgorithmicSystemRequest request,
            Authentication authentication) {
        long id = algorithmicSystems.upsert(new AlgorithmicSystemStore.AlgorithmicSystem(
                entityId, request.name(), request.decides(),
                request.purposeCodes() == null ? List.of() : request.purposeCodes(),
                request.automatedDecisionMaking(), request.owner()));
        auditStore.record(actorOf(authentication), "ALGORITHMIC_SYSTEM_REGISTERED", entityId,
                "algorithmic_system", String.valueOf(id),
                Map.of("name", request.name(),
                        "automatedDecisionMaking",
                        String.valueOf(request.automatedDecisionMaking())));
        return Map.of("id", id, "registered", true);
    }

    /**
     * @param artefactSha256 hash of the report. Required: Rule 13 asks for evidence available on
     *                       audit, and a reference with no hash points at a document that can be
     *                       swapped without this row noticing
     */
    public record SdfCompletionRequest(@NotBlank String conductedBy, @NotBlank String artefactRef,
                                       @NotBlank String artefactSha256, String findings) {
    }

    /**
     * @param decides what the system decides about people, in a sentence
     * @param automatedDecisionMaking whether it makes decisions PIPA treats as automated, which
     *                                carries a separate consent requirement of its own
     */
    public record AlgorithmicSystemRequest(@NotBlank String name, @NotBlank String decides,
                                           List<String> purposeCodes,
                                           boolean automatedDecisionMaking, String owner) {
    }

    // ---------------------------------------------------------------------------------------
    // Korea — the two-yearly re-confirmation (Network Act Enforcement Decree Art. 62-3)
    // ---------------------------------------------------------------------------------------

    /**
     * Consents owing their two-yearly Korean confirmation, oldest obligation first.
     *
     * <p>A work queue, not a list of problems with the consents on it. Every row here is a live,
     * valid consent that somebody owes an affirmative check on; nothing about appearing here makes
     * the consent unusable, and the decision API keeps allowing on all of them. See V19's header.
     */
    @GetMapping("/reconfirmation/due")
    public List<ReconfirmationStore.Reconfirmation> reconfirmationsDue(
            @RequestParam String entityId,
            @RequestParam(defaultValue = "200") int limit) {
        requireKoreaReconfirmation();
        return reconfirmationStore.open(entityId, Math.min(limit, 1000));
    }

    /**
     * Refuses the Korean re-confirmation routes when the group has not switched the obligation on.
     *
     * <p>Guarded at the handler rather than by making the whole controller conditional, because
     * these four routes share a class with sixty that have nothing to do with Korea. The queue they
     * read is empty while the sweeper is dark, so leaving them mapped would answer an operator's
     * question with a clean-looking empty list — which reads as "nothing is owed" and means
     * "nothing is being looked for". A 404 naming the flag says which of the two it is.
     */
    private void requireKoreaReconfirmation() {
        if (!properties.getFeatures().isKoreaReconfirmation()) {
            throw new FeatureDisabledException("uds.consent.features.korea-reconfirmation",
                    "the Korean two-year re-confirmation queue (Network Act Enforcement Decree "
                            + "Art. 62-3)");
        }
    }

    /**
     * Records that the confirmation was sent, and what it disclosed.
     *
     * <p>All three of Art. 62-3(2)'s disclosures are required. The store refuses a partial one
     * rather than recording an obligation as discharged by something that did not discharge it —
     * the failure otherwise being a register that reads clean and a finding on first inspection.
     */
    @PostMapping("/reconfirmation/{id}/sent")
    public Map<String, Object> markReconfirmationSent(
            @PathVariable long id,
            @Valid @RequestBody ReconfirmationSentRequest request,
            Authentication authentication) {
        requireKoreaReconfirmation();
        int updated = reconfirmationStore.markSent(id, request.senderName(),
                request.disclosedConsentDate(), request.withdrawalMethod(), request.channel(),
                Instant.now());
        if (updated == 0) {
            throw new IllegalArgumentException("reconfirmation " + id + " is not due; it does not "
                    + "exist or has already been sent or closed");
        }
        auditStore.record(actorOf(authentication), "RECONFIRMATION_SENT", null,
                "consent_reconfirmation", String.valueOf(id),
                Map.of("senderName", request.senderName(),
                        "withdrawalMethod", request.withdrawalMethod(),
                        "channel", request.channel() == null ? "" : request.channel()));
        return Map.of("id", id, "status", "SENT", "recorded", true);
    }

    /**
     * Records what the recipient answered.
     *
     * <p>{@code WITHDRAWN} closes the queue row and does not itself withdraw the consent — that is
     * a ledger event, appended through the consent API like every other withdrawal, because a
     * consent whose withdrawal existed only as a status on an administrative table would be
     * withdrawn everywhere except in the record that is supposed to prove it.
     */
    @PostMapping("/reconfirmation/{id}/completed")
    public Map<String, Object> completeReconfirmation(
            @PathVariable long id,
            @Valid @RequestBody ReconfirmationCompletionRequest request,
            Authentication authentication) {
        requireKoreaReconfirmation();
        int updated = reconfirmationStore.complete(id, request.status(), actorOf(authentication),
                request.note(), Instant.now());
        if (updated == 0) {
            throw new IllegalArgumentException("reconfirmation " + id + " is not open; it does "
                    + "not exist or has already been closed");
        }
        auditStore.record(actorOf(authentication), "RECONFIRMATION_COMPLETED", null,
                "consent_reconfirmation", String.valueOf(id),
                Map.of("status", request.status(), "note", request.note()));
        return Map.of("id", id, "status", request.status(), "recorded", true);
    }

    /** Runs the re-confirmation sweep now rather than waiting for the twelve-hour schedule. */
    @PostMapping("/reconfirmation/sweep")
    public ReconfirmationSweeper.Report sweepReconfirmations() {
        requireKoreaReconfirmation();
        return reconfirmationSweeper.run(Instant.now());
    }

    /**
     * @param senderName           Art. 62-3(2): who the recipient was told is sending
     * @param disclosedConsentDate the consent date shown to the recipient
     * @param withdrawalMethod     how they were told to maintain or withdraw
     */
    public record ReconfirmationSentRequest(@NotBlank String senderName,
                                            @NotNull Instant disclosedConsentDate,
                                            @NotBlank String withdrawalMethod,
                                            String channel) {
    }

    /**
     * @param status MAINTAINED, WITHDRAWN or NOT_APPLICABLE
     * @param note   what the recipient actually said, or why the row does not apply
     */
    public record ReconfirmationCompletionRequest(@NotBlank String status, @NotBlank String note) {
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

    /**
     * The person taking the action, refusing the request if the caller did not say who.
     *
     * <p>This used to return {@code authentication.getName()} — the API credential — in six
     * identical copies across this package. {@code compliance-console} is one credential held by a
     * compliance team, so an append-only audit row attributing an action to it is permanently and
     * unfixably ambiguous about who authorised it, which is the one question an audit trail exists
     * to answer.
     *
     * <p>{@link Actor#required} throws when {@code X-UDS-Actor} is absent, which lands on the 400
     * handler. Applied to the audited operations rather than to every read: a query attributed to
     * the credential that ran it is accurate, and demanding the header on every GET would break
     * every integration for no attribution gained. The evidence bundle is the deliberate
     * exception — it is a GET, and it composes one named person's entire record, which is exactly
     * the disclosure worth having a name against.
     */
    private static String actorOf(Authentication authentication) {
        return Actor.required(authentication).actorId();
    }
}
