package com.datagami.rentaxis.core.service.voucher;

import com.datagami.rentaxis.api.dto.ledger.TrialBalanceRowDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.VendorService;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Posting a Purchase/Service Invoice (spec §10.1).
 *
 * <p><b>Transactions.</b> {@code TenantAspect} enables the Hibernate tenant filter
 * only inside one, and {@code JournalLine.account} is LAZY, so every read-back of
 * the journal goes through {@link #tx} and is flattened into {@link Row} while the
 * session is still open.</p>
 */
@SpringBootTest
@Testcontainers
class PurchaseInvoicePostingIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired VoucherService vouchers;
    @Autowired AccountService accounts;
    @Autowired VendorService vendorService;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired LedgerQueryService ledger;
    @Autowired JournalEntryRepository entries;
    @Autowired JournalLineRepository lines;
    @Autowired TenantDefaultAccountMappingRepository defaults;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    UUID tenantId, propertyId;
    Account pestControl, lifeguard, inputVat;
    Vendor vendor;

    /** One journal line, flattened inside the transaction that read it. */
    record Row(UUID accountId, BigDecimal debit, BigDecimal credit, UUID propertyId, String narration) {}

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("PISR-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        accounts.seedDefaultAccounts();

        Property p = new Property();
        p.setNameEn("Ocean Residencia");
        p.setEmirate(Emirate.DUBAI);
        propertyId = propertyRepo.save(p).getId();

        Account expenseGroup = accounts.getAccountByCode("D-01");
        pestControl = accounts.createLeaf("PEST CONTROL AMC OCEAN RESIDENCIA", expenseGroup, propertyId);
        lifeguard = accounts.createLeaf("LIFEGUARD EXP - OCEAN RESIDENCIA", expenseGroup, propertyId);
        inputVat = accounts.createLeaf("VAT Receivable", accounts.getAccountByCode("A-02-04"), null);
        mapDefault(AccountRole.INPUT_VAT, inputVat);

        Vendor v = new Vendor();
        v.setNameEn("Emrill Services LLC");
        vendor = vendorService.createVendor(v);

        fiscal.setBooksStartDate(LocalDate.of(2026, 10, 1));
        fiscal.lockThrough(LocalDate.of(2026, 9, 30));
    }

    @AfterEach void clear() { TenantContextHolder.clear(); }

    private void mapDefault(AccountRole role, Account a) {
        TenantDefaultAccountMapping m = new TenantDefaultAccountMapping();
        m.setRole(role);
        m.setAccount(a);
        defaults.save(m);
    }

    /**
     * 2,000 at 5% (VAT 100.00) plus 3,000 zero-rated: net 5,000.00, VAT 100.00,
     * gross 5,100.00. Every figure asserted below is re-derived from this fixture.
     */
    private Voucher draftTwoLineInvoice() {
        return vouchers.createDraft(new VoucherService.VoucherInput(
                VoucherType.PISR, LocalDate.of(2026, 10, 15), vendor.getId(), "EMR-4471",
                "Pest control and lifeguard — October", propertyId, null, null, null, null,
                List.of(new VoucherService.VoucherLineInput(pestControl.getId(), "Pest control AMC",
                                new BigDecimal("2000.00"), new BigDecimal("5"), propertyId, null),
                        new VoucherService.VoucherLineInput(lifeguard.getId(), "Lifeguard — zero rated",
                                new BigDecimal("3000.00"), BigDecimal.ZERO, propertyId, null))));
    }

    private List<Row> journalRows(UUID entryId) {
        return tx.execute(s -> lines.findByEntry_IdOrderByLineNoAsc(entryId).stream()
                .map(l -> new Row(l.getAccount().getId(), l.getDebit(), l.getCredit(),
                        l.getPropertyId(), l.getNarration()))
                .toList());
    }

    private List<JournalEntry> voucherJournals(UUID voucherId) {
        return tx.execute(s -> entries.findBySourceTypeAndSourceIdOrderByEntryDateAscCreatedAtAsc(
                JournalSourceType.VOUCHER, voucherId));
    }

    /** Σ debits − Σ credits over the whole tenant: only a half-written entry can move it. */
    private void assertTrialBalanceBalances() {
        List<TrialBalanceRowDTO> rows = tx.execute(s -> ledger.trialBalance(LocalDate.of(2030, 1, 1), null));
        assertThat(rows).as("trial balance rows").isNotEmpty();
        BigDecimal debit = rows.stream().map(TrialBalanceRowDTO::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = rows.stream().map(TrialBalanceRowDTO::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debit).as("trial balance debits").isGreaterThan(BigDecimal.ZERO);
        assertThat(debit).as("trial balance").isEqualByComparingTo(credit);
    }

    /**
     * Spec §10.1: Dr each expense line, Dr INPUT_VAT for the summed VAT, Cr the
     * vendor's payable account for the gross. Asserting the exact line set is the
     * point of this test — a balanced journal with the VAT on the wrong side or
     * folded into the expense would still satisfy the balance trigger.
     */
    @Test
    void postingWritesExpenseLinesInputVatAndTheVendorCredit() {
        Voucher posted = vouchers.post(draftTwoLineInvoice().getId());

        assertThat(posted.getStatus()).isEqualTo(VoucherStatus.POSTED);
        assertThat(posted.getVoucherNumber()).startsWith("PISR-");
        assertThat(posted.getPostedAt()).isNotNull();

        JournalEntry e = tx.execute(s -> entries.findById(posted.getJournalId()).orElseThrow());
        assertThat(e.getDocType()).isEqualTo(JournalDocType.PISR);
        assertThat(e.getEntryDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(e.getSourceType()).isEqualTo(JournalSourceType.VOUCHER);
        assertThat(e.getSourceId()).isEqualTo(posted.getId());
        assertThat(e.getPropertyId()).isEqualTo(propertyId);
        assertThat(e.getEntryNumber()).isEqualTo(posted.getVoucherNumber());

        assertThat(journalRows(e.getId()))
                .extracting(Row::accountId, Row::debit, Row::credit)
                .containsExactly(
                        tuple(pestControl.getId(), new BigDecimal("2000.00"), new BigDecimal("0.00")),
                        tuple(lifeguard.getId(), new BigDecimal("3000.00"), new BigDecimal("0.00")),
                        tuple(inputVat.getId(), new BigDecimal("100.00"), new BigDecimal("0.00")),
                        tuple(vendor.getPayableAccount().getId(), new BigDecimal("0.00"), new BigDecimal("5100.00")));
        assertTrialBalanceBalances();
    }

    /**
     * Rider from the Tasks 1–2 review: VAT is computed per line and summed, never
     * on the net total. Three lines of 100.10 at 5% are 5.005 each — HALF_UP to
     * 5.01 — so the single INPUT_VAT line is 15.03 and the vendor is credited
     * 315.33. Computing 5% of the 300.30 net instead gives 15.02 / 315.32.
     */
    @Test
    void theInputVatLineIsTheSumOfPerLineVatNotVatOfTheNetTotal() {
        VoucherService.VoucherLineInput line = new VoucherService.VoucherLineInput(
                pestControl.getId(), "Fil-level rounding", new BigDecimal("100.10"), new BigDecimal("5"), null, null);
        Voucher posted = vouchers.post(vouchers.createDraft(new VoucherService.VoucherInput(
                VoucherType.PISR, LocalDate.of(2026, 10, 15), vendor.getId(), "EMR-4472", "Three odd lines",
                propertyId, null, null, null, null, List.of(line, line, line))).getId());

        List<Row> rows = journalRows(posted.getJournalId());
        assertThat(rows).filteredOn(r -> r.accountId().equals(inputVat.getId()))
                .singleElement()
                .satisfies(r -> assertThat(r.debit()).isEqualByComparingTo("15.03"));
        assertThat(rows).filteredOn(r -> r.accountId().equals(vendor.getPayableAccount().getId()))
                .singleElement()
                .satisfies(r -> assertThat(r.credit()).isEqualByComparingTo("315.33"));
        assertTrialBalanceBalances();
    }

    /** A fully zero-rated invoice must not emit a 0.00 INPUT_VAT line — the CHECK forbids it anyway. */
    @Test
    void aZeroRatedInvoiceHasNoInputVatLine() {
        Voucher v = vouchers.createDraft(new VoucherService.VoucherInput(
                VoucherType.PISR, LocalDate.of(2026, 10, 15), vendor.getId(), null, "Zero rated",
                propertyId, null, null, null, null,
                List.of(new VoucherService.VoucherLineInput(lifeguard.getId(), null,
                        new BigDecimal("1500.00"), BigDecimal.ZERO, propertyId, null))));
        Voucher posted = vouchers.post(v.getId());
        assertThat(journalRows(posted.getJournalId()))
                .hasSize(2)
                .noneMatch(r -> r.accountId().equals(inputVat.getId()));
    }

    /** Lines carry their own property dimension so the property-filtered GL is right. */
    @Test
    void everyJournalLineCarriesThePropertyDimension() {
        Voucher posted = vouchers.post(draftTwoLineInvoice().getId());
        assertThat(journalRows(posted.getJournalId()))
                .allSatisfy(r -> assertThat(r.propertyId()).isEqualTo(propertyId));
    }

    @Test
    void postingTwiceIsRejected() {
        Voucher posted = vouchers.post(draftTwoLineInvoice().getId());
        assertThatThrownBy(() -> vouchers.post(posted.getId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("POSTED");
        assertThat(voucherJournals(posted.getId())).hasSize(1);
    }

    /** The period lock belongs to PostingService; this proves the voucher path does not bypass it. */
    @Test
    void postingIntoALockedPeriodIsRejected() {
        Voucher v = vouchers.createDraft(new VoucherService.VoucherInput(
                VoucherType.PISR, LocalDate.of(2026, 9, 15), vendor.getId(), null, "Before cut-over",
                propertyId, null, null, null, null,
                List.of(new VoucherService.VoucherLineInput(pestControl.getId(), null,
                        new BigDecimal("100.00"), BigDecimal.ZERO, propertyId, null))));
        assertThatThrownBy(() -> vouchers.post(v.getId()))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThat(vouchers.get(v.getId()).getStatus()).isEqualTo(VoucherStatus.DRAFT);
    }

    /**
     * Spec §5.5: the vendor leaf is created silently — if it is missing, say so
     * instead of NPEing.
     *
     * <p>The FK is cleared with SQL on purpose: {@code VendorService.updateVendor}
     * keeps the existing leaf when the update carries none, so there is no service
     * path that detaches a vendor from its ledger account.</p>
     */
    @Test
    void aVendorWithoutAPayableAccountIsRejectedAtDraftTime() {
        Vendor bare = new Vendor();
        bare.setNameEn("No Ledger Co");
        Vendor saved = vendorService.createVendor(bare);
        jdbc.update("update vendors set payable_account_id = null where id = ?", saved.getId());

        assertThatThrownBy(() -> vouchers.createDraft(new VoucherService.VoucherInput(
                VoucherType.PISR, LocalDate.of(2026, 10, 15), saved.getId(), null, "x",
                propertyId, null, null, null, null,
                List.of(new VoucherService.VoucherLineInput(pestControl.getId(), null,
                        new BigDecimal("10.00"), BigDecimal.ZERO, null, null)))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("payable account");
    }

    /**
     * A vendor deactivated between drafting and posting. Deactivated with SQL so
     * that only the vendor's own flag moves: {@code updateVendor} would deactivate
     * the payable leaf too, and then PostingService's inactive-account check would
     * be what refused the posting rather than this one.
     */
    @Test
    void anInactiveVendorIsRefusedAtPost() {
        UUID voucherId = draftTwoLineInvoice().getId();
        jdbc.update("update vendors set is_active = false where id = ?", vendor.getId());
        assertThatThrownBy(() -> vouchers.post(voucherId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("inactive");
        assertThat(vouchers.get(voucherId).getStatus()).isEqualTo(VoucherStatus.DRAFT);
    }

    /**
     * A purchase invoice buys an expense or an asset. Crediting income through the
     * PISR path would read as a sale with the signs inverted, and the entry would
     * still balance, so only an explicit account-type check catches it.
     */
    @Test
    void aPisrLineOnAnIncomeAccountIsRefusedAtPost() {
        Account income = accounts.createLeaf("Other Income - Ocean", accounts.getAccountByCode("C-01-02"), null);
        Voucher v = vouchers.createDraft(new VoucherService.VoucherInput(
                VoucherType.PISR, LocalDate.of(2026, 10, 15), vendor.getId(), null, "Wrong account",
                propertyId, null, null, null, null,
                List.of(new VoucherService.VoucherLineInput(income.getId(), null,
                        new BigDecimal("100.00"), BigDecimal.ZERO, null, null))));
        assertThatThrownBy(() -> vouchers.post(v.getId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("expense or asset");
    }

    /** P0: posting is a write, so the tenant filter has to hold on the by-id load too. */
    @Test
    void tenantBCannotPostTenantAsVoucher() {
        UUID voucherId = draftTwoLineInvoice().getId();

        LandlordOrg orgB = new LandlordOrg();
        orgB.setName("PISR-B-" + UUID.randomUUID());
        TenantContextHolder.setTenantId(orgRepo.save(orgB).getId());
        assertThatThrownBy(() -> vouchers.post(voucherId)).isInstanceOf(NotFoundException.class);

        TenantContextHolder.setTenantId(tenantId);
        assertThat(vouchers.get(voucherId).getStatus()).isEqualTo(VoucherStatus.DRAFT);
    }

    /**
     * Two clerks (or one double-click) posting the same draft must leave one
     * journal, not two. The row lock decides it; the loser re-reads POSTED.
     */
    @Test
    void twoSimultaneousPostsWriteOneJournal() throws Exception {
        UUID voucherId = draftTwoLineInvoice().getId();
        UUID tenant = tenantId;

        CyclicBarrier bothReady = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Callable<Object>> racers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            racers.add(() -> {
                TenantContextHolder.setTenantId(tenant);
                try {
                    bothReady.await(10, TimeUnit.SECONDS);
                    return vouchers.post(voucherId);
                } catch (RuntimeException ex) {
                    return ex;
                } finally {
                    TenantContextHolder.clear();
                }
            });
        }
        List<Object> outcomes = new ArrayList<>();
        try {
            for (Future<Object> f : pool.invokeAll(racers, 60, TimeUnit.SECONDS)) outcomes.add(f.get());
        } finally {
            pool.shutdownNow();
        }

        assertThat(outcomes).filteredOn(Voucher.class::isInstance).as("winners").hasSize(1);
        assertThat(outcomes).filteredOn(o -> !(o instanceof Voucher)).as("losers")
                .allMatch(BusinessRuleViolationException.class::isInstance);
        assertThat(voucherJournals(voucherId)).as("journals for the voucher").hasSize(1);
        assertTrialBalanceBalances();
    }
}
