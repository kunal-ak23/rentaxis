package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.BookingCreateRequest;
import com.datagami.rentaxis.api.dto.BookingDetailDTO;
import com.datagami.rentaxis.api.dto.BookingRequestDTO;
import com.datagami.rentaxis.api.dto.DecisionRequest;
import com.datagami.rentaxis.api.dto.MyFacilitiesDTO;
import com.datagami.rentaxis.api.exception.AccessDeniedException;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.BookingService;
import com.datagami.rentaxis.core.service.FacilityService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.BookingRequest;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.ParkingSpot;
import com.datagami.rentaxis.domain.entity.PropertyAmenity;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus;
import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.BookingRequestRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.ParkingSpotRepository;
import com.datagami.rentaxis.domain.repository.PropertyAmenityRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Booking lifecycle HTTP surface, admin and renter sides. RBAC lives here —
 * {@code BookingService} implements none of it (gate-pass split). Two checks
 * exist nowhere else: {@link #requireUnitOnActiveLease} (a renter may only
 * book against a unit on their own ACTIVE lease — the service trusts the unit
 * completely), and the {@link #release} role branch (renter path passes
 * actorIsAdmin=false so the service enforces the owner rule). Renters never
 * see other applicants — otherRequests appears only in the admin detail, and
 * {@code /facilities/my} carries pendingCount/held only (no identities).
 */
@RestController
@RequestMapping("/api/v1")
public class BookingController {

    private static final String RENTER_AUTHORITY = "ROLE_RENTER";

    private final BookingService bookingService;
    private final FacilityService facilityService;
    private final RenterRepository renterRepository;
    private final LeaseRepository leaseRepository;
    private final UnitRepository unitRepository;
    private final UserRepository userRepository;
    private final UserPropertyAssignmentRepository assignmentRepository;
    private final PropertyAmenityRepository amenityRepository;
    private final ParkingSpotRepository parkingSpotRepository;
    private final BookingRequestRepository bookingRequestRepository;

    public BookingController(BookingService bookingService,
                             FacilityService facilityService,
                             RenterRepository renterRepository,
                             LeaseRepository leaseRepository,
                             UnitRepository unitRepository,
                             UserRepository userRepository,
                             UserPropertyAssignmentRepository assignmentRepository,
                             PropertyAmenityRepository amenityRepository,
                             ParkingSpotRepository parkingSpotRepository,
                             BookingRequestRepository bookingRequestRepository) {
        this.bookingService = bookingService;
        this.facilityService = facilityService;
        this.renterRepository = renterRepository;
        this.leaseRepository = leaseRepository;
        this.unitRepository = unitRepository;
        this.userRepository = userRepository;
        this.assignmentRepository = assignmentRepository;
        this.amenityRepository = amenityRepository;
        this.parkingSpotRepository = parkingSpotRepository;
        this.bookingRequestRepository = bookingRequestRepository;
    }

    // ----------------------------------------------------------------- admin

    @GetMapping("/bookings")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','PROPERTY_MANAGER')")
    public ResponseEntity<Page<BookingRequestDTO>> list(
            @RequestParam(required = false) UUID propertyId,
            @RequestParam(required = false) BookingRequestStatus status,
            @RequestParam(required = false) BookingResourceType resourceType,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable) {
        checkPropertyManagerAccess(propertyId);
        Page<BookingRequest> page = bookingService.search(tenantId(), propertyId, status, resourceType, pageable);
        Map<UUID, String> unitNumbers = unitNumbers(page.getContent());
        Map<UUID, User> renters = renterUsers(page.getContent());
        Map<UUID, String> resourceNames = resourceNames(page.getContent());
        return ResponseEntity.ok(page.map(b -> toDTO(b, unitNumbers, renters, resourceNames)));
    }

    @GetMapping("/bookings/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','PROPERTY_MANAGER')")
    public ResponseEntity<BookingDetailDTO> get(@PathVariable UUID id) {
        BookingRequest booking = bookingService.get(tenantId(), id);
        checkPropertyManagerAccess(booking.getPropertyId());
        return ResponseEntity.ok(new BookingDetailDTO(
                toDTO(booking), toDTOs(bookingService.otherRequests(booking))));
    }

