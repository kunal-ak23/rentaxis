package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.PropertyScope;
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
    private final TenantReferences refs;
    private final PropertyScope propertyScope;

    public PropertyContactService(PropertyContactRepository repository, TenantReferences refs,
                                  PropertyScope propertyScope) {
        this.repository = repository;
        this.refs = refs;
        this.propertyScope = propertyScope;
    }

    /**
     * A property manager reads the contacts of their own buildings only (audit
     * F9 / D-F7); another building's list is empty.
     */
    @Transactional(readOnly = true)
    public List<PropertyContact> getContactsByPropertyId(UUID propertyId) {
        if (!propertyScope.canAccessProperty(propertyId)) {
            return List.of();
        }
        return repository.findByPropertyIdOrderBySortOrderAscCreatedAtAsc(propertyId);
    }

    /**
     * The path's property must be in the caller's tenant (it used to be saved
     * unchecked, pointing at any UUID) and, for a property manager, assigned.
     */
    @Transactional
    public PropertyContact create(PropertyContact contact) {
        refs.property(contact.getPropertyId());
        propertyScope.requireCanAccessProperty(contact.getPropertyId());
        return repository.save(contact);
    }

    @Transactional
    public PropertyContact update(UUID propertyId, UUID id, PropertyContact updated) {
        PropertyContact existing = scoped(propertyId, id);
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
    public void delete(UUID propertyId, UUID id) {
        repository.delete(scoped(propertyId, id));
    }

    /**
     * The contact named by the path, on the property named by the path, in the
     * caller's tenant and scope. The path's property used to be ignored on PUT and
     * DELETE, and a manager could edit any building's contacts. 404 otherwise.
     */
    private PropertyContact scoped(UUID propertyId, UUID id) {
        PropertyContact existing = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Contact not found"));
        UUID currentTenantId = TenantContextHolder.getTenantId();
        if ((currentTenantId != null && !currentTenantId.equals(existing.getTenantId()))
                || !existing.getPropertyId().equals(propertyId)) {
            throw new NotFoundException("Contact not found");
        }
        propertyScope.requireCanAccessProperty(existing.getPropertyId(), "Contact not found");
        return existing;
    }
}
