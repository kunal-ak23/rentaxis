package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.ParkingSpot;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.User;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/** GET /bookings/quote: the server's fee quote for a bay and a date range, VAT as on approval. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BookingQuoteControllerIT extends AbstractPostgresIT {

    @LocalServerPort int port;

    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired ParkingSpotRepository spotRepo;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired PropertyService propertyService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LeaseService leaseService;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired LeasePostingService posting;

    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Dubai"));

    private CrossTenantHttp http;
    private LeaseTestFixtures fx;
    private User admin;
    private User renterUser;
    private User vatRenterUser;
    private UUID vatUnitId;
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
        Renter r = fx.renter();
        renterUser = userRepo.findById(r.getUserId()).orElseThrow();
        LocalDate start = today.minusDays(30);
        fx.postedLease(fx.unit(), r, start.minusDays(5), start, today.plusDays(300),
                List.of(line("RENT", "60000")), 1, LeaseTestFixtures.nextChequeNumber());
        Renter vatRenter = fx.createRenter("Vat Renter");
        vatRenterUser = userRepo.findById(vatRenter.getUserId()).orElseThrow();
        com.datagami.rentaxis.domain.entity.Unit vatUnit = fx.createUnit(p, "102");
        vatUnitId = vatUnit.getId();
        fx.postedLease(vatUnit, vatRenter, start.minusDays(5), start, today.plusDays(300),
                List.of(LeaseTestFixtures.vatLine("RENT", "60000")), 1, LeaseTestFixtures.nextChequeNumber());
        bay = bay(p.getId(), "P-1");
        admin = http.admin(t);
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private ParkingSpot bay(UUID propertyId, String number) {
        ParkingSpot s = new ParkingSpot();
        s.setTenantId(fx.tenantId());
        s.setPropertyId(propertyId);
        s.setSpotNumber(number);
        s.setFeeType("PER_MONTH");
        s.setFeeAmount(new BigDecimal("300"));
        return spotRepo.save(s);
    }

    private static String path(ParkingSpot spot) {
        return "/api/v1/bookings/quote?resourceType=PARKING_SPOT&resourceId=" + spot.getId()
                + "&start=2026-12-01&end=2027-01-31";
    }

    private static BigDecimal num(ResponseEntity<Map> res, String key) {
        return new BigDecimal(String.valueOf(res.getBody().get(key)));
    }

    @Test
    void staffGetAMonthlyQuoteWithFivePercentVat() {
        ResponseEntity<Map> res = http.call(admin, HttpMethod.GET, path(bay), null);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("feeType")).isEqualTo("PER_MONTH");
        assertThat(num(res, "unitAmount")).isEqualByComparingTo("300");
        assertThat(num(res, "net")).isEqualByComparingTo("600.00");
        assertThat(num(res, "vat")).isEqualByComparingTo("30.00");
        assertThat(num(res, "total")).isEqualByComparingTo("630.00");
    }

    /** Final review m4: staff booking for a renter see the VAT that renter's lease will be charged. */
    @Test
    void staffQuotingForARenterGetVatPerThatRentersLease() {
        ResponseEntity<Map> noVat = http.call(admin, HttpMethod.GET, path(bay) + "&unitId=" + fx.unit().getId()
                + "&renterUserId=" + renterUser.getId(), null);
        assertThat(noVat.getStatusCode().value()).isEqualTo(200);
        assertThat(num(noVat, "vat")).isEqualByComparingTo("0");
        assertThat(num(noVat, "total")).isEqualByComparingTo("600.00");

        ResponseEntity<Map> withVat = http.call(admin, HttpMethod.GET, path(bay) + "&unitId=" + vatUnitId
                + "&renterUserId=" + vatRenterUser.getId(), null);
        assertThat(num(withVat, "vat")).isEqualByComparingTo("30.00");

        // A renter with no live lease on that unit: the standard rate (the booking itself is refused).
        ResponseEntity<Map> mismatch = http.call(admin, HttpMethod.GET, path(bay) + "&unitId=" + vatUnitId
                + "&renterUserId=" + renterUser.getId(), null);
        assertThat(num(mismatch, "vat")).isEqualByComparingTo("30.00");
    }

    @Test
    void aRenterOnALeaseWithoutVatGetsNoVat() {
        ResponseEntity<Map> res = http.call(renterUser, HttpMethod.GET, path(bay), null);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(num(res, "net")).isEqualByComparingTo("600.00");
        assertThat(num(res, "vat")).isEqualByComparingTo("0");
        assertThat(num(res, "total")).isEqualByComparingTo("600.00");
    }

    @Test
    void aRenterOnAVatLeaseGetsFivePercentVat() {
        ResponseEntity<Map> res = http.call(vatRenterUser, HttpMethod.GET, path(bay), null);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(num(res, "net")).isEqualByComparingTo("600.00");
        assertThat(num(res, "vat")).isEqualByComparingTo("30.00");
        assertThat(num(res, "total")).isEqualByComparingTo("630.00");
    }

    @Test
    void aRenterCannotQuoteAnotherPropertysBay() {
        TenantContextHolder.setTenantId(fx.tenantId());
        Property other = http.property("Other");
        TenantContextHolder.clear();
        ParkingSpot far = bay(other.getId(), "X-1");
        assertThat(http.call(renterUser, HttpMethod.GET, path(far), null).getStatusCode().value()).isEqualTo(404);
        // Staff of the tenant still can.
        assertThat(http.call(admin, HttpMethod.GET, path(far), null).getStatusCode().value()).isEqualTo(200);
    }
    // ---- PR #468 review I1: the quote's dates are bounded before anything is priced

    private String quoteFor(ParkingSpot spot, String start, String end) {
        return "/api/v1/bookings/quote?resourceType=PARKING_SPOT&resourceId=" + spot.getId()
                + "&start=" + start + "&end=" + end;
    }

    @Test
    void aFarOffOrTooLongRangeIsRefused() {
        for (User caller : List.of(admin, renterUser)) {
            ResponseEntity<Map> far = http.call(caller, HttpMethod.GET, quoteFor(bay, "2026-12-01", "9999-12-31"), null);
            assertThat(far.getStatusCode().value()).isEqualTo(400);
            assertThat(far.getBody().get("code")).isEqualTo("booking.rangeTooLong");
            ResponseEntity<Map> tooLong = http.call(caller, HttpMethod.GET, quoteFor(bay, "2026-12-01", "2028-12-02"), null);
            assertThat(tooLong.getStatusCode().value()).isEqualTo(400);
            assertThat(tooLong.getBody().get("code")).isEqualTo("booking.rangeTooLong");
        }
        assertThat(http.call(admin, HttpMethod.GET, quoteFor(bay, "2026-12-01", "2028-12-01"), null)
                .getStatusCode().value()).as("exactly the cap").isEqualTo(200);
    }

    @Test
    void anEndBeforeTheStartIsRefused() {
        assertThat(http.call(admin, HttpMethod.GET, quoteFor(bay, "2026-12-10", "2026-12-01"), null)
                .getStatusCode().value()).isEqualTo(400);
    }

    // ---- PR #468 review m8: who may book on behalf of a renter

    private Map<String, Object> onBehalfBody() {
        return Map.of("renterUserId", renterUser.getId().toString(),
                "booking", Map.of("resourceType", "PARKING_SPOT", "resourceId", bay.getId().toString(),
                        "unitId", fx.unit().getId().toString(),
                        "preferredDate", today.plusDays(3).toString(), "preferredEndDate", today.plusDays(40).toString()));
    }

    @Test
    void aRenterCannotBookOnBehalf() {
        assertThat(http.call(renterUser, HttpMethod.POST, "/api/v1/bookings/on-behalf", onBehalfBody())
                .getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void aManagerOfAnotherPropertyCannotBookOnBehalf() {
        User pm = http.user(fx.tenantId(), com.datagami.rentaxis.domain.entity.enums.UserRole.PROPERTY_MANAGER);
        assertThat(http.call(pm, HttpMethod.POST, "/api/v1/bookings/on-behalf", onBehalfBody())
                .getStatusCode().value()).isEqualTo(404);
    }
}