    @PostMapping("/bookings/{id}/approve")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','PROPERTY_MANAGER')")
    public ResponseEntity<BookingRequestDTO> approve(@PathVariable UUID id,
                                                     @Valid @RequestBody(required = false) DecisionRequest body) {
        UUID tenantId = tenantId();
        checkPropertyManagerAccess(bookingService.get(tenantId, id).getPropertyId());
        return ResponseEntity.ok(toDTO(bookingService.approve(
                tenantId, id, currentUserId(), body == null ? null : body.adminNote())));
    }

    @PostMapping("/bookings/{id}/reject")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','PROPERTY_MANAGER')")
    public ResponseEntity<BookingRequestDTO> reject(@PathVariable UUID id,
                                                    @Valid @RequestBody(required = false) DecisionRequest body) {
        UUID tenantId = tenantId();
        checkPropertyManagerAccess(bookingService.get(tenantId, id).getPropertyId());
        return ResponseEntity.ok(toDTO(bookingService.reject(
                tenantId, id, currentUserId(), body == null ? null : body.adminNote())));
    }

    /**
     * Shared admin/renter endpoint, branching on role like GatePassController.isGuard():
     * renters go through the owner-enforcing path, admins (after the PM assignment
     * check, propertyId derived from the loaded booking) may release any approved
     * parking booking in the tenant.
     */
    @PostMapping("/bookings/{id}/release")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','PROPERTY_MANAGER','RENTER')")
    public ResponseEntity<BookingRequestDTO> release(@PathVariable UUID id) {
        UUID tenantId = tenantId();
        if (isRenter()) {
            return ResponseEntity.ok(toDTO(bookingService.release(tenantId, id, currentUserId(), false)));
        }
        BookingRequest booking = bookingService.get(tenantId, id);
        checkPropertyManagerAccess(booking.getPropertyId());
        return ResponseEntity.ok(toDTO(bookingService.release(tenantId, id, currentUserId(), true)));
    }

    // ---------------------------------------------------------------- renter

    /**
     * Facilities visible to the caller's active-lease units. Renters get
     * pendingCount/held only — never other applicants' identities. Counts are
     * resolved via two batch queries total (one per resource type), never
     * per-resource, regardless of how many units/leases the renter has.
     */
    @GetMapping("/facilities/my")
    @PreAuthorize("hasRole('RENTER')")
    public ResponseEntity<MyFacilitiesDTO> myFacilities() {
        UUID tenantId = tenantId();
        UUID userId = currentUserId();
        Renter renter = renterRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("No renter profile linked to this user"));
        if (!tenantId.equals(renter.getTenantId())) {
            throw new NotFoundException("No renter profile linked to this user");
        }
        List<Unit> units = leaseRepository.findByRenterId(renter.getId()).stream()
                .filter(l -> l.getStatus() == LeaseStatus.ACTIVE)
                .filter(l -> tenantId.equals(l.getTenantId()))
                .map(Lease::getUnit)
                .toList();

        Map<UUID, PropertyAmenity> amenitiesById = new LinkedHashMap<>();
        Map<UUID, ParkingSpot> spotsById = new LinkedHashMap<>();
        Map<UUID, String> propertyNameByAmenity = new HashMap<>();
        Map<UUID, String> propertyNameBySpot = new HashMap<>();
        for (Unit unit : units) {
            FacilityService.VisibleFacilities visible = facilityService.visibleFacilities(tenantId, unit);
            String propertyName = unit.getProperty().getNameEn();
            for (PropertyAmenity a : visible.amenities()) {
                amenitiesById.putIfAbsent(a.getId(), a);
                propertyNameByAmenity.putIfAbsent(a.getId(), propertyName);
            }
            for (ParkingSpot s : visible.parkingSpots()) {
                spotsById.putIfAbsent(s.getId(), s);
                propertyNameBySpot.putIfAbsent(s.getId(), propertyName);
            }
        }

