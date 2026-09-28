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
    @Autowired com.datagami.rentaxis.core.service.baddebt.BadDebtService badDebts;
    @Autowired com.datagami.rentaxis.domain.repository.PenaltyAssessmentRepository assessments;
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
    @Autowired com.datagami.rentaxis.domain.repository.BookingRequestRepository bookingRepo;
    @Autowired com.datagami.rentaxis.domain.repository.LeaseRepository leaseRepo;
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

    /** PR #361 R1 P1-1: a booking whose fee was written off as a bad debt can no longer be cancelled. */
    @Test
    void aBookingWhoseFeeWasWrittenOffCannotBeCancelled() {
        PropertyAmenity hall = amenity("Hall", "PER_BOOKING", "300");
        BookingRequest b = book(hall, LocalDate.now().plusDays(10));
        BookingRequest approved = tx.execute(s -> bookings.approve(fixtures.tenantId(), b.getId(), UUID.randomUUID(), null));
        UUID row = assessments.findById(approved.getChargeId()).orElseThrow().getCollectionCheque().getId();
        UUID leaseId = assessments.findById(approved.getChargeId()).orElseThrow().getLease().getId();
        var w = badDebts.propose(new com.datagami.rentaxis.core.service.baddebt.BadDebtService.ProposeRequest(leaseId,
                List.of(row), LocalDate.now(), "gone"));
        badDebts.approve(w.id(), null);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> bookings.cancel(fixtures.tenantId(), b.getId(), renterUser)))
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode()).isEqualTo("booking.feeWrittenOff"));
    }

    /** Break-it R3 ops3 F7: the same renter, amenity and day cannot be booked twice, and a second fee is never posted. */
    @Test
    void theSameAmenityAndDayCannotBeBookedOrApprovedTwice() {
        PropertyAmenity pool = amenity("PoolFee", "PER_BOOKING", "100");
        LocalDate day = LocalDate.now().plusDays(14);
        BookingRequest first = book(pool, day);
        tx.execute(s -> bookings.approve(fixtures.tenantId(), first.getId(), UUID.randomUUID(), null));

        assertThatThrownBy(() -> book(pool, day))
                .isInstanceOf(com.datagami.rentaxis.api.exception.SlotConflictException.class)
                .satisfies(e -> assertThat(((com.datagami.rentaxis.api.exception.SlotConflictException) e).getCode())
                        .isEqualTo("booking.alreadyBooked"));

        // A clashing request that got in some other way (legacy row) is refused at approval.
        BookingRequest legacy = tx.execute(s -> {
            BookingRequest b = new BookingRequest();
            b.setTenantId(fixtures.tenantId());
            b.setUnitId(fixtures.unit().getId());
            b.setRenterUserId(renterUser);
            b.setResourceType(BookingResourceType.AMENITY);
            b.setAmenityId(pool.getId());
            b.setPropertyId(pool.getPropertyId());
            b.setPreferredDate(day);
            b.setPreferredStartTime(LocalTime.of(11, 0));
            b.setPreferredEndTime(LocalTime.of(12, 0));
            b.setFeeAmount(new BigDecimal("100.00"));
            b.setStatus(com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus.PENDING);
            return bookingRepo.saveAndFlush(b);
        });
        assertThatThrownBy(() -> tx.execute(s -> bookings.approve(fixtures.tenantId(), legacy.getId(), UUID.randomUUID(), null)))
                .satisfies(e -> assertThat(((com.datagami.rentaxis.api.exception.SlotConflictException) e).getCode())
                        .isEqualTo("booking.renterAlreadyBooked"));
        assertThat(jdbc.queryForObject("select count(*) from penalty_assessments where source_type = 'BOOKING' and tenant_id = ?",
                Integer.class, fixtures.tenantId())).isOne();
    }

    /** Break-it R3 ops3 F8: past dates and dates outside the contract are refused on request and on approval. */
    @Test
    void aPastOrOutOfContractBookingIsRefused() {
        PropertyAmenity pool = amenity("PoolFee", "PER_BOOKING", "100");
        assertThatThrownBy(() -> book(pool, LocalDate.now(BookingService.DUBAI).minusDays(1)))
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode()).isEqualTo("booking.dateInPast"));
        // The contract ends 2027-04-30.
        assertThatThrownBy(() -> book(pool, LocalDate.of(2027, 5, 10)))
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode()).isEqualTo("booking.outsideLease"));

        BookingRequest stale = tx.execute(s -> {
            BookingRequest b = new BookingRequest();
            b.setTenantId(fixtures.tenantId());
            b.setUnitId(fixtures.unit().getId());
            b.setRenterUserId(renterUser);
            b.setResourceType(BookingResourceType.AMENITY);
            b.setAmenityId(pool.getId());
            b.setPropertyId(pool.getPropertyId());
            b.setPreferredDate(LocalDate.of(2020, 1, 1));
            b.setFeeAmount(new BigDecimal("100.00"));
            b.setStatus(com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus.PENDING);
            return bookingRepo.saveAndFlush(b);
        });
        assertThatThrownBy(() -> tx.execute(s -> bookings.approve(fixtures.tenantId(), stale.getId(), UUID.randomUUID(), null)))
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode()).isEqualTo("booking.dateInPast"));
        assertThat(jdbc.queryForObject("select count(*) from penalty_assessments where source_type = 'BOOKING' and tenant_id = ?",
                Integer.class, fixtures.tenantId())).isZero();
    }

    /**
     * Review r3C I1: an early-posted renewal flips the running lease to RENEWED while its
     * term still runs; the renter can still book inside it, and a request made before the
     * renewal can still be approved.
     */
    @Test
    void anEarlyPostedRenewalStillLetsTheRenterBookInTheCurrentTerm() {
        PropertyAmenity pool = amenity("PoolFee", "PER_BOOKING", "100");
        BookingRequest before = book(pool, LocalDate.now().plusDays(10));

        // The renewal (2027-05-01 → 2028-04-30) is posted early; the running lease becomes RENEWED.
        fixtures.postedLease(LocalDate.of(2026, 9, 1), LocalDate.of(2027, 5, 1), LocalDate.of(2028, 4, 30),
                List.of(vatLine("RENT", "120000")), 4, null);
        int flipped = jdbc.update("update leases set status = 'RENEWED' where tenant_id = ? and start_date = ?",
                fixtures.tenantId(), LocalDate.of(2026, 5, 1));
        assertThat(flipped).isOne();

        BookingRequest approved = tx.execute(s -> bookings.approve(fixtures.tenantId(), before.getId(), UUID.randomUUID(), null));
        assertThat(approved.getChargeId()).isNotNull();
        PropertyAmenity gym = amenity("Gym", "FREE", null);
        assertThat(book(gym, LocalDate.now().plusDays(20)).getId()).isNotNull();
        // Into the renewal's term as well.
        PropertyAmenity bbq = amenity("BBQ", "FREE", null);   // one pending per amenity: a second one
        assertThat(book(bbq, LocalDate.of(2027, 6, 1)).getId()).isNotNull();
    }

    /**
     * Coordinator R3 round 1: one rule for "the renter's current contract" (bookings, gate
     * passes, tickets): live and today inside the term. An ended lease still marked ACTIVE
     * is not current; the running term of an early-posted renewal (RENEWED) is.
     */
    private int current(UUID t, LocalDate day) {
        Integer n = tx.execute(s -> leaseRepo.findCurrentForRenterUser(t, renterUser, day).size());
        return n == null ? 0 : n;
    }

    @Test
    void theCurrentContractRuleIsTodayInsideALiveTerm() {
        UUID t = fixtures.tenantId();
        assertThat(current(t, LocalDate.now())).isOne();
        // The term ended 2027-04-30; still ACTIVE (no expiry job ran) — not current.
        assertThat(current(t, LocalDate.of(2027, 5, 10))).isZero();
        // An early-posted renewal flips the running term to RENEWED: still current until it ends.
        jdbc.update("update leases set status = 'RENEWED' where tenant_id = ?", t);
        assertThat(current(t, LocalDate.now())).isOne();
        assertThat(current(t, LocalDate.of(2027, 5, 10))).isZero();
    }
}
