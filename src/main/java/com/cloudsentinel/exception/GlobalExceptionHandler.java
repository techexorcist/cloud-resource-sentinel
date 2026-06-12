package com.cloudsentinel.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Centralized exception handler for all REST controllers in the Cloud Resource Sentinel API.
 *
 * <p>This class uses Spring's {@link RestControllerAdvice} to intercept exceptions thrown by
 * any {@code @RestController} and convert them into consistent JSON error responses with a
 * {@code "detail"} field describing the error. This ensures that API consumers always receive
 * a predictable error format regardless of the exception type.</p>
 *
 * <p>The handler maps different exception types to appropriate HTTP status codes:</p>
 * <ul>
 *   <li>{@link ResponseStatusException} -- uses the status code embedded in the exception</li>
 *   <li>{@link IllegalArgumentException} -- mapped to {@code 400 Bad Request} (invalid input)</li>
 *   <li>{@link IllegalStateException} -- mapped to {@code 429 Too Many Requests} (queue full,
 *       duplicate scan, etc.)</li>
 *   <li>All other exceptions -- mapped to {@code 500 Internal Server Error} with a generic
 *       message to avoid leaking internal details</li>
 * </ul>
 *
 * <p>Unhandled exceptions are logged at ERROR level with full stack traces for debugging,
 * while the client receives only a sanitized message.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles {@link ResponseStatusException} instances thrown by controllers.
     *
     * <p>These exceptions carry their own HTTP status code and reason phrase, which are
     * forwarded directly to the client. If no reason is provided, a fallback message of
     * {@code "Unknown error"} is used.</p>
     *
     * @param ex the response status exception containing the HTTP status and optional reason
     * @return a {@link ResponseEntity} with the exception's status code and a JSON body
     *         containing a {@code "detail"} field
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(Map.of("detail", ex.getReason() != null ? ex.getReason() : "Unknown error"));
    }

    /**
     * Handles {@link IllegalArgumentException} instances, typically thrown when request
     * validation fails (e.g., invalid profile name, unsupported region, malformed input).
     *
     * @param ex the illegal argument exception with a descriptive message
     * @return a {@link ResponseEntity} with HTTP 400 Bad Request and a JSON body
     *         containing the exception message in the {@code "detail"} field
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(Map.of("detail", ex.getMessage()));
    }

    /**
     * Handles {@link IllegalStateException} instances, which in this application typically
     * indicate that the scan queue is full (max 7 queued jobs) or that a duplicate scan
     * was submitted for a profile that already has an active job.
     *
     * <p>Mapped to HTTP 429 (Too Many Requests) to signal rate-limiting or capacity constraints
     * to the client, allowing it to retry later or inform the user.</p>
     *
     * @param ex the illegal state exception with a descriptive message
     * @return a {@link ResponseEntity} with HTTP 429 Too Many Requests and a JSON body
     *         containing the exception message in the {@code "detail"} field
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("detail", ex.getMessage()));
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Catch-all handler for any unhandled exceptions not covered by the more specific handlers.
     *
     * <p>Logs the full exception (including stack trace) at ERROR level for debugging and
     * operational alerting, but returns only a generic error message to the client to avoid
     * leaking internal implementation details, stack traces, or sensitive information.</p>
     *
     * @param ex the unhandled exception
     * @return a {@link ResponseEntity} with HTTP 500 Internal Server Error and a JSON body
     *         containing a generic {@code "detail"} message
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("detail", "An internal error occurred"));
    }
}
