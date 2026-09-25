package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.dto.ledger.FiscalYearDTO;
import com.datagami.rentaxis.api.dto.ledger.ReverseRequest;
import com.datagami.rentaxis.api.dto.ledger.TrialBalanceRowDTO;
import com.datagami.rentaxis.api.dto.ledger.YearClosePreviewDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.recognition.RecognitionService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.JournalLine;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.JournalStatus;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Year-end close (spec 2026-09-24 §3). Two years of one lease: 36,500 of rent over
 * 01/07/2024 → 30/06/2025 (100.00 a day) and a 1,000 admin fee (income at
 * posting, 20/06/2024), plus 200 of property expense in 2024 and 300 in 2025.
 * FY 2024 profit = 18,400 (184 days) + 1,000 − 200 = 19,200; FY 2025 = 18,100 − 300.
 */
@SpringBootTest
class YearEndCloseIT extends AbstractPostgresIT {

    @Autowired YearEndCloseService closes;
    @Autowired LedgerQueryService ledger;
    @Autowired PostingService posting;
    @Autowired JournalService journalService;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired RecognitionService recognition;
    @Autowired LeasePostingService leasePosting;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired LeaseService leaseService;
    @Autowired AccountResolver resolver;
    @Autowired AccountService accountService;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired JournalEntryRepository entries;
    @Autowired JournalLineRepository journalLines;
    @Autowired UnitRepository unitRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired TransactionTemplate tx;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.datagami.rentaxis.domain.repository.AccountRepository accountRepo;