        List<UUID> amenityIds = List.copyOf(amenitiesById.keySet());
        List<UUID> spotIds = List.copyOf(spotsById.keySet());
        Map<UUID, Long> amenityPending = batchCounts(
                amenityIds.isEmpty() ? List.of()
                        : bookingRequestRepository.countByAmenityIdIn(tenantId, amenityIds, BookingRequestStatus.PENDING));
        Map<UUID, Long> spotPending = batchCounts(
                spotIds.isEmpty() ? List.of()
                        : bookingRequestRepository.countByParkingSpotIdIn(tenantId, spotIds, BookingRequestStatus.PENDING));
        Map<UUID, Long> spotHeld = batchCounts(
                spotIds.isEmpty() ? List.of()
                        : bookingRequestRepository.countByParkingSpotIdIn(tenantId, spotIds, BookingRequestStatus.APPROVED));

        List<MyFacilitiesDTO.RenterAmenityDTO> amenityDTOs = amenitiesById.values().stream()
                .map(a -> new MyFacilitiesDTO.RenterAmenityDTO(a.getId(), a.getPropertyId(),
                        propertyNameByAmenity.get(a.getId()), a.getNameEn(), a.getNameAr(),
                        a.getDescription(), a.isBookable(), amenityPending.getOrDefault(a.getId(), 0L)))
                .toList();
        List<MyFacilitiesDTO.RenterParkingSpotDTO> spotDTOs = spotsById.values().stream()
                .map(s -> new MyFacilitiesDTO.RenterParkingSpotDTO(s.getId(), s.getPropertyId(),
                        propertyNameBySpot.get(s.getId()), s.getSpotNumber(), s.getLevel(), s.isCovered(),
                        spotHeld.getOrDefault(s.getId(), 0L) > 0, spotPending.getOrDefault(s.getId(), 0L)))
                .toList();

