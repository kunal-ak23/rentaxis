package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.AmenityCreateRequest;
import com.datagami.rentaxis.api.dto.AmenityUpdateRequest;
import com.datagami.rentaxis.api.dto.ParkingSpotBulkCreateRequest;
import com.datagami.rentaxis.api.dto.ParkingSpotCreateRequest;
import com.datagami.rentaxis.api.dto.ParkingSpotUpdateRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.AmenityBuildingScope;
import com.datagami.rentaxis.domain.entity.Building;
import com.datagami.rentaxis.domain.entity.ParkingSpot;
import com.datagami.rentaxis.domain.entity.ParkingSpotBuildingScope;
import com.datagami.rentaxis.domain.entity.PropertyAmenity;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.repository.AmenityBuildingScopeRepository;
import com.datagami.rentaxis.domain.repository.BuildingRepository;
import com.datagami.rentaxis.domain.repository.ParkingSpotBuildingScopeRepository;
import com.datagami.rentaxis.domain.repository.ParkingSpotRepository;
import com.datagami.rentaxis.domain.repository.PropertyAmenityRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Inventory CRUD and renter visibility for bookable facilities. No role logic
 * here — RBAC and property-manager assignment checks live in the controllers,
 * same split as the gate-pass module. Cross-tenant lookups throw
 * {@link NotFoundException} (404, never 403) so ids cannot be probed.
 */
@Service
@Transactional
public class FacilityService {

    private final PropertyAmenityRepository amenityRepository;
    private final AmenityBuildingScopeRepository amenityScopeRepository;
    private final ParkingSpotRepository spotRepository;
    private final ParkingSpotBuildingScopeRepository spotScopeRepository;
    private final PropertyRepository propertyRepository;
    private final BuildingRepository buildingRepository;

    public FacilityService(PropertyAmenityRepository amenityRepository,
                           AmenityBuildingScopeRepository amenityScopeRepository,
                           ParkingSpotRepository spotRepository,
                           ParkingSpotBuildingScopeRepository spotScopeRepository,
                           PropertyRepository propertyRepository,
                           BuildingRepository buildingRepository) {
        this.amenityRepository = amenityRepository;
        this.amenityScopeRepository = amenityScopeRepository;
        this.spotRepository = spotRepository;
        this.spotScopeRepository = spotScopeRepository;
        this.propertyRepository = propertyRepository;
        this.buildingRepository = buildingRepository;
    }

    // ------------------------------------------------------------ amenities

    @Transactional(readOnly = true)
    public Page<PropertyAmenity> listAmenities(UUID tenantId, UUID propertyId, Pageable pageable) {
        if (propertyId != null) {
            return amenityRepository.findByTenantIdAndPropertyId(tenantId, propertyId, pageable);
        }
        return amenityRepository.findByTenantId(tenantId, pageable);
    }

    @Transactional(readOnly = true)
    public PropertyAmenity getAmenity(UUID tenantId, UUID id) {
        PropertyAmenity amenity = amenityRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Amenity not found"));
        if (!Objects.equals(amenity.getTenantId(), tenantId)) {
            throw new NotFoundException("Amenity not found");
        }
        return amenity;
    }

    public PropertyAmenity createAmenity(UUID tenantId, AmenityCreateRequest req) {
        if (req.propertyId() == null) {
            throw new BusinessRuleViolationException("propertyId is required");
        }
        if (req.nameEn() == null || req.nameEn().isBlank()) {
            throw new BusinessRuleViolationException("nameEn is required");
        }
        requirePropertyInTenant(tenantId, req.propertyId());

        PropertyAmenity amenity = new PropertyAmenity();
        amenity.setTenantId(tenantId);
        amenity.setPropertyId(req.propertyId());
        amenity.setNameEn(req.nameEn().trim());
        amenity.setNameAr(req.nameAr());
        amenity.setDescription(req.description());
        // Boolean-unboxing landmine: req.bookable() is a boxed Boolean and may be
        // null (client omitted the field). Null-check before touching the
        // primitive entity field instead of relying on ternary auto-unboxing.
        amenity.setBookable(req.bookable() == null || req.bookable());
        amenity.setActive(true);
        PropertyAmenity saved = amenityRepository.save(amenity);
        replaceAmenityScopes(tenantId, saved, req.buildingIds() == null ? List.of() : req.buildingIds());
        return saved;
    }

