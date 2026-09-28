package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.cheque.ClearBatchRequest;
import com.datagami.rentaxis.api.dto.cheque.DepositBatchRequest;
import com.datagami.rentaxis.api.dto.lease.PostLeaseResponse;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Break-it round 2 (money2) F1/F2: deposit, clear (single and batch) and bounce
 * record something the bank has already done, so none may be dated after today —
 * the batch Clear's rule, from one shared validator. A 2126 bounce used to take
 * CBR-26/1 and refuse every real 2026 bounce with "uq_journal_entries_number".
 */
@SpringBootTest
class ChequeBankEventDatesIT extends AbstractPostgresIT {

    @Autowired ChequeService cheques;
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

    private LeaseTestFixtures fixtures;
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Dubai"));

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, chequeGeneration, posting);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    /** Four quarterly cheques over a running year; the ones dated in the past. */
    private List<UUID> pastCheques() {
        LocalDate start = TODAY.minusMonths(7).withDayOfMonth(1);
        PostLeaseResponse r = fixtures.postedLease(start.minusDays(10), start, start.plusYears(1).minusDays(1),
                List.of(line("RENT", "40000")), 4, "300100");
        return r.cheques().stream()
                .filter(c -> c.chequeDate() != null && !c.chequeDate().isAfter(TODAY.minusDays(2)))
                .map(c -> c.id()).toList();
    }

    private long cbrCount() {
        return jdbc.queryForObject("select count(*) from journal_entries where tenant_id = ? and doc_type = 'CBR'",
                Long.class, fixtures.tenantId());
    }

    private static void inFuture(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("is in the future")
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode()).isEqualTo("date.inFuture"));
    }

    @Test
    void aDepositOrAClearDatedAfterTodayIsRefusedAndTodayIsNot() {
        List<UUID> ids = pastCheques();
        UUID first = ids.get(0);
        inFuture(() -> cheques.deposit(first, ChequeActionRequest.on(TODAY.plusDays(1))));
        inFuture(() -> cheques.deposit(first, ChequeActionRequest.on(LocalDate.of(2126, 1, 10))));
        assertThat(cheques.deposit(first, ChequeActionRequest.on(TODAY)).status()).isEqualTo(ChequeStatus.DEPOSITED);

        inFuture(() -> cheques.clear(first, ChequeActionRequest.on(TODAY.plusDays(1))));
        inFuture(() -> cheques.clear(first, ChequeActionRequest.on(LocalDate.of(2027, 2, 26).isAfter(TODAY)
                ? LocalDate.of(2027, 2, 26) : TODAY.plusMonths(5))));
        assertThat(cheques.clear(first, ChequeActionRequest.on(TODAY)).status()).isEqualTo(ChequeStatus.CLEARED);
    }

    @Test
    void theBatchDepositAndBatchClearShareTheRule() {
        List<UUID> ids = pastCheques();
        List<UUID> two = ids.subList(0, 2);
        inFuture(() -> cheques.depositBatch(new DepositBatchRequest(two, TODAY.plusDays(1), null)));
        assertThat(cheques.depositBatch(new DepositBatchRequest(two, TODAY, null))).hasSize(2);
        inFuture(() -> cheques.clearBatch(new ClearBatchRequest(two, TODAY.plusDays(1), null)));
        assertThat(cheques.clearBatch(new ClearBatchRequest(two, TODAY, null))).hasSize(2);
    }

    /** C2 / P1: the century typo is refused before a CBR number is drawn; today's bounce still works. */
    @Test
    void aBounceDatedAfterTodayIsRefusedBeforeANumberIsDrawn() {
        List<UUID> ids = pastCheques();
        UUID a = ids.get(0);
        UUID b = ids.get(1);
        cheques.deposit(a, ChequeActionRequest.on(TODAY));
        cheques.deposit(b, ChequeActionRequest.on(TODAY));

        inFuture(() -> cheques.bounce(a, new ChequeActionRequest(LocalDate.of(2126, 1, 15), null, ChequeFailureReason.BOUNCE, null)));
        inFuture(() -> cheques.bounce(a, new ChequeActionRequest(TODAY.plusDays(1), null, ChequeFailureReason.BOUNCE, null)));
        assertThat(cbrCount()).isZero();

        assertThat(cheques.bounce(a, new ChequeActionRequest(TODAY, null, ChequeFailureReason.BOUNCE, null)).status())
                .isEqualTo(ChequeStatus.BOUNCED);
        assertThat(cheques.bounce(b, new ChequeActionRequest(TODAY, null, ChequeFailureReason.BOUNCE, null)).status())
                .isEqualTo(ChequeStatus.BOUNCED);
        assertThat(cbrCount()).isEqualTo(2);
    }
}
