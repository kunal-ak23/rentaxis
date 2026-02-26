package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class UnitService {

    private final UnitRepository repository;

    public UnitService(UnitRepository repository) {
        this.repository = repository;
    }

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

    @Transactional
    public List<Unit> bulkCreateUnits(UUID propertyId, UUID buildingId, List<Unit> units) {
        for (Unit unit : units) {
            // These relationships should ideally be resolved correctly
            // via real Property/Building reference loading, but we set IDs for hibernate
            // proxy if doing it optimally
            if (unit.getProperty() == null) {
                com.datagami.rentaxis.domain.entity.Property p = new com.datagami.rentaxis.domain.entity.Property();
                p.setId(propertyId);
                unit.setProperty(p);
            }
            if (buildingId != null && unit.getBuilding() == null) {
                com.datagami.rentaxis.domain.entity.Building b = new com.datagami.rentaxis.domain.entity.Building();
                b.setId(buildingId);
                unit.setBuilding(b);
            }
        }
        return repository.saveAll(units);
    }
}
