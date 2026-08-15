package com.uds.consent.service.api;

import com.uds.consent.core.decision.DecisionRequest;
import com.uds.consent.core.decision.DecisionResponse;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.policy.PolicyEngine;
import com.uds.consent.service.api.dto.ConsentApi;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * The decision API — the question every system asks before it processes anything.
 *
 * <p>Athena's dialer calls this before each number. DenCRM calls it before an export. The
 * campaign tools call it before a send. One endpoint, one answer, one place a regulator can be
 * shown how the answer was reached.
 *
 * <p>This endpoint is not meant to be on the hot path of a field device. Devices carry a signed
 * snapshot and decide locally in microseconds; this is for server-side callers and for devices
 * that have connectivity and want the freshest answer.
 */
@RestController
@RequestMapping("/v1")
public class DecisionController {

    /**
     * Batch ceiling. Large enough for a dialer to pre-flight a working batch in one call, small
     * enough that a single request cannot monopolise a worker thread and stall the callers behind
     * it.
     */
    private static final int MAX_BATCH = 1000;

    private final PolicyEngine engine;

    public DecisionController(PolicyEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/evaluate")
    @PreAuthorize("hasAnyRole('DECISION', 'CAPTURE', 'ADMIN')")
    public ConsentApi.EvaluateResponse evaluate(
            @Valid @RequestBody ConsentApi.EvaluateRequest request) {
        return toResponse(engine.evaluate(toDomain(request)));
    }

    /**
     * Evaluates many requests at once.
     *
     * <p>Answers are returned in request order, and a denial for one subject never affects
     * another. A campaign of fifty thousand contacts becomes fifty calls rather than fifty
     * thousand.
     */
    @PostMapping("/evaluate/batch")
    @PreAuthorize("hasAnyRole('DECISION', 'ADMIN')")
    public List<ConsentApi.EvaluateResponse> evaluateBatch(
            @Valid @RequestBody List<ConsentApi.EvaluateRequest> requests) {
        if (requests.size() > MAX_BATCH) {
            throw new IllegalArgumentException(
                    "batch of " + requests.size() + " exceeds the maximum of " + MAX_BATCH);
        }
        return requests.stream()
                .map(request -> toResponse(engine.evaluate(toDomain(request))))
                .toList();
    }

    private static DecisionRequest toDomain(ConsentApi.EvaluateRequest request) {
        return new DecisionRequest(
                request.entityId(),
                request.subjectId(),
                request.purposeCode(),
                request.channel(),
                request.jurisdiction() == null ? Jurisdiction.IN : request.jurisdiction(),
                request.applicationId(),
                // Callers may pin the evaluation instant, which is what makes a past decision
                // reproducible during an audit rather than merely describable.
                request.at() == null ? Instant.now() : request.at(),
                request.clientId(),
                request.campaignId(),
                request.vendorId(),
                request.context());
    }

    private static ConsentApi.EvaluateResponse toResponse(DecisionResponse decision) {
        return new ConsentApi.EvaluateResponse(
                decision.outcome().name(),
                decision.reason().name(),
                decision.explanation(),
                decision.legalBasis(),
                decision.purposeCode(),
                decision.purposeVersion(),
                decision.policyVersion(),
                decision.evaluatedAt(),
                decision.consentExpiresAt(),
                decision.obligations());
    }
}
