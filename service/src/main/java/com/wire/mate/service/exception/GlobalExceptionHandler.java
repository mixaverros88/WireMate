package com.wire.mate.service.exception;

import com.wire.mate.service.config.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Maps every domain and framework exception to an RFC 9457 / RFC 7807
 * {@link ProblemDetail}. The contract for clients is:
 *
 * <ul>
 *   <li>Errors are JSON, content-type {@code application/problem+json}</li>
 *   <li>Each response includes the request URI as {@code instance}, the
 *       correlation ID (so an operator can find the matching log line) and
 *       a server-side timestamp</li>
 *   <li>Stack traces are <em>never</em> included in the response body</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final URI ERROR_BASE = URI.create("https://api.wiremate.local/errors/");

    @ExceptionHandler(ProjectAlreadyExistsException.class)
    public ProblemDetail handleProjectAlreadyExists(ProjectAlreadyExistsException ex, HttpServletRequest request) {
        return enrich(problem(HttpStatus.CONFLICT, "Project Already Exists", "project-already-exists", ex.getMessage()), request);
    }

    @ExceptionHandler(MockAlreadyExistsException.class)
    public ProblemDetail handleMockAlreadyExists(MockAlreadyExistsException ex, HttpServletRequest request) {
        return enrich(problem(HttpStatus.CONFLICT, "Mock Already Exists", "mock-already-exists", ex.getMessage()), request);
    }

    @ExceptionHandler(ProjectNotFoundException.class)
    public ProblemDetail handleProjectNotFound(ProjectNotFoundException ex, HttpServletRequest request) {
        return enrich(problem(HttpStatus.NOT_FOUND, "Project Not Found", "project-not-found", ex.getMessage()), request);
    }

    @ExceptionHandler(MockNotFoundException.class)
    public ProblemDetail handleMockNotFound(MockNotFoundException ex, HttpServletRequest request) {
        return enrich(problem(HttpStatus.NOT_FOUND, "Mock Not Found", "mock-not-found", ex.getMessage()), request);
    }

    @ExceptionHandler(WireMockPublishException.class)
    public ProblemDetail handleWireMockPublish(WireMockPublishException ex, HttpServletRequest request) {
        log.warn("WireMock publish failed: {}", ex.getMessage());
        return enrich(problem(HttpStatus.BAD_GATEWAY, "WireMock Publish Failed", "wiremock-publish-failed", ex.getMessage()), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation Error", "validation-error", "Validation failed");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", error.getDefaultMessage() == null ? "invalid" : error.getDefaultMessage()))
                .toList();
        problem.setProperty("errors", errors);
        return enrich(problem, request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String detail = "Parameter '" + ex.getName() + "' has invalid value '" + ex.getValue() + "'";
        return enrich(problem(HttpStatus.BAD_REQUEST, "Invalid Parameter", "invalid-parameter", detail), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
        // Don't echo the parser message back — it can leak internal class names.
        return enrich(problem(HttpStatus.BAD_REQUEST, "Malformed Request Body", "malformed-body",
                "Request body could not be parsed"), request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return enrich(problem(HttpStatus.CONFLICT, "Conflict", "data-integrity",
                "The request conflicts with the current state of the resource."), request);
    }

    /**
     * Last-resort handler. Anything that reaches this method is unexpected and
     * is logged with full stack trace, but the client sees a generic
     * {@code 500} with no internals.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception", ex);
        return enrich(problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "internal-error",
                "An unexpected error occurred"), request);
    }

    private static ProblemDetail problem(HttpStatusCode status, String title, String typeSlug, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(ERROR_BASE.resolve(typeSlug));
        return problem;
    }

    private static ProblemDetail enrich(ProblemDetail problem, HttpServletRequest request) {
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
        return problem;
    }
}
