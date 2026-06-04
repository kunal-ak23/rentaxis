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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Integration test (real Postgres) for the dashboard monthly aggregation:
 * expected = all amounts due in the month; collected = the COLLECTED/DEPOSITED/
 * CLEARED subset. Verifies grouping, the collected-status set, and the date window.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Testcontainers
class PaymentScheduleRepositoryMonthlyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired PaymentScheduleRepository repo;
    @Autowired PropertyRepository propertyRepository;
    @Autowired UnitRepository unitRepository;
    @Autowired RenterRepository renterRepository;
    @Autowired LeaseRepository leaseRepository;
    @Autowired LandlordOrgRepository landlordOrgRepository;

    private Property property;
    private Unit unit;
    private Lease lease;

    private final YearMonth thisMonth = YearMonth.from(LocalDate.now());
    private final YearMonth lastMonth = thisMonth.minusMonths(1);

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("MonthlyIT-" + UUID.randomUUID());
        org = landlordOrgRepository.save(org);
        TenantContextHolder.setTenantId(org.getId());
        property = newProperty();
        unit = newUnit(property);
        lease = newLease(unit);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void aggregatesExpectedAndCollectedByDueMonth() {
        // This month: expected 3500, collected 2500 (CLEARED 2000 + COLLECTED 500; PENDING 1000 not collected)
        schedule(thisMonth.atDay(5), new BigDecimal("1000"), PaymentStatus.PENDING);
        schedule(thisMonth.atDay(6), new BigDecimal("2000"), PaymentStatus.CLEARED);
        schedule(thisMonth.atDay(7), new BigDecimal("500"), PaymentStatus.COLLECTED);
        // Last month: expected 1400, collected 1000 (DEPOSITED 1000; PENDING 400 not collected)
        schedule(lastMonth.atDay(10), new BigDecimal("1000"), PaymentStatus.DEPOSITED);
        schedule(lastMonth.atDay(11), new BigDecimal("400"), PaymentStatus.PENDING);
        // Outside the window — must be excluded.
        schedule(thisMonth.minusMonths(15).atDay(1), new BigDecimal("9999"), PaymentStatus.CLEARED);

        LocalDate from = thisMonth.minusMonths(11).atDay(1);
        LocalDate to = thisMonth.plusMonths(1).atDay(1);
        Map<String, BigDecimal[]> byYm = new HashMap<>();
        for (Object[] r : repo.aggregateMonthlyCollection(from, to)) {
            byYm.put((String) r[0], new BigDecimal[]{(BigDecimal) r[1], (BigDecimal) r[2]});
        }

        String thisKey = String.format("%04d-%02d", thisMonth.getYear(), thisMonth.getMonthValue());
        String lastKey = String.format("%04d-%02d", lastMonth.getYear(), lastMonth.getMonthValue());

        assertThat(byYm).containsKeys(thisKey, lastKey);
        assertThat(byYm.get(thisKey)[0]).isEqualByComparingTo("3500");
        assertThat(byYm.get(thisKey)[1]).isEqualByComparingTo("2500");
        assertThat(byYm.get(lastKey)[0]).isEqualByComparingTo("1400");
        assertThat(byYm.get(lastKey)[1]).isEqualByComparingTo("1000");
        // 15-months-ago row excluded by the window
        assertThat(byYm.values().stream().noneMatch(v -> v[0].compareTo(new BigDecimal("9999")) == 0)).isTrue();
    }

    // ---- fixtures ----

    private Property newProperty() {
        Property p = new Property();
        p.setNameEn("Monthly-Prop-" + UUID.randomUUID());
        p.setEmirate(Emirate.DUBAI);
        return propertyRepository.save(p);
    }

    private Unit newUnit(Property p) {
        Unit u = new Unit();
        u.setProperty(p);
        u.setUnitNumber("U-1");
        return unitRepository.save(u);
    }

    private Lease newLease(Unit u) {
        Renter renter = new Renter();
        renter.setNameEn("Renter-" + UUID.randomUUID());
        renter = renterRepository.save(renter);
        Lease l = new Lease();
        l.setUnit(u);
        l.setRenter(renter);
        l.setStartDate(LocalDate.now().minusMonths(13));
        l.setEndDate(LocalDate.now().plusMonths(11));
        l.setStatus(LeaseStatus.ACTIVE);
        l.setRentAmount(new BigDecimal("60000"));
        l.setMonthlyRent(new BigDecimal("5000"));
        return leaseRepository.save(l);
    }

    private PaymentSchedule schedule(LocalDate dueDate, BigDecimal amount, PaymentStatus status) {
        PaymentSchedule s = new PaymentSchedule();
        s.setProperty(property);
        s.setLease(lease);
        s.setUnit(unit);
        s.setInstallmentNumber(1);
        s.setDueDate(dueDate);
        s.setAmount(amount);
        s.setStatus(status);
        return repo.save(s);
    }
}
