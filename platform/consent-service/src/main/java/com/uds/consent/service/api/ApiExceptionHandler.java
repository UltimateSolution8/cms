package com.uds.consent.service.api;

import com.uds.consent.core.snapshot.SnapshotVerifier;
import com.uds.consent.service.ConsentManagerRelayService;
import com.uds.consent.service.NoticeService;
import com.uds.consent.service.PrincipalPortalService;
import com.uds.consent.service.ReceiptService;
import com.uds.consent.service.RightsService;
import com.uds.consent.service.config.FeatureDisabledException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Turns failures into responses.
 *
 * <p>Two rules run through this class. Errors carry enough detail for the integrating engineer to
 * fix their call, because a vague 400 costs an afternoon. And errors never carry personal data —
 * no phone number, no email, no name — because error responses end up in logs, in ticket
 * attachments and in screenshots pasted into chat.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onValidationFailure(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Request validation failed");
        return problem;
    }

    /**
     * A body this platform could not parse — malformed JSON, or a value outside an enum.
     *
     * <p>Found the same way as the 404 above: by a new suite sending a plausible-looking
     * {@code captureMethod} that is not one of the twelve the platform accepts, and getting
     * {@code 500 Internal Server Error} with "check the service logs with the trace id". Spring
     * raises {@code HttpMessageNotReadableException} before the handler runs, and the catch-all was
     * swallowing it.
     *
     * <p>That is the wrong answer in a way that costs real time. An integrator sending
     * {@code "WEB_FORM"} instead of {@code "CHECKBOX_OPT_IN"} is told the platform is broken rather
     * than that their value is not in the list, and reasonably retries — and every retry writes
     * another ERROR line about an unhandled failure that is not one. Capture surfaces are built
     * against this API by teams outside the platform group; the failure to make this legible is
     * paid for in their afternoons.
     *
     * <p>The most specific cause is echoed because Jackson names the field and lists the accepted
     * values, which is exactly what the caller needs. It is safe to return: the message describes
     * the <em>shape</em> of what was sent, and a body that failed to parse never became a value the
     * platform could leak.
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ProblemDetail onUnreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                e.getMostSpecificCause().getMessage());
        problem.setTitle("Request body could not be read");
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onIllegalArgument(IllegalArgumentException e) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid request");
        return problem;
    }

    @ExceptionHandler(ReceiptService.ReceiptNotFoundException.class)
    public ProblemDetail onReceiptNotFound(ReceiptService.ReceiptNotFoundException e) {
        // 404 rather than 400. A receipt number that names nothing is almost always a
        // transcription error by somebody reading it off a printout during a grievance call, and
        // "not found" is the answer that tells them to check the digits.
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Receipt not found");
        return problem;
    }

    @ExceptionHandler(NoticeService.NoticeNotFoundException.class)
    public ProblemDetail onNoticeNotFound(NoticeService.NoticeNotFoundException e) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Notice not found");
        return problem;
    }

    /**
     * The notice exists but not in the language asked for.
     *
     * <p>Answered with the gap made explicit — which languages do exist — rather than by quietly
     * returning English. A subject shown a notice in a language they do not read has not been
     * informed, and a consent record captured against it looks valid and is not. The response
     * carries enough for a capture surface to offer a real choice instead of a false one.
     *
     * <p>Logged by the service at WARN, not here: it is a translation gap the group should close,
     * not a mistake the caller made.
     */
    @ExceptionHandler(NoticeService.TranslationNotAvailableException.class)
    public ProblemDetail onTranslationUnavailable(NoticeService.TranslationNotAvailableException e) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Notice not available in that language");
        problem.setProperty("noticeId", e.noticeId());
        problem.setProperty("noticeVersion", e.version());
        problem.setProperty("requestedLanguage", e.requestedLanguage());
        problem.setProperty("availableLanguages", e.availableLanguages());
        return problem;
    }

    /**
     * A relay from something claiming to be a Consent Manager that may not relay.
     *
     * <p>403 rather than 404, and the same 403 whether the registration is unknown, suspended or
     * deregistered. The distinction matters to an operator and not to the caller: telling an
     * unregistered caller that the number they guessed exists but is suspended turns this endpoint
     * into a way of enumerating the register. The refusal is recorded in full on the enforcement
     * log, which is where the detail belongs.
     */
    @ExceptionHandler(ConsentManagerRelayService.RelayRefusedException.class)
    public ProblemDetail onRelayRefused(ConsentManagerRelayService.RelayRefusedException e) {
        log.warn("refused consent-manager relay for registration={}: {}",
                e.registrationId(), e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                "this relay will not be honoured");
        problem.setTitle("Consent Manager relay refused");
        return problem;
    }

    /**
     * A route on a regulatory surface this deployment has not switched on.
     *
     * <p>404 rather than 403 or 501. The surface is not forbidden and it is not unimplemented — it
     * is code that exists, is tested, and is deliberately dark because the obligation behind it
     * does not reach UDS yet. From the caller's side there is nothing there, and saying so is both
     * the truth and the answer that does not invite them to go and ask for a permission nobody can
     * grant.
     *
     * <p>The property name is carried on the response because the likeliest reader is an operator
     * comparing two environments, not an integrator.
     */
    @ExceptionHandler(FeatureDisabledException.class)
    public ProblemDetail onFeatureDisabled(FeatureDisabledException e) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Feature not enabled");
        problem.setProperty("feature", e.property());
        return problem;
    }

    /**
     * A disclosing right cannot be closed as fulfilled while nobody recorded who was asking.
     *
     * <p>409 like the fulfilment gate beside it, and the two are deliberately distinguishable in
     * the body. Both refuse the same call for different reasons, and an operator told the wrong one
     * fixes the wrong thing — so the title names verification, the type is carried as a property,
     * and the detail names the route that clears it.
     */
    @ExceptionHandler(RightsService.VerificationMissingException.class)
    public ProblemDetail onVerificationMissing(RightsService.VerificationMissingException e) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Identity not verified");
        problem.setProperty("requestType", e.type().name());
        return problem;
    }

    /**
     * Verification has already been recorded on this request.
     *
     * <p>409 rather than 200-and-ignore: a caller who thinks they have recorded a check and has
     * not would otherwise close the request believing the record says something it does not.
     */
    @ExceptionHandler(RightsService.VerificationAlreadyRecordedException.class)
    public ProblemDetail onVerificationAlreadyRecorded(
            RightsService.VerificationAlreadyRecordedException e) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Verification already recorded");
        return problem;
    }

    /**
     * A rights request cannot be closed as fulfilled, because a system that had to act has not.
     *
     * <p>409 rather than 400: nothing about the request is malformed and the caller is not wrong to
     * be trying — the state of the world is simply not yet what the closure would assert. That
     * distinction matters to whoever is working the queue against a statutory clock, because a 400
     * says "fix your call" and this says "finish the work".
     *
     * <p>The outstanding systems are named on the response. The operator's next question is always
     * "which one", and an answer that made them go and look it up would cost minutes on a deadline
     * measured in days but spent in ten-minute pieces.
     */
    @ExceptionHandler(RightsService.FulfilmentIncompleteException.class)
    public ProblemDetail onFulfilmentIncomplete(RightsService.FulfilmentIncompleteException e) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Fulfilment not evidenced");
        problem.setProperty("outstandingSystems", e.outstanding());
        return problem;
    }

    /**
     * A data principal's verification code did not match.
     *
     * <p>404, and one shape for every cause: no such reference, wrong code, already used, expired.
     * The distinctions are exactly what an attacker would want — a 404 for an unknown reference and
     * a 401 for a wrong code would confirm which references exist, and separating "already used"
     * from "expired" would confirm that a real person had one.
     *
     * <p>Not logged at WARN either. This route is unauthenticated, so a scanner walking it would
     * otherwise fill the log an incident is reconstructed from. The attempt counter on the
     * reference is the record that matters, and it is bounded.
     */
    @ExceptionHandler(PrincipalPortalService.VerificationFailed.class)
    public ProblemDetail onVerificationFailed(PrincipalPortalService.VerificationFailed e) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Reference or code not recognised");
        return problem;
    }

    @ExceptionHandler(SnapshotVerifier.SnapshotVerificationException.class)
    public ProblemDetail onSnapshotVerificationFailure(
            SnapshotVerifier.SnapshotVerificationException e) {
        // Logged at error level regardless of cause. A snapshot that fails verification is either
        // a rotation gone wrong or someone tampering with enforcement state on a device; both
        // want a human to look.
        log.error("snapshot verification failed: {}", e.getMessage());
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
        problem.setTitle("Snapshot could not be verified");
        return problem;
    }

    /**
     * The database refusing to let history be rewritten.
     *
     * <p>Returns 409 rather than 500 because it is not a server fault — it is the append-only
     * guarantee working. The message names the constraint so that whoever hits it can see whether
     * they tried to update the ledger or simply replayed an idempotency key.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail onDataIntegrityViolation(DataIntegrityViolationException e) {
        String cause = e.getMostSpecificCause().getMessage();
        log.warn("data integrity violation: {}", cause);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, cause);
        problem.setTitle("Write rejected by the evidence plane");
        return problem;
    }

    /**
     * A caller authenticated successfully and asked for something its role does not permit.
     *
     * <p>Handled explicitly because the catch-all below would otherwise turn it into a 500, and a
     * 500 is the wrong answer in a way that matters: an integrator seeing it assumes the platform
     * is broken and retries, and the genuine finding — that a dialer's credential is being used to
     * write consent — is lost in a noise of server errors instead of standing out.
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ProblemDetail onAccessDenied(org.springframework.security.access.AccessDeniedException e) {
        log.warn("access denied: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                "This client's role does not permit that operation.");
        problem.setTitle("Forbidden");
        return problem;
    }

    /**
     * A path this platform does not serve.
     *
     * <p>Found by {@code FeatureFlagIT} while asserting that a dark feature's routes are gone, and
     * it turned out not to be about feature flags at all. Spring raises
     * {@code NoResourceFoundException} for any unmatched path, and the catch-all below was
     * swallowing it — so <em>every</em> request to a URL this platform does not serve came back
     * {@code 500 Internal Server Error} with "check the service logs with the trace id", and wrote
     * an ERROR line saying "unhandled failure serving request".
     *
     * <p>Both halves of that were wrong in ways that cost something. An integrator with a typo in a
     * path was told the platform was broken and would reasonably retry, instead of being told to
     * fix the URL. And once the rights- and breach-SLA ERROR logs are wired to the on-call channel
     * — which {@code OPERATIONS.md} §4 assumes — any scanner walking the internet for
     * {@code /wp-admin} would page somebody. An alerting channel that cries wolf is an alerting
     * channel nobody reads, which is precisely the failure the SLA logs exist to avoid.
     *
     * <p>Not logged here at all. A 404 is the caller's mistake or a scanner's routine, and neither
     * is a platform event.
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ProblemDetail onNoSuchRoute(
            org.springframework.web.servlet.resource.NoResourceFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                // The method and path are the caller's own request echoed back, so there is
                // nothing here they did not already know — and a path on this API never contains
                // an identifier, only a subject surrogate.
                "This platform serves no route at " + e.getHttpMethod() + " /" + e.getResourcePath()
                        + ". See /v3/api-docs for what it does serve.");
        problem.setTitle("No such route");
        return problem;
    }

    /**
     * Everything else Spring MVC refuses before a handler runs.
     *
     * <p>Three separate defects of this family turned up while writing the suites for this
     * release — an unmatched path, an enum value outside the accepted set, and a body sent as
     * {@code text/plain} — and each came back {@code 500 Internal Server Error} telling the caller
     * the platform was broken. They share one cause: {@code @ExceptionHandler(Exception.class)}
     * below catches everything, and Spring's own protocol failures were reaching it.
     *
     * <p>Fixing them one at a time would have fixed three and left the rest — an unsupported
     * method, a missing parameter, an unacceptable {@code Accept} header — waiting to be found the
     * same accidental way. So this handles the family: every one of these implements
     * {@link ErrorResponse} and already knows its own status code and its own RFC 7807 body.
     * Deferring to that is both more correct than anything written here and permanently complete.
     *
     * <p>Logged at {@code WARN}, not {@code ERROR}. These are all caller mistakes or scanner
     * traffic; routing them to the channel that carries statutory SLA breaches is how that channel
     * stops being read.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> onUnexpectedFailure(Exception e) {
        // Spring's own protocol refusals come through here, and used to be answered as 500s. The
        // instanceof rather than a handler per type is deliberate: ErrorResponse is an interface,
        // not a Throwable, so @ExceptionHandler cannot bind it — and enumerating the concrete
        // types would fix the three found so far and leave an unsupported method, a missing
        // parameter and an unacceptable Accept header waiting to be found the same accidental way.
        //
        // Each of these already knows its own status and its own RFC 7807 body, which is both more
        // correct than anything written here and permanently complete. WARN, not ERROR: they are
        // caller mistakes and scanner traffic, and routing those to the channel that carries
        // statutory SLA breaches is how that channel stops being read.
        if (e instanceof ErrorResponse spring) {
            log.warn("request refused before reaching a handler: {} ({})",
                    spring.getBody().getDetail(), spring.getStatusCode());
            return ResponseEntity.status(spring.getStatusCode()).body(spring.getBody());
        }

        log.error("unhandled failure serving request", e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                // Deliberately opaque. The exception message could contain a query fragment, and
                // query fragments in this system contain identifier hashes.
                "The request could not be completed. Check the service logs with the trace id.");
        problem.setTitle("Internal error");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }
}
