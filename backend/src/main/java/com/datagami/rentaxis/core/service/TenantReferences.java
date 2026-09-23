package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.IdRef;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Building;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.repository.BuildingRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Resolves ids that arrive in a request body to managed rows of the caller's
 * tenant: the same pattern as {@code BankAccountService.resolveProperty}.
 *
 * <p>Call it inside the service's transaction. The Hibernate tenant filter is
 * only enabled there, and the explicit tenant comparison is the belt and braces
 * that still holds when it is not. A foreign id and a missing id get the same
 * 404, so the answer does not confirm that another tenant's row exists.</p>
 */
@Component
public class TenantReferences {

    private final PropertyRepository propertyRepository;
    private final BuildingRepository buildingRepository;

    public TenantReferences(PropertyRepository propertyRepository, BuildingRepository buildingRepository) {
        this.propertyRepository = propertyRepository;
        this.buildingRepository = buildingRepository;
    }

    /** The property named by {@code ref}, or null when the body names none. */
    public Property propertyOrNull(IdRef ref) {
        if (ref == null) return null;
        return property(requireId(ref, "property"));
    }

    public Property property(UUID id) {
        if (id == null) throw new BusinessRuleViolationException("property.id is required");
        return propertyRepository.findById(id)
                .filter(p -> inCurrentTenant(p.getTenantId()))
                .orElseThrow(() -> new NotFoundException("Property not found"));
    }

    /**
     * The building named by {@code id}, which must stand on {@code property}. A
     * building of this tenant on a different property is a 400, not a 404: the
     * caller can see both, the pairing is simply wrong.
     */
    public Building building(UUID id, Property property) {
        if (id == null) throw new BusinessRuleViolationException("building.id is required");
        Building b = buildingRepository.findById(id)
                .filter(x -> inCurrentTenant(x.getTenantId()))
                .orElseThrow(() -> new NotFoundException("Building not found"));
        UUID onProperty = b.getProperty() == null ? null : b.getProperty().getId();
        if (property == null || !property.getId().equals(onProperty)) {
            throw new BusinessRuleViolationException("That building is not part of the selected property");
        }
        return b;
    }

    static UUID requireId(IdRef ref, String field) {
        if (ref.id() == null) throw new BusinessRuleViolationException(field + ".id is required");
        return ref.id();
    }

    /** Belt and braces over the Hibernate tenant filter: an id from a request body never crosses tenants. */
    public static boolean inCurrentTenant(UUID rowTenantId) {
        UUID current = TenantContextHolder.getTenantId();
        return current != null && current.equals(rowTenantId);
    }
}
