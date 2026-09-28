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
    @Autowired com.datagami.rentaxis.core.service.ledger.PostingService journal;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @Autowired com.datagami.rentaxis.domain.repository.ChequeRepository chequeRepo;
    /** A pass-through spy; one test makes it fail for one organisation's rows. */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.datagami.rentaxis.core.service.cheque.ChequeQueryService chequeQueries;

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private record Posted(UUID chequeId, UUID renterUserId) {
    }

    /** One instalment dated seven days ago on a no-grace contract: a day-7 reminder is due. */
    private enum Then { NOTHING, DEPOSIT, BOUNCE, BOUNCE_AND_SETTLE }

    private Posted sevenDaysLate(boolean deposit) {
        return sevenDaysLate(deposit ? Then.DEPOSIT : Then.NOTHING);
    }

    private Posted sevenDaysLate(Then then) {
        LeaseTestFixtures f = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap().withLeaseServices(leaseService, generation, posting);
        LocalDate start = LocalDate.now().minusDays(7);
        PostLeaseResponse posted = f.postedLease(start.minusDays(5), start, start.plusYears(1).minusDays(1),
                List.of(line("RENT", "60000")), 1, LeaseTestFixtures.nextChequeNumber());
        jdbc.update("update leases set grace_period_days = 0 where id = ?", posted.lease().getId());
        UUID chequeId = posted.cheques().get(0).id();
        assertThat(posted.cheques().get(0).chequeDate()).isEqualTo(start);
        if (then != Then.NOTHING) {
            chequeService.deposit(chequeId, ChequeActionRequest.on(LocalDate.now()));
        }
        if (then == Then.BOUNCE || then == Then.BOUNCE_AND_SETTLE) {
            chequeService.bounce(chequeId, ChequeActionRequest.on(LocalDate.now()));
        }
        if (then == Then.BOUNCE_AND_SETTLE) {
            // A counter receipt against the lease receivable pays the bounced debt (F14-52).
            tx.executeWithoutResult(s -> {
                var c = chequeRepo.findById(chequeId).orElseThrow();
                var lease = c.getLease();
                var dims = new com.datagami.rentaxis.core.service.ledger.PostingRequest.Dimensions(
                        c.getProperty().getId(), lease.getUnit().getId(), lease.getId(), lease.getRenter().getId(), null);
                var amount = c.getAmount();
                var receivable = lease.getReceivableAccountId() != null
                        ? com.datagami.rentaxis.core.service.ledger.PostingRequest.cr(lease.getReceivableAccountId(), amount)
                        : com.datagami.rentaxis.core.service.ledger.PostingRequest.cr(
                                com.datagami.rentaxis.domain.entity.enums.AccountRole.RENT_RECEIVABLE, amount);
                journal.post(new com.datagami.rentaxis.core.service.ledger.PostingRequest(
                        com.datagami.rentaxis.domain.entity.enums.JournalDocType.JV, LocalDate.now(), "Counter receipt", dims,
                        com.datagami.rentaxis.domain.entity.enums.JournalSourceType.MANUAL, null, null, List.of(
                                com.datagami.rentaxis.core.service.ledger.PostingRequest.dr(
                                        com.datagami.rentaxis.domain.entity.enums.AccountRole.CASH, amount),
                                receivable)));
            });
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

    /** Review I1: a bounce the ledger has settled gets no reminder; an unsettled one still does. */
    @Test
    void aBounceSettledInTheLedgerIsNotRemindedButAnUnsettledOneIs() {
        Posted settled = sevenDaysLate(Then.BOUNCE_AND_SETTLE);
        Posted open = sevenDaysLate(Then.BOUNCE);
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();

        scheduler.sendDailyNotifications();

        assertThat(overdueNotices(open)).as("control: an unsettled bounce is chased").isEqualTo(1);
        assertThat(overdueNotices(settled)).as("the ledger has settled it").isZero();
    }

    // ------------------------------------------------------------------
    // review I2: one organisation's ledger problem does not silence the others
    // ------------------------------------------------------------------

    private UUID tenantOf(Posted p) {
        return tx.execute(st -> chequeRepo.findById(p.chequeId()).orElseThrow().getTenantId());
    }

    /** The lease's receivable can no longer be resolved: no lease override, no property or default mapping. */
    private void unmapReceivable(Posted p) {
        tx.executeWithoutResult(st -> {
            var c = chequeRepo.findById(p.chequeId()).orElseThrow();
            jdbc.update("update leases set receivable_account_id = null where id = ?", c.getLease().getId());
            jdbc.update("delete from property_account_mappings where property_id = ? and role = 'RENT_RECEIVABLE'",
                    c.getProperty().getId());
            jdbc.update("delete from tenant_default_account_mappings where tenant_id = ? and role = 'RENT_RECEIVABLE'",
                    c.getTenantId());
        });
    }

    /** A live contract ending in 30 days, in an organisation of its own: its renter gets an expiry notice. */
    private UUID[] contractEndingInThirtyDays() {
        LeaseTestFixtures f = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap().withLeaseServices(leaseService, generation, posting);
        LocalDate end = LocalDate.now().plusDays(30);
        LocalDate start = end.minusYears(1).plusDays(1);
        UUID leaseId = f.postedLease(start.minusDays(5), start, end, List.of(line("RENT", "60000")), 1,
                LeaseTestFixtures.nextChequeNumber()).lease().getId();
        return new UUID[]{leaseId, f.renter().getUserId()};
    }

    private int expiryNotices(UUID[] lease) {
        return jdbc.queryForObject("select count(*) from notifications where user_id = ? and reference_id = ?"
                + " and type = 'LEASE_EXPIRING'", Integer.class, lease[1], lease[0]);
    }

    /**
     * A bounce on a lease whose receivable account cannot be resolved: the ledger read
     * answers "unknown" instead of throwing, the bounce is chased at face value (fail
     * toward reminding), and every other organisation's reminders and notices go out.
     */
    @Test
    void anUnmappedReceivableIsRemindedAtFaceValueAndOtherOrganisationsAreUnaffected() {
        Posted unmapped = sevenDaysLate(Then.BOUNCE);
        unmapReceivable(unmapped);
        Posted healthy = sevenDaysLate(Then.NOTHING);
        UUID[] ending = contractEndingInThirtyDays();
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();

        scheduler.sendDailyNotifications();

        assertThat(overdueNotices(unmapped)).as("unknown balance: reminded at face value").isEqualTo(1);
        assertThat(overdueNotices(healthy)).isEqualTo(1);
        assertThat(expiryNotices(ending)).isEqualTo(1);
    }

    /**
     * Whatever else fails in one organisation's page is caught outside its transaction:
     * that organisation's page is skipped and logged, the others are reminded, and the
     * expiring-contract notices still go out.
     */
    @Test
    void aFailureInOneOrganisationDoesNotStopTheOthersOrTheExpiryNotices() {
        Posted broken = sevenDaysLate(Then.BOUNCE);
        UUID brokenOrg = tenantOf(broken);
        Posted healthy = sevenDaysLate(Then.NOTHING);
        UUID[] ending = contractEndingInThirtyDays();
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
        org.mockito.Mockito.doAnswer(inv -> {
            java.util.Collection<com.datagami.rentaxis.domain.entity.Cheque> rows = inv.getArgument(0);
            if (rows.stream().anyMatch(c -> brokenOrg.equals(c.getTenantId()))) {
                throw new com.datagami.rentaxis.api.exception.NotFoundException("Account not found");
            }
            return inv.callRealMethod();
        }).when(chequeQueries).bouncedOpenAmounts(org.mockito.ArgumentMatchers.any());

        try {
            scheduler.sendDailyNotifications();
        } finally {
            org.mockito.Mockito.reset(chequeQueries);
        }

        assertThat(overdueNotices(broken)).as("its page failed and was skipped").isZero();
        assertThat(overdueNotices(healthy)).isEqualTo(1);
        assertThat(expiryNotices(ending)).isEqualTo(1);
    }
}
