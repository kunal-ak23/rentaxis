package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.AmenityCreateRequest;
import com.datagami.rentaxis.api.dto.BookingCreateRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.BookingRequest;
import com.datagami.rentaxis.domain.entity.PropertyAmenity;
import com.datagami.rentaxis.domain.entity.Renter;
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
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.vatLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F14-50: a pool at 50/hour — the renter's request carries the quote; approval posts
 * a BOOKING_FEE charge with VAT on the VAT lease; cancelling before the slot
 * reverses it (credit note); a free amenity charges nothing.
 */
@SpringBootTest
class BookingFeeIT extends AbstractPostgresIT {

    @Autowired BookingService bookings;
    @Autowired FacilityService facilities;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired LeaseService leaseService;
    @Autowired AccountService accountService;
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

    private PropertyAmenity amenity(String name, String type, String amount) {
        return facilities.createAmenity(fixtures.tenantId(), new AmenityCreateRequest(fixtures.property().getId(), name,
                null, null, true, null, type, amount == null ? null : new BigDecimal(amount)));
    }

    private BookingRequest book(PropertyAmenity a, LocalDate day) {
        return tx.execute(s -> bookings.create(fixtures.tenantId(), renterUser, fixtures.unit(), new BookingCreateRequest(
                BookingResourceType.AMENITY, a.getId(), fixtures.unit().getId(), day, null,
                LocalTime.of(10, 0), LocalTime.of(12, 30), null)));
    }

    @Test
    void aPaidAmenityIsQuotedChargedOnApprovalAndReversedWhenCancelledBeforeItsSlot() {
        PropertyAmenity pool = amenity("Pool", "PER_HOUR", "50");
        LocalDate day = LocalDate.now().plusDays(10);
        BookingRequest b = book(pool, day);
        assertThat(b.getFeeAmount()).isEqualByComparingTo("125.00");   // 2.5 h × 50

        BookingRequest approved = tx.execute(s -> bookings.approve(fixtures.tenantId(), b.getId(), UUID.randomUUID(), null));
        assertThat(approved.getChargeId()).isNotNull();
        assertThat(jdbc.queryForObject("select status from penalty_assessments where id = ?", String.class, approved.getChargeId()))
                .isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("select vat_amount from penalty_assessments where id = ?", BigDecimal.class,
                approved.getChargeId())).isEqualByComparingTo("6.25");
        assertThat(jdbc.queryForObject("select source_type from penalty_assessments where id = ?", String.class,
                approved.getChargeId())).isEqualTo("BOOKING");

        tx.executeWithoutResult(s -> bookings.cancel(fixtures.tenantId(), b.getId(), renterUser));
        assertThat(jdbc.queryForObject("select status from penalty_assessments where id = ?", String.class, approved.getChargeId()))
                .isEqualTo("REVERSED");
        assertThat(jdbc.queryForObject("select count(*) from tax_invoices where kind = 'CREDIT_NOTE' and tenant_id = ?",
                Integer.class, fixtures.tenantId())).isOne();
    }

    @Test
    void aFreeAmenityChargesNothingAndAFeeNeedsAnAmount() {
        PropertyAmenity gym = amenity("Gym", "FREE", null);
        BookingRequest b = book(gym, LocalDate.now().plusDays(3));
        assertThat(b.getFeeAmount()).isEqualByComparingTo("0");
        BookingRequest approved = tx.execute(s -> bookings.approve(fixtures.tenantId(), b.getId(), UUID.randomUUID(), null));
        assertThat(approved.getChargeId()).isNull();
        assertThatThrownBy(() -> amenity("BBQ", "PER_BOOKING", null))
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode()).isEqualTo("booking.feeAmount"));
        PropertyAmenity hall = amenity("Hall", "PER_BOOKING", "300");
        assertThat(book(hall, LocalDate.now().plusDays(5)).getFeeAmount()).isEqualByComparingTo("300.00");
    }
}
