package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.WaivePenaltyDTO;
import com.datagami.rentaxis.core.service.PenaltyService;
import com.datagami.rentaxis.domain.entity.PaymentPenalty;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class PenaltyController {

    private final PenaltyService penaltyService;

    public PenaltyController(PenaltyService penaltyService) {
        this.penaltyService = penaltyService;
    }

    @GetMapping("/leases/{leaseId}/penalties")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<List<PaymentPenalty>> getPenalties(@PathVariable UUID leaseId) {
        return ResponseEntity.ok(penaltyService.getPenaltiesByLeaseId(leaseId));
    }

    @PutMapping("/penalties/{id}/waive")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<PaymentPenalty> waivePenalty(
            @PathVariable UUID id,
            @Valid @RequestBody WaivePenaltyDTO dto) {
        UUID currentUserId = UUID.fromString(
                SecurityContextHolder.getContext().getAuthentication().getName());
        return ResponseEntity.ok(penaltyService.waivePenalty(id, dto.getReason(), currentUserId));
    }

    @PostMapping("/leases/{leaseId}/penalties/recalculate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<List<PaymentPenalty>> recalculatePenalties(@PathVariable UUID leaseId) {
        return ResponseEntity.ok(penaltyService.recalculateForLease(leaseId));
    }
}
