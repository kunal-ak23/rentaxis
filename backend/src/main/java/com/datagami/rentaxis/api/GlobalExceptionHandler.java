package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.exception.AccessDeniedException;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.api.exception.SlotConflictException;
import com.datagami.rentaxis.core.service.BulkAttachValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Map;
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
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", true,
                "message", ex.getMessage(),
                "status", 400
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
    @ExceptionHandler(org.springframework.security.authentication.BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleBadCredentials(
            org.springframework.security.authentication.BadCredentialsException ex) {
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
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(
            org.springframework.web.server.ResponseStatusException ex) {
        String reason = ex.getReason() != null ? ex.getReason() : "Request failed";
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                "error", true,
                "message", reason,
                "status", ex.getStatusCode().value()
        ));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntime(RuntimeException ex) {
        log.error("Unhandled exception", ex);
        String message = ex.getMessage() != null ? ex.getMessage() : "An internal error occurred";
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", true,
                "message", message,
                "status", 500
        ));
    }
}
