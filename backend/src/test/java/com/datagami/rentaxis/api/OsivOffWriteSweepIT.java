package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Building;
import com.datagami.rentaxis.domain.entity.GatePass;
import com.datagami.rentaxis.domain.entity.GuardPropertyAssignment;
import com.datagami.rentaxis.domain.entity.MaintenanceTicket;
import com.datagami.rentaxis.domain.entity.ParkingSpot;
import com.datagami.rentaxis.domain.entity.PropertyAmenity;
import com.datagami.rentaxis.domain.entity.RenewalOpportunity;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.UnitListing;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.enums.GatePassOrigin;
import com.datagami.rentaxis.domain.entity.enums.GatePassStatus;
import com.datagami.rentaxis.domain.entity.enums.GatePassType;
import com.datagami.rentaxis.domain.entity.enums.GateVisitorType;
import com.datagami.rentaxis.domain.entity.enums.ListingStatus;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.domain.entity.enums.TicketCategory;
import com.datagami.rentaxis.domain.entity.enums.TicketPriority;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.BuildingRepository;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.GatePassRepository;
import com.datagami.rentaxis.domain.repository.GuardPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.MaintenanceTicketRepository;
import com.datagami.rentaxis.domain.repository.ParkingSpotRepository;
import com.datagami.rentaxis.domain.repository.PropertyAmenityRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RenewalOpportunityRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitListingRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The writes' half of {@link OsivOffRouteSweepIT}: with open-session-in-view off, a handler
 * that loads an entity outside a transaction and hands it on (or maps it after the service's
 * transaction ended) fails with a LazyInitializationException — a 500 the renter sees as
 * "could not book" (sim4y S16-05: every amenity and parking booking failed on prod).
 *
 * <p>Seeds one organisation with a posted contract, a bookable amenity, a parking bay, a
 * gate pass, a ticket, a published listing and a renewal opportunity, then drives the
 * renter-, guard- and manager-facing mutating routes with valid bodies over real HTTP
 * (no test transaction around the request). Every call is checked on the log (any
 * "could not initialize proxy" / "no session" / LazyInitializationException fails it,
 * naming the route) and on the status (no 5xx); the routes a renter must be able to use
 * are also held to their success status.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class OsivOffWriteSweepIT extends AbstractPostgresIT {

    @LocalServerPort int port;

    @Autowired Environment env;
    @Autowired LeaseService leaseService;
    @Autowired ChequeGenerationService generation;
    @Autowired LeasePostingService posting;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired UserPropertyAssignmentRepository assignmentRepo;
    @Autowired GuardPropertyAssignmentRepository guardAssignmentRepo;
    @Autowired MaintenanceTicketRepository ticketRepo;
    @Autowired BuildingRepository buildingRepo;
    @Autowired PropertyAmenityRepository amenityRepo;
    @Autowired ParkingSpotRepository spotRepo;
    @Autowired GatePassRepository passRepo;
    @Autowired UnitListingRepository listingRepo;
    @Autowired ChequeRepository chequeRepo;
    @Autowired LeaseRepository leaseRepo;
    @Autowired RenewalOpportunityRepository opportunityRepo;
    @Autowired TenantFeatureService featureService;
    @Autowired TransactionTemplate tx;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.datagami.rentaxis.core.service.BlobStorageService blob;

    private static final java.time.ZoneId DUBAI = java.time.ZoneId.of("Asia/Dubai");
    private final ObjectMapper json = new ObjectMapper();
    private LeaseTestFixtures f;
    private User admin;
    private User pm;
    private User guard;
    private User renterUser;
    private UUID unitId;
    private UUID leaseId;
    private UUID amenityId;
    private UUID spotId;
    private UUID ticketId;
    private UUID listingId;
    private UUID opportunityId;
    private UUID chequeId;
    private GatePass pass;
    private UUID pendingPassId;

    private final List<String> lazy = new ArrayList<>();
    private final List<String> fiveXx = new ArrayList<>();
    private final List<String> unexpected = new ArrayList<>();

    @BeforeEach
    void seed() {
        f = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo, propertyService, accountService,
                propertyAccountService, chargeTypeService).bootstrap().withLeaseServices(leaseService, generation, posting);
        UUID t = f.tenantId();
        featureService.setEnabled(t, TenantFeature.LISTINGS, true);

        Building building = new Building();
        building.setTenantId(t);
        building.setProperty(f.property());
        building.setNameEn("Tower A");
        building = buildingRepo.save(building);
        Unit unit = unitRepo.findById(f.unit().getId()).orElseThrow();
        unit.setBuilding(building);
        unitRepo.save(unit);
        unitId = unit.getId();

        Renter renter = f.renter();
        leaseId = f.postedLease(LocalDate.now().minusMonths(2), LocalDate.now().minusMonths(1).withDayOfMonth(1),
                LocalDate.now().minusMonths(1).withDayOfMonth(1).plusYears(1).minusDays(1),
                List.of(line("RENT", "60000")), 4, LeaseTestFixtures.nextChequeNumber()).lease().getId();
        chequeId = tx.execute(s -> chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId).getFirst().getId());

        admin = user(UserRole.TENANT_ADMIN);
        pm = user(UserRole.PROPERTY_MANAGER);
        guard = user(UserRole.SECURITY_GUARD);
        renterUser = userRepo.findById(renter.getUserId()).orElseThrow();
        UserPropertyAssignment a = new UserPropertyAssignment();
        a.setUserId(pm.getId());
        a.setPropertyId(f.property().getId());
        assignmentRepo.save(a);
        GuardPropertyAssignment g = new GuardPropertyAssignment();
        g.setTenantId(t);
        g.setUserId(guard.getId());
        g.setPropertyId(f.property().getId());
        guardAssignmentRepo.save(g);

        PropertyAmenity amenity = new PropertyAmenity();
        amenity.setTenantId(t);
        amenity.setPropertyId(f.property().getId());
        amenity.setNameEn("BBQ Deck");
        amenityId = amenityRepo.save(amenity).getId();
        ParkingSpot spot = new ParkingSpot();
        spot.setTenantId(t);
        spot.setPropertyId(f.property().getId());
        spot.setSpotNumber("P-01");
        spotId = spotRepo.save(spot).getId();

        GatePass p = new GatePass();
        p.setTenantId(t);
        p.setPropertyId(f.property().getId());
        p.setUnitId(unitId);
        p.setCreatedByUserId(renterUser.getId());
        p.setOrigin(GatePassOrigin.RENTER);
        p.setGuestName("Guest");
        p.setGuestPhone("+971501234567");
        p.setVisitorType(GateVisitorType.GUEST);
        p.setPassType(GatePassType.SINGLE_USE);
        p.setValidFrom(Instant.now().minus(5, ChronoUnit.MINUTES));
        p.setValidTo(Instant.now().plus(2, ChronoUnit.HOURS));
        p.setStatus(GatePassStatus.ACTIVE);
        p.setQrToken(UUID.randomUUID().toString().replace("-", "") + "0123456789abcdef");
        p.setNumericCode(String.format("%08d", Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100_000_000L)));
        pass = passRepo.save(p);
        GatePass pending = new GatePass();
        pending.setTenantId(t);
        pending.setPropertyId(f.property().getId());
        pending.setUnitId(unitId);
        pending.setCreatedByUserId(renterUser.getId());
        pending.setOrigin(GatePassOrigin.RENTER);
        pending.setGuestName("Courier");
        pending.setGuestPhone("+971502223344");
        pending.setVisitorType(GateVisitorType.GUEST);
        pending.setPassType(GatePassType.SINGLE_USE);
        pending.setValidFrom(Instant.now().minus(5, ChronoUnit.MINUTES));
        pending.setValidTo(Instant.now().plus(2, ChronoUnit.HOURS));
        pending.setStatus(GatePassStatus.PENDING_APPROVAL);
        pending.setQrToken(UUID.randomUUID().toString().replace("-", "") + "fedcba9876543210");
        pending.setNumericCode(String.format("%08d", Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 100_000_000L)));
        pendingPassId = passRepo.save(pending).getId();

        ticketId = tx.execute(s -> {
            MaintenanceTicket ticket = new MaintenanceTicket();
            ticket.setTenantId(t);
            ticket.setProperty(propertyRepo.findById(f.property().getId()).orElseThrow());
            ticket.setUnit(unitRepo.findById(unitId).orElseThrow());
            ticket.setReportedBy(renterUser.getId());
            ticket.setTitle("Leak");
            ticket.setCategory(TicketCategory.PLUMBING);
            ticket.setPriority(TicketPriority.MEDIUM);
            return ticketRepo.save(ticket).getId();
        });

        UnitListing listing = new UnitListing();
        listing.setTenantId(t);
        listing.setUnitId(f.createUnit(f.property(), "102").getId());
        listing.setStatus(ListingStatus.PUBLISHED);
        listing.setTitleEn("Sunny one-bed");
        listing.setSlug("sunny-one-bed-" + UUID.randomUUID().toString().substring(0, 6));
        listing.setAnnualRent(new BigDecimal("60000"));
        listingId = listingRepo.save(listing).getId();

        opportunityId = tx.execute(s -> {
            RenewalOpportunity o = new RenewalOpportunity();
            o.setTenantId(t);
            o.setLease(leaseRepo.findById(leaseId).orElseThrow());
            return opportunityRepo.save(o).getId();
        });
        org.mockito.Mockito.when(blob.uploadGateVisitor(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(
                new com.datagami.rentaxis.core.service.BlobStorageService.UploadResult("https://blob.test/v.jpg", "v.jpg"));
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    /** S16-05: the renter books an amenity and a parking bay; the manager decides; the renter cancels. */
    @Test
    void aRenterBooksAnAmenityAndAParkingBayWithOsivOff(CapturedOutput out) throws Exception {
        assertThat(env.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
        LocalDate day = LocalDate.now().plusDays(3);
        JsonNode amenityBooking = expect(out, 201, renterUser, HttpMethod.POST, "/api/v1/bookings", Map.of(
                "resourceType", "AMENITY", "resourceId", amenityId, "unitId", unitId,
                "preferredDate", day.toString(), "preferredStartTime", "18:00", "preferredEndTime", "20:00"));
        JsonNode parkingBooking = expect(out, 201, renterUser, HttpMethod.POST, "/api/v1/bookings", Map.of(
                "resourceType", "PARKING_SPOT", "resourceId", spotId, "unitId", unitId,
                "preferredDate", day.toString(), "preferredEndDate", day.plusMonths(1).toString()));
        assertNoFailures();
        assertThat(amenityBooking.get("status").asText()).isEqualTo("PENDING");
        assertThat(amenityBooking.get("unitNumber").asText()).isEqualTo(f.unit().getUnitNumber());
        assertThat(parkingBooking.get("resourceName").asText()).isEqualTo("P-01");

        expect(out, 200, pm, HttpMethod.POST, "/api/v1/bookings/" + parkingBooking.get("id").asText() + "/approve",
                Map.of("adminNote", "ok"));
        expect(out, 200, admin, HttpMethod.POST, "/api/v1/bookings/" + parkingBooking.get("id").asText() + "/release",
                Map.of());
        expect(out, 200, renterUser, HttpMethod.POST, "/api/v1/bookings/" + amenityBooking.get("id").asText() + "/cancel",
                null);
        JsonNode again = expect(out, 201, renterUser, HttpMethod.POST, "/api/v1/bookings", Map.of(
                "resourceType", "AMENITY", "resourceId", amenityId, "unitId", unitId, "preferredDate", day.toString()));
        expect(out, 200, pm, HttpMethod.POST, "/api/v1/bookings/" + again.get("id").asText() + "/reject",
                Map.of("adminNote", "full"));
        assertNoFailures();
    }

    /** Every other renter-, guard- and manager-facing write on the seeded rows. */
    @Test
    void renterGuardAndManagerWritesDoNotLoadALazyAssociationOutsideATransaction(CapturedOutput out) throws Exception {
        String unit = unitId.toString();
        // gate passes: renter creates and cancels, guard scans and decides
        JsonNode created = expect(out, 200, renterUser, HttpMethod.POST, "/api/v1/gatepass", Map.of(
                "unitId", unit, "guestName", "Visitor", "guestPhone", "+971501112233", "passType", "SINGLE_USE",
                "validFrom", Instant.now().toString(), "validTo", Instant.now().plus(3, ChronoUnit.HOURS).toString()));
        expect(out, 200, renterUser, HttpMethod.POST, "/api/v1/gatepass/" + created.get("id").asText() + "/cancel", null);
        expect(out, 200, guard, HttpMethod.POST, "/api/v1/gatepass/scan",
                Map.of("qrToken", pass.getQrToken(), "direction", "ENTRY"));
        call(out, guard, HttpMethod.POST, "/api/v1/gatepass/" + pendingPassId + "/approval", Map.of("approved", true));
        MultiValueMap<String, Object> walkIn = new LinkedMultiValueMap<>();
        walkIn.add("propertyId", f.property().getId().toString());
        walkIn.add("unitId", unit);
        walkIn.add("name", "Walk In");
        walkIn.add("phone", "+971509998877");
        walkIn.add("visitorType", "GUEST");
        walkIn.add("photo", new org.springframework.core.io.ByteArrayResource(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 1, 2}) {
            @Override public String getFilename() { return "visitor.jpg"; }
        });
        int walkInFrom = out.length();
        ResponseEntity<String> w = send(guard, HttpMethod.POST, "/api/v1/gatepass/walk-in", walkIn, MediaType.MULTIPART_FORM_DATA);
        record(out, guard, "POST /api/v1/gatepass/walk-in", w, walkInFrom);
        if (w.getStatusCode().is2xxSuccessful()) {
            String walkInId = json.readTree(w.getBody()).get("id").asText();
            call(out, renterUser, HttpMethod.POST, "/api/v1/gatepass/resident-approvals/" + walkInId, Map.of("approved", true));
            call(out, guard, HttpMethod.POST, "/api/v1/gatepass/walk-in/" + walkInId + "/admit", null);
        }

        // tickets: renter raises, replies and rates; manager works it
        JsonNode ticket = expect(out, 200, renterUser, HttpMethod.POST, "/api/v1/tickets", Map.of(
                "propertyId", f.property().getId(), "unitId", unit, "title", "AC not cooling",
                "description", "Warm air", "category", "HVAC", "priority", "HIGH"));
        expect(out, 200, renterUser, HttpMethod.POST, "/api/v1/tickets/" + ticketId + "/replies", Map.of("message", "Still leaking"));
        expect(out, 200, pm, HttpMethod.POST, "/api/v1/tickets/" + ticketId + "/replies", Map.of("message", "Plumber booked"));
        call(out, pm, HttpMethod.PUT, "/api/v1/tickets/" + ticket.get("id").asText() + "/assign", Map.of("assignTo", pm.getId()));
        call(out, pm, HttpMethod.PUT, "/api/v1/tickets/" + ticket.get("id").asText() + "/status", Map.of("status", "IN_PROGRESS"));
        call(out, pm, HttpMethod.PUT, "/api/v1/tickets/" + ticketId + "/assign", Map.of("assignTo", pm.getId()));
        call(out, pm, HttpMethod.PUT, "/api/v1/tickets/" + ticketId + "/status", Map.of("status", "IN_PROGRESS"));
        call(out, pm, HttpMethod.PUT, "/api/v1/tickets/" + ticketId + "/status", Map.of("status", "RESOLVED"));
        call(out, renterUser, HttpMethod.PUT, "/api/v1/tickets/" + ticketId + "/rate", Map.of("rating", 5, "comment", "ok"));

        // marketplace wishlist and enquiry, renewal intent, meetings, lease accept/reject
        expect(out, 201, renterUser, HttpMethod.POST, "/api/marketplace/listings/" + listingId + "/interest", Map.of("note", "Keen"));
        expect(out, 204, renterUser, HttpMethod.DELETE, "/api/marketplace/listings/" + listingId + "/interest", null);
        expect(out, 200, renterUser, HttpMethod.POST, "/api/v1/me/renewals/" + opportunityId + "/intent", Map.of("intent", "RENEW"));
        JsonNode meeting = expect(out, 201, renterUser, HttpMethod.POST, "/api/v1/meetings", Map.of(
                "type", "OFFICE_VISIT", "purpose", "LEASE_RENEWAL", "slotStart", LocalDate.now(DUBAI).plusDays(2).atTime(10, 0).atZone(DUBAI).toInstant().toString(),
                "hostUserId", pm.getId(), "leaseId", leaseId, "propertyId", f.property().getId(), "unitId", unit));
        call(out, pm, HttpMethod.PUT, "/api/v1/meetings/" + meeting.get("id").asText() + "/approve", null);
        call(out, renterUser, HttpMethod.PUT, "/api/v1/meetings/" + meeting.get("id").asText() + "/cancel", null);
        call(out, renterUser, HttpMethod.PUT, "/api/v1/leases/" + leaseId + "/accept", null);
        call(out, renterUser, HttpMethod.PUT, "/api/v1/leases/" + leaseId + "/reject", null);

        // cheques-only payments and the renter's small writes
        call(out, renterUser, HttpMethod.POST, "/api/v1/online-payments/create-order", Map.of("chequeId", chequeId));
        call(out, renterUser, HttpMethod.POST, "/api/v1/online-payments/cancel/" + chequeId, null);
        call(out, renterUser, HttpMethod.PUT, "/api/v1/notifications/read-all", null);
        call(out, renterUser, HttpMethod.POST, "/api/v1/notifications/devices/register", Map.of("token", "t-" + UUID.randomUUID(), "platform", "ANDROID"));

        // manager side of the same rows
        call(out, pm, HttpMethod.PUT, "/api/listings/" + listingId, Map.of("titleEn", "Sunny one-bed, renovated", "annualRent", 62000));
        call(out, pm, HttpMethod.POST, "/api/listings/" + listingId + "/unlist", null);
        call(out, pm, HttpMethod.POST, "/api/listings/" + listingId + "/publish", null);
        call(out, admin, HttpMethod.POST, "/api/v1/buildings", Map.of("nameEn", "Tower B", "property", Map.of("id", f.property().getId())));
        call(out, admin, HttpMethod.POST, "/api/v1/units", Map.of("unitNumber", "901", "property", Map.of("id", f.property().getId())));
        call(out, admin, HttpMethod.POST, "/api/v1/leases/" + leaseId + "/renewal/mark-renewed", Map.of());
        // the cheque desk (cheques-only collection)
        call(out, admin, HttpMethod.PUT, "/api/v1/cheques/" + chequeId + "/receive", null);
        call(out, admin, HttpMethod.PUT, "/api/v1/cheques/" + chequeId + "/deposit", null);
        call(out, admin, HttpMethod.PUT, "/api/v1/cheques/" + chequeId + "/clear", null);
        assertNoFailures();
    }

    // -------------------------------------------------------------- helpers

    private void assertNoFailures() {
        assertThat(lazy).as("writes that loaded a lazy association outside a transaction (OSIV off)").isEmpty();
        assertThat(fiveXx).as("writes answering 5xx on seeded data").isEmpty();
        assertThat(unexpected).as("writes a renter/guard/manager must be able to make").isEmpty();
    }

    private JsonNode expect(CapturedOutput out, int status, User caller, HttpMethod method, String url, Object body)
            throws Exception {
        ResponseEntity<String> res = call(out, caller, method, url, body);
        if (res.getStatusCode().value() != status) {
            unexpected.add(caller.getRole() + " " + method + " " + url + " -> " + res.getStatusCode().value() + " " + res.getBody());
            assertNoFailures();
        }
        return res.getBody() == null ? json.nullNode() : json.readTree(res.getBody());
    }

    private ResponseEntity<String> call(CapturedOutput out, User caller, HttpMethod method, String url, Object body) {
        int from = out.length();
        ResponseEntity<String> res = send(caller, method, url, body, MediaType.APPLICATION_JSON);
        record(out, caller, method + " " + url, res, from);
        return res;
    }

    private void record(CapturedOutput out, User caller, String route, ResponseEntity<String> res, int from) {
        System.out.println("OsivOffWriteSweepIT: " + caller.getRole() + " " + route + " -> " + res.getStatusCode().value()
                + (res.getStatusCode().value() >= 400 && res.getBody() != null ? " " + res.getBody().substring(0, Math.min(160, res.getBody().length())) : ""));
        String log = out.toString().substring(Math.min(from, out.length()));
        if (log.contains("LazyInitializationException") || log.contains("could not initialize proxy")
                || log.contains("- no session") || log.contains("no Session")) {
            lazy.add(caller.getRole() + " " + route);
        } else if (res.getStatusCode().value() >= 500) {
            fiveXx.add(caller.getRole() + " " + route + " -> " + res.getStatusCode().value() + " " + res.getBody());
        }
    }

    private ResponseEntity<String> send(User caller, HttpMethod method, String url, Object body, MediaType type) {
        RestClient.RequestBodySpec spec = RestClient.builder().baseUrl("http://localhost:" + port).build()
                .method(method).uri(url)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", f.tenantId().toString())
                .header("X-User-Tenant-Id", f.tenantId().toString());
        if (body != null) spec = spec.contentType(type).body(body);
        return spec.retrieve().onStatus(s -> true, (req, r) -> { }).toEntity(String.class);
    }

    private User user(UserRole role) {
        User u = new User();
        u.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@t.io");
        u.setName(role.name());
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(f.tenantId());
        u.setPhoneNumber("+9715" + String.format("%08d", Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 100_000_000L)));
        return userRepo.save(u);
    }
}
