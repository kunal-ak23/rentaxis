package com.datagami.rentaxis.core.service.renewal;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.RenewalOpportunity;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.RenewalStage;
import com.datagami.rentaxis.domain.repository.*;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.RenewalTestFixtures;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RenewalOpportunityServiceTest extends AbstractPostgresIT {

    @Autowired RenewalOpportunityService service;
    @Autowired LeaseRepository leaseRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired RenewalOpportunityRepository oppRepo;

    UUID tenantId;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("Org-" + UUID.randomUUID());
        org = orgRepo.save(org);
        tenantId = org.getId();
        TenantContextHolder.setTenantId(tenantId);
    }

    @AfterEach
    void tearDown() { TenantContextHolder.clear(); }

    @Test
    void opens_opportunity_for_lease_in_90_day_window() {
        LocalDate today = LocalDate.of(2026, 6, 1);
        Lease lease = RenewalTestFixtures.createActiveLease(orgRepo, userRepo, renterRepo, propertyRepo, unitRepo, leaseRepo, tenantId, today.minusYears(1).plusDays(15), today.plusDays(85));

        int opened = service.openOpportunitiesForCurrentTenant(today);

        assertThat(opened).isEqualTo(1);
        List<RenewalOpportunity> opps = oppRepo.findByTenantIdAndStageIn(tenantId, List.of(RenewalStage.OPEN));
        assertThat(opps).hasSize(1);
        assertThat(opps.get(0).getLease().getId()).isEqualTo(lease.getId());
    }

    @Test
    void does_not_open_for_lease_beyond_90_days() {
        LocalDate today = LocalDate.of(2026, 6, 1);
        RenewalTestFixtures.createActiveLease(orgRepo, userRepo, renterRepo, propertyRepo, unitRepo, leaseRepo, tenantId, today, today.plusDays(200));

        int opened = service.openOpportunitiesForCurrentTenant(today);
        assertThat(opened).isZero();
    }

    @Test
    void idempotent_does_not_open_second_opportunity() {
        LocalDate today = LocalDate.of(2026, 6, 1);
        RenewalTestFixtures.createActiveLease(orgRepo, userRepo, renterRepo, propertyRepo, unitRepo, leaseRepo, tenantId, today.minusYears(1), today.plusDays(85));

        service.openOpportunitiesForCurrentTenant(today);
        int second = service.openOpportunitiesForCurrentTenant(today);

        assertThat(second).isZero();
    }

    @Test
    void closes_opportunity_when_lease_terminated() {
        LocalDate today = LocalDate.of(2026, 6, 1);
        Lease lease = RenewalTestFixtures.createActiveLease(orgRepo, userRepo, renterRepo, propertyRepo, unitRepo, leaseRepo, tenantId, today.minusYears(1), today.plusDays(50));
        service.openOpportunitiesForCurrentTenant(today);

        lease.setStatus(LeaseStatus.TERMINATED);
        leaseRepo.save(lease);

        int closed = service.closeStaleOpportunitiesForCurrentTenant();
        assertThat(closed).isEqualTo(1);
        List<RenewalOpportunity> closedOpps = oppRepo.findByTenantIdAndStageIn(tenantId, List.of(RenewalStage.CLOSED_LOST));
        assertThat(closedOpps).hasSize(1);
    }
}
