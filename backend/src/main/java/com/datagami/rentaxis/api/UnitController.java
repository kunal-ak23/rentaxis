package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.UnitService;
import com.datagami.rentaxis.domain.entity.Unit;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
    public ResponseEntity<Unit> createUnit(@RequestBody Unit unit) {
        return ResponseEntity.ok(service.createUnit(unit));
    }

    @GetMapping
    public ResponseEntity<List<Unit>> getAllUnits() {
        return ResponseEntity.ok(service.getAllUnits());
    }

    @GetMapping("/property/{propertyId}")
    public ResponseEntity<List<Unit>> getUnitsByProperty(@PathVariable UUID propertyId) {
        return ResponseEntity.ok(service.getUnitsByProperty(propertyId));
    }
}
