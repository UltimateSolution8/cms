package com.uds.consent.service.api;

import com.uds.consent.core.snapshot.SnapshotVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onIllegalArgument(IllegalArgumentException e) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid request");
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

    @ExceptionHandler(Exception.class)
    public ProblemDetail onUnexpectedFailure(Exception e) {
        log.error("unhandled failure serving request", e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                // Deliberately opaque. The exception message could contain a query fragment, and
                // query fragments in this system contain identifier hashes.
                "The request could not be completed. Check the service logs with the trace id.");
        problem.setTitle("Internal error");
        return problem;
    }
}
