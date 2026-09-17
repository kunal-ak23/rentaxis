package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.BulkPropertyImportResultDTO;
import com.datagami.rentaxis.api.dto.CreatePropertyDTO;
import com.datagami.rentaxis.api.dto.PropertyStatsDTO;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.domain.entity.Property;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/properties")
public class PropertyController {

    private final PropertyService service;

    public PropertyController(PropertyService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<Property> createProperty(@Valid @RequestBody CreatePropertyDTO dto) {
        Property property = new Property();
        property.setNameEn(dto.getNameEn());
        property.setNameAr(dto.getNameAr());
        property.setType(dto.getType());
        property.setEmirate(dto.getEmirate());
        property.setAddress(dto.getAddress());
        property.setMakaniNumber(dto.getMakaniNumber());
        if (dto.getFixedExpenses() != null) {
            property.setFixedExpenses(dto.getFixedExpenses());
        }
        return ResponseEntity.ok(service.createProperty(property));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER', 'ACCOUNTANT')")
    public ResponseEntity<List<PropertyStatsDTO>> getAllProperties() {
        return ResponseEntity.ok(service.getAllPropertiesWithStats());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER', 'ACCOUNTANT')")
    public ResponseEntity<Property> getPropertyById(@PathVariable UUID id) {
        return ResponseEntity.ok(service.getPropertyById(id));
    }

    @GetMapping("/{id}/managers")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<List<com.datagami.rentaxis.domain.entity.User>> getPropertyManagers(@PathVariable UUID id) {
        return ResponseEntity.ok(service.getPropertyManagers(id));
    }

    @PostMapping("/import")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<BulkPropertyImportResultDTO> importProperty(
            @RequestParam("file") MultipartFile file,
            @RequestParam String nameEn,
            @RequestParam(required = false) String nameAr,
            @RequestParam String emirate,
            @RequestParam(required = false) String address,
            @RequestParam(required = false, defaultValue = "RESIDENTIAL") String type,
            @RequestParam(required = false) String makaniNumber) {

        BulkPropertyImportResultDTO result = service.importPropertyWithUnits(
                file, nameEn, nameAr, emirate, address, type, makaniNumber);

        if (!result.getErrors().isEmpty()) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/import/template")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<byte[]> downloadImportTemplate() {
        String csv = "BuildingName,UnitNumber,UnitType,SizeSqft,ExpectedRent,Status\n"
                + "Tower A,101,BHK1,850,60000,VACANT\n"
                + "Tower A,102,BHK2,1200,85000,VACANT\n"
                + ",V01,BHK3,4000,300000,VACANT\n";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"property-import-template.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv.getBytes());
    }
}
