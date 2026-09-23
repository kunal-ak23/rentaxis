package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.*;
import com.datagami.rentaxis.core.service.LeaseInteractionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/leases/{leaseId}/interactions")
@RequiredArgsConstructor
public class LeaseInteractionController {

    private final LeaseInteractionService service;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_TENANT_ADMIN','ROLE_PROPERTY_MANAGER')")
    public Page<InteractionDTO> list(@PathVariable UUID leaseId, Pageable pageable) {
        return service.list(leaseId, pageable);
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_TENANT_ADMIN','ROLE_PROPERTY_MANAGER')")
    public ResponseEntity<InteractionDTO> create(@PathVariable UUID leaseId,
                                                  @Valid @RequestBody CreateInteractionRequest req,
                                                  @AuthenticationPrincipal String userIdStr) {
        return ResponseEntity.status(201).body(service.create(leaseId, req, UUID.fromString(userIdStr)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_TENANT_ADMIN','ROLE_PROPERTY_MANAGER')")
    public InteractionDTO update(@PathVariable UUID leaseId, @PathVariable UUID id,
                                 @Valid @RequestBody UpdateInteractionRequest req) {
        return service.update(leaseId, id, req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_TENANT_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID leaseId, @PathVariable UUID id) {
        service.softDelete(leaseId, id);
        return ResponseEntity.noContent().build();
    }
}