    private LeaseTestFixtures fixtures;
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 25);
    private static final LocalDate FY24_END = LocalDate.of(2024, 12, 31);

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, chequeGeneration, leasePosting);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private void twoYears(boolean recognise) {
        fixtures.postedLease(LocalDate.of(2024, 6, 20), LocalDate.of(2024, 7, 1), LocalDate.of(2025, 6, 30),
                List.of(line("RENT", "36500"), line("ADMIN_FEE", "1000")), 4, null);
        expense(LocalDate.of(2024, 12, 15), "200");
        expense(LocalDate.of(2025, 3, 10), "300");
        if (recognise) recognition.runTo(LocalDate.of(2025, 12, 31), false);
    }

    /** Bank charges booked to the property, paid in cash. */
    private void expense(LocalDate date, String amount) {
        UUID property = fixtures.property().getId();
        posting.post(new PostingRequest(JournalDocType.JV, date, "Building repairs", PostingRequest.Dimensions.ofProperty(property),
                JournalSourceType.MANUAL, null, null, List.of(
                PostingRequest.dr(AccountRole.BANK_CHARGES, new BigDecimal(amount)),
                PostingRequest.cr(AccountRole.CASH, new BigDecimal(amount)))));
    }

    private BigDecimal balance(LocalDate asOf, AccountRole role, boolean excludeClosing) {
        UUID id = tx.execute(s -> resolver.resolve(role, fixtures.property().getId()).getId());
        return tx.execute(s -> ledger.trialBalance(asOf, null, excludeClosing)).stream()
                .filter(r -> r.accountId().equals(id)).map(TrialBalanceRowDTO::balance)
                .findFirst().orElse(BigDecimal.ZERO);
    }

    private BigDecimal balanceOf(UUID accountId, LocalDate asOf) {
        return tx.execute(s -> ledger.trialBalance(asOf, null, false)).stream()
                .filter(r -> r.accountId().equals(accountId)).map(TrialBalanceRowDTO::balance)
                .findFirst().orElse(BigDecimal.ZERO);
    }

    private BigDecimal pnlTotal(LocalDate asOf, boolean excludeClosing) {
        return tx.execute(s -> ledger.trialBalance(asOf, null, excludeClosing)).stream()
                .filter(r -> r.accountType().equals("INCOME") || r.accountType().equals("EXPENSE"))
                .map(TrialBalanceRowDTO::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private LocalDate lock() {
        return tx.execute(s -> fiscal.get().getBooksLockedThrough());
    }

    private JournalStatus statusOf(UUID journalId) {
        return tx.execute(s -> entries.findById(journalId).orElseThrow().getStatus());
    }

    private FiscalYearDTO year(int fy) {
        return closes.list(TODAY).stream().filter(y -> y.fiscalYear() == fy).findFirst().orElseThrow();
    }

    @Test
    void closingMovesTheYearsProfitToRetainedEarningsAndLocksTheYear() {
        twoYears(true);
        YearClosePreviewDTO preview = closes.preview(2024, TODAY);
        assertThat(preview.blockers()).isEmpty();
        assertThat(preview.netResult()).isEqualByComparingTo("19200");
        assertThat(preview.retainedEarnings()).singleElement()
                .satisfies(r -> assertThat(r.profit()).isEqualByComparingTo("19200"));

        FiscalYearDTO closed = closes.close(2024, false, TODAY);
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.journalNumber()).isEqualTo("YEC-24/1");
        assertThat(closed.netResult()).isEqualByComparingTo("19200");   // the P&L is unchanged by the close

        // Post-closing TB at year end: P&L is zero, Retained Earnings holds the profit.
        assertThat(pnlTotal(FY24_END, false)).isEqualByComparingTo("0");
        assertThat(balance(FY24_END, AccountRole.RETAINED_EARNINGS, false)).isEqualByComparingTo("-19200");
        // Pre-closing TB (the toggle): what the accountant reviewed.
        assertThat(balance(FY24_END, AccountRole.RENTAL_INCOME, true)).isEqualByComparingTo("-18400");
        assertThat(pnlTotal(FY24_END, true)).isEqualByComparingTo("-19200");
        // The next day: income starts again from zero, RE carries the profit.
        assertThat(balance(LocalDate.of(2025, 1, 31), AccountRole.RENTAL_INCOME, false)).isEqualByComparingTo("-3100");
        assertThat(balance(LocalDate.of(2025, 1, 1), AccountRole.RETAINED_EARNINGS, false)).isEqualByComparingTo("-19200");

        // No renter, lease or unit on the closing entry; the RE line carries the property.
        List<JournalLine> yec = tx.execute(s -> journalLines.findByEntry_IdOrderByLineNoAsc(closed.journalId()));
        assertThat(yec).allSatisfy(l -> {
            assertThat(l.getRenterId()).isNull();
            assertThat(l.getLeaseId()).isNull();
            assertThat(l.getUnitId()).isNull();
        });
        UUID re = tx.execute(s -> resolver.resolve(AccountRole.RETAINED_EARNINGS, null).getId());
        assertThat(yec.stream().filter(l -> l.getAccountId().equals(re)))
                .singleElement().satisfies(l -> assertThat(l.getPropertyId()).isEqualTo(fixtures.property().getId()));

        assertThat(lock()).isEqualTo(FY24_END);
        assertThatThrownBy(() -> expense(LocalDate.of(2024, 12, 20), "50"))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("locked");
    }

    @Test
    void yearsCloseInOrderAndOnlyTheLatestReopens() {
        twoYears(true);
        assertThatThrownBy(() -> closes.close(2025, false, TODAY))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("Close fiscal year 2024 first");
        closes.close(2024, false, TODAY);
        closes.close(2025, false, TODAY);
        assertThat(year(2025).netResult()).isEqualByComparingTo("17800");
        assertThat(balance(LocalDate.of(2025, 12, 31), AccountRole.RETAINED_EARNINGS, false)).isEqualByComparingTo("-37000");
        assertThatThrownBy(() -> closes.reopen(2024, "Audit adjustment", TODAY))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("later closed year first");
    }

    @Test
    void reopeningRestoresTheTrialBalanceAndTheLockAndAReCloseIsFresh() {
        twoYears(true);
        FiscalYearDTO first = closes.close(2024, false, TODAY);
        assertThatThrownBy(() -> closes.reopen(2024, "  ", TODAY))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("reason");

        FiscalYearDTO reopened = closes.reopen(2024, "Missed a supplier invoice", TODAY);
        assertThat(reopened.status()).isEqualTo("REOPENED");
        assertThat(reopened.reopenReason()).isEqualTo("Missed a supplier invoice");
        assertThat(balance(FY24_END, AccountRole.RENTAL_INCOME, false)).isEqualByComparingTo("-18400");
        assertThat(balance(FY24_END, AccountRole.RETAINED_EARNINGS, false)).isEqualByComparingTo("0");
        assertThat(statusOf(first.journalId()))
                .isEqualTo(JournalStatus.REVERSED);
        // The one backwards move: the lock goes to the day before the year.
        assertThat(lock()).isEqualTo(LocalDate.of(2023, 12, 31));

        expense(LocalDate.of(2024, 12, 20), "50");
        FiscalYearDTO again = closes.close(2024, false, TODAY);
        assertThat(again.journalNumber()).isEqualTo("YEC-24/3");  // /2 is the reversal's mirror
        assertThat(again.netResult()).isEqualByComparingTo("19150");
        assertThat(balance(FY24_END, AccountRole.RETAINED_EARNINGS, false)).isEqualByComparingTo("-19150");
    }

    @Test
    void plannedRecognitionBlocksTheClose() {
        twoYears(false);
        YearClosePreviewDTO preview = closes.preview(2024, TODAY);
        assertThat(preview.blockers()).extracting(YearClosePreviewDTO.Issue::code).contains("recognitionPending");
        assertThatThrownBy(() -> closes.close(2024, false, TODAY))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("recognition");
        assertThat(closes.preview(2026, TODAY).blockers()).extracting(YearClosePreviewDTO.Issue::code).contains("notEnded");
    }

    @Test
    void theJournalApiCannotReverseAYecAndAnImportCannotPostIntoAClosedYear() {
        twoYears(true);
        FiscalYearDTO closed = closes.close(2024, false, TODAY);
        assertThatThrownBy(() -> journalService.reverse(closed.journalId(), new ReverseRequest(TODAY, "no")))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("year-end close");
        assertThatThrownBy(() -> posting.post(new PostingRequest(JournalDocType.JV, LocalDate.of(2024, 11, 1), "Imported",
                PostingRequest.Dimensions.none(), JournalSourceType.IMPORT, null, UUID.randomUUID(), List.of(
                PostingRequest.dr(AccountRole.BANK_CHARGES, new BigDecimal("10")),
                PostingRequest.cr(AccountRole.CASH, new BigDecimal("10"))))))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("is closed; re-open it first");
    }

    /** The accountant has usually locked past year end already: the close posts anyway and never moves the lock back. */
    @Test
    void aLockAlreadyPastYearEndIsKept() {
        twoYears(true);
        fiscal.lockThrough(LocalDate.of(2025, 1, 31));
        FiscalYearDTO closed = closes.close(2024, false, TODAY);
        assertThat(closed.journalId()).isNotNull();
        assertThat(lock()).isEqualTo(LocalDate.of(2025, 1, 31));
        assertThat(balance(FY24_END, AccountRole.RETAINED_EARNINGS, false)).isEqualByComparingTo("-19200");
    }

    /**
     * PR #358 R1 P2-1: a leaf retired before this change still holds its balance; the
     * closing entry zeroes it anyway (doc-type exemption), and re-open reverses it.
     */
    @Test
    void theCloseZeroesAnInactiveLeafAndReopenReversesIt() {
        twoYears(true);
        UUID rental = tx.execute(s -> resolver.resolve(AccountRole.RENTAL_INCOME, fixtures.property().getId()).getId());
        jdbc.update("update accounts set is_active = false where id = ?", rental);
        closes.close(2024, false, TODAY);
        assertThat(balanceOf(rental, FY24_END)).isEqualByComparingTo("0");
        closes.reopen(2024, "Correction", TODAY);
        assertThat(balanceOf(rental, FY24_END)).isEqualByComparingTo("-18400");
        // Any other document still cannot post to it.
        assertThatThrownBy(() -> posting.post(new PostingRequest(JournalDocType.JV, LocalDate.of(2026, 9, 1), "x",
                PostingRequest.Dimensions.none(), JournalSourceType.MANUAL, null, null, List.of(
                PostingRequest.dr(AccountRole.CASH, new BigDecimal("1")),
                PostingRequest.cr(rental, new BigDecimal("1"))))))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("inactive account");
    }

    /** PR #358 R1 P2-1: an account with a balance cannot be deactivated; once closed out it can. */
    @Test
    void anAccountWithABalanceCannotBeDeactivated() {
        twoYears(true);
        UUID rental = tx.execute(s -> resolver.resolve(AccountRole.RENTAL_INCOME, fixtures.property().getId()).getId());
        var a = tx.execute(s -> accountRepo.findById(rental).orElseThrow());
        AccountService.AccountUpdate off = new AccountService.AccountUpdate(a.getName(), a.getNameEn(), a.getNameAr(),
                a.getAlias(), a.getDescription(), a.getAccountSubType(), false, a.getDisplayOrder(),
                a.getProperty() == null ? null : a.getProperty().getId());
        assertThatThrownBy(() -> accountService.updateAccount(rental, off))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("still has a balance of -36500.00")
                .extracting(e -> ((BusinessRuleViolationException) e).getCode())
                .isEqualTo("account.deactivateWithBalance");
        closes.close(2024, false, TODAY);
        closes.close(2025, false, TODAY);
        assertThat(accountService.updateAccount(rental, off).isActive()).isFalse();
    }

    // ------------------------------------------------------------------
    // PR #358 R1: a post racing the close
    // ------------------------------------------------------------------

    private <T> java.util.concurrent.Future<T> inTenant(java.util.concurrent.ExecutorService pool,
                                                        java.util.concurrent.Callable<T> work) {
        UUID tenant = fixtures.tenantId();
        return pool.submit(() -> {
            TenantContextHolder.setTenantId(tenant);
            LeaseTestFixtures.authenticateAsTenantAdmin();
            try {
                return work.call();
            } finally {
                TenantContextHolder.clear();
                LeaseTestFixtures.clearAuth();
            }
        });
    }

    /** A backdated post still in flight when Close is pressed: the close waits for it and includes it. */
    @Test
    void aPostInFlightIsInsideTheClose() throws Exception {
        twoYears(true);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var posted = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try {
            var post = inTenant(pool, () -> tx.execute(s -> {
                expense(LocalDate.of(2024, 12, 20), "100");
                posted.countDown();
                try { release.await(20, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { throw new RuntimeException(e); }
                return null;
            }));
            assertThat(posted.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var close = inTenant(pool, () -> closes.close(2024, false, TODAY));
            Thread.sleep(700);
            assertThat(close.isDone()).as("the close waits for the post in flight").isFalse();
            release.countDown();
            post.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(close.get(20, java.util.concurrent.TimeUnit.SECONDS).netResult()).isEqualByComparingTo("19100");
            assertThat(balance(FY24_END, AccountRole.RETAINED_EARNINGS, false)).isEqualByComparingTo("-19100");
            assertThat(pnlTotal(FY24_END, false)).isEqualByComparingTo("0");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** A backdated post arriving while the close is committing: it waits, then meets the lock. */
    @Test
    void aPostDuringTheCloseIsRefusedAfterIt() throws Exception {
        twoYears(true);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var closed = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try {
            var close = inTenant(pool, () -> tx.execute(s -> {
                FiscalYearDTO y = closes.close(2024, false, TODAY);
                closed.countDown();
                try { release.await(20, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { throw new RuntimeException(e); }
                return y;
            }));
            assertThat(closed.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var post = inTenant(pool, () -> { expense(LocalDate.of(2024, 12, 20), "100"); return null; });
            Thread.sleep(700);
            assertThat(post.isDone()).as("the post waits for the close").isFalse();
            release.countDown();
            close.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertThatThrownBy(() -> post.get(20, java.util.concurrent.TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(BusinessRuleViolationException.class)
                    .rootCause().hasMessageContaining("locked");
            assertThat(balance(FY24_END, AccountRole.RETAINED_EARNINGS, false)).isEqualByComparingTo("-19200");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void aLossYearDebitsRetainedEarnings() {
        expense(LocalDate.of(2024, 5, 1), "750");
        FiscalYearDTO closed = closes.close(2024, false, TODAY);
        assertThat(closed.netResult()).isEqualByComparingTo("-750");
        assertThat(balance(FY24_END, AccountRole.RETAINED_EARNINGS, false)).isEqualByComparingTo("750");
        JournalEntry yec = tx.execute(s -> entries.findById(closed.journalId()).orElseThrow());
        assertThat(yec.getDocType()).isEqualTo(JournalDocType.YEC);
        assertThat(yec.getEntryDate()).isEqualTo(FY24_END);
    }
}
