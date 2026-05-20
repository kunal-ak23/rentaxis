package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.BulkPropertyImportResultDTO;
import com.datagami.rentaxis.api.dto.PropertyStatsDTO;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Building;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.PropertyType;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.entity.enums.UnitType;
import com.datagami.rentaxis.domain.repository.BuildingRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PropertyService {

    private final PropertyRepository repository;
    private final UnitRepository unitRepository;
    private final BuildingRepository buildingRepository;
    private final UserPropertyAssignmentRepository propertyAssignmentRepository;
    private final UserService userService;

    public PropertyService(PropertyRepository repository, UnitRepository unitRepository,
            BuildingRepository buildingRepository,
            UserPropertyAssignmentRepository propertyAssignmentRepository,
            UserService userService) {
        this.repository = repository;
        this.unitRepository = unitRepository;
        this.buildingRepository = buildingRepository;
        this.propertyAssignmentRepository = propertyAssignmentRepository;
        this.userService = userService;
    }

    @Transactional
    public Property createProperty(Property property) {
        // Within-tenant uniqueness on name_en is enforced by the partial
        // unique index added in migration 60. Catch the DataIntegrityViolation
        // and translate it to a user-friendly IllegalArgumentException so
        // controllers can surface a 400 with a clear message instead of a
        // 500 with a Postgres error string.
        try {
            return repository.save(property);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            String msg = e.getMostSpecificCause() != null
                    ? e.getMostSpecificCause().getMessage() : "";
            if (msg.contains("ux_properties_tenant_name_en_lower")) {
                throw new IllegalArgumentException(
                        "A property named '" + property.getNameEn()
                                + "' already exists in this tenant. " +
                                "Property names must be unique within an organization.",
                        e);
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public List<Property> getAllProperties() {
        List<Property> all = repository.findAll();
        return filterByRole(all);
    }

    @Transactional(readOnly = true)
    public Property getPropertyById(UUID id) {
        Property property = repository.findById(id)
                .orElseThrow(() -> new com.datagami.rentaxis.api.exception.NotFoundException("Property not found"));
        UUID tenantId = com.datagami.rentaxis.core.tenant.TenantContextHolder.getTenantId();
        if (tenantId != null && !tenantId.equals(property.getTenantId())) {
            throw new com.datagami.rentaxis.api.exception.NotFoundException("Property not found");
        }
        return property;
    }

    @Transactional(readOnly = true)
    public List<PropertyStatsDTO> getAllPropertiesWithStats() {
        List<Property> properties = repository.findAll();
        properties = filterByRole(properties);
        return properties.stream().map(this::calculateStats).collect(Collectors.toList());
    }

    @Transactional
    public BulkPropertyImportResultDTO importPropertyWithUnits(
            MultipartFile file, String nameEn, String nameAr, String emirate,
            String address, String type, String makaniNumber) {

        BulkPropertyImportResultDTO result = new BulkPropertyImportResultDTO();
        result.setPropertyName(nameEn);
        List<String> errors = new ArrayList<>();

        // Parse and validate CSV first (no DB writes yet)
        List<String[]> parsedRows = new ArrayList<>();
        Set<String> buildingNames = new LinkedHashSet<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            String line;
            boolean firstLine = true;
            int rowNum = 1;

            while ((line = reader.readLine()) != null) {
                if (firstLine) {
                    firstLine = false;
                    continue;
                }
                rowNum++;

                String[] parts = line.split(",", -1);
                if (parts.length < 6) {
                    errors.add("Row " + rowNum + ": Expected 6 columns but found " + parts.length);
                    continue;
                }

                String buildingName = parts[0].trim();
                String unitNumber = parts[1].trim();
                String unitTypeStr = parts[2].trim();
                String sizeSqftStr = parts[3].trim();
                String expectedRentStr = parts[4].trim();
                String statusStr = parts[5].trim();

                if (unitNumber.isEmpty()) {
                    errors.add("Row " + rowNum + ": UnitNumber is required");
                    continue;
                }

                if (!unitTypeStr.isEmpty()) {
                    try {
                        UnitType.valueOf(unitTypeStr);
                    } catch (IllegalArgumentException e) {
                        errors.add("Row " + rowNum + ": Invalid unit type '" + unitTypeStr + "'");
                        continue;
                    }
                }

                if (!sizeSqftStr.isEmpty()) {
                    try {
                        new BigDecimal(sizeSqftStr);
                    } catch (NumberFormatException e) {
                        errors.add("Row " + rowNum + ": Invalid SizeSqft '" + sizeSqftStr + "'");
                        continue;
                    }
                }

                if (!expectedRentStr.isEmpty()) {
                    try {
                        new BigDecimal(expectedRentStr);
                    } catch (NumberFormatException e) {
                        errors.add("Row " + rowNum + ": Invalid ExpectedRent '" + expectedRentStr + "'");
                        continue;
                    }
                }

                if (!statusStr.isEmpty()) {
                    try {
                        UnitStatus.valueOf(statusStr);
                    } catch (IllegalArgumentException e) {
                        errors.add("Row " + rowNum + ": Invalid status '" + statusStr + "'");
                        continue;
                    }
                }

                if (!buildingName.isEmpty()) {
                    buildingNames.add(buildingName);
                }

                parsedRows.add(new String[]{buildingName, unitNumber, unitTypeStr, sizeSqftStr, expectedRentStr, statusStr});
            }
        } catch (Exception e) {
            errors.add("Failed to read CSV file: " + e.getMessage());
        }

        // If validation errors, return early without saving anything
        if (!errors.isEmpty()) {
            result.setErrors(errors);
            return result;
        }

        // All rows valid — now persist everything
        Property property = new Property();
        property.setNameEn(nameEn);
        property.setNameAr(nameAr);
        property.setEmirate(Emirate.valueOf(emirate.toUpperCase()));
        property.setType(PropertyType.valueOf(type.toUpperCase()));
        property.setAddress(address);
        property.setMakaniNumber(makaniNumber);
        property = repository.save(property);
        result.setPropertyId(property.getId());

        // Create buildings
        Map<String, Building> buildingMap = new HashMap<>();
        for (String bName : buildingNames) {
            Building building = new Building();
            building.setNameEn(bName);
            building.setProperty(property);
            building = buildingRepository.save(building);
            buildingMap.put(bName, building);
        }

        // Create units
        for (String[] row : parsedRows) {
            Unit unit = new Unit();
            unit.setProperty(property);
            if (!row[0].isEmpty()) {
                unit.setBuilding(buildingMap.get(row[0]));
            }
            unit.setUnitNumber(row[1]);
            if (!row[2].isEmpty()) unit.setType(UnitType.valueOf(row[2]));
            if (!row[3].isEmpty()) unit.setSizeSqft(new BigDecimal(row[3]));
            if (!row[4].isEmpty()) unit.setExpectedRent(new BigDecimal(row[4]));
            if (!row[5].isEmpty()) {
                unit.setStatus(UnitStatus.valueOf(row[5]));
            }
            unitRepository.save(unit);
        }

        result.setBuildingsCreated(buildingMap.size());
        result.setUnitsCreated(parsedRows.size());
        return result;
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
        dto.setAssignedManagers(userService.getAssignedManagers(property.getId()));
        return dto;
    }

    public List<com.datagami.rentaxis.domain.entity.User> getPropertyManagers(UUID propertyId) {
        return userService.getAssignedManagers(propertyId);
    }
}