    public PropertyAmenity updateAmenity(UUID tenantId, UUID id, AmenityUpdateRequest req) {
        PropertyAmenity amenity = getAmenity(tenantId, id);
        if (req.nameEn() != null) amenity.setNameEn(req.nameEn().trim());
        if (req.nameAr() != null) amenity.setNameAr(req.nameAr());
        if (req.description() != null) amenity.setDescription(req.description());
        if (req.bookable() != null) amenity.setBookable(req.bookable());
        if (req.active() != null) amenity.setActive(req.active());
        PropertyAmenity saved = amenityRepository.save(amenity);
        if (req.buildingIds() != null) {
            replaceAmenityScopes(tenantId, saved, req.buildingIds());
        }
        return saved;
    }

    /** Soft-deactivate: hides from renters, leaves existing requests untouched. */
    public void deactivateAmenity(UUID tenantId, UUID id) {
        PropertyAmenity amenity = getAmenity(tenantId, id);
        amenity.setActive(false);
        amenityRepository.save(amenity);
    }

    @Transactional(readOnly = true)
    public List<UUID> amenityBuildingIds(UUID amenityId) {
        return amenityScopeRepository.findByAmenityId(amenityId).stream()
                .map(AmenityBuildingScope::getBuildingId)
                .toList();
    }

    // -------------------------------------------------------------- parking

    @Transactional(readOnly = true)
    public Page<ParkingSpot> listParkingSpots(UUID tenantId, UUID propertyId, Pageable pageable) {
        if (propertyId != null) {
            return spotRepository.findByTenantIdAndPropertyId(tenantId, propertyId, pageable);
        }
        return spotRepository.findByTenantId(tenantId, pageable);
    }

    @Transactional(readOnly = true)
    public ParkingSpot getParkingSpot(UUID tenantId, UUID id) {
        ParkingSpot spot = spotRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Parking spot not found"));
        if (!Objects.equals(spot.getTenantId(), tenantId)) {
            throw new NotFoundException("Parking spot not found");
        }
        return spot;
    }

    public ParkingSpot createParkingSpot(UUID tenantId, ParkingSpotCreateRequest req) {
        if (req.propertyId() == null) {
            throw new BusinessRuleViolationException("propertyId is required");
        }
        requirePropertyInTenant(tenantId, req.propertyId());
        String spotNumber = requireSpotNumber(req.spotNumber());
        requireSpotNumberFree(tenantId, req.propertyId(), spotNumber);

        ParkingSpot spot = newSpot(tenantId, req.propertyId(), spotNumber, req.level(), req.covered());
        ParkingSpot saved = spotRepository.save(spot);
        replaceSpotScopes(tenantId, saved, req.buildingIds() == null ? List.of() : req.buildingIds());
        return saved;
    }

    public List<ParkingSpot> bulkCreateParkingSpots(UUID tenantId, ParkingSpotBulkCreateRequest req) {
        if (req.propertyId() == null) {
            throw new BusinessRuleViolationException("propertyId is required");
        }
        requirePropertyInTenant(tenantId, req.propertyId());
        if (req.spotNumbers() == null || req.spotNumbers().isEmpty()) {
            throw new BusinessRuleViolationException("spotNumbers must not be empty");
        }
        // Dedupe while preserving order; validate every number before creating any.
        Set<String> numbers = new LinkedHashSet<>();
        for (String raw : req.spotNumbers()) {
            numbers.add(requireSpotNumber(raw));
        }
        for (String number : numbers) {
            requireSpotNumberFree(tenantId, req.propertyId(), number);
        }
        List<UUID> buildingIds = req.buildingIds() == null ? List.of() : req.buildingIds();
        List<ParkingSpot> created = new ArrayList<>();
        for (String number : numbers) {
            ParkingSpot saved = spotRepository.save(
                    newSpot(tenantId, req.propertyId(), number, req.level(), req.covered()));
            replaceSpotScopes(tenantId, saved, buildingIds);
            created.add(saved);
        }
        return created;
    }

    public ParkingSpot updateParkingSpot(UUID tenantId, UUID id, ParkingSpotUpdateRequest req) {
        ParkingSpot spot = getParkingSpot(tenantId, id);
        if (req.spotNumber() != null) {
            String spotNumber = requireSpotNumber(req.spotNumber());
            if (!spotNumber.equals(spot.getSpotNumber())) {
                // Self-excluding uniqueness check: the create-path exists query would
                // collide with this row's own (unchanged) number.
                requireSpotNumberFreeForUpdate(tenantId, spot.getPropertyId(), spotNumber, id);
                spot.setSpotNumber(spotNumber);
            }
        }
        if (req.level() != null) spot.setLevel(req.level());
        if (req.covered() != null) spot.setCovered(req.covered());
        if (req.active() != null) spot.setActive(req.active());
        ParkingSpot saved = spotRepository.save(spot);
        if (req.buildingIds() != null) {
            replaceSpotScopes(tenantId, saved, req.buildingIds());
        }
        return saved;
    }

