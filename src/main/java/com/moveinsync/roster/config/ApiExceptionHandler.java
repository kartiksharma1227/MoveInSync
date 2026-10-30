package com.moveinsync.roster.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Turns every error into the same RFC 9457 problem body: a stable machine-readable {@code code}, a fixed human
 * sentence, and the correlation ID. Input values are never echoed back, because they may be personal data.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE) // ahead of Spring's default handlers, so every error gets this shape
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, Object>> onStatus(ResponseStatusException e, HttpServletRequest request) {
        String code = e.getReason() != null ? e.getReason() : codeFor(e.getStatusCode());
        return respond(e.getStatusCode(), code, request, null);
    }

    /** @Valid failures: which field broke which rule, never the value that was sent. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> onInvalid(MethodArgumentNotValidException e, HttpServletRequest request) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(), "code", String.valueOf(fe.getCode())))
                .toList();
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", request, errors);
    }

    /** Broken JSON. Jackson's own message quotes the offending input, so it is deliberately dropped. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> onUnreadable(HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", request, null);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<Map<String, Object>> onMissingParam(MissingServletRequestParameterException e, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER", request, List.of(Map.of("field", e.getParameterName())));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<Map<String, Object>> onMissingPart(MissingServletRequestPartException e, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER", request, List.of(Map.of("field", e.getRequestPartName())));
    }

    /** E.g. snapshotAt=yesterday. Names the parameter, doesn't repeat the value. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, Object>> onTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", request, List.of(Map.of("field", e.getName())));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, Object>> onTooLarge(HttpServletRequest request) {
        return respond(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", request, null);
    }

    /** Someone else changed this record between our read and our write: reload and retry. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<Map<String, Object>> onConcurrentEdit(HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", request, null);
    }

    /** The database can't be reached. Temporary, so tell the client to retry (batches are idempotent). */
    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    ResponseEntity<Map<String, Object>> onDatabaseDown(Exception e, HttpServletRequest request) {
        log.error("Database unavailable correlationId={} type={}", CorrelationIdFilter.current(), e.getClass().getName());
        return respond(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", request, null);
    }

    /** From @PreAuthorize. Must be explicit, or the catch-all below would report it as a 500. */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String, Object>> onDenied(HttpServletRequest request) {
        return respond(HttpStatus.FORBIDDEN, "FORBIDDEN", request, null);
    }

    /**
     * Anything else. Framework errors that already know their status (405, 404 for unknown paths, 415 …) keep it.
     * A real bug becomes a 500, logged with its type and location but not its message, which could contain data.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> onUnexpected(Exception e, HttpServletRequest request) {
        if (e instanceof ErrorResponse known) {
            return respond(known.getStatusCode(), codeFor(known.getStatusCode()), request, null);
        }
        StackTraceElement where = e.getStackTrace().length > 0 ? e.getStackTrace()[0] : null;
        log.error("Unhandled error correlationId={} type={} at={}", CorrelationIdFilter.current(),
                e.getClass().getName(), where);
        log.debug("Unhandled error stack trace", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", request, null);
    }

    private ResponseEntity<Map<String, Object>> respond(HttpStatusCode status, String code, HttpServletRequest request,
                                                        List<?> errors) {
        Map<String, Object> body = body(status, code, routeOf(request));
        if (errors != null) {
            body.put("errors", errors);
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    /**
     * The matched route, e.g. /api/v1/employees/{employeeId}, never the raw path: path values are caller input and
     * could be personal data. Null when nothing matched (unknown path).
     */
    private static String routeOf(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern instanceof String route ? route : null;
    }

    /** The one error body shape, also used by the security layer for 401 and 403. */
    public static Map<String, Object> body(HttpStatusCode status, String code, String route) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        HttpStatus known = HttpStatus.resolve(status.value());
        body.put("title", known != null ? known.getReasonPhrase() : "Error");
        body.put("code", code);
        body.put("detail", detailFor(status.value()));
        body.put("instance", route);
        body.put("correlationId", CorrelationIdFilter.current());
        return body;
    }

    private static String codeFor(HttpStatusCode status) {
        HttpStatus known = HttpStatus.resolve(status.value());
        return known != null ? known.name() : "ERROR";
    }

    private static String detailFor(int status) {
        return switch (status) {
            case 400 -> "The request is invalid.";
            case 401 -> "Authentication is required.";
            case 403 -> "You are not allowed to perform this action.";
            case 404 -> "The requested resource was not found.";
            case 409 -> "The request conflicts with the current state of the resource.";
            case 413 -> "The request is too large.";
            case 503 -> "Temporarily unavailable. Retry the request; it is safe to resend.";
            case 500 -> "An unexpected error occurred. Quote the correlation ID when reporting it.";
            default -> "The request could not be completed.";
        };
    }
}
