package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.CreateLeaseDTO;
import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.LeaseEventDTO;
import com.datagami.rentaxis.core.service.LeaseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/leases")
@RequiredArgsConstructor
public class LeaseController {

    private final LeaseService leaseService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<List<LeaseDTO>> getAllLeases() {
        return ResponseEntity.ok(leaseService.getAllLeases());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<LeaseDTO> getLeaseById(@PathVariable UUID id) {
        return ResponseEntity.ok(leaseService.getLeaseById(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<LeaseDTO> createDraftLease(@Valid @RequestBody CreateLeaseDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(leaseService.createDraftLease(dto));
    }

    @PutMapping("/{id}/activate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<LeaseDTO> activateLease(@PathVariable UUID id) {
        return ResponseEntity.ok(leaseService.activateLease(id));
    }

    @PostMapping("/{id}/terminate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<LeaseDTO> terminateLease(
            @PathVariable UUID id,
            @RequestParam(required = false) String notes) {
        return ResponseEntity.ok(leaseService.terminateLease(id, notes));
    }

    @GetMapping("/{id}/events")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<List<LeaseEventDTO>> getLeaseEvents(@PathVariable UUID id) {
        return ResponseEntity.ok(leaseService.getLeaseEvents(id));
    }
}
