package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.UnitService;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.entity.enums.UnitType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/units")
public class UnitController {

    private final UnitService service;

    public UnitController(UnitService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<Unit> createUnit(@RequestBody Unit unit) {
        return ResponseEntity.ok(service.createUnit(unit));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<List<Unit>> getAllUnits() {
        return ResponseEntity.ok(service.getAllUnits());
    }

    @GetMapping("/property/{propertyId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<List<Unit>> getUnitsByProperty(@PathVariable UUID propertyId) {
        return ResponseEntity.ok(service.getUnitsByProperty(propertyId));
    }

    @PostMapping("/bulk")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<List<Unit>> bulkUploadUnits(
            @RequestParam("file") MultipartFile file,
            @RequestParam("propertyId") UUID propertyId,
            @RequestParam(value = "buildingId", required = false) UUID buildingId) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        List<Unit> units = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (firstLine) { // Skip header
                    firstLine = false;
                    continue;
                }
                String[] parts = line.split(",");
                if (parts.length >= 1) {
                    Unit u = new Unit();
                    u.setUnitNumber(parts[0].trim());
                    if (parts.length > 1 && !parts[1].isBlank())
                        u.setType(UnitType.valueOf(parts[1].trim()));
                    if (parts.length > 2 && !parts[2].isBlank())
                        u.setSizeSqft(new BigDecimal(parts[2].trim()));
                    if (parts.length > 3 && !parts[3].isBlank())
                        u.setExpectedRent(new BigDecimal(parts[3].trim()));
                    if (parts.length > 4 && !parts[4].isBlank())
                        u.setStatus(UnitStatus.valueOf(parts[4].trim()));
                    units.add(u);
                }
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }

        return ResponseEntity.ok(service.bulkCreateUnits(propertyId, buildingId, units));
    }
}
