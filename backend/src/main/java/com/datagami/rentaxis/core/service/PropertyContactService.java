package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.PropertyContact;
import com.datagami.rentaxis.domain.repository.PropertyContactRepository;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.UUID;

@Service
public class PropertyContactService {

    private final PropertyContactRepository repository;

    public PropertyContactService(PropertyContactRepository repository) {
        this.repository = repository;
    }

    public List<PropertyContact> getContactsByPropertyId(UUID propertyId) {
        return repository.findByPropertyIdOrderBySortOrderAscCreatedAtAsc(propertyId);
    }

    public PropertyContact create(PropertyContact contact) {
        return repository.save(contact);
    }

    public PropertyContact update(UUID id, PropertyContact updated) {
        PropertyContact existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Contact not found"));
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

    public void delete(UUID id) {
        repository.deleteById(id);
    }
}
