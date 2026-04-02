package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.PropertyContact;
import com.datagami.rentaxis.domain.repository.PropertyContactRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Service
public class PropertyContactService {

    private final PropertyContactRepository repository;

    public PropertyContactService(PropertyContactRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<PropertyContact> getContactsByPropertyId(UUID propertyId) {
        return repository.findByPropertyIdOrderBySortOrderAscCreatedAtAsc(propertyId);
    }

    @Transactional
    public PropertyContact create(PropertyContact contact) {
        return repository.save(contact);
    }

    @Transactional
    public PropertyContact update(UUID id, PropertyContact updated) {
        PropertyContact existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Contact not found"));
        verifyTenantOwnership(existing);
        existing.setCategory(updated.getCategory());
        existing.setCustomLabel(updated.getCustomLabel());
        existing.setName(updated.getName());
        existing.setPhone(updated.getPhone());
        existing.setEmail(updated.getEmail());
        existing.setAddress(updated.getAddress());
        existing.setNotes(updated.getNotes());
        existing.setSortOrder(updated.getSortOrder());
        return repository.save(existing);
    }

    @Transactional
    public void delete(UUID id) {
        PropertyContact existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Contact not found"));
        verifyTenantOwnership(existing);
        repository.delete(existing);
    }

    private void verifyTenantOwnership(PropertyContact contact) {
        UUID currentTenantId = TenantContextHolder.getTenantId();
        if (currentTenantId != null && !currentTenantId.equals(contact.getTenantId())) {
            throw new RuntimeException("Access denied: contact belongs to a different tenant");
        }
    }
}
