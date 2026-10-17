package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.BookingRequest;
import com.datagami.rentaxis.domain.entity.Building;
import com.datagami.rentaxis.domain.entity.ParkingSpot;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus;
import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.BookingRequestRepository;
import com.datagami.rentaxis.domain.repository.BuildingRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.ParkingSpotRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.CrossTenantHttp;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Attached parking (2026-10-17 ruling), end to end over HTTP against Postgres:
 * attach and detach; an attached bay is refused by the server, hidden from the
 * bookable list and shown as allocated parking to its unit's renter only; an
 * unattached bay is held only for its booked dates; and another organisation can
 * neither attach to our units nor see or move our bays.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AttachedParkingIT extends AbstractPostgresIT {

    @LocalServerPort int port;

    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired BuildingRepository buildingRepo;
    @Autowired ParkingSpotRepository spotRepo;
    @Autowired BookingRequestRepository bookingRepo;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired PropertyService propertyService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LeaseService leaseService;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired LeasePostingService posting;
    @Autowired JdbcTemplate jdbc;

    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Dubai"));

    private CrossTenantHttp http;
    private LeaseTestFixtures fx;
    private User admin;
    private User renterOne;     // contract on unit 101
    private User renterTwo;     // contract on unit 102, same property
    private Unit unitOne;
    private Unit unitTwo;
    private Unit otherTowerUnit;
    private ParkingSpot bay;

    @BeforeEach
    void setUp() {
        http = new CrossTenantHttp(port, orgRepo, userRepo, accountService, propertyAccountService, propertyService);
        fx = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, chequeGeneration, posting);
        UUID t = fx.tenantId();
        Property p = fx.property();

        Building towerA = building(p, "Tower A");
        Building towerB = building(p, "Tower B");
        unitOne = fx.unit();
        unitOne.setBuilding(towerA);
        unitOne = unitRepo.save(unitOne);
        unitTwo = fx.createUnit(p, "102");
        unitTwo.setBuilding(towerA);
        unitTwo = unitRepo.save(unitTwo);
        otherTowerUnit = fx.createUnit(p, "201");
        otherTowerUnit.setBuilding(towerB);
        otherTowerUnit = unitRepo.save(otherTowerUnit);

        Renter r1 = fx.renter();
        Renter r2 = fx.createRenter("Second Renter");
        renterOne = userRepo.findById(r1.getUserId()).orElseThrow();
        renterTwo = userRepo.findById(r2.getUserId()).orElseThrow();
        LocalDate start = today.minusDays(30);
        LocalDate end = today.plusDays(300);
        fx.postedLease(unitOne, r1, start.minusDays(5), start, end, List.of(line("RENT", "60000")), 1,
                LeaseTestFixtures.nextChequeNumber());
        fx.postedLease(unitTwo, r2, start.minusDays(5), start, end, List.of(line("RENT", "60000")), 1,
                LeaseTestFixtures.nextChequeNumber());

        bay = new ParkingSpot();
        bay.setTenantId(t);
        bay.setPropertyId(p.getId());
        bay.setSpotNumber("P-1");
        bay = spotRepo.save(bay);
        jdbc.update("insert into parking_spot_building_scopes (id, tenant_id, parking_spot_id, building_id) values (?,?,?,?)",
                UUID.randomUUID(), t, bay.getId(), towerA.getId());

        admin = http.admin(t);
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private Building building(Property p, String name) {
        Building b = new Building();
        b.setProperty(p);
        b.setNameEn(name);
        b.setTenantId(fx.tenantId());
        return buildingRepo.save(b);
    }

    private ResponseEntity<Map> attach(User caller, UUID spotId, UUID unitId) {
        Map<String, Object> body = new HashMap<>();
        body.put("unitId", unitId == null ? null : unitId.toString());
        return http.call(caller, HttpMethod.PUT, "/api/v1/parking-spots/" + spotId + "/attachment", body);
    }

    private ResponseEntity<Map> requestBay(User renter, Unit unit, LocalDate from, LocalDate to) {
        Map<String, Object> body = new HashMap<>();
        body.put("resourceType", "PARKING_SPOT");
        body.put("resourceId", bay.getId().toString());
        body.put("unitId", unit.getId().toString());
        body.put("preferredDate", from.toString());
        body.put("preferredEndDate", to == null ? null : to.toString());
        return http.call(renter, HttpMethod.POST, "/api/v1/bookings", body);
    }

    private ResponseEntity<Map> approve(String bookingId) {
        return http.call(admin, HttpMethod.POST, "/api/v1/bookings/" + bookingId + "/approve", Map.of());
    }

    /** An APPROVED booking written straight to the table — history the HTTP door would refuse as past-dated. */
    private void approvedBooking(Unit unit, User renter, LocalDate from, LocalDate to) {
        BookingRequest b = new BookingRequest();
        b.setTenantId(fx.tenantId());
        b.setPropertyId(bay.getPropertyId());
        b.setResourceType(BookingResourceType.PARKING_SPOT);
        b.setParkingSpotId(bay.getId());
        b.setUnitId(unit.getId());
        b.setRenterUserId(renter.getId());
        b.setPreferredDate(from);
        b.setPreferredEndDate(to);
        b.setStatus(BookingRequestStatus.APPROVED);
        bookingRepo.saveAndFlush(b);
    }

    private static String code(ResponseEntity<Map> res) {
        Object c = res.getBody() == null ? null : res.getBody().get("code");
        return c == null ? null : c.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(ResponseEntity<Map> res, String key) {
        return (List<Map<String, Object>>) res.getBody().get(key);
    }

    private static List<Object> ids(List<Map<String, Object>> rows) {
        return rows.stream().map(m -> m.get("id")).toList();
    }

    // ---------------------------------------------------------------- attach / detach

    @Test
    void aManagerAttachesAndDetachesABay() {
        ResponseEntity<Map> attached = attach(admin, bay.getId(), unitOne.getId());
        assertThat(attached.getStatusCode().value()).isEqualTo(200);
        assertThat(attached.getBody().get("attachedUnitId")).isEqualTo(unitOne.getId().toString());
        assertThat(attached.getBody().get("attachedUnitNumber")).isEqualTo("101");
        assertThat(attached.getBody().get("attachedBuildingName")).isEqualTo("Tower A");

        ResponseEntity<Map> page = http.call(admin, HttpMethod.GET,
                "/api/v1/parking-spots?propertyId=" + bay.getPropertyId(), null);
        assertThat(list(page, "content")).singleElement()
                .satisfies(row -> assertThat(row.get("attachedUnitNumber")).isEqualTo("101"));

        ResponseEntity<Map> summary = http.call(admin, HttpMethod.GET,
                "/api/v1/parking-spots/summary?propertyId=" + bay.getPropertyId(), null);
        assertThat(((Number) summary.getBody().get("attached")).longValue()).isEqualTo(1);
        assertThat(((Number) summary.getBody().get("free")).longValue()).isZero();

        ResponseEntity<Map> detached = attach(admin, bay.getId(), null);
        assertThat(detached.getStatusCode().value()).isEqualTo(200);
        assertThat(detached.getBody().get("attachedUnitId")).isNull();
        assertThat(spotRepo.findById(bay.getId()).orElseThrow().getAttachedUnitId()).isNull();
    }

    @Test
    void aBayCanBeAttachedWhenItIsAdded_andARefusedUnitCreatesNothing() {
        Map<String, Object> body = new HashMap<>();
        body.put("propertyId", bay.getPropertyId().toString());
        body.put("spotNumber", "P-NEW");
        body.put("attachedUnitId", unitTwo.getId().toString());
        ResponseEntity<Map> created = http.call(admin, HttpMethod.POST, "/api/v1/parking-spots", body);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getBody().get("attachedUnitId")).isEqualTo(unitTwo.getId().toString());
        assertThat(created.getBody().get("attachedUnitNumber")).isEqualTo("102");

        Map<String, Object> bulk = new HashMap<>();
        bulk.put("propertyId", bay.getPropertyId().toString());
        bulk.put("spotNumbers", List.of("P-B1", "P-B2"));
        bulk.put("attachedUnitId", unitOne.getId().toString());
        ResponseEntity<List> bulkRes = http.request(admin, HttpMethod.POST, "/api/v1/parking-spots/bulk")
                .contentType(MediaType.APPLICATION_JSON).body(bulk)
                .retrieve().onStatus(s -> true, (req, r) -> { }).toEntity(List.class);
        assertThat(bulkRes.getStatusCode().value()).isEqualTo(201);
        assertThat(bulkRes.getBody()).hasSize(2).allSatisfy(row ->
                assertThat(((Map<?, ?>) row).get("attachedUnitNumber")).isEqualTo("101"));

        // Scoped to Tower A, attached to Tower B's unit: refused, and no bay is left behind.
        Map<String, Object> refused = new HashMap<>();
        refused.put("propertyId", bay.getPropertyId().toString());
        refused.put("spotNumber", "P-BAD");
        refused.put("buildingIds", List.of(unitOne.getBuilding().getId().toString()));
        refused.put("attachedUnitId", otherTowerUnit.getId().toString());
        ResponseEntity<Map> res = http.call(admin, HttpMethod.POST, "/api/v1/parking-spots", refused);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(code(res)).isEqualTo("parking.attachUnitOutsideBay");
        assertThat(spotRepo.findAll().stream().filter(s -> fx.tenantId().equals(s.getTenantId()))
                .map(ParkingSpot::getSpotNumber).toList())
                .contains("P-NEW", "P-B1", "P-B2").doesNotContain("P-BAD");
        assertThat(spotRepo.findAll().stream().filter(s -> List.of("P-B1", "P-B2").contains(s.getSpotNumber())
                        && fx.tenantId().equals(s.getTenantId())))
                .allSatisfy(s -> assertThat(s.getAttachedUnitId()).isEqualTo(unitOne.getId()));
    }

    @Test
    void anEditMovesTowersAndUnitTogether_andARefusedEditChangesNothing() {
        UUID towerA = unitOne.getBuilding().getId();
        UUID towerB = otherTowerUnit.getBuilding().getId();
        assertThat(attach(admin, bay.getId(), unitOne.getId()).getStatusCode().value()).isEqualTo(200);

        // Tower A → Tower B and unit 101 → 201 in one edit: neither step alone would be allowed.
        Map<String, Object> move = new HashMap<>();
        move.put("buildingIds", List.of(towerB.toString()));
        move.put("setAttachment", true);
        move.put("attachedUnitId", otherTowerUnit.getId().toString());
        ResponseEntity<Map> moved = http.call(admin, HttpMethod.PUT, "/api/v1/parking-spots/" + bay.getId(), move);
        assertThat(moved.getStatusCode().value()).isEqualTo(200);
        assertThat(moved.getBody().get("attachedUnitId")).isEqualTo(otherTowerUnit.getId().toString());

        // Rename + back to 101 while keeping Tower B: the attach is refused, and the whole
        // edit with it — the bay keeps its number, its tower and unit 201 (no detached gap).
        Map<String, Object> bad = new HashMap<>();
        bad.put("spotNumber", "P-RENAMED");
        bad.put("buildingIds", List.of(towerB.toString()));
        bad.put("setAttachment", true);
        bad.put("attachedUnitId", unitOne.getId().toString());
        ResponseEntity<Map> refused = http.call(admin, HttpMethod.PUT, "/api/v1/parking-spots/" + bay.getId(), bad);
        assertThat(refused.getStatusCode().value()).isEqualTo(400);
        assertThat(code(refused)).isEqualTo("parking.attachUnitOutsideBay");

        ParkingSpot after = spotRepo.findById(bay.getId()).orElseThrow();
        assertThat(after.getAttachedUnitId()).isEqualTo(otherTowerUnit.getId());
        assertThat(after.getSpotNumber()).isEqualTo(bay.getSpotNumber());
        assertThat(jdbc.queryForList("select building_id from parking_spot_building_scopes where parking_spot_id = ?",
                UUID.class, bay.getId())).containsExactly(towerB);

        // setAttachment with a null unit detaches; without the flag the attachment is left alone.
        Map<String, Object> detach = new HashMap<>();
        detach.put("setAttachment", true);
        detach.put("attachedUnitId", null);
        assertThat(http.call(admin, HttpMethod.PUT, "/api/v1/parking-spots/" + bay.getId(), Map.of("level", "B2"))
                .getBody().get("attachedUnitId")).isEqualTo(otherTowerUnit.getId().toString());
        assertThat(http.call(admin, HttpMethod.PUT, "/api/v1/parking-spots/" + bay.getId(), detach)
                .getBody().get("attachedUnitId")).isNull();
        assertThat(towerA).isNotEqualTo(towerB);
    }

    @Test
    void aUnitOutsideTheBaysTowersIsRefused() {
        ResponseEntity<Map> res = attach(admin, bay.getId(), otherTowerUnit.getId());
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(code(res)).isEqualTo("parking.attachUnitOutsideBay");
        assertThat(spotRepo.findById(bay.getId()).orElseThrow().getAttachedUnitId()).isNull();
    }

    @Test
    void aBayWithAPendingOrApprovedBookingReachingTodayCannotBeAttached() {
        ResponseEntity<Map> pending = requestBay(renterTwo, unitTwo, today.plusDays(5), today.plusDays(10));
        assertThat(pending.getStatusCode().value()).isEqualTo(201);

        ResponseEntity<Map> res = attach(admin, bay.getId(), unitOne.getId());
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(code(res)).isEqualTo("parking.attachHasBookings");

        // Rejected: nothing reaches today any more, and an ENDED approved booking never blocks.
        http.call(admin, HttpMethod.POST, "/api/v1/bookings/" + pending.getBody().get("id") + "/reject", Map.of());
        approvedBooking(unitTwo, renterTwo, today.minusDays(20), today.minusDays(1));
        assertThat(attach(admin, bay.getId(), unitOne.getId()).getStatusCode().value()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- renters

    @Test
    void theUnitsRenterSeesTheBayAsAllocatedAndNobodyCanBookIt() {
        assertThat(attach(admin, bay.getId(), unitOne.getId()).getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Map> mine = http.call(renterOne, HttpMethod.GET, "/api/v1/facilities/my", null);
        assertThat(mine.getStatusCode().value()).isEqualTo(200);
        assertThat(list(mine, "allocatedParking")).singleElement().satisfies(a -> {
            assertThat(a.get("id")).isEqualTo(bay.getId().toString());
            assertThat(a.get("unitNumber")).isEqualTo("101");
            assertThat(a.get("spotNumber")).isEqualTo("P-1");
        });
        assertThat(ids(list(mine, "parkingSpots"))).doesNotContain(bay.getId().toString());

        // The other renter of the same property sees neither the allocation nor the bay.
        ResponseEntity<Map> theirs = http.call(renterTwo, HttpMethod.GET, "/api/v1/facilities/my", null);
        assertThat(list(theirs, "allocatedParking")).isEmpty();
        assertThat(ids(list(theirs, "parkingSpots"))).doesNotContain(bay.getId().toString());

        // And the server refuses the request, whoever asks — not only the UI.
        ResponseEntity<Map> refused = requestBay(renterTwo, unitTwo, today.plusDays(1), today.plusDays(3));
        assertThat(refused.getStatusCode().value()).isEqualTo(400);
        assertThat(code(refused)).isEqualTo("booking.spotAttached");
        ResponseEntity<Map> own = requestBay(renterOne, unitOne, today.plusDays(1), today.plusDays(3));
        assertThat(code(own)).isEqualTo("booking.spotAttached");
        assertThat(bookingRepo.findByParkingSpotIdAndStatusInOrderByCreatedAtAsc(bay.getId(),
                List.of(BookingRequestStatus.PENDING, BookingRequestStatus.APPROVED))).isEmpty();
    }

    // ---------------------------------------------------------------- dates

    @Test
    void anUnattachedBayIsHeldOnlyForItsBookedDates() {
        // Ended: frees the bay (the old rule held it until someone released it).
        approvedBooking(unitTwo, renterTwo, today.minusDays(60), today.minusDays(31));
        ResponseEntity<Map> first = requestBay(renterOne, unitOne, today.plusDays(1), today.plusDays(10));
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(approve((String) first.getBody().get("id")).getStatusCode().value()).isEqualTo(200);

        // Overlapping: refused.
        ResponseEntity<Map> clash = requestBay(renterTwo, unitTwo, today.plusDays(5), today.plusDays(15));
        assertThat(clash.getStatusCode().value()).isEqualTo(409);
        assertThat(code(clash)).isEqualTo("booking.spotTaken");

        // Not overlapping: allowed, and approved alongside the first.
        ResponseEntity<Map> later = requestBay(renterTwo, unitTwo, today.plusDays(11), today.plusDays(20));
        assertThat(later.getStatusCode().value()).isEqualTo(201);
        assertThat(approve((String) later.getBody().get("id")).getStatusCode().value()).isEqualTo(200);

        // The renter sees the taken dates (not who took them); the bay is not held today.
        ResponseEntity<Map> mine = http.call(renterOne, HttpMethod.GET, "/api/v1/facilities/my", null);
        Map<String, Object> row = list(mine, "parkingSpots").stream()
                .filter(m -> bay.getId().toString().equals(m.get("id"))).findFirst().orElseThrow();
        assertThat(row.get("held")).isEqualTo(false);
        assertThat((List<?>) row.get("bookedRanges")).hasSize(2);
    }

    @Test
    void twoOverlappingPendingRequestsCannotBothBeApproved() {
        ResponseEntity<Map> a = requestBay(renterOne, unitOne, today.plusDays(1), today.plusDays(10));
        ResponseEntity<Map> b = requestBay(renterTwo, unitTwo, today.plusDays(8), today.plusDays(12));
        assertThat(a.getStatusCode().value()).isEqualTo(201);
        assertThat(b.getStatusCode().value()).isEqualTo(201);

        assertThat(approve((String) a.getBody().get("id")).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map> second = approve((String) b.getBody().get("id"));
        assertThat(second.getStatusCode().value()).isEqualTo(409);
        assertThat(code(second)).isEqualTo("booking.spotTaken");
    }

    @Test
    void theDatabaseRefusesOverlappingApprovedBookingsOfOneBay() {
        approvedBooking(unitOne, renterOne, today.plusDays(1), today.plusDays(10));
        assertThatThrownBy(() -> approvedBooking(unitTwo, renterTwo, today.plusDays(10), today.plusDays(12)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ex_booking_spot_no_overlap");
        // Back to back is not an overlap.
        approvedBooking(unitTwo, renterTwo, today.plusDays(11), today.plusDays(12));
    }

    // ---------------------------------------------------------------- tenant isolation (P0)

    @Test
    void anotherOrganisationCanNeitherAttachToOurUnitsNorSeeOrMoveOurBays() {
        UUID tenantB = http.tenant("Park-XT-");
        Property pb = http.property("Their Tower");
        ParkingSpot theirBay = new ParkingSpot();
        theirBay.setTenantId(tenantB);
        theirBay.setPropertyId(pb.getId());
        theirBay.setSpotNumber("X-1");
        theirBay = spotRepo.save(theirBay);
        User adminB = http.admin(tenantB);
        TenantContextHolder.clear();

        // Our unit, from their bay: the unit is not theirs to name.
        assertThat(attach(adminB, theirBay.getId(), unitOne.getId()).getStatusCode().value()).isEqualTo(404);
        // Our bay, from their org: not found, and untouched.
        assertThat(attach(adminB, bay.getId(), null).getStatusCode().value()).isEqualTo(404);
        assertThat(attach(adminB, bay.getId(), unitOne.getId()).getStatusCode().value()).isEqualTo(404);
        // Their list of our property shows nothing of ours.
        ResponseEntity<Map> page = http.call(adminB, HttpMethod.GET,
                "/api/v1/parking-spots?propertyId=" + bay.getPropertyId(), null);
        assertThat(list(page, "content")).isEmpty();

        // And the database itself refuses a cross-organisation attachment.
        UUID theirBayId = theirBay.getId();
        assertThatThrownBy(() -> jdbc.update("update parking_spots set attached_unit_id = ? where id = ?",
                unitOne.getId(), theirBayId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_spot_attached_unit");
        assertThat(spotRepo.findById(theirBayId).orElseThrow().getAttachedUnitId()).isNull();
    }
}