        return ResponseEntity.ok(new MyFacilitiesDTO(amenityDTOs, spotDTOs));
    }

    @PostMapping("/bookings")
    @PreAuthorize("hasRole('RENTER')")
    public ResponseEntity<BookingRequestDTO> create(@Valid @RequestBody BookingCreateRequest req) {
        UUID tenantId = tenantId();
        UUID userId = currentUserId();
        Unit unit = requireUnitOnActiveLease(tenantId, userId, req.unitId());
        BookingRequest booking = bookingService.create(tenantId, userId, unit, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDTO(booking));
    }

    @GetMapping("/bookings/my")
    @PreAuthorize("hasRole('RENTER')")
    public List<BookingRequestDTO> mine() {
        return toDTOs(bookingService.listMine(tenantId(), currentUserId()));
    }

    @PostMapping("/bookings/{id}/cancel")
    @PreAuthorize("hasRole('RENTER')")
    public ResponseEntity<BookingRequestDTO> cancel(@PathVariable UUID id) {
        return ResponseEntity.ok(toDTO(bookingService.cancel(tenantId(), id, currentUserId())));
    }

    // -------------------------------------------------------------- helpers

    /**
     * The renter-side authorization check for POST /bookings: the caller must hold
     * an ACTIVE lease on the unit. 404 rather than 403 so a renter cannot use the
     * status code to discover unit ids (GatePassController.requireUnitOnActiveLease).
     */
    private Unit requireUnitOnActiveLease(UUID tenantId, UUID userId, UUID unitId) {
        if (unitId == null) {
            throw new BusinessRuleViolationException("unitId is required");
        }
        Renter renter = renterRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("No renter profile linked to this user"));
        if (!tenantId.equals(renter.getTenantId())) {
            throw new NotFoundException("No renter profile linked to this user");
        }
        Lease lease = leaseRepository.findByUnitIdAndStatus(unitId, LeaseStatus.ACTIVE).stream()
                .filter(l -> tenantId.equals(l.getTenantId()))
                .filter(l -> l.getRenter().getId().equals(renter.getId()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Unit is not on an active lease of yours"));
        return lease.getUnit();
    }

    /** UnitListingController.checkPropertyManagerAccess / AmenityController pattern, keyed on propertyId. */
    private void checkPropertyManagerAccess(UUID propertyId) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isPm = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_PROPERTY_MANAGER"));
        if (!isPm) return;

        if (propertyId == null) {
            throw new AccessDeniedException("propertyId is required for property managers");
        }
        UUID userId = UUID.fromString(auth.getName());
        if (!assignmentRepository.existsByUserIdAndPropertyId(userId, propertyId)) {
            throw new AccessDeniedException("You are not assigned to this property");
        }
    }

    private boolean isRenter() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(RENTER_AUTHORITY::equals);
    }

    private UUID currentUserId() {
        return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName());
    }

    private UUID tenantId() {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            throw new BusinessRuleViolationException("No tenant context on this request");
        }
        return tenantId;
    }

    private static Map<UUID, Long> batchCounts(List<Object[]> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        return rows.stream().collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1]));
    }

    // ---- mapping: batched joins, GatePassController.toSummaries style ----

    private List<BookingRequestDTO> toDTOs(List<BookingRequest> bookings) {
        Map<UUID, String> unitNumbers = unitNumbers(bookings);
        Map<UUID, User> renters = renterUsers(bookings);
        Map<UUID, String> resourceNames = resourceNames(bookings);
        return bookings.stream().map(b -> toDTO(b, unitNumbers, renters, resourceNames)).toList();
    }

    private BookingRequestDTO toDTO(BookingRequest b) {
        return toDTOs(List.of(b)).getFirst();
    }

    private BookingRequestDTO toDTO(BookingRequest b, Map<UUID, String> unitNumbers,
                                    Map<UUID, User> renters, Map<UUID, String> resourceNames) {
        User renter = renters.get(b.getRenterUserId());
        UUID resourceId = b.getResourceType() == BookingResourceType.AMENITY
                ? b.getAmenityId() : b.getParkingSpotId();
        return new BookingRequestDTO(b.getId(), b.getResourceType(), b.getAmenityId(),
                b.getParkingSpotId(), resourceNames.get(resourceId), b.getPropertyId(),
                b.getUnitId(), unitNumbers.get(b.getUnitId()), b.getRenterUserId(),
                renter == null ? null : renter.getName(),
                renter == null ? null : renter.getEmail(),
                renter == null ? null : renter.getPhoneNumber(),
                b.getNote(), b.getPreferredDate(), b.getStatus(), b.getAdminNote(),
                b.getDecidedByUserId(), b.getDecidedAt(), b.getCreatedAt());
    }

    private Map<UUID, String> unitNumbers(List<BookingRequest> bookings) {
        List<UUID> distinct = bookings.stream().map(BookingRequest::getUnitId).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> byId = new HashMap<>();
        for (Unit unit : unitRepository.findAllById(distinct)) {
            byId.put(unit.getId(), unit.getUnitNumber());
        }
        return byId;
    }

    /** Tenant-scoped in SQL, matching GatePassController.guardNames. */
    private Map<UUID, User> renterUsers(List<BookingRequest> bookings) {
        List<UUID> distinct = bookings.stream().map(BookingRequest::getRenterUserId).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<UUID, User> byId = new HashMap<>();
        for (User user : userRepository.findByTenantIdAndIdIn(tenantId(), distinct)) {
            byId.put(user.getId(), user);
        }
        return byId;
    }

    private Map<UUID, String> resourceNames(List<BookingRequest> bookings) {
        List<UUID> amenityIds = bookings.stream()
                .filter(b -> b.getAmenityId() != null)
                .map(BookingRequest::getAmenityId).distinct().toList();
        List<UUID> spotIds = bookings.stream()
                .filter(b -> b.getParkingSpotId() != null)
                .map(BookingRequest::getParkingSpotId).distinct().toList();
        Map<UUID, String> names = new HashMap<>();
        if (!amenityIds.isEmpty()) {
            for (PropertyAmenity a : amenityRepository.findAllById(amenityIds)) {
                names.put(a.getId(), a.getNameEn());
            }
        }
        if (!spotIds.isEmpty()) {
            for (ParkingSpot s : parkingSpotRepository.findAllById(spotIds)) {
                names.put(s.getId(), s.getSpotNumber());
            }
        }
        return names;
    }
}
