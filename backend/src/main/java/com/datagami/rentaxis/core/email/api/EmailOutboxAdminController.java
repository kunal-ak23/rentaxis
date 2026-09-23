package com.datagami.rentaxis.core.email.api;

import com.datagami.rentaxis.core.email.outbox.EmailOutbox;
import com.datagami.rentaxis.core.email.outbox.EmailOutboxRepository;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/email/outbox")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
public class EmailOutboxAdminController {

    private final EmailOutboxRepository repo;

    /**
     * What an admin needs to see about a queued email, and nothing that works as
     * a credential. The rendered body is left out on purpose (audit A-F9): a
     * USER_INVITED email carries the live set-password link, so returning
     * {@code bodyHtml} let a tenant admin redeem any pending invite in the tenant
     * — renters, managers, co-admins — before the invitee did, with no trail.
     * Marketing bodies likewise carry unsubscribe tokens.
     */
    public record OutboxRow(UUID id, UUID tenantId, String eventType, String eventCategory,
                            UUID recipientUserId, String recipientEmail, String recipientLocale,
                            String subject, EmailOutbox.Status status, Instant scheduledAt,
                            int attempts, int maxAttempts, Instant lastAttemptAt, String lastError,
                            String azureDeliveryStatus, String referenceType, UUID referenceId,
                            Instant createdAt, Instant updatedAt) {
        static OutboxRow of(EmailOutbox e) {
            return new OutboxRow(e.getId(), e.getTenantId(), e.getEventType(), e.getEventCategory(),
                    e.getRecipientUserId(), e.getRecipientEmail(), e.getRecipientLocale(),
                    e.getSubject(), e.getStatus(), e.getScheduledAt(),
                    e.getAttempts(), e.getMaxAttempts(), e.getLastAttemptAt(), e.getLastError(),
                    e.getAzureDeliveryStatus(), e.getReferenceType(), e.getReferenceId(),
                    e.getCreatedAt(), e.getUpdatedAt());
        }
    }

    @GetMapping
    public List<OutboxRow> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String eventType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<EmailOutbox> result = isSuperAdmin()
                ? repo.findAll(pageable)
                : repo.findByTenantId(currentTenantId(), pageable);
        return result.getContent().stream().map(OutboxRow::of).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<OutboxRow> get(@PathVariable UUID id) {
        return scopedFind(id).map(OutboxRow::of).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<Void> retry(@PathVariable UUID id) {
        return scopedFind(id).map(row -> {
            row.setStatus(EmailOutbox.Status.PENDING);
            row.setScheduledAt(Instant.now());
            row.setLastError(null);
            repo.save(row);
            return ResponseEntity.noContent().<Void>build();
        }).orElse(ResponseEntity.notFound().build());
    }

    // --- helpers ---

    private boolean isSuperAdmin() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities()
                .stream().anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
    }

    private UUID currentTenantId() {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            throw new TenantIdMissingException();
        }
        return tenantId;
    }

    private Optional<EmailOutbox> scopedFind(UUID id) {
        if (isSuperAdmin()) {
            return repo.findById(id);
        }
        return repo.findByIdAndTenantId(id, currentTenantId());
    }

    /** Thrown when a TENANT_ADMIN request arrives without a resolvable tenant — surfaces as 403. */
    @org.springframework.web.bind.annotation.ResponseStatus(
            value = org.springframework.http.HttpStatus.FORBIDDEN,
            reason = "Tenant context unavailable")
    static class TenantIdMissingException extends RuntimeException {
        TenantIdMissingException() { super("Tenant context unavailable"); }
    }
}