    /** Soft-deactivate: hides from renters, leaves existing requests untouched. */
    public void deactivateParkingSpot(UUID tenantId, UUID id) {
        ParkingSpot spot = getParkingSpot(tenantId, id);
        spot.setActive(false);
        spotRepository.save(spot);
    }

    @Transactional(readOnly = true)
    public List<UUID> parkingSpotBuildingIds(UUID parkingSpotId) {
        return spotScopeRepository.findByParkingSpotId(parkingSpotId).stream()
                .map(ParkingSpotBuildingScope::getBuildingId)
                .toList();
    }

    // -------------------------------------------------- renter visibility

    public record VisibleFacilities(List<PropertyAmenity> amenities, List<ParkingSpot> parkingSpots) {
    }

    /**
     * Everything the given unit may see: active facilities of the unit's property
     * where the facility has no scope rows (all towers) OR the unit's building is
     * in the scope set. A unit with no building sees only unscoped facilities.
     * Scopes are batched — two queries regardless of facility count. Client-visible
     * lists use the createdAt-ordered finders, not the unordered variants.
     */
    @Transactional(readOnly = true)
    public VisibleFacilities visibleFacilities(UUID tenantId, Unit unit) {
        UUID propertyId = unit.getProperty().getId();
        UUID unitBuildingId = unit.getBuilding() == null ? null : unit.getBuilding().getId();

        List<PropertyAmenity> amenities =
                amenityRepository.findByTenantIdAndPropertyIdAndActiveTrueOrderByCreatedAtAsc(tenantId, propertyId);
        Map<UUID, List<UUID>> amenityScopes = amenities.isEmpty() ? Map.of()
                : amenityScopeRepository.findByAmenityIdIn(
                        amenities.stream().map(PropertyAmenity::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(AmenityBuildingScope::getAmenityId,
                        Collectors.mapping(AmenityBuildingScope::getBuildingId, Collectors.toList())));
        List<PropertyAmenity> visibleAmenities = amenities.stream()
                .filter(a -> visible(amenityScopes.getOrDefault(a.getId(), List.of()), unitBuildingId))
                .toList();

        List<ParkingSpot> spots =
                spotRepository.findByTenantIdAndPropertyIdAndActiveTrueOrderByCreatedAtAsc(tenantId, propertyId);
        Map<UUID, List<UUID>> spotScopes = spots.isEmpty() ? Map.of()
                : spotScopeRepository.findByParkingSpotIdIn(
                        spots.stream().map(ParkingSpot::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(ParkingSpotBuildingScope::getParkingSpotId,
                        Collectors.mapping(ParkingSpotBuildingScope::getBuildingId, Collectors.toList())));
        List<ParkingSpot> visibleSpots = spots.stream()
                .filter(s -> visible(spotScopes.getOrDefault(s.getId(), List.of()), unitBuildingId))
                .toList();

        return new VisibleFacilities(visibleAmenities, visibleSpots);
    }

    /** Single-resource visibility check for the booking path. Includes the property match. */
    @Transactional(readOnly = true)
    public boolean amenityVisibleToUnit(PropertyAmenity amenity, Unit unit) {
        if (!Objects.equals(amenity.getPropertyId(), unit.getProperty().getId())) {
            return false;
        }
        UUID unitBuildingId = unit.getBuilding() == null ? null : unit.getBuilding().getId();
        return visible(amenityBuildingIds(amenity.getId()), unitBuildingId);
    }

    /** Single-resource visibility check for the booking path. Includes the property match. */
    @Transactional(readOnly = true)
    public boolean parkingSpotVisibleToUnit(ParkingSpot spot, Unit unit) {
        if (!Objects.equals(spot.getPropertyId(), unit.getProperty().getId())) {
            return false;
        }
        UUID unitBuildingId = unit.getBuilding() == null ? null : unit.getBuilding().getId();
        return visible(parkingSpotBuildingIds(spot.getId()), unitBuildingId);
    }

    private static boolean visible(List<UUID> scopeBuildingIds, UUID unitBuildingId) {
        if (scopeBuildingIds.isEmpty()) {
            return true; // no scope rows = all towers
        }
        return unitBuildingId != null && scopeBuildingIds.contains(unitBuildingId);
    }

    // -------------------------------------------------------------- helpers

    private void requirePropertyInTenant(UUID tenantId, UUID propertyId) {
        // Explicit tenant-scoped existence check, not the Hibernate filter's job —
        // same reasoning as GatePassController.setGuardProperties.
        if (!propertyRepository.existsByIdAndTenantId(propertyId, tenantId)) {
            throw new NotFoundException("Property not found");
        }
    }

    private static String requireSpotNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessRuleViolationException("spotNumber is required");
        }
        return raw.trim();
    }

