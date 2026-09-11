package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.CreateLeaseDTO;
import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lease's stored total and its cheques have to agree.
 *
 * <p>They did not. The schedule is generated from
 * {@code monthlyRent × DateMath.monthsInclusive}, but the total was taken
 * verbatim from the caller, and the web wizard computed its own month count
 * that ignored the day of the month. A lease running 3 Oct 2026 to 3 Oct 2027
 * was submitted as 13 months: the cheques totalled 60,000 while the lease
 * record, the contract PDF and the unit's actual rent all said 65,000.</p>
 */
@SpringBootTest
@Testcontainers
class LeaseTermMonthCountIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired LeaseService leaseService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired PaymentScheduleRepository scheduleRepo;

    private UUID unitId;
    private UUID renterId;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("TermOrg-" + UUID.randomUUID());
        org = orgRepo.save(org);
        TenantContextHolder.setTenantId(org.getId());
    }

    /**
     * A fresh vacant unit. Built per call rather than once in setUp: a lease
     * occupies its unit, so a second lease on the same one is refused.
     */
    private void freshUnit() {
        Property property = new Property();
        property.setNameEn("Term Tower " + UUID.randomUUID());
        property.setEmirate(Emirate.DUBAI);
        property.setType(PropertyType.RESIDENTIAL);
        property = propertyRepo.save(property);

        Unit unit = new Unit();
        unit.setProperty(property);
        unit.setUnitNumber("T-" + UUID.randomUUID().toString().substring(0, 6));
        unit.setStatus(UnitStatus.VACANT);
        unitId = unitRepo.save(unit).getId();

        Renter renter = new Renter();
        renter.setNameEn("Term Renter");
        renter.setEmail("term-" + UUID.randomUUID() + "@example.com");
        renterId = renterRepo.save(renter).getId();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private LeaseDTO createLease(LocalDate start, LocalDate end, BigDecimal monthly,
                                 BigDecimal callerTotal, int cheques) {
        freshUnit();
        CreateLeaseDTO dto = new CreateLeaseDTO();
        dto.setUnitId(unitId);
        dto.setRenterId(renterId);
        dto.setStartDate(start);
        dto.setEndDate(end);
        dto.setMonthlyRent(monthly);
        dto.setRentAmount(callerTotal);
        dto.setDepositAmount(new BigDecimal("5000"));
        dto.setPaymentTerms(cheques);
        dto.setPaymentMethod("CHEQUE");
        return leaseService.createDraftLease(dto);
    }

    private BigDecimal scheduledRent(UUID leaseId) {
        return scheduleRepo.findByLeaseId(leaseId).stream()
                .filter(r -> !r.isCharge() && !r.isSecurityDeposit() && !r.isBookingDeposit())
                .map(PaymentSchedule::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The reported lease, with the wrong total the wizard used to submit. */
    @Test
    void aYearFromTheThirdToTheThirdIsTwelveMonths() {
        LeaseDTO lease = createLease(
                LocalDate.of(2026, 10, 3),
                LocalDate.of(2027, 10, 3),
                new BigDecimal("5000"),
                new BigDecimal("65000"),   // 13 months — what the caller sent
                6);

        assertThat(lease.getRentAmount())
                .as("the caller's 13-month total must not be stored")
                .isEqualByComparingTo("60000");
        assertThat(scheduledRent(lease.getId()))
                .as("and the cheques must add up to the same figure")
                .isEqualByComparingTo("60000");
    }

    @Test
    void theStoredTotalAlwaysEqualsWhatTheChequesAddUpTo() {
        // Each of these ends mid-month, where the caller's count and the
        // schedule's used to diverge. A month-end lease (Jan 1 → Dec 31) agreed
        // all along, which is why this went unnoticed.
        record Term(LocalDate start, LocalDate end, String expected) {}
        List<Term> terms = List.of(
                new Term(LocalDate.of(2026, 10, 3), LocalDate.of(2027, 10, 3), "60000"),
                new Term(LocalDate.of(2026, 10, 3), LocalDate.of(2027, 10, 2), "60000"),
                new Term(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "60000"),
                new Term(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 12, 31), "35000"),
                new Term(LocalDate.of(2026, 3, 15), LocalDate.of(2026, 9, 14), "30000"));

        for (Term t : terms) {
            LeaseDTO lease = createLease(t.start(), t.end(), new BigDecimal("5000"),
                    new BigDecimal("999999"), 1);
            assertThat(lease.getRentAmount())
                    .as("stored total for %s → %s", t.start(), t.end())
                    .isEqualByComparingTo(t.expected());
            assertThat(scheduledRent(lease.getId()))
                    .as("scheduled total for %s → %s", t.start(), t.end())
                    .isEqualByComparingTo(t.expected());
        }
    }

    /**
     * A lease quoted as a lump sum for the term, with no monthly figure, still
     * takes the caller's total — there is nothing to derive it from.
     */
    @Test
    void aLumpSumLeaseKeepsTheTotalItWasGiven() {
        LeaseDTO lease = createLease(
                LocalDate.of(2026, 10, 3),
                LocalDate.of(2027, 10, 3),
                null,
                new BigDecimal("72500"),
                4);

        assertThat(lease.getRentAmount()).isEqualByComparingTo("72500");
        assertThat(scheduledRent(lease.getId())).isEqualByComparingTo("72500");
    }
}
