package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.core.service.VendorService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.BookingRequest;
import com.datagami.rentaxis.domain.entity.Building;
import com.datagami.rentaxis.domain.entity.GatePass;
import com.datagami.rentaxis.domain.entity.GuardPropertyAssignment;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.MaintenanceTicket;
import com.datagami.rentaxis.domain.entity.PropertyAmenity;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.TicketReply;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.UnitListing;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.Vendor;
import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;
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
import com.datagami.rentaxis.domain.repository.BookingRequestRepository;
import com.datagami.rentaxis.domain.repository.BuildingRepository;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.GatePassRepository;
import com.datagami.rentaxis.domain.repository.GuardPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.MaintenanceTicketRepository;
import com.datagami.rentaxis.domain.repository.PropertyAmenityRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.TicketReplyRepository;
import com.datagami.rentaxis.domain.repository.UnitListingRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Safety net for open-session-in-view being off (scale PR A, P1-11; PR #366 review P1-1).
 *
 * <p>With OSIV off, an entity mapped or serialised after its transaction has ended can no
 * longer load a lazy association: the request fails with a LazyInitializationException (a
 * 500). The failure only shows on a screen that has data, for the role that reaches it, so
 * this seeds one organisation with every kind of row the screens map — a posted contract
 * with cheques, a draft, a building, an amenity and a booking, a gate pass, a ticket with a
 * reply, a published listing, a vendor — and calls every GET route the application registers
 * whose path variables it can fill from those rows (routes needing a row kind the seed does not
 * create — vouchers, payment runs, staff, meetings, bank statements — are skipped and counted), as TENANT_ADMIN, PROPERTY_MANAGER, ACCOUNTANT, SECURITY_GUARD, RENTER, a
 * SUPER_ADMIN inside the organisation and an anonymous caller. Path variables are filled
 * from the seeded ids (by name, and for {@code {id}} by the path segment before it).</p>
 *
 * <p>The assertion is on the log, not the status: any "could not initialize proxy",
 * "no session" or LazyInitializationException fails it, naming the route and role. Other
 * 5xx answers fail it too (none on the seeded data today); a route that legitimately refuses
 * its call answers 4xx.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class OsivOffRouteSweepIT extends AbstractPostgresIT {

    @LocalServerPort int port;

    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
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
    @Autowired TicketReplyRepository replyRepo;
    @Autowired BuildingRepository buildingRepo;
    @Autowired PropertyAmenityRepository amenityRepo;
    @Autowired BookingRequestRepository bookingRepo;
    @Autowired GatePassRepository passRepo;
    @Autowired UnitListingRepository listingRepo;
    @Autowired ChequeRepository chequeRepo;
    @Autowired TenantFeatureService featureService;
    @Autowired VendorService vendorService;
    @Autowired TransactionTemplate tx;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.datagami.rentaxis.core.service.BlobStorageService blob;

    private LeaseTestFixtures f;
    private final Map<String, String> vars = new HashMap<>();
    private final Map<String, String> idBySegment = new HashMap<>();
    private final Map<String, User> callers = new java.util.LinkedHashMap<>();

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
        Unit second = f.createUnit(f.property(), "102");

        Renter renter = f.renter();
        UUID lease = f.postedLease(LocalDate.now().minusMonths(2), LocalDate.now().minusMonths(1).withDayOfMonth(1),
                LocalDate.now().minusMonths(1).withDayOfMonth(1).plusYears(1).minusDays(1),
                List.of(line("RENT", "60000")), 4, LeaseTestFixtures.nextChequeNumber()).lease().getId();
        UUID draft = f.draftLease(second, f.createRenter("Draft Renter"), LocalDate.now(), LocalDate.now().plusMonths(1),
                LocalDate.now().plusMonths(1).plusYears(1).minusDays(1), List.of(line("RENT", "40000")));

        User admin = user(UserRole.TENANT_ADMIN);
        User pm = user(UserRole.PROPERTY_MANAGER);
        User accountant = user(UserRole.ACCOUNTANT);
        User guard = user(UserRole.SECURITY_GUARD);
        User superAdmin = user(UserRole.SUPER_ADMIN);
        User renterUser = userRepo.findById(renter.getUserId()).orElseThrow();
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
        amenity.setNameEn("Pool");
        amenity = amenityRepo.save(amenity);
        BookingRequest booking = new BookingRequest();
        booking.setTenantId(t);
        booking.setPropertyId(f.property().getId());
        booking.setResourceType(BookingResourceType.AMENITY);
        booking.setAmenityId(amenity.getId());
        booking.setUnitId(unit.getId());
        booking.setRenterUserId(renterUser.getId());
        booking.setPreferredDate(LocalDate.now().plusDays(3));
        booking = bookingRepo.save(booking);

        GatePass pass = new GatePass();
        pass.setTenantId(t);
        pass.setPropertyId(f.property().getId());
        pass.setUnitId(unit.getId());
        pass.setCreatedByUserId(renterUser.getId());
        pass.setOrigin(GatePassOrigin.RENTER);
        pass.setGuestName("Guest");
        pass.setGuestPhone("+971501234567");
        pass.setVisitorType(GateVisitorType.GUEST);
        pass.setPassType(GatePassType.SINGLE_USE);
        pass.setValidFrom(Instant.now());
        pass.setValidTo(Instant.now().plus(2, ChronoUnit.HOURS));
        pass.setStatus(GatePassStatus.ACTIVE);
        pass.setQrToken(UUID.randomUUID().toString().replace("-", "") + "0123456789abcdef");
        pass.setNumericCode(String.format("%08d", Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100_000_000L)));
        pass = passRepo.save(pass);

        UUID ticketId = tx.execute(s -> {
            MaintenanceTicket ticket = new MaintenanceTicket();
            ticket.setTenantId(t);
            ticket.setProperty(propertyRepo.findById(f.property().getId()).orElseThrow());
            ticket.setUnit(unitRepo.findById(unit.getId()).orElseThrow());
            ticket.setReportedBy(renterUser.getId());
            ticket.setTitle("Leak");
            ticket.setCategory(TicketCategory.PLUMBING);
            ticket.setPriority(TicketPriority.MEDIUM);
            ticket = ticketRepo.save(ticket);
            TicketReply reply = new TicketReply();
            reply.setTenantId(t);
            reply.setTicket(ticket);
            reply.setUserId(admin.getId());
            reply.setUserName("Admin");
            reply.setMessage("On it");
            replyRepo.save(reply);
            return ticket.getId();
        });

        UnitListing listing = new UnitListing();
        listing.setTenantId(t);
        listing.setUnitId(unit.getId());
        listing.setStatus(ListingStatus.PUBLISHED);
        listing.setTitleEn("Sunny one-bed");
        listing.setSlug("sunny-one-bed-" + UUID.randomUUID().toString().substring(0, 6));
        listing.setAnnualRent(new BigDecimal("60000"));
        listing = listingRepo.save(listing);

        Vendor v = new Vendor();
        v.setNameEn("Sweep Vendor");
        v = vendorService.createVendor(v);

        LandlordOrg org = orgRepo.findById(t).orElseThrow();
        String cheque = tx.execute(s -> chequeRepo.findByLease_IdOrderBySeqNoAsc(lease).getFirst().getId().toString());

        vars.put("propertyId", f.property().getId().toString());
        vars.put("unitId", unit.getId().toString());
        vars.put("leaseId", lease.toString());
        vars.put("renterId", renter.getId().toString());
        vars.put("vendorId", v.getId().toString());
        vars.put("userId", pm.getId().toString());
        vars.put("tenantSlug", org.getSlug());
        vars.put("slug", listing.getSlug());
        vars.put("unitSlug", listing.getSlug());
        vars.put("accountId", v.getPayableAccount() == null ? UUID.randomUUID().toString() : v.getPayableAccount().getId().toString());
        vars.put("type", "ASSET");
        vars.put("code", "A-01");
        vars.put("fy", String.valueOf(LocalDate.now().getYear()));

        idBySegment.put("leases", lease.toString());
        idBySegment.put("drafts", draft.toString());
        idBySegment.put("properties", f.property().getId().toString());
        idBySegment.put("units", unit.getId().toString());
        idBySegment.put("renters", renter.getId().toString());
        idBySegment.put("buildings", building.getId().toString());
        idBySegment.put("tickets", ticketId.toString());
        idBySegment.put("bookings", booking.getId().toString());
        idBySegment.put("gatepass", pass.getId().toString());
        idBySegment.put("listings", listing.getId().toString());
        idBySegment.put("cheques", cheque);
        idBySegment.put("vendors", v.getId().toString());
        idBySegment.put("amenities", amenity.getId().toString());
        idBySegment.put("tenants", t.toString());
        idBySegment.put("users", pm.getId().toString());
        if (v.getPayableAccount() != null) idBySegment.put("accounts", v.getPayableAccount().getId().toString());

        callers.put("TENANT_ADMIN", admin);
        callers.put("PROPERTY_MANAGER", pm);
        callers.put("ACCOUNTANT", accountant);
        callers.put("SECURITY_GUARD", guard);
        callers.put("RENTER", renterUser);
        callers.put("SUPER_ADMIN", superAdmin);
        callers.put("ANONYMOUS", null);
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    @Test
    void noGetRouteFailsToLoadALazyAssociationForAnyRole(CapturedOutput output) {
        assertThat(env.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
        List<String> routes = getRoutes();
        assertThat(routes).hasSizeGreaterThan(150);

        List<String> lazy = new ArrayList<>();
        TreeSet<String> other5xx = new TreeSet<>();
        int called = 0;
        int skipped = 0;
        for (String route : routes) {
            String url = fill(route);
            if (url == null) {
                skipped++;
                continue;
            }
            for (Map.Entry<String, User> caller : callers.entrySet()) {
                int from = output.length();
                int status = get(caller.getValue(), url);
                called++;
                String log = output.toString().substring(from);
                if (log.contains("LazyInitializationException") || log.contains("could not initialize proxy")
                        || log.contains("- no session") || log.contains("no Session")) {
                    lazy.add(caller.getKey() + " GET " + url);
                } else if (status >= 500) {
                    other5xx.add(caller.getKey() + " " + status + " GET " + url);
                }
            }
        }
        System.out.println("OsivOffRouteSweepIT: " + routes.size() + " GET routes (" + skipped
                + " skipped, no seeded row for a path variable), " + called + " calls");
        assertThat(lazy).as("routes that failed to load a lazy association with open-session-in-view off").isEmpty();
        assertThat(other5xx).as("routes answering 5xx on seeded data").isEmpty();
    }

    /**
     * The three routes PR #366's review found failing with OSIV off, answered with their
     * data: the renter's facilities (unit → property), the marketplace listing cards
     * (unit → property) and the public listing page (unit → building / property).
     */
    @Test
    void theRoutesTheReviewFoundAnswerWithTheirLazyFieldsFilled() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        var facilities = json.readTree(body(callers.get("RENTER"), "/api/v1/facilities/my"));
        assertThat(facilities.get("amenities").get(0).get("propertyName").asText()).isEqualTo(f.property().getNameEn());

        var cards = json.readTree(body(callers.get("RENTER"), "/api/marketplace/" + vars.get("tenantSlug") + "/listings"));
        assertThat(cards.get("content").get(0).get("propertyName").asText()).isEqualTo(f.property().getNameEn());

        var page = json.readTree(body(null, "/public/l/" + vars.get("tenantSlug") + "/" + vars.get("slug")));
        assertThat(page.get("buildingName").asText()).isEqualTo("Tower A");
        var list = json.readTree(body(null, "/public/l/" + vars.get("tenantSlug")));
        assertThat(list.get("content").get(0).get("buildingName").asText()).isEqualTo("Tower A");
    }

    private String body(User caller, String url) {
        RestClient.RequestHeadersSpec<?> spec = RestClient.builder().baseUrl("http://localhost:" + port).build()
                .method(HttpMethod.GET).uri(url);
        if (caller != null) {
            spec = spec.header("X-User-Id", caller.getId().toString())
                    .header("X-User-Role", caller.getRole().name())
                    .header("X-Tenant-Id", f.tenantId().toString())
                    .header("X-User-Tenant-Id", f.tenantId().toString());
        }
        ResponseEntity<String> res = spec.retrieve().onStatus(s -> true, (req, r) -> { }).toEntity(String.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).as("%s -> %s %s", url, res.getStatusCode(), res.getBody()).isTrue();
        return res.getBody();
    }

    private List<String> getRoutes() {
        List<String> out = new ArrayList<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            var methods = info.getMethodsCondition().getMethods();
            if (!methods.isEmpty() && !methods.contains(RequestMethod.GET)) continue;
            if (info.getPathPatternsCondition() == null) continue;
            for (String p : info.getPathPatternsCondition().getPatternValues()) {
                if (p.startsWith("/api/") || p.startsWith("/public/")) out.add(p);
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    private static final Pattern VAR = Pattern.compile("\\{([^}:]+)(?::[^}]*)?}");

    /** The route with its variables filled from the seed, or null when one is unknown. */
    private String fill(String route) {
        Matcher m = VAR.matcher(route);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String value = vars.get(name);
            if (value == null && name.equals("id")) {
                String[] parts = route.substring(0, m.start()).split("/");
                value = idBySegment.get(parts[parts.length - 1]);
            }
            if (value == null) return null;
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private int get(User caller, String url) {
        RestClient.RequestHeadersSpec<?> spec = RestClient.builder().baseUrl("http://localhost:" + port).build()
                .method(HttpMethod.GET).uri(url);
        if (caller != null) {
            spec = spec.header("X-User-Id", caller.getId().toString())
                    .header("X-User-Role", caller.getRole().name())
                    .header("X-Tenant-Id", f.tenantId().toString())
                    .header("X-User-Tenant-Id", f.tenantId().toString());
        }
        try {
            ResponseEntity<byte[]> res = spec.retrieve().onStatus(s -> true, (req, r) -> { }).toEntity(byte[].class);
            return res.getStatusCode().value();
        } catch (RuntimeException e) {
            return 599;
        }
    }

    private User user(UserRole role) {
        User u = new User();
        u.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@t.io");
        u.setName(role.name());
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(role == UserRole.SUPER_ADMIN ? null : f.tenantId());
        u.setPhoneNumber("+9715" + String.format("%08d", Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 100_000_000L)));
        return userRepo.save(u);
    }
}
