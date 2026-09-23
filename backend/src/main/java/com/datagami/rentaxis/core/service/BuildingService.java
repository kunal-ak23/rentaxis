package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.BuildingRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.Building;
import com.datagami.rentaxis.domain.repository.BuildingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class BuildingService {

    private final BuildingRepository repository;
    private final TenantReferences refs;
    private final com.datagami.rentaxis.core.security.PropertyScope propertyScope;

    public BuildingService(BuildingRepository repository, TenantReferences refs,
                           com.datagami.rentaxis.core.security.PropertyScope propertyScope) {
        this.propertyScope = propertyScope;
        this.repository = repository;
        this.refs = refs;
    }

    /**
     * Creates a building from a request body. The property arrives as an id and is
     * resolved here, inside the transaction and the caller's tenant: another
     * tenant's or a missing property is a 404 and nothing is saved.
     */
    @Transactional
    public Building createBuilding(BuildingRequest r) {
        if (r.nameEn() == null || r.nameEn().isBlank()) {
            throw new BusinessRuleViolationException("Building name is required");
        }
        if (r.property() == null) {
            throw new BusinessRuleViolationException("property.id is required");
        }
        Building b = new Building();
        b.setProperty(refs.propertyOrNull(r.property()));
        b.setNameEn(r.nameEn().trim());
        b.setNameAr(r.nameAr());
        b.setFloors(r.floors());
        return repository.save(b);
    }

    /**
     * Saves a building built in code. Internal and test use only: its property must
     * already be a managed row of the caller's tenant. Request bodies go through
     * {@link #createBuilding(BuildingRequest)}.
     */
    @Transactional
    public Building createBuilding(Building building) {
        return repository.save(building);
    }

    @Transactional(readOnly = true)
    public List<Building> getBuildingsByProperty(UUID propertyId) {
        // Property managers: their own buildings only (audit D-F7).
        if (!propertyScope.canAccessProperty(propertyId)) {
            return List.of();
        }
        return repository.findByPropertyId(propertyId);
    }

    @Transactional(readOnly = true)
    public List<Building> getAllBuildings() {
        return propertyScope.filter(repository.findAll(),
                b -> b.getProperty() != null ? b.getProperty().getId() : null);
    }

    @Transactional(readOnly = true)
    public Building getBuildingById(UUID id) {
        Building building = repository.findById(id).orElseThrow(() -> new NotFoundException("Building not found"));
        propertyScope.requireCanAccessProperty(
                building.getProperty() != null ? building.getProperty().getId() : null, "Building not found");
        return building;
    }

    @Transactional
    public void deleteBuilding(UUID id) {
        repository.deleteById(id);
    }
}
