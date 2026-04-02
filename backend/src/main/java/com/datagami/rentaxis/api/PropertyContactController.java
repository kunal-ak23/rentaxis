package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.PropertyContactDTO;
import com.datagami.rentaxis.core.service.PropertyContactService;
import com.datagami.rentaxis.domain.entity.PropertyContact;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/properties/{propertyId}/contacts")
public class PropertyContactController {

    private final PropertyContactService service;

    public PropertyContactController(PropertyContactService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<PropertyContact>> getContacts(@PathVariable UUID propertyId) {
        return ResponseEntity.ok(service.getContactsByPropertyId(propertyId));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<PropertyContact> createContact(
            @PathVariable UUID propertyId,
            @Valid @RequestBody PropertyContactDTO dto) {
        PropertyContact contact = new PropertyContact();
        contact.setPropertyId(propertyId);
        contact.setCategory(dto.getCategory());
        contact.setCustomLabel(dto.getCustomLabel());
        contact.setName(dto.getName());
        contact.setPhone(dto.getPhone());
        contact.setEmail(dto.getEmail());
        contact.setAddress(dto.getAddress());
        contact.setNotes(dto.getNotes());
        contact.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0);
        return ResponseEntity.ok(service.create(contact));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<PropertyContact> updateContact(
            @PathVariable UUID propertyId,
            @PathVariable UUID id,
            @Valid @RequestBody PropertyContactDTO dto) {
        PropertyContact contact = new PropertyContact();
        contact.setPropertyId(propertyId);
        contact.setCategory(dto.getCategory());
        contact.setCustomLabel(dto.getCustomLabel());
        contact.setName(dto.getName());
        contact.setPhone(dto.getPhone());
        contact.setEmail(dto.getEmail());
        contact.setAddress(dto.getAddress());
        contact.setNotes(dto.getNotes());
        contact.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0);
        return ResponseEntity.ok(service.update(id, contact));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
    public ResponseEntity<Void> deleteContact(@PathVariable UUID propertyId, @PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
