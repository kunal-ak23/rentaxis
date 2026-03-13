package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.StaffService;
import com.datagami.rentaxis.domain.entity.Staff;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/staff")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
public class StaffController {

    private final StaffService service;

    public StaffController(StaffService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<Staff>> getAllStaff() {
        return ResponseEntity.ok(service.getAllStaff());
    }

    @GetMapping("/by-property/{propertyId}")
    public ResponseEntity<List<Staff>> getByProperty(@PathVariable UUID propertyId) {
        return ResponseEntity.ok(service.getByProperty(propertyId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Staff> getStaffById(@PathVariable UUID id) {
        return ResponseEntity.ok(service.getStaffById(id));
    }

    @PostMapping
    public ResponseEntity<Staff> createStaff(@RequestBody Staff staff) {
        return ResponseEntity.ok(service.createStaff(staff));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Staff> updateStaff(@PathVariable UUID id, @RequestBody Staff staff) {
        return ResponseEntity.ok(service.updateStaff(id, staff));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteStaff(@PathVariable UUID id) {
        service.deleteStaff(id);
        return ResponseEntity.noContent().build();
    }
}
