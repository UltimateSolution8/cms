package com.uds.consent.service.api;

import com.uds.consent.core.model.IdentifierType;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.RightsRequestType;
import com.uds.consent.service.PrincipalPortalService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * The only routes on this platform a data principal can reach without a credential.
 *
 * <p>DPDP <strong>Rule 14(1)</strong> requires a Data Fiduciary to prominently publish the means by
 * which a principal makes a request to exercise their rights. The platform has modelled the
 * <em>means</em> since the notice work — {@code NoticeStore} carries {@code rightsUri} and every
 * consent receipt reproduces it — and pointed it at nothing. These three routes are what it points
 * at.
 *
 * <p><strong>Rule 14(1)'s other half is still UDS's.</strong> The Rule also requires publishing
 * "the particulars of such information as may be required to identify" the principal, and that is
 * a policy about how hard the group makes it to exercise a right: ask too little and a request
 * cannot be safely authenticated, ask too much and the identification requirement becomes the
 * obstruction a regulator reads it as. {@code IdentifierType} is the vocabulary that answer will be
 * expressed in, and the answer is not code's to pick. See {@code REGULATORY_HANDOFF.md} §4.
 *
 * <p>Rate-limited as a PUBLIC route — 20 requests per second per address, per instance. A corporate
 * NAT is one address for a whole building, which is why that limit is not tighter.
 */
@RestController
@RequestMapping("/v1/portal")
public class PrincipalPortalController {

    private final PrincipalPortalService portal;

    public PrincipalPortalController(PrincipalPortalService portal) {
        this.portal = portal;
    }

    /**
     * Files a request, and confirms nothing about the person filing it.
     *
     * <p><strong>The response is identical whether or not the group holds a file on that
     * identifier</strong>, because this path never looks. A route that answered differently would
     * let anyone with a list of phone numbers learn which of them UDS holds data about — a
     * disclosure about every person on that list, produced by the feature built to serve them.
     *
     * <p>202 rather than 201: nothing has been created that the caller can point at yet. A request
     * exists once they prove the identifier is theirs.
     */
    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SubmissionResponse submit(@Valid @RequestBody SubmissionRequest request) {
        PrincipalPortalService.Submission submission = portal.submit(
                request.entityId(), request.identifierType(), request.identifierValue(),
                request.requestType(),
                request.jurisdiction() == null ? Jurisdiction.IN : request.jurisdiction());

        return new SubmissionResponse(submission.reference(), submission.expiresAt(),
                "If we hold data under that identifier, a one-time code is on its way to it. "
                        + "Enter the code with this reference to confirm the request. The code "
                        + "expires in 24 hours.");
    }

    /**
     * Confirms the identifier is the caller's, and starts the statutory clock.
     *
     * <p>The clock starts here and not at submission. {@code StatutoryClock} produces a deadline
     * capped by Rule 14(3) at ninety days; letting an anonymous submission start it would let
     * anybody burn the group's whole response window for somebody else, repeatedly, without ever
     * proving they were that person.
     */
    @PostMapping("/requests/{reference}/verify")
    public VerifiedResponse verify(@PathVariable String reference,
                                   @Valid @RequestBody VerifyRequest request) {
        var filed = portal.verify(reference, request.code());
        return new VerifiedResponse(reference, filed.requestId(), filed.status().name(),
                filed.receivedAt(), filed.dueAt(),
                "Your request has been recorded and we will respond by the date shown.");
    }

    /**
     * Status, for a principal holding the reference and the code.
     *
     * <p>Status and dates only. Not the evidence bundle, not the subject id, not the request
     * details — a code delivered to an email address is not the authentication standard on which to
     * hand over a person's complete file, and that route stays behind ADMIN.
     */
    @GetMapping("/requests/{reference}")
    public PrincipalPortalService.Status status(@PathVariable String reference,
                                                @RequestParam String code) {
        return portal.status(reference, code)
                .orElseThrow(PrincipalPortalService.VerificationFailed::new);
    }

    public record SubmissionRequest(
            @NotBlank String entityId,
            @NotNull IdentifierType identifierType,
            @NotBlank @Size(max = 320) String identifierValue,
            @NotNull RightsRequestType requestType,
            Jurisdiction jurisdiction) {
    }

    /**
     * @param message deliberately conditional in its wording — "if we hold data under that
     *                identifier" — because the platform genuinely does not know, and a message
     *                promising delivery would be the disclosure this route is designed to avoid
     */
    public record SubmissionResponse(String reference, Instant expiresAt, String message) {
    }

    public record VerifyRequest(@NotBlank @Size(max = 32) String code) {
    }

    public record VerifiedResponse(String reference, String requestId, String status,
                                   Instant receivedAt, Instant dueAt, String message) {
    }
}
