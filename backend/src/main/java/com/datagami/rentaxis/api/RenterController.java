package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.CreateRenterDTO;
import com.datagami.rentaxis.api.dto.RenterDTO;
import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.RenterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/renters")
@RequiredArgsConstructor
public class RenterController {

    private final RenterService renterService;
    private final LeaseService leaseService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER', 'ACCOUNTANT')")
    public ResponseEntity<List<RenterDTO>> getAllRenters() {
        return ResponseEntity.ok(renterService.getAllRenters());
    }

    /** Scale P1-3: searched (name, phone, email) and paged in the database. */
    @GetMapping("/paged")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER', 'ACCOUNTANT')")
    public ResponseEntity<org.springframework.data.domain.Page<RenterDTO>> getRentersPaged(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(renterService.searchPaged(q, page, size));
    }

    /** Scale P1-6: the async renter picker. */
    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER', 'ACCOUNTANT')")
    public ResponseEntity<List<com.datagami.rentaxis.api.dto.lookup.RenterOptionDTO>> searchRenters(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(renterService.search(q, limit));
    }

    /** Scale P1-6: labels for a handful of renter ids (at most 200). */
    @GetMapping("/names")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER', 'ACCOUNTANT')")
    public ResponseEntity<List<com.datagami.rentaxis.api.dto.lookup.RenterOptionDTO>> renterNames(
            @RequestParam List<UUID> ids) {
        return ResponseEntity.ok(renterService.names(ids));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER', 'ACCOUNTANT')")
    public ResponseEntity<RenterDTO> getRenterById(@PathVariable UUID id) {
        return ResponseEntity.ok(renterService.getRenterById(id));
    }

    /** The renter's contracts, newest first (#8, renter detail page). */
    @GetMapping("/{id}/leases")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER', 'ACCOUNTANT')")
    public ResponseEntity<List<LeaseDTO>> getRenterLeases(@PathVariable UUID id) {
        return ResponseEntity.ok(leaseService.getLeasesForRenter(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<RenterDTO> createRenter(@Valid @RequestBody CreateRenterDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(renterService.createRenter(dto));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<RenterDTO> updateRenter(@PathVariable UUID id, @Valid @RequestBody CreateRenterDTO dto) {
        return ResponseEntity.ok(renterService.updateRenter(id, dto));
    }
}
