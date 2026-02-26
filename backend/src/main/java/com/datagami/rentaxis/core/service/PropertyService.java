package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PropertyStatsDTO;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PropertyService {

    private final PropertyRepository repository;
    private final UnitRepository unitRepository;

    public PropertyService(PropertyRepository repository, UnitRepository unitRepository) {
        this.repository = repository;
        this.unitRepository = unitRepository;
    }

    @Transactional
    public Property createProperty(Property property) {
        return repository.save(property);
    }

    @Transactional(readOnly = true)
    public List<Property> getAllProperties() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public Property getPropertyById(UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("Property not found"));
    }

    @Transactional(readOnly = true)
    public List<PropertyStatsDTO> getAllPropertiesWithStats() {
        List<Property> properties = repository.findAll();
        return properties.stream().map(this::calculateStats).collect(Collectors.toList());
    }

    private PropertyStatsDTO calculateStats(Property property) {
        List<Unit> units = unitRepository.findByPropertyId(property.getId());
        PropertyStatsDTO dto = new PropertyStatsDTO();
        dto.setProperty(property);
        dto.setPropertyCount(units.size());
        dto.setVacancies(units.stream().filter(u -> u.getStatus() == UnitStatus.VACANT).count());
        dto.setRevenueAtCapacity(units.stream().map(Unit::getExpectedRent).reduce(BigDecimal.ZERO, BigDecimal::add));
        dto.setActualRevenue(units.stream().map(Unit::getActualRent).reduce(BigDecimal.ZERO, BigDecimal::add));
        return dto;
    }
}
