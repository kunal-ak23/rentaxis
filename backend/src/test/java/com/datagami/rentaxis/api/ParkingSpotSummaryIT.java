package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.BookingRequest;
import com.datagami.rentaxis.domain.entity.ParkingSpot;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus;
import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.BookingRequestRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.ParkingSpotRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.CrossTenantHttp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demo feedback 2026-09-29: {@code GET /api/v1/parking-spots/summary} feeds the
 * property Overview's parking tiles. It counts the caller's tenant only — another
 * Organisation's spots and bookings on a property of the same shape are never in
 * the figures — and a property of another Organisation answers zeros, not its data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ParkingSpotSummaryIT extends AbstractPostgresIT {

    @LocalServerPort int port;

    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired PropertyService propertyService;
    @Autowired ParkingSpotRepository spotRepo;
    @Autowired BookingRequestRepository bookingRepo;
    @Autowired UnitRepository unitRepo;

    private CrossTenantHttp http;
    private User adminA;
    private UUID propertyA;
    private UUID propertyB;

    @BeforeEach
    void setUp() {
        http = new CrossTenantHttp(port, orgRepo, userRepo, accountService, propertyAccountService, propertyService);

        // Organisation B: four active spots, three held, one inactive — none of it may reach A.
        UUID tenantB = http.tenant("Park-B-");
        Property pb = http.property("Other Tower");
        propertyB = pb.getId();
        seed(tenantB, pb, 4, 3, 1);

        // Organisation A: five active spots, two held (one of them twice over the years), one inactive.
        UUID tenantA = http.tenant("Park-A-");
        Property pa = http.property("Palm Ridge");
        propertyA = pa.getId();
        seed(tenantA, pa, 5, 2, 1);
        adminA = http.admin(tenantA);
        TenantContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    /** {@code active} spots, the first {@code held} of them with an APPROVED booking, plus {@code inactive} deactivated ones. */
    private void seed(UUID tenantId, Property property, int active, int held, int inactive) {
        Unit unit = new Unit();
        unit.setProperty(property);
        unit.setUnitNumber("U-" + UUID.randomUUID().toString().substring(0, 6));
        unit.setTenantId(tenantId);
        unit = unitRepo.save(unit);
        User renter = http.user(tenantId, UserRole.RENTER);
        for (int i = 0; i < active + inactive; i++) {
            ParkingSpot s = new ParkingSpot();
            s.setTenantId(tenantId);
            s.setPropertyId(property.getId());
            s.setSpotNumber("P-" + i);
            s.setActive(i < active);
            s = spotRepo.save(s);
            if (i < held) {
                book(tenantId, property, unit, renter, s, BookingRequestStatus.APPROVED);
                // A spot with history is still one spot.
                book(tenantId, property, unit, renter, s, BookingRequestStatus.RELEASED);
            } else if (i < active) {
                // A pending request does not assign the spot.
                book(tenantId, property, unit, renter, s, BookingRequestStatus.PENDING);
            }
        }
    }

    private void book(UUID tenantId, Property property, Unit unit, User renter, ParkingSpot s, BookingRequestStatus status) {
        BookingRequest b = new BookingRequest();
        b.setTenantId(tenantId);
        b.setPropertyId(property.getId());
        b.setResourceType(BookingResourceType.PARKING_SPOT);
        b.setParkingSpotId(s.getId());
        b.setUnitId(unit.getId());
        b.setRenterUserId(renter.getId());
        b.setStatus(status);
        bookingRepo.save(b);
    }

    private static long n(ResponseEntity<Map> res, String key) {
        return ((Number) res.getBody().get(key)).longValue();
    }

    @Test
    void countsTheCallersOwnSpotsOnly() {
        var res = http.call(adminA, HttpMethod.GET, "/api/v1/parking-spots/summary?propertyId=" + propertyA, null);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(n(res, "total")).isEqualTo(5);
        assertThat(n(res, "assigned")).isEqualTo(2);
        assertThat(n(res, "free")).isEqualTo(3);
        assertThat(n(res, "inactive")).isEqualTo(1);
    }

    @Test
    void anotherOrganisationsPropertyAnswersZerosNotItsData() {
        var res = http.call(adminA, HttpMethod.GET, "/api/v1/parking-spots/summary?propertyId=" + propertyB, null);

        if (res.getStatusCode().value() == 200) {
            assertThat(n(res, "total")).isZero();
            assertThat(n(res, "assigned")).isZero();
            assertThat(n(res, "free")).isZero();
            assertThat(n(res, "inactive")).isZero();
        } else {
            assertThat(res.getStatusCode().value()).isEqualTo(404);
        }
    }

    @Test
    void propertyIdIsRequired() {
        var res = http.call(adminA, HttpMethod.GET, "/api/v1/parking-spots/summary", null);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }
}
