package com.datagami.rentaxis.core.service;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scale P1-1: the daily expiry reminder reads each organisation's live contracts ending on
 * the target day with one bounded query ({@code status IN (ACTIVE, NOTICE_GIVEN) AND
 * end_date = :d}) instead of {@code leaseRepository.findAll()}, and it still reaches every
 * organisation — the job runs with no tenant in context.
 */
@SpringBootTest
class NotificationSchedulerExpiryIT extends AbstractPostgresIT {

    @Autowired NotificationScheduler scheduler;
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
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private record Posted(UUID leaseId, UUID renterUserId) {
    }

    /** A posted contract ending on {@code end}, in a fresh organisation. */
    private Posted contractEnding(LocalDate end) {
        LeaseTestFixtures f = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap().withLeaseServices(leaseService, generation, posting);
        LocalDate start = end.minusYears(1).plusDays(1);
        UUID leaseId = f.postedLease(start.minusDays(5), start, end, List.of(line("RENT", "60000")), 1,
                LeaseTestFixtures.nextChequeNumber()).lease().getId();
        return new Posted(leaseId, f.renter().getUserId());
    }

    private int expiryNotices(UUID userId, UUID leaseId) {
        return jdbc.queryForObject("select count(*) from notifications where user_id = ? and reference_id = ?"
                + " and type = 'LEASE_EXPIRING'", Integer.class, userId, leaseId);
    }

    @Test
    void everyOrganisationsContractEndingInThirtyDaysIsRemindedAndNoOther() {
        LocalDate today = LocalDate.now();
        Posted a = contractEnding(today.plusDays(30));
        Posted b = contractEnding(today.plusDays(30));      // another organisation
        Posted notYet = contractEnding(today.plusDays(31));
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();

        scheduler.sendDailyNotifications();

        assertThat(expiryNotices(a.renterUserId(), a.leaseId())).isEqualTo(1);
        assertThat(expiryNotices(b.renterUserId(), b.leaseId())).isEqualTo(1);
        assertThat(expiryNotices(notYet.renterUserId(), notYet.leaseId())).isZero();
    }
}
