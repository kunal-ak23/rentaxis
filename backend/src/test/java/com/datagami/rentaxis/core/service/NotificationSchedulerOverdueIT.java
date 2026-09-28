package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.lease.PostLeaseResponse;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
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
 * Tutorial bug 2026-09-28-03, the reminder side: the Tenant is told an instalment is
 * "overdue" only when they can still pay it. A cheque already deposited with the bank
 * is the landlord's to chase with the bank, not the Tenant's to be nagged about.
 */
@SpringBootTest
class NotificationSchedulerOverdueIT extends AbstractPostgresIT {

    @Autowired NotificationScheduler scheduler;
    @Autowired LeaseService leaseService;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService generation;
    @Autowired ChequeService chequeService;
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

    private record Posted(UUID chequeId, UUID renterUserId) {
    }

    /** One instalment dated seven days ago on a no-grace contract: a day-7 reminder is due. */
    private Posted sevenDaysLate(boolean deposit) {
        LeaseTestFixtures f = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap().withLeaseServices(leaseService, generation, posting);
        LocalDate start = LocalDate.now().minusDays(7);
        PostLeaseResponse posted = f.postedLease(start.minusDays(5), start, start.plusYears(1).minusDays(1),
                List.of(line("RENT", "60000")), 1, LeaseTestFixtures.nextChequeNumber());
        jdbc.update("update leases set grace_period_days = 0 where id = ?", posted.lease().getId());
        UUID chequeId = posted.cheques().get(0).id();
        assertThat(posted.cheques().get(0).chequeDate()).isEqualTo(start);
        if (deposit) {
            chequeService.deposit(chequeId, ChequeActionRequest.on(LocalDate.now()));
        }
        return new Posted(chequeId, f.renter().getUserId());
    }

    private int overdueNotices(Posted p) {
        return jdbc.queryForObject("select count(*) from notifications where user_id = ? and reference_id = ?"
                + " and type = 'PAYMENT_OVERDUE'", Integer.class, p.renterUserId(), p.chequeId());
    }

    @Test
    void aDepositedChequeIsNotRemindedAsOverdueButAnUnpaidOneIs() {
        Posted deposited = sevenDaysLate(true);
        Posted unpaid = sevenDaysLate(false);
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();

        scheduler.sendDailyNotifications();

        assertThat(overdueNotices(unpaid)).as("control: an unpaid cheque 7 days late is chased").isEqualTo(1);
        assertThat(overdueNotices(deposited)).as("a cheque at the bank is not the Tenant's to pay").isZero();
    }
}
