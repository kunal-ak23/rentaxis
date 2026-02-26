package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.PropertyStatsDTO;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.domain.entity.Property;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
    public ResponseEntity<Property> createProperty(@RequestBody Property property) {
        return ResponseEntity.ok(service.createProperty(property));
    }

    @GetMapping
    public ResponseEntity<List<PropertyStatsDTO>> getAllProperties() {
        return ResponseEntity.ok(service.getAllPropertiesWithStats());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Property> getPropertyById(@PathVariable UUID id) {
        return ResponseEntity.ok(service.getPropertyById(id));
    }
}
