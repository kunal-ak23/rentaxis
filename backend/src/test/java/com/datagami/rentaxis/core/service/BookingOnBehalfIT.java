package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.AmenityCreateRequest;
import com.datagami.rentaxis.api.dto.BookingCreateRequest;
import com.datagami.rentaxis.api.dto.ParkingSpotCreateRequest;
import com.datagami.rentaxis.api.dto.ParkingSpotUpdateRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.SlotConflictException;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.BookingRequest;
import com.datagami.rentaxis.domain.entity.ParkingSpot;
import com.datagami.rentaxis.domain.entity.PropertyAmenity;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus;
import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.vatLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec 2026-11-19 section 4: staff book a bay or an amenity for a renter. The booking is created
 * and approved in one go, so the fee posts at once; a renter who is not on the unit, or a
 * bay that is taken, leaves no booking and no charge behind.
 */
@SpringBootTest
class BookingOnBehalfIT extends AbstractPostgresIT {

    @Autowired BookingService bookings;
    @Autowired FacilityService facilities;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired LeaseService leaseService;
    @Autowired AccountService accountService;
    @Autowired AccountResolver accountResolver;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    private LeaseTestFixtures fixtures;
    private UUID renterUser;
    private final UUID staff = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, chequeGeneration, posting);
        Renter r = fixtures.renter();
        renterUser = r.getUserId() != null ? r.getUserId() : UUID.randomUUID();
        r.setUserId(renterUser);
        renterRepo.save(r);
        fixtures.postedLease(LocalDate.of(2026, 4, 20), LocalDate.of(2026, 5, 1), LocalDate.of(2027, 4, 30),
                List.of(vatLine("RENT", "120000")), 4, null);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private BookingRequest onBehalf(UUID forRenter, BookingCreateRequest req) {
        return tx.execute(s -> bookings.createOnBehalf(fixtures.tenantId(), staff, forRenter, fixtures.unit(), req));
    }

    private PropertyAmenity banquetHall() {
        return facilities.createAmenity(fixtures.tenantId(), new AmenityCreateRequest(
                fixtures.property().getId(), "Banquet Hall", null, null, true, null, "PER_DAY", new BigDecimal("1000"), null));
    }

    private int bookingRows() {
        return jdbc.queryForObject("select count(*) from booking_requests where tenant_id = ?", Integer.class,
                fixtures.tenantId());
    }

    private int bookingCharges() {
        return jdbc.queryForObject("select count(*) from penalty_assessments where source_type = 'BOOKING'"
                + " and tenant_id = ?", Integer.class, fixtures.tenantId());
    }

    @Test
    void staffBookingIsApprovedAndChargedAtOnce() {
        PropertyAmenity hall = banquetHall();
        LocalDate day = LocalDate.now(ZoneId.of("Asia/Dubai")).plusDays(10);

        BookingRequest b = onBehalf(renterUser, new BookingCreateRequest(BookingResourceType.AMENITY, hall.getId(),
                fixtures.unit().getId(), day, null, null, null, null));

        assertThat(b.getStatus()).isEqualTo(BookingRequestStatus.APPROVED);
        assertThat(b.getRenterUserId()).isEqualTo(renterUser);
        assertThat(b.getChargeId()).isNotNull();
        UUID jv = jdbc.queryForObject("select id from journal_entries where source_type = 'PENALTY' and source_id = ?",
                UUID.class, b.getChargeId());
        assertThat(jdbc.queryForObject("select doc_type from journal_entries where id = ?", String.class, jv)).isEqualTo("JV");
        UUID amenityIncome = tx.execute(s -> accountResolver.resolve(AccountRole.AMENITY_INCOME, fixtures.property().getId()))
                .getId();
        assertThat(jdbc.queryForObject("select coalesce(sum(credit), 0) - coalesce(sum(debit), 0) from journal_lines"
                + " where journal_entry_id = ? and account_id = ?", BigDecimal.class, jv, amenityIncome))
                .isEqualByComparingTo("1000.00");
    }

    @Test
    void renterNotOnTheUnitIsRefused() {
        PropertyAmenity hall = banquetHall();
        LocalDate day = LocalDate.now(ZoneId.of("Asia/Dubai")).plusDays(10);

        assertThatThrownBy(() -> onBehalf(UUID.randomUUID(), new BookingCreateRequest(BookingResourceType.AMENITY,
                hall.getId(), fixtures.unit().getId(), day, null, null, null, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting(e -> ((BusinessRuleViolationException) e).getCode()).isEqualTo("booking.noLease");
        assertThat(bookingRows()).isZero();
        assertThat(bookingCharges()).isZero();
    }

    @Test
    void overlappingBayIsRefused() {
        ParkingSpot s0 = facilities.createParkingSpot(fixtures.tenantId(), new ParkingSpotCreateRequest(
                fixtures.property().getId(), "P-77", "B1", null, null, null, null));
        ParkingSpot bay = facilities.updateParkingSpot(fixtures.tenantId(), s0.getId(),
                new ParkingSpotUpdateRequest(null, null, null, null, null, "PER_DAY", new BigDecimal("50")));
        LocalDate from = LocalDate.now(ZoneId.of("Asia/Dubai")).plusDays(5);
        BookingRequest first = onBehalf(renterUser, new BookingCreateRequest(BookingResourceType.PARKING_SPOT, bay.getId(),
                fixtures.unit().getId(), from, from.plusDays(2), null, null, null));
        assertThat(first.getStatus()).isEqualTo(BookingRequestStatus.APPROVED);

        assertThatThrownBy(() -> onBehalf(renterUser, new BookingCreateRequest(BookingResourceType.PARKING_SPOT,
                bay.getId(), fixtures.unit().getId(), from.plusDays(1), from.plusDays(3), null, null, null)))
                .isInstanceOf(SlotConflictException.class)
                .extracting(e -> ((SlotConflictException) e).getCode()).isEqualTo("booking.spotTaken");
        assertThat(bookingRows()).as("only the first booking").isOne();
        assertThat(bookingCharges()).isOne();
    }
    /**
     * PR #468 review m6: staff booking the dates the renter already asked for get the renter's
     * pending request back from create; it is approved at the bay's price now (what staff were
     * quoted), with the fee snapshot taken again, not at the price frozen on the old request.
     */
    @Test
    void onBehalfOfAPendingRequestChargesTheCurrentPrice() {
        ParkingSpot s0 = facilities.createParkingSpot(fixtures.tenantId(), new ParkingSpotCreateRequest(
                fixtures.property().getId(), "P-78", "B1", null, null, null, null));
        ParkingSpot bay = facilities.updateParkingSpot(fixtures.tenantId(), s0.getId(),
                new ParkingSpotUpdateRequest(null, null, null, null, null, "PER_DAY", new BigDecimal("50")));
        LocalDate from = LocalDate.now(ZoneId.of("Asia/Dubai")).plusDays(5);
        BookingCreateRequest req = new BookingCreateRequest(BookingResourceType.PARKING_SPOT, bay.getId(),
                fixtures.unit().getId(), from, from.plusDays(2), null, null, null);
        BookingRequest pending = tx.execute(s -> bookings.create(fixtures.tenantId(), renterUser, fixtures.unit(), req));
        assertThat(pending.getFeeAmount()).isEqualByComparingTo("150.00");

        facilities.updateParkingSpot(fixtures.tenantId(), bay.getId(),
                new ParkingSpotUpdateRequest(null, null, null, null, null, "PER_DAY", new BigDecimal("80")));
        BookingRequest b = onBehalf(renterUser, req);

        assertThat(b.getId()).as("the renter's own request, approved").isEqualTo(pending.getId());
        assertThat(b.getStatus()).isEqualTo(BookingRequestStatus.APPROVED);
        assertThat(b.getFeeAmount()).isEqualByComparingTo("240.00");
        assertThat(b.getFeeType()).isEqualTo("PER_DAY");
        assertThat(b.getFeeUnitAmount()).isEqualByComparingTo("80.00");
        assertThat(jdbc.queryForObject("select amount from penalty_assessments where id = ?", BigDecimal.class,
                b.getChargeId())).isEqualByComparingTo("240.00");
        assertThat(bookingRows()).isOne();
    }
}
