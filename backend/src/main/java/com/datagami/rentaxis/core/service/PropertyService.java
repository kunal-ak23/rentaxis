package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PropertyStatsDTO;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
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
    private final UserPropertyAssignmentRepository propertyAssignmentRepository;

    public PropertyService(PropertyRepository repository, UnitRepository unitRepository,
            UserPropertyAssignmentRepository propertyAssignmentRepository) {
        this.repository = repository;
        this.unitRepository = unitRepository;
        this.propertyAssignmentRepository = propertyAssignmentRepository;
    }

    @Transactional
    public Property createProperty(Property property) {
        return repository.save(property);
    }

    @Transactional(readOnly = true)
    public List<Property> getAllProperties() {
        List<Property> all = repository.findAll();
        return filterByRole(all);
    }

    @Transactional(readOnly = true)
    public Property getPropertyById(UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("Property not found"));
    }

    @Transactional(readOnly = true)
    public List<PropertyStatsDTO> getAllPropertiesWithStats() {
        List<Property> properties = repository.findAll();
        properties = filterByRole(properties);
        return properties.stream().map(this::calculateStats).collect(Collectors.toList());
    }

    /**
     * Filters properties based on the current user's role.
     * PROPERTY_MANAGER only sees assigned properties.
     */
    private List<Property> filterByRole(List<Property> properties) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null)
            return properties;

        boolean isPropertyManager = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_PROPERTY_MANAGER"));

        if (isPropertyManager) {
            String userId = (String) auth.getPrincipal();
            UUID userUUID = UUID.fromString(userId);
            List<UUID> assignedPropertyIds = propertyAssignmentRepository.findByUserId(userUUID)
                    .stream()
                    .map(a -> a.getPropertyId())
                    .toList();

            return properties.stream()
                    .filter(p -> assignedPropertyIds.contains(p.getId()))
                    .collect(Collectors.toList());
        }

        return properties;
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
