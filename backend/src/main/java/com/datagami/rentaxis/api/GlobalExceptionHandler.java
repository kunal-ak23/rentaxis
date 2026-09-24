package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.exception.AccessDeniedException;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.api.exception.SlotConflictException;
import com.datagami.rentaxis.core.service.BulkAttachValidationException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(SlotConflictException.class)
    public ResponseEntity<Map<String, Object>> handleSlotConflict(SlotConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", ex.getMessage(),
                "nextAvailableSlot", ex.getNextAvailableSlot() != null ? ex.getNextAvailableSlot().toString() : "unavailable"
        ));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", true,
                "message", ex.getMessage(),
                "status", 404
        ));
    }

    @ExceptionHandler(BusinessRuleViolationException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessRule(BusinessRuleViolationException ex) {
        if (ex.getCode() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", true,
                    "message", ex.getMessage(),
                    "status", 400
            ));
        }
        // F14-09: a translatable refusal also carries its key and values.
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", true,
                "message", ex.getMessage(),
                "status", 400,
                "code", ex.getCode(),
                "args", ex.getArgs()
        ));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "error", true,
                "message", ex.getMessage(),
                "status", 403
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", true,
                "message", "Validation failed: " + errors,
                "status", 400
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", true,
                "message", ex.getMessage() == null ? "Invalid request" : ex.getMessage(),
                "status", 400
        ));
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleSpringAccessDenied(org.springframework.security.access.AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "error", true,
                "message", "Access denied",
                "status", 403
        ));
    }

    @ExceptionHandler(BulkAttachValidationException.class)
    public ResponseEntity<Map<String, Object>> handleBulkAttach(BulkAttachValidationException ex) {
        HttpStatus status = ex.isConflict() ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of(
                "error", "validation_failed",
                "rows", ex.getRows()
        ));
    }

    /**
     * Failed authentication → 401. Without this, the catch-all
     * {@link #handleRuntime} below would swallow it as a 500, since
     * BadCredentialsException is itself a RuntimeException.
     *
     * <p>The message is deliberately generic and never echoes {@code ex}: the
     * throwing service uses one constant string for every failure mode, and
     * this handler must not reintroduce a distinction it worked to remove.
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "error", true,
                "message", "Invalid credentials",
                "status", 401
        ));
    }

    /**
     * Honors the status carried by a ResponseStatusException (e.g. 429 from
     * OTP throttling). Also needed to keep {@link #handleRuntime} from
     * downgrading these to 500 — ExceptionHandlerExceptionResolver runs ahead
     * of Spring's ResponseStatusExceptionResolver, so the catch-all wins
     * without an explicit handler here.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {
        String reason = ex.getReason() != null ? ex.getReason() : "Request failed";
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                "error", true,
                "message", reason,
                "status", ex.getStatusCode().value()
        ));
    }

    /**
     * Request body that would not parse or would not deserialize.
     *
     * <p>400, not 500: nothing of ours ran, so nothing of ours failed. See
     * {@link #clientError} for why the caller is not told what Jackson said.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return clientError("Malformed request body", ex);
    }

    /**
     * A parameter or path variable that would not convert to the declared type
     * — most often a non-UUID where a UUID is expected.
     *
     * <p>The parameter name is safe to return and is the only part worth
     * returning: it comes from the handler signature, and it is what the
     * caller needs in order to fix the request. The rejected value and the
     * required Java type stay out of the response.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return clientError("Invalid value for parameter '" + ex.getName() + "'", ex);
    }

    /**
     * A required request parameter the caller did not send.
     *
     * <p>Unlike its three neighbours this one is a checked ServletException,
     * so it never reached {@link #handleRuntime} and Spring's default resolver
     * already answered 400. What it did not do is produce our error body — the
     * response was 400 with nothing in it, which a client parsing
     * {@code message} cannot read. Handled here for shape, not for status.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParameter(MissingServletRequestParameterException ex) {
        return clientError("Required parameter '" + ex.getParameterName() + "' is missing", ex);
    }

    /**
     * The global {@code spring.servlet.multipart.max-file-size} limit (application.yml,
     * 10MB) rejects an oversized upload before any controller code runs — a
     * {@code MaxUploadSizeExceededException} that used to fall through to
     * {@link #handleRuntime} as an opaque 500, because nothing here or in
     * {@code VoucherController} handled it (security ruling, Task 5 fix round 1;
     * contrast {@code ChequeExtractionController}, which already handles the same
     * exception locally, only for its own endpoint and in a different body shape).
     * Global, so every upload endpoint gets the same clean 400, not just this one.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("Rejected an oversized upload: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", true,
                "message", "File is larger than 10 MB",
                "status", 400
        ));
    }

    /**
     * A file could not be read or written — the local disk copy failed, the
     * requested attachment's file went missing from disk, or the client aborted
     * the upload mid-stream. {@code VoucherController.upload}/{@code download}
     * (and {@code DeductionAttachmentController}'s equivalents) declare
     * {@code throws IOException} with nothing here to catch it, so it used to
     * surface as Spring's bare default error page instead of this app's
     * {@code {error,message,status}} shape.
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, Object>> handleIOException(IOException ex) {
        log.warn("I/O failure serving a request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", true,
                "message", "The file could not be read or written",
                "status", 400
        ));
    }

    /**
     * Bean-validation failure raised outside request-body binding: on a method
     * parameter where a controller is {@code @Validated}, or by Hibernate on
     * an annotated entity at flush (today, {@code Vendor}).
     *
     * <p>Returns the constraint messages but not {@code ex.getMessage()},
     * which prefixes each with its violation path. Those messages are written
     * for exactly this audience — "Vendor name (English) is required" — so
     * answering a bare "Validation failed" would throw away the only part the
     * caller can act on. The path is the part that must not go out: for method
     * validation it is the handler's own method and parameter name
     * ({@code someMethod.someParam}), which describes our code, not the
     * request.
     *
     * <p>De-duplicated and sorted because the violation set is unordered: two
     * identical bad requests must not produce two different strings.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        Set<ConstraintViolation<?>> violations = ex.getConstraintViolations();
        String messages = violations == null ? "" : violations.stream()
                .map(ConstraintViolation::getMessage)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.joining(", "));
        return clientError(messages.isBlank() ? "Validation failed" : "Validation failed: " + messages, ex);
    }

    /**
     * Shared body for the framework-raised caller errors above: the request
     * never reached application code because it did not parse, did not
     * convert, or was missing a value the signature requires.
     *
     * <p>Two things this exists to get right.
     *
     * <p>Status. Three of the four are RuntimeExceptions, so without
     * an explicit handler {@link #handleRuntime} claims them and answers 500.
     * That misleads clients that branch on status — a 500 invites a retry of a
     * request that can never succeed — and it buries genuine server faults in
     * whatever counts 5xx.
     *
     * <p>Body. The catch-all copies {@code ex.getMessage()} into the response,
     * and for these types the message is internal detail: Jackson quotes the
     * rejected payload fragment and names the target type, the mismatch
     * message names the required Java class and echoes the offending value,
     * and a violation message spells out the controller method it came from.
     * This advice serves unauthenticated endpoints — {@code /public/**} and
     * the Firebase token exchange, see
     * {@link com.datagami.rentaxis.security.PublicRateLimitFilter} — so the
     * detail goes to the log and only the shape of the mistake goes back.
     *
     * <p>WARN rather than ERROR, and the message rather than the stack: a
     * malformed request is ordinary traffic on a public surface. Logging it at
     * ERROR would recreate, in the alerting channel, exactly the noise the
     * status fix removes.
     */
    private ResponseEntity<Map<String, Object>> clientError(String message, Exception ex) {
        log.warn("Rejected as 400 ({}): {}", ex.getClass().getSimpleName(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", true,
                "message", message,
                "status", 400
        ));
    }

    /**
     * The catch-all. Its message is a CONSTANT, deliberately.
     *
     * <p>This used to copy {@code ex.getMessage()} into the response body,
     * which made every unhandled exception an information-disclosure channel.
     * The realistic sources are worse than they first look: Hibernate and JPA
     * exceptions carry SQL fragments plus table, column and constraint names;
     * Java's helpful NullPointerExceptions name our own classes, fields and
     * methods; and the Azure and Razorpay SDKs can surface endpoint URLs and
     * request ids. This advice is reachable unauthenticated — see the public
     * paths in {@code PublicRateLimitFilter} — so anything that reaches here is
     * assumed to be something an anonymous caller must not read.
     *
     * <p>Nothing is lost operationally: the full exception and its stack still
     * go to the log at ERROR, which is where a 500 belongs. Only the caller's
     * copy is redacted. Note the contrast with the client-error handlers above,
     * which log at WARN — a malformed request is ordinary traffic, an unhandled
     * exception is not.
     */
    /**
     * A foreign-key or unique violation is a conflict with data that already
     * exists, not an internal fault — 409, and name the constraint.
     *
     * <p>Without this these fell through to {@link #handleRuntime} as a bare
     * 500 "An internal error occurred", which is how account deletion failing on
     * {@code fk_pae_user} looked from the outside: no indication of which
     * relationship blocked it, or that the caller could do anything about it.
     * The constraint name is safe to return — it is schema shape, not data — and
     * it is the one detail that makes these diagnosable from a bug report.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        String constraint = null;
        Throwable cause = ex.getCause();
        if (cause instanceof org.hibernate.exception.ConstraintViolationException hce) {
            constraint = hce.getConstraintName();
        }
        log.warn("Data integrity violation{}", constraint != null ? " on " + constraint : "", ex);

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("error", true);
        body.put("status", 409);
        body.put("message", constraint != null
                ? "This action conflicts with existing related records (" + constraint + ")."
                : "This action conflicts with existing related records.");
        if (constraint != null) {
            body.put("constraint", constraint);
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Two requests wanted the same rows at once: a lock timeout, a deadlock Postgres
     * broke (40P01), or an optimistic version check (a VAT tax point's
     * {@code @Version}). Nothing was written; the same request a moment later
     * succeeds — so a 409 that says so, not a 500 (PR #348 re-review N3).
     */
    @ExceptionHandler({org.springframework.dao.ConcurrencyFailureException.class,
            jakarta.persistence.PessimisticLockException.class,
            jakarta.persistence.OptimisticLockException.class,
            jakarta.persistence.LockTimeoutException.class,
            org.hibernate.exception.LockAcquisitionException.class,
            org.hibernate.StaleStateException.class})
    public ResponseEntity<Map<String, Object>> handleConcurrency(Exception ex) {
        log.warn("Concurrent update refused: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", true,
                "message", "This record was being changed by another request at the same time. Please try again.",
                "status", 409
        ));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntime(RuntimeException ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", true,
                "message", "An internal error occurred",
                "status", 500
        ));
    }
}
