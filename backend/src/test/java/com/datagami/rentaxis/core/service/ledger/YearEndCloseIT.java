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
