package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.UnitRequest;
import com.datagami.rentaxis.core.service.UnitService;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.entity.enums.UnitType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    public ResponseEntity<Unit> createUnit(@RequestBody UnitRequest request) {
        return ResponseEntity.ok(service.createUnit(request));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER', 'ACCOUNTANT')")
    public ResponseEntity<List<Unit>> getAllUnits() {
        return ResponseEntity.ok(service.getAllUnits());
    }

    /**
     * Scale P1-3: {@code GET /units} filtered and paged in the database. {@code floor} is the
     * unit-number prefix a floor's units share ("07" for 07-01, 07-02 ...).
     */
    @GetMapping("/paged")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER', 'ACCOUNTANT')")
    public ResponseEntity<org.springframework.data.domain.Page<Unit>> getUnitsPaged(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) java.util.UUID propertyId,
            @RequestParam(required = false) UnitStatus status,
            @RequestParam(required = false) String floor,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(service.searchPaged(q, propertyId, status, floor, page, size));
    }

    /** Scale P1-6: the async unit picker. */
    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER', 'ACCOUNTANT')")
    public ResponseEntity<List<com.datagami.rentaxis.api.dto.lookup.UnitOptionDTO>> searchUnits(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) java.util.UUID propertyId,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(service.search(q, propertyId, limit));
    }

    /** Scale P1-6: labels for a handful of unit ids (at most 200). */
    @GetMapping("/names")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER', 'ACCOUNTANT')")
    public ResponseEntity<List<com.datagami.rentaxis.api.dto.lookup.UnitOptionDTO>> unitNames(
            @RequestParam List<java.util.UUID> ids) {
        return ResponseEntity.ok(service.names(ids));
    }

    @GetMapping("/property/{propertyId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER', 'ACCOUNTANT')")
    public ResponseEntity<List<Unit>> getUnitsByProperty(@PathVariable UUID propertyId) {
        return ResponseEntity.ok(service.getUnitsByProperty(propertyId));
    }

    @PostMapping("/bulk")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<?> bulkUploadUnits(
            @RequestParam("file") MultipartFile file,
            @RequestParam("propertyId") UUID propertyId,
            @RequestParam(value = "buildingId", required = false) UUID buildingId) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", true,
                    "message", "CSV file is empty",
                    "status", 400
            ));
        }

        List<Unit> units = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            String line;
            boolean firstLine = true;
            int rowNum = 1; // header row
            while ((line = reader.readLine()) != null) {
                if (firstLine) { // Skip header
                    firstLine = false;
                    continue;
                }
                rowNum++;
                if (line.isBlank()) {
                    continue;
                }
                Unit u = parseRow(line.split(","), rowNum, errors);
                if (u != null) {
                    units.add(u);
                }
            }
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", true,
                    "message", "Failed to read CSV file: " + e.getMessage(),
                    "status", 400
            ));
        }

        if (!errors.isEmpty()) {
            // Malformed content is a client error: report it row-by-row
            // (mirroring POST /api/v1/properties/import) instead of a bodyless 500.
            return ResponseEntity.badRequest().body(Map.of(
                    "error", true,
                    "message", "CSV contains invalid rows",
                    "status", 400,
                    "errors", errors
            ));
        }

        return ResponseEntity.ok(service.bulkCreateUnits(propertyId, buildingId, units));
    }

    private static Unit parseRow(String[] parts, int rowNum, List<String> errors) {
        if (parts.length < 1 || parts[0].isBlank()) {
            errors.add("Row " + rowNum + ": UnitNumber is required");
            return null;
        }
        Unit u = new Unit();
        u.setUnitNumber(parts[0].trim());
        boolean valid = true;
        if (parts.length > 1 && !parts[1].isBlank()) {
            try {
                u.setType(UnitType.valueOf(parts[1].trim()));
            } catch (IllegalArgumentException e) {
                errors.add("Row " + rowNum + ": Invalid unit type '" + parts[1].trim() + "'");
                valid = false;
            }
        }
        if (parts.length > 2 && !parts[2].isBlank()) {
            try {
                u.setSizeSqft(new BigDecimal(parts[2].trim()));
            } catch (NumberFormatException e) {
                errors.add("Row " + rowNum + ": Invalid SizeSqft '" + parts[2].trim() + "'");
                valid = false;
            }
        }
        if (parts.length > 3 && !parts[3].isBlank()) {
            try {
                u.setExpectedRent(new BigDecimal(parts[3].trim()));
            } catch (NumberFormatException e) {
                errors.add("Row " + rowNum + ": Invalid ExpectedRent '" + parts[3].trim() + "'");
                valid = false;
            }
        }
        if (parts.length > 4 && !parts[4].isBlank()) {
            try {
                u.setStatus(UnitStatus.valueOf(parts[4].trim()));
            } catch (IllegalArgumentException e) {
                errors.add("Row " + rowNum + ": Invalid status '" + parts[4].trim() + "'");
                valid = false;
            }
        }
        return valid ? u : null;
    }
}