    private void requireSpotNumberFree(UUID tenantId, UUID propertyId, String spotNumber) {
        // Friendly 400 ahead of uq_parking_spot_number; the DB constraint is the backstop.
        if (spotRepository.existsByTenantIdAndPropertyIdAndSpotNumber(tenantId, propertyId, spotNumber)) {
            throw new BusinessRuleViolationException("Spot number already exists: " + spotNumber);
        }
    }

    private void requireSpotNumberFreeForUpdate(UUID tenantId, UUID propertyId, String spotNumber, UUID excludingId) {
        // Self-excluding variant for the edit path: the create-path exists query
        // would collide with this same row when the number is left unchanged.
        if (spotRepository.existsByTenantIdAndPropertyIdAndSpotNumberAndIdNot(
                tenantId, propertyId, spotNumber, excludingId)) {
            throw new BusinessRuleViolationException("Spot number already exists: " + spotNumber);
        }
    }

    private static ParkingSpot newSpot(UUID tenantId, UUID propertyId, String spotNumber,
                                       String level, Boolean covered) {
        ParkingSpot spot = new ParkingSpot();
        spot.setTenantId(tenantId);
        spot.setPropertyId(propertyId);
        spot.setSpotNumber(spotNumber);
        spot.setLevel(level);
        // Boolean-unboxing landmine: covered is a boxed Boolean and may be null
        // (client omitted the field). Null-check before touching the primitive
        // entity field instead of relying on ternary auto-unboxing.
        spot.setCovered(covered == null || covered);
        spot.setActive(true);
        return spot;
    }

    private void replaceAmenityScopes(UUID tenantId, PropertyAmenity amenity, List<UUID> buildingIds) {
        List<UUID> requested = validateBuildings(amenity.getPropertyId(), buildingIds);
        amenityScopeRepository.deleteByTenantIdAndAmenityId(tenantId, amenity.getId());
        // uq_amenity_building is checked per-statement: flush so a re-inserted
        // building does not collide with its own pending delete (same reasoning
        // as GatePassController.setGuardProperties).
        amenityScopeRepository.flush();
        for (UUID buildingId : requested) {
            AmenityBuildingScope scope = new AmenityBuildingScope();
            scope.setTenantId(tenantId);
            scope.setAmenityId(amenity.getId());
            scope.setBuildingId(buildingId);
            amenityScopeRepository.save(scope);
        }
    }

    private void replaceSpotScopes(UUID tenantId, ParkingSpot spot, List<UUID> buildingIds) {
        List<UUID> requested = validateBuildings(spot.getPropertyId(), buildingIds);
        spotScopeRepository.deleteByTenantIdAndParkingSpotId(tenantId, spot.getId());
        spotScopeRepository.flush();
        for (UUID buildingId : requested) {
            ParkingSpotBuildingScope scope = new ParkingSpotBuildingScope();
            scope.setTenantId(tenantId);
            scope.setParkingSpotId(spot.getId());
            scope.setBuildingId(buildingId);
            spotScopeRepository.save(scope);
        }
    }

    private List<UUID> validateBuildings(UUID propertyId, List<UUID> buildingIds) {
        List<UUID> requested = buildingIds.stream().filter(Objects::nonNull).distinct().toList();
        if (requested.isEmpty()) {
            return requested;
        }
        Set<UUID> propertyBuildings = buildingRepository.findByPropertyId(propertyId).stream()
                .map(Building::getId)
                .collect(Collectors.toSet());
        for (UUID buildingId : requested) {
            if (!propertyBuildings.contains(buildingId)) {
                throw new BusinessRuleViolationException("Building is not in this property: " + buildingId);
            }
        }
        return requested;
    }
}
