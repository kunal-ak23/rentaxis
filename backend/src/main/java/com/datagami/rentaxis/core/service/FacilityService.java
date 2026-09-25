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
import org.springframework.dao.DataIntegrityViolationException;
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

    /** Migration 69's unique index names — kept in sync with the constraint translation below. */
    static final String UQ_PARKING_SPOT_NUMBER = "uq_parking_spot_number";
    static final String UQ_AMENITY_BUILDING = "uq_amenity_building";
    static final String UQ_PARKING_SPOT_BUILDING = "uq_parking_spot_building";

    /** Bulk-import guardrail: keeps a single request from generating an unbounded insert batch. */
    static final int MAX_BULK_SPOT_NUMBERS = 500;

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
        // F14-50: free, a fee per booking or per hour.
        BookingFeeService.validate(req.feeType(), req.feeAmount(), false);
        amenity.setFeeType(req.feeType() == null ? "FREE" : req.feeType());
        amenity.setFeeAmount("FREE".equals(amenity.getFeeType()) || req.feeAmount() == null
                ? java.math.BigDecimal.ZERO : req.feeAmount());
        PropertyAmenity saved = amenityRepository.save(amenity);

        // Freshly created — no existing scope rows to replace, so insert directly
        // instead of routing through the delete+flush+insert replace path.
        List<UUID> requested = validateBuildings(req.propertyId(),
                req.buildingIds() == null ? List.of() : req.buildingIds());
        insertAmenityScopeRows(tenantId, saved.getId(), requested);
        return saved;
    }

    public PropertyAmenity updateAmenity(UUID tenantId, UUID id, AmenityUpdateRequest req) {
        PropertyAmenity amenity = getAmenity(tenantId, id);
        if (req.nameEn() != null) {
            if (req.nameEn().isBlank()) {
                throw new BusinessRuleViolationException("nameEn is required");
            }
            amenity.setNameEn(req.nameEn().trim());
        }
        if (req.nameAr() != null) amenity.setNameAr(req.nameAr());
        if (req.description() != null) amenity.setDescription(req.description());
        if (req.bookable() != null) amenity.setBookable(req.bookable());
        if (req.active() != null) amenity.setActive(req.active());
        if (req.feeType() != null) {
            BookingFeeService.validate(req.feeType(), req.feeAmount(), false);
            amenity.setFeeType(req.feeType());
            amenity.setFeeAmount("FREE".equals(req.feeType()) ? java.math.BigDecimal.ZERO : req.feeAmount());
        }
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

        ParkingSpot saved = saveSpot(
                newSpot(tenantId, req.propertyId(), spotNumber, req.level(), req.covered()), spotNumber);

        // Freshly created — no existing scope rows to replace, so insert directly
        // instead of routing through the delete+flush+insert replace path.
        List<UUID> requested = validateBuildings(req.propertyId(),
                req.buildingIds() == null ? List.of() : req.buildingIds());
        insertSpotScopeRows(tenantId, saved.getId(), requested);
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
        if (req.spotNumbers().size() > MAX_BULK_SPOT_NUMBERS) {
            throw new BusinessRuleViolationException(
                    "Too many spots in one request (max " + MAX_BULK_SPOT_NUMBERS + ")");
        }
        // Dedupe while preserving order; validate every number before creating any.
        Set<String> numbers = new LinkedHashSet<>();
        for (String raw : req.spotNumbers()) {
            numbers.add(requireSpotNumber(raw));
        }
        for (String number : numbers) {
            requireSpotNumberFree(tenantId, req.propertyId(), number);
        }
        // propertyId and buildingIds are identical for every spot in this batch —
        // validate the requested scope once (loop-invariant) instead of
        // re-querying buildingRepository on every iteration.
        List<UUID> requestedBuildingIds = validateBuildings(req.propertyId(),
                req.buildingIds() == null ? List.of() : req.buildingIds());
        List<ParkingSpot> created = new ArrayList<>();
        for (String number : numbers) {
            ParkingSpot saved = saveSpot(
                    newSpot(tenantId, req.propertyId(), number, req.level(), req.covered()), number);
            insertSpotScopeRows(tenantId, saved.getId(), requestedBuildingIds);
            created.add(saved);
        }
        return created;
    }

    public ParkingSpot updateParkingSpot(UUID tenantId, UUID id, ParkingSpotUpdateRequest req) {
        ParkingSpot spot = getParkingSpot(tenantId, id);
        if (req.spotNumber() != null) {
            String spotNumber = requireSpotNumber(req.spotNumber());
            if (!spotNumber.equals(spot.getSpotNumber())) {
                // Number actually changed — the unchanged-number case is already
                // short-circuited by the equals check above, so a genuine self-match
                // against this row's own (not-yet-persisted) new number can't occur.
                // The AndIdNot variant here is defense-in-depth, not the mechanism
                // that avoids a false positive.
                requireSpotNumberFreeForUpdate(tenantId, spot.getPropertyId(), spotNumber, id);
                spot.setSpotNumber(spotNumber);
            }
        }
        if (req.level() != null) spot.setLevel(req.level());
        if (req.covered() != null) spot.setCovered(req.covered());
        if (req.active() != null) spot.setActive(req.active());
        if (req.feeType() != null) {
            BookingFeeService.validate(req.feeType(), req.feeAmount(), true);
            spot.setFeeType(req.feeType());
            spot.setFeeAmount("FREE".equals(req.feeType()) ? java.math.BigDecimal.ZERO : req.feeAmount());
        }
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
     * Everything the given unit may see: amenities honour their optional building
     * scope, while active parking is available property-wide. Parking is a shared
     * inventory and the booking workflow prevents a held space being requested.
     * Client-visible lists use the createdAt-ordered finders.
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
        return new VisibleFacilities(visibleAmenities, spots);
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

    /** Parking is visible and requestable to every active renter of the same property. */
    @Transactional(readOnly = true)
    public boolean parkingSpotVisibleToUnit(ParkingSpot spot, Unit unit) {
        return Objects.equals(spot.getPropertyId(), unit.getProperty().getId());
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
        // Self-excluding variant for the edit path. Callers only reach this after
        // a genuine number change (the unchanged-number case is short-circuited
        // before this is called), so a self-match can't actually happen here —
        // excluding the row's own id is defense-in-depth, not what prevents a
        // false positive.
        if (spotRepository.existsByTenantIdAndPropertyIdAndSpotNumberAndIdNot(
                tenantId, propertyId, spotNumber, excludingId)) {
            throw new BusinessRuleViolationException("Spot number already exists: " + spotNumber);
        }
    }

    /**
     * Saves a newly created spot and translates a uq_parking_spot_number race
     * into a friendly 400. The existsBy pre-check above is a happy-path
     * message-quality guard, but it's TOCTOU against the DB-level unique
     * constraint (migration 69) — a concurrent request can still slip in
     * between the check and this save. saveAndFlush (not save) so the INSERT
     * actually executes inside this try — GenerationType.UUID assigns the id
     * in memory, so Hibernate is otherwise free to defer the INSERT to a later
     * auto-flush or commit, past this catch (same reasoning as
     * UserService#createUser's saveAndFlush).
     */
    private ParkingSpot saveSpot(ParkingSpot spot, String spotNumber) {
        try {
            return spotRepository.saveAndFlush(spot);
        } catch (DataIntegrityViolationException e) {
            throw translateConstraintViolation(e, UQ_PARKING_SPOT_NUMBER,
                    "Spot number already exists: " + spotNumber);
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

    /** Delete+flush+insert: only for the edit path, where scope rows may already exist. */
    private void replaceAmenityScopes(UUID tenantId, PropertyAmenity amenity, List<UUID> buildingIds) {
        List<UUID> requested = validateBuildings(amenity.getPropertyId(), buildingIds);
        amenityScopeRepository.deleteByTenantIdAndAmenityId(tenantId, amenity.getId());
        // uq_amenity_building is checked per-statement: flush so a re-inserted
        // building does not collide with its own pending delete (same reasoning
        // as GatePassController.setGuardProperties).
        amenityScopeRepository.flush();
        insertAmenityScopeRows(tenantId, amenity.getId(), requested);
        try {
            amenityScopeRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw translateConstraintViolation(e, UQ_AMENITY_BUILDING,
                    "Duplicate building scope for amenity " + amenity.getId());
        }
    }

    /** Delete+flush+insert: only for the edit path, where scope rows may already exist. */
    private void replaceSpotScopes(UUID tenantId, ParkingSpot spot, List<UUID> buildingIds) {
        List<UUID> requested = validateBuildings(spot.getPropertyId(), buildingIds);
        spotScopeRepository.deleteByTenantIdAndParkingSpotId(tenantId, spot.getId());
        spotScopeRepository.flush();
        insertSpotScopeRows(tenantId, spot.getId(), requested);
        try {
            spotScopeRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw translateConstraintViolation(e, UQ_PARKING_SPOT_BUILDING,
                    "Duplicate building scope for parking spot " + spot.getId());
        }
    }

    /**
     * Insert-only: for the create paths, where the resource is brand new and has
     * no existing scope rows to replace, so there's nothing to delete and no
     * pending-delete collision to flush around.
     */
    private void insertAmenityScopeRows(UUID tenantId, UUID amenityId, List<UUID> buildingIds) {
        for (UUID buildingId : buildingIds) {
            AmenityBuildingScope scope = new AmenityBuildingScope();
            scope.setTenantId(tenantId);
            scope.setAmenityId(amenityId);
            scope.setBuildingId(buildingId);
            amenityScopeRepository.save(scope);
        }
    }

    /**
     * Insert-only: for the create paths, where the resource is brand new and has
     * no existing scope rows to replace, so there's nothing to delete and no
     * pending-delete collision to flush around.
     */
    private void insertSpotScopeRows(UUID tenantId, UUID parkingSpotId, List<UUID> buildingIds) {
        for (UUID buildingId : buildingIds) {
            ParkingSpotBuildingScope scope = new ParkingSpotBuildingScope();
            scope.setTenantId(tenantId);
            scope.setParkingSpotId(parkingSpotId);
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

    /**
     * Translates a unique-constraint race into a friendly 400. Follows the
     * catch-and-translate pattern in PropertyService#createProperty: the
     * happy-path existsBy/validate checks above are message-quality guards, but
     * they're TOCTOU against the DB-level unique constraints from migration 69
     * — a concurrent request can still slip through between the check and the
     * save this wraps. An unrecognized constraint name means this violation
     * wasn't the one being guarded against, so the original exception is
     * rethrown rather than mislabeled.
     */
    private static RuntimeException translateConstraintViolation(
            DataIntegrityViolationException e, String constraintName, String message) {
        Throwable cause = e.getMostSpecificCause();
        String causeMessage = cause != null ? cause.getMessage() : null;
        if (causeMessage != null && causeMessage.contains(constraintName)) {
            return new BusinessRuleViolationException(message);
        }
        return e;
    }
}
