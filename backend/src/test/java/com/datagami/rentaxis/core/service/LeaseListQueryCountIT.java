package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.util.List;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scale P1-2: mapping a page of contracts costs the same number of statements whatever the
 * page size — the side reads (addendum Ejari, documents, credit addenda, successor, lines,
 * rent-free periods) are one IN query each per page, not five or six per row.
 */
@SpringBootTest
class LeaseListQueryCountIT extends AbstractPostgresIT {

    @Autowired LeaseService leaseService;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService generation;
    @Autowired AccountService accountService;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired EntityManagerFactory emf;

    @BeforeEach
    void setUp() {
        LeaseTestFixtures f = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap().withLeaseServices(leaseService, generation, posting);
        for (int i = 0; i < 8; i++) {
            Unit u = f.createUnit(f.property(), "Q" + i);
            Renter r = f.createRenter("Count Renter " + i);
            f.postedLease(u, r, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 1), LocalDate.of(2027, 1, 31),
                    List.of(line("RENT", "48000")), 2, LeaseTestFixtures.nextChequeNumber());
        }
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private long statementsFor(int pageSize) {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        Page<LeaseDTO> page = leaseService.getAllLeasesPaged(null, PageRequest.of(0, pageSize));
        assertThat(page.getContent()).hasSize(pageSize);
        assertThat(page.getContent()).allSatisfy(d -> assertThat(d.getLines()).isNotEmpty());
        long n = stats.getPrepareStatementCount();
        stats.setStatisticsEnabled(false);
        return n;
    }

    @Test
    void aPageOfEightCostsNoMoreStatementsThanAPageOfTwo() {
        long two = statementsFor(2);
        long eight = statementsFor(8);
        assertThat(eight).as("statements for 8 rows vs %s for 2", two).isLessThanOrEqualTo(two);
        assertThat(eight).as("a small constant per page").isLessThanOrEqualTo(15);
    }
}
