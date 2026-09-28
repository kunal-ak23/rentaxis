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

    /**
     * #10, moved here from ChequeServiceIT (review M2): on each cheque's own date, a row
     * whose date has not arrived cannot have been banked — asked of the same shared
     * rule, on the real today — and nothing in the run is deposited.
     */
    @Test
    void depositBatchOnOwnDatesRefusesARowDatedInTheFuture() {
        LocalDate start = TODAY.minusMonths(7).withDayOfMonth(1);
        PostLeaseResponse r = fixtures.postedLease(start.minusDays(10), start, start.plusYears(1).minusDays(1),
                List.of(line("RENT", "40000")), 4, "300500");
        List<UUID> ids = r.cheques().stream().map(c -> c.id()).toList();
        assertThat(r.cheques()).anySatisfy(c -> assertThat(c.chequeDate()).isAfter(TODAY));

        assertThatThrownBy(() -> cheques.depositBatch(new DepositBatchRequest(ids, TODAY, null, true)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("has not arrived yet");
        assertThat(jdbc.queryForObject("select count(*) from cheques where lease_id = ? and status = 'DEPOSITED'",
                Long.class, r.lease().getId())).isZero();
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

    // ------------------------------------------------------------------
    // break-it R3 money3 N2 and the sweep
    // ------------------------------------------------------------------

    private PostLeaseResponse runningLease(String firstNumber) {
        LocalDate start = TODAY.minusMonths(7).withDayOfMonth(1);
        return fixtures.postedLease(start.minusDays(10), start, start.plusYears(1).minusDays(1),
                List.of(line("RENT", "40000")), 4, firstNumber);
    }

    private static com.datagami.rentaxis.api.dto.lease.ChequeRowInput cashRow(LocalDate posting, LocalDate on, String amount) {
        return new com.datagami.rentaxis.api.dto.lease.ChequeRowInput(null, null, posting, null, on, null, null, null,
                new java.math.BigDecimal(amount), "cash at the counter", com.datagami.rentaxis.domain.entity.enums.ChequeMode.CASH);
    }

    private long journalsAfterToday(String docType) {
        return jdbc.queryForObject("select count(*) from journal_entries where tenant_id = ? and doc_type = ? and entry_date > ?",
                Long.class, fixtures.tenantId(), docType, TODAY);
    }

    /**
     * RC1–RC3: Receive on a CASH row took CRT-99/1 dated 31/12/2099 (and a receipt three
     * months ahead) — the clear's "not after today" rule was never asked there.
     */
    @Test
    void aReceiptDatedAfterTodayIsRefusedAndTodayIsNot() {
        PostLeaseResponse r = runningLease("301100");
        UUID cash = cheques.addRowToPostedLease(r.lease().getId(), cashRow(TODAY.minusDays(3), TODAY.minusDays(3), "500")).id();
        inFuture(() -> cheques.receive(cash, ChequeActionRequest.on(TODAY.plusDays(1))));
        inFuture(() -> cheques.receive(cash, ChequeActionRequest.on(TODAY.plusMonths(3))));
        inFuture(() -> cheques.receive(cash, ChequeActionRequest.on(LocalDate.of(2099, 12, 31))));
        assertThat(journalsAfterToday("CRT")).isZero();
        assertThat(cheques.receive(cash, ChequeActionRequest.on(TODAY)).status()).isEqualTo(ChequeStatus.CLEARED);
    }

    /** CN1/CN2: a cancellation reversed the PDR as PDR-99/1 dated 31/12/2099. */
    @Test
    void aCancellationDatedAfterTodayIsRefusedAndTodayIsNot() {
        PostLeaseResponse r = runningLease("301200");
        UUID pdc = r.cheques().stream().filter(c -> c.chequeDate().isAfter(TODAY)).findFirst().orElseThrow().id();
        long pdrMirrorsBefore = journalsAfterToday("PDR");
        inFuture(() -> cheques.cancel(pdc, ChequeActionRequest.on(TODAY.plusYears(1).plusDays(1))));
        inFuture(() -> cheques.cancel(pdc, ChequeActionRequest.on(LocalDate.of(2099, 12, 31))));
        inFuture(() -> cheques.cancel(pdc, ChequeActionRequest.on(TODAY.plusDays(1))));
        assertThat(journalsAfterToday("PDR")).isEqualTo(pdrMirrorsBefore);
        assertThat(cheques.cancel(pdc, ChequeActionRequest.on(TODAY)).status()).isEqualTo(ChequeStatus.CANCELLED);
    }

    /** N2 ruling: a counter receipt is money that has arrived — not after today, not merely within a year. */
    @Test
    void aCounterReceiptIsNotDatedAfterToday() {
        PostLeaseResponse r = runningLease("301300");
        inFuture(() -> cheques.cashReceipt(r.lease().getId(), cashRow(null, TODAY.plusDays(1), "700")));
        assertThat(cheques.cashReceipt(r.lease().getId(), cashRow(null, TODAY, "700")).status()).isEqualTo(ChequeStatus.CLEARED);
    }

    /** Sweep: a row added to a posted lease posts its PDR on its posting date — the one-year window. */
    @Test
    void aRowAddedToAPostedLeaseCannotPostMoreThanAYearAhead() {
        PostLeaseResponse r = runningLease("301400");
        var row = new com.datagami.rentaxis.api.dto.lease.ChequeRowInput(null, null, LocalDate.of(2099, 12, 31),
                LeaseTestFixtures.nextChequeNumber(), TODAY.plusMonths(2), "Emirates NBD", null, null,
                new java.math.BigDecimal("900"), null, null);
        assertThatThrownBy(() -> cheques.addRowToPostedLease(r.lease().getId(), row))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode()).isEqualTo("posting.dateTooFarAhead"));
        assertThat(jdbc.queryForObject(
                "select count(*) from journal_entries where tenant_id = ? and doc_type = 'PDR' and entry_date > ?",
                Long.class, fixtures.tenantId(), TODAY.plusYears(1))).isZero();
    }
}
