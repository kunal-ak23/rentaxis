package com.datagami.rentaxis.core.service.renewal;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import com.datagami.rentaxis.testsupport.RenewalTestFixtures;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "app.renewal.scheduler.enabled=true")
@Testcontainers
class LeaseRenewalSchedulerIntegrationTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired LeaseRenewalScheduler scheduler;
    @Autowired RenewalOpportunityRepository oppRepo;
    @Autowired LeaseReminderRepository reminderRepo;
    @Autowired LeaseRepository leaseRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired UnitRepository unitRepo;

    @AfterEach
    void tearDown() { TenantContextHolder.clear(); }

    @Test
    void cross_tenant_isolation_no_leak() {
        LocalDate today = LocalDate.of(2026, 6, 1);

        LandlordOrg orgA = new LandlordOrg(); orgA.setName("OrgA-" + UUID.randomUUID()); orgA = orgRepo.save(orgA);
        LandlordOrg orgB = new LandlordOrg(); orgB.setName("OrgB-" + UUID.randomUUID()); orgB = orgRepo.save(orgB);

        TenantContextHolder.setTenantId(orgA.getId());
        RenewalTestFixtures.createActiveLease(orgRepo, userRepo, renterRepo, propertyRepo, unitRepo, leaseRepo, orgA.getId(), today.minusYears(1), today.plusDays(85));
        TenantContextHolder.setTenantId(orgB.getId());
        RenewalTestFixtures.createActiveLease(orgRepo, userRepo, renterRepo, propertyRepo, unitRepo, leaseRepo, orgB.getId(), today.minusYears(1), today.plusDays(85));
        TenantContextHolder.clear();

        scheduler.runNow(today);

        TenantContextHolder.setTenantId(orgA.getId());
        var oppsA = oppRepo.findByTenantIdAndStageIn(orgA.getId(), List.of(RenewalStage.OPEN));
        assertThat(oppsA).hasSize(1);
        var remindersA = reminderRepo.findByOpportunityId(oppsA.get(0).getId());
        assertThat(remindersA.stream().filter(r -> r.getStatus() == ReminderStatus.SENT).count()).isEqualTo(2);

        TenantContextHolder.setTenantId(orgB.getId());
        var oppsB = oppRepo.findByTenantIdAndStageIn(orgB.getId(), List.of(RenewalStage.OPEN));
        assertThat(oppsB).hasSize(1);
    }

    @Test
    void terminated_lease_closes_with_MOVED_OUT() {
        LocalDate today = LocalDate.of(2026, 6, 1);
        LandlordOrg org = new LandlordOrg(); org.setName("Org-" + UUID.randomUUID()); org = orgRepo.save(org);
        UUID tenantId = org.getId();
        TenantContextHolder.setTenantId(tenantId);
        Lease lease = RenewalTestFixtures.createActiveLease(orgRepo, userRepo, renterRepo, propertyRepo, unitRepo, leaseRepo, tenantId, today.minusYears(1), today.plusDays(50));
        TenantContextHolder.clear();

        // First run: opens opportunity + fires 60-day reminder.
        scheduler.runNow(today);

        TenantContextHolder.setTenantId(tenantId);
        // Terminate the lease.
        lease.setStatus(LeaseStatus.TERMINATED);
        leaseRepo.save(lease);
        TenantContextHolder.clear();

        // Second run: should close.
        scheduler.runNow(today.plusDays(1));

        TenantContextHolder.setTenantId(tenantId);
        var closed = oppRepo.findByTenantIdAndStageIn(tenantId, List.of(RenewalStage.CLOSED_LOST));
        assertThat(closed).hasSize(1);
        assertThat(closed.get(0).getOutcome()).isEqualTo(RenewalOutcome.MOVED_OUT);
    }
}
