package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.CreateRenterDTO;
import com.datagami.rentaxis.api.dto.RenterDTO;
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

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER', 'ACCOUNTANT')")
    public ResponseEntity<List<RenterDTO>> getAllRenters() {
        return ResponseEntity.ok(renterService.getAllRenters());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER', 'ACCOUNTANT')")
    public ResponseEntity<RenterDTO> getRenterById(@PathVariable UUID id) {
        return ResponseEntity.ok(renterService.getRenterById(id));
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
