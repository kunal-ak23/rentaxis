package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.util.Loaded;
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
    private final com.datagami.rentaxis.core.security.PropertyScope propertyScope;
    private final com.datagami.rentaxis.domain.repository.LeaseRepository leaseRepository;

    public UnitService(UnitRepository repository, TenantReferences refs,
                       com.datagami.rentaxis.core.security.PropertyScope propertyScope,
                       com.datagami.rentaxis.domain.repository.LeaseRepository leaseRepository) {
        this.propertyScope = propertyScope;
        this.leaseRepository = leaseRepository;
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
        return Loaded.with(repository.save(u), Unit::getProperty, Unit::getBuilding);
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
        // A property manager lists units of their own buildings only (audit D-F7):
        // another building's list, with its current tenants' names, is empty.
        if (!propertyScope.canAccessProperty(propertyId)) {
            return List.of();
        }
        return withOccupancy(repository.findByPropertyId(propertyId));
    }

    @Transactional(readOnly = true)
    public List<Unit> getAllUnits() {
        return withOccupancy(propertyScope.filter(repository.findAll(),
                u -> u.getProperty() != null ? u.getProperty().getId() : null));
    }

    /**
     * F14-01: fills each unit's date-based occupancy from its posted leases, in one
     * query. A lease posted today for next month reserves the unit; it occupies it
     * from its start date.
     */
    List<Unit> withOccupancy(List<Unit> units) {
        if (units.isEmpty()) return units;
        java.time.LocalDate today = java.time.LocalDate.now();
        java.util.Map<UUID, List<com.datagami.rentaxis.domain.entity.Lease>> byUnit = new java.util.HashMap<>();
        leaseRepository.currentOrUpcomingOnUnits(units.stream().map(Unit::getId).toList(), today)
                .forEach(l -> byUnit.computeIfAbsent(l.getUnit().getId(), k -> new java.util.ArrayList<>()).add(l));
        for (Unit u : units) {
            List<com.datagami.rentaxis.domain.entity.Lease> leases = byUnit.getOrDefault(u.getId(), List.of());
            boolean covered = leases.stream().anyMatch(l -> l.getStartDate() == null || !l.getStartDate().isAfter(today));
            com.datagami.rentaxis.domain.entity.Lease next = leases.stream()
                    .filter(l -> l.getStartDate() != null && l.getStartDate().isAfter(today)
                            && l.getStatus() != com.datagami.rentaxis.domain.entity.enums.LeaseStatus.RENEWED)
                    .min(java.util.Comparator.comparing(com.datagami.rentaxis.domain.entity.Lease::getStartDate))
                    .orElse(null);
            u.setOccupancy(occupancyOf(u.getStatus(), covered, next != null));
            u.setNextLeaseStart(next != null ? next.getStartDate() : null);
            u.setNextTenantName(next != null && next.getRenter() != null ? next.getRenter().getNameEn() : null);
        }
        // Serialised with their property and building after this transaction ends (OSIV off).
        return Loaded.all(units, Unit::getProperty, Unit::getBuilding);
    }

    static String occupancyOf(com.datagami.rentaxis.domain.entity.enums.UnitStatus status, boolean covered,
                              boolean upcoming) {
        if (covered) return "OCCUPIED";
        if (upcoming) return "RESERVED";
        return status == com.datagami.rentaxis.domain.entity.enums.UnitStatus.MAINTENANCE ? "MAINTENANCE" : "VACANT";
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
