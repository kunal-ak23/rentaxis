package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.PromoterAccessRequest;
import com.datagami.rentaxis.api.dto.PromoterDTO;
import com.datagami.rentaxis.api.dto.PromoterInviteRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.PromoAdApprovalService;
import com.datagami.rentaxis.core.service.PromoterAdminService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The admin side of the promoter role (promoter plan, "Admin API"): approving and
 * rejecting promoters' ads, and Promotions → Promoters. Same audience as
 * {@link PromotionAdminController}, under the same base path.
 */
@RestController
@RequestMapping("/api/v1/promotions")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
public class PromotionApprovalController {

    public record RejectRequest(String reason) {
    }

    private final PromoAdApprovalService approvals;
    private final PromoterAdminService promoters;

    public PromotionApprovalController(PromoAdApprovalService approvals, PromoterAdminService promoters) {
        this.approvals = approvals;
        this.promoters = promoters;
    }

    /** {@code {count}}: ads waiting for a decision (the Ads tab badge). */
    @GetMapping("/ads/pending-count")
    public ResponseEntity<Map<String, Long>> pendingCount() {
        return ResponseEntity.ok(Map.of("count", approvals.pendingCount(requireTenant())));
    }

    /** No body. 204; a non-PENDING ad is 400, another organisation's 404. */
    @PostMapping("/ads/{id}/approve")
    public ResponseEntity<Void> approve(@PathVariable UUID id) {
        approvals.approve(requireTenant(), id, callerId());
        return ResponseEntity.noContent().build();
    }

    /** {@code {reason}} (required, at most 500). 204; a non-PENDING ad is 400. */
    @PostMapping("/ads/{id}/reject")
    public ResponseEntity<Void> reject(@PathVariable UUID id, @RequestBody(required = false) RejectRequest body) {
        approvals.reject(requireTenant(), id, callerId(), body == null ? null : body.reason());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/promoters")
    public ResponseEntity<List<PromoterDTO>> promoters() {
        return ResponseEntity.ok(promoters.list(requireTenant()));
    }

    @PostMapping("/promoters")
    public ResponseEntity<PromoterDTO> invite(@Valid @RequestBody PromoterInviteRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(promoters.invite(requireTenant(), req));
    }

    @PutMapping("/promoters/{userId}")
    public ResponseEntity<PromoterDTO> updateAccess(@PathVariable UUID userId,
                                                    @Valid @RequestBody PromoterAccessRequest req) {
        return ResponseEntity.ok(promoters.updateAccess(requireTenant(), userId, req));
    }

    /** As {@code PromotionAdminController.requireTenant}: a SUPER_ADMIN must name the organisation. */
    private static UUID requireTenant() {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            throw new BusinessRuleViolationException("X-Tenant-Id is required to manage promotions");
        }
        return tenantId;
    }

    private static UUID callerId() {
        try {
            return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
