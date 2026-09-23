package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.BuildingRequest;
import com.datagami.rentaxis.core.service.BuildingService;
import com.datagami.rentaxis.domain.entity.Building;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/buildings")
public class BuildingController {

    private final BuildingService service;

    public BuildingController(BuildingService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<Building> createBuilding(@RequestBody BuildingRequest request) {
        return ResponseEntity.ok(service.createBuilding(request));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<List<Building>> getAllBuildings() {
        return ResponseEntity.ok(service.getAllBuildings());
    }

    @GetMapping("/property/{propertyId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<List<Building>> getBuildingsByProperty(@PathVariable UUID propertyId) {
        return ResponseEntity.ok(service.getBuildingsByProperty(propertyId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<Building> getBuildingById(@PathVariable UUID id) {
        return ResponseEntity.ok(service.getBuildingById(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<Void> deleteBuilding(@PathVariable UUID id) {
        service.deleteBuilding(id);
        return ResponseEntity.ok().build();
    }
}
