package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.UnitRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.domain.entity.Building;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class UnitService {

    private final UnitRepository repository;
    private final TenantReferences refs;

    public UnitService(UnitRepository repository, TenantReferences refs) {
        this.repository = repository;
        this.refs = refs;
    }

    /**
     * Creates a unit from a request body. The property and building arrive as ids
     * and are resolved here, inside the transaction and the caller's tenant, before
     * anything is saved: another tenant's (or a missing) property or building is a
     * 404, and a building that is not on the property is a 400.
     */
    @Transactional
    public Unit createUnit(UnitRequest r) {
        if (r.unitNumber() == null || r.unitNumber().isBlank()) {
            throw new BusinessRuleViolationException("Unit number is required");
        }
        if (r.property() == null) {
            throw new BusinessRuleViolationException("property.id is required");
        }
        Property property = refs.propertyOrNull(r.property());
        Building building = r.building() == null ? null : refs.building(r.building().id(), property);

        Unit u = new Unit();
        u.setProperty(property);
        u.setBuilding(building);
        u.setUnitNumber(r.unitNumber().trim());
        if (r.type() != null) u.setType(r.type());
        u.setSizeSqft(r.sizeSqft());
        if (r.status() != null) u.setStatus(r.status());
        if (r.expectedRent() != null) u.setExpectedRent(r.expectedRent());
        if (r.actualRent() != null) u.setActualRent(r.actualRent());
        u.setCurrentTenantName(r.currentTenantName());
        return repository.save(u);
    }

    /**
     * Saves a unit built in code. Internal and test use only: its property and
     * building must already be managed rows of the caller's tenant. Request bodies
     * go through {@link #createUnit(UnitRequest)}.
     */
    @Transactional
    public Unit createUnit(Unit unit) {
        return repository.save(unit);
    }

    @Transactional(readOnly = true)
    public List<Unit> getUnitsByProperty(UUID propertyId) {
        return repository.findByPropertyId(propertyId);
    }

    @Transactional(readOnly = true)
    public List<Unit> getAllUnits() {
        return repository.findAll();
    }

    /**
     * The CSV import. {@code propertyId} and {@code buildingId} are request
     * parameters, so they are resolved in the caller's tenant exactly as
     * {@link #createUnit(UnitRequest)} resolves its body: they used to become
     * id-only stubs that Hibernate wrote as foreign keys unchecked. Every row
     * takes the resolved property and building; nothing a row carries overrides
     * them.
     */
    @Transactional
    public List<Unit> bulkCreateUnits(UUID propertyId, UUID buildingId, List<Unit> units) {
        Property property = refs.property(propertyId);
        Building building = buildingId == null ? null : refs.building(buildingId, property);
        for (Unit unit : units) {
            unit.setProperty(property);
            unit.setBuilding(building);
        }
        return repository.saveAll(units);
    }
}
