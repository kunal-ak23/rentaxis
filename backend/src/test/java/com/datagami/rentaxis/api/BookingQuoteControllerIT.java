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

    @Test
    void aRenterOnALeaseWithoutVatGetsNoVat() {
        ResponseEntity<Map> res = http.call(renterUser, HttpMethod.GET, path(bay), null);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(num(res, "net")).isEqualByComparingTo("600.00");
        assertThat(num(res, "vat")).isEqualByComparingTo("0");
        assertThat(num(res, "total")).isEqualByComparingTo("600.00");
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
}
