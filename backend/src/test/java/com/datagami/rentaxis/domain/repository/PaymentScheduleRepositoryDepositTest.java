package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Integration test (real Postgres) for {@code findChequesToDeposit}: only
 * COLLECTED cheques with a non-null chequeDate on/before today are returned,
 * ordered chequeDate ASC, scoped by property.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Testcontainers
class PaymentScheduleRepositoryDepositTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired PaymentScheduleRepository repo;
    @Autowired PropertyRepository propertyRepository;
    @Autowired UnitRepository unitRepository;
    @Autowired RenterRepository renterRepository;
    @Autowired LeaseRepository leaseRepository;
    @Autowired LandlordOrgRepository landlordOrgRepository;

    private final LocalDate today = LocalDate.now();
    // Ordering is driven by the Pageable (the controller's @PageableDefault),
    // so the test exercises the production path: query + sorted Pageable.
    private final Pageable page = PageRequest.of(0, 50, Sort.by("chequeDate").ascending());

    private Property property;
    private Unit unit;
    private Lease lease;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("DepositIT-" + UUID.randomUUID());
        org = landlordOrgRepository.save(org);
        TenantContextHolder.setTenantId(org.getId());
        property = newProperty("Deposit-Prop-A");
        unit = newUnit(property, "A-1");
        lease = newLease(unit);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void returnsOnlyCollectedChequesDueForDeposit_orderedByChequeDateAsc() {
        PaymentSchedule duePast = schedule(property, lease, unit, PaymentStatus.COLLECTED, today.minusDays(3));
        PaymentSchedule dueToday = schedule(property, lease, unit, PaymentStatus.COLLECTED, today);
        // Excluded:
        schedule(property, lease, unit, PaymentStatus.COLLECTED, today.plusDays(2));   // post-dated future
        schedule(property, lease, unit, PaymentStatus.COLLECTED, null);                // no banking date
        schedule(property, lease, unit, PaymentStatus.PENDING, today.minusDays(1));    // not collected
        schedule(property, lease, unit, PaymentStatus.DEPOSITED, today.minusDays(1));  // already deposited
        schedule(property, lease, unit, PaymentStatus.CLEARED, today.minusDays(1));    // already cleared

        List<PaymentSchedule> result = repo.findChequesToDeposit(null, today, page).getContent();

        assertThat(result).extracting(PaymentSchedule::getId)
                .containsExactly(duePast.getId(), dueToday.getId()); // ASC by chequeDate
    }

    @Test
    void scopesByProperty() {
        PaymentSchedule onA = schedule(property, lease, unit, PaymentStatus.COLLECTED, today.minusDays(1));

        Property propertyB = newProperty("Deposit-Prop-B");
        Unit unitB = newUnit(propertyB, "B-1");
        Lease leaseB = newLease(unitB);
        schedule(propertyB, leaseB, unitB, PaymentStatus.COLLECTED, today.minusDays(1));

        List<PaymentSchedule> result = repo.findChequesToDeposit(property.getId(), today, page).getContent();

        assertThat(result).extracting(PaymentSchedule::getId).containsExactly(onA.getId());
    }

    // ---- fixtures ----

    private Property newProperty(String name) {
        Property p = new Property();
        p.setNameEn(name);
        p.setEmirate(Emirate.DUBAI);
        return propertyRepository.save(p);
    }

    private Unit newUnit(Property p, String number) {
        Unit u = new Unit();
        u.setProperty(p);
        u.setUnitNumber(number);
        return unitRepository.save(u);
    }

    private Lease newLease(Unit u) {
        Renter renter = new Renter();
        renter.setNameEn("Renter-" + UUID.randomUUID());
        renter = renterRepository.save(renter);

        Lease l = new Lease();
        l.setUnit(u);
        l.setRenter(renter);
        l.setStartDate(today.minusMonths(1));
        l.setEndDate(today.plusMonths(11));
        l.setStatus(LeaseStatus.ACTIVE);
        l.setRentAmount(new BigDecimal("60000"));
        return leaseRepository.save(l);
    }

    private PaymentSchedule schedule(Property p, Lease l, Unit u, PaymentStatus status, LocalDate chequeDate) {
        PaymentSchedule s = new PaymentSchedule();
        s.setProperty(p);
        s.setLease(l);
        s.setUnit(u);
        s.setInstallmentNumber(1);
        s.setDueDate(today); // due_date is NOT NULL
        s.setChequeDate(chequeDate);
        s.setAmount(new BigDecimal("5000"));
        s.setStatus(status);
        return repo.save(s);
    }
}
