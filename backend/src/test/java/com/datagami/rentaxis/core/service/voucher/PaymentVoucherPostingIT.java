package com.datagami.rentaxis.core.service.voucher;

import com.datagami.rentaxis.api.dto.ledger.AccountLedgerDTO;
import com.datagami.rentaxis.api.dto.ledger.TrialBalanceRowDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Posting a Bank/Cash Payment Voucher (spec §10.2).
 *
 * <p>Read-backs go through {@link #tx}: the tenant filter is only enabled inside
 * a transaction and {@code JournalLine.account} is LAZY.</p>
 */
@SpringBootTest
@Testcontainers
class PaymentVoucherPostingIT {

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
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    UUID tenantId;
    Account bank, salaries, inputVat;
    Vendor vendor;

    /** One journal line, flattened inside the transaction that read it. */
    record Row(UUID accountId, BigDecimal debit, BigDecimal credit, String narration) {}

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("BPV-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        accounts.seedDefaultAccounts();
        bank = accounts.createLeaf("Emirates Islamic - Head Office", accounts.getAccountByCode("A-02-02"), null);
        salaries = accounts.createLeaf("Staff Salaries", accounts.getAccountByCode("D-01"), null);
        inputVat = accounts.createLeaf("VAT Receivable", accounts.getAccountByCode("A-02-04"), null);
        TenantDefaultAccountMapping m = new TenantDefaultAccountMapping();
        m.setRole(AccountRole.INPUT_VAT);
        m.setAccount(inputVat);
        defaults.save(m);
        Vendor v = new Vendor();
        v.setNameEn("Emrill Services LLC");
        vendor = vendorService.createVendor(v);
        fiscal.setBooksStartDate(LocalDate.of(2026, 10, 1));
        fiscal.lockThrough(LocalDate.of(2026, 9, 30));
    }

    @AfterEach void clear() { TenantContextHolder.clear(); }

    private List<Row> journalRows(UUID entryId) {
        return tx.execute(s -> lines.findByEntry_IdOrderByLineNoAsc(entryId).stream()
                .map(l -> new Row(l.getAccount().getId(), l.getDebit(), l.getCredit(), l.getNarration()))
                .toList());
    }

    private List<JournalEntry> voucherJournals(UUID voucherId) {
        return tx.execute(s -> entries.findBySourceTypeAndSourceIdOrderByEntryDateAscCreatedAtAsc(
                JournalSourceType.VOUCHER, voucherId));
    }

    private void assertTrialBalanceBalances() {
        List<TrialBalanceRowDTO> rows = tx.execute(s -> ledger.trialBalance(LocalDate.of(2030, 1, 1), null));
        assertThat(rows).as("trial balance rows").isNotEmpty();
        BigDecimal debit = rows.stream().map(TrialBalanceRowDTO::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = rows.stream().map(TrialBalanceRowDTO::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debit).as("trial balance debits").isGreaterThan(BigDecimal.ZERO);
        assertThat(debit).as("trial balance").isEqualByComparingTo(credit);
    }

    /**
     * Spec §10.2: Dr the lines, Cr the payment account. The lines here are a vendor
     * payable (settling an invoice) and an expense (a direct payment with no invoice)
     * in one voucher — that mix is the reason BPV lines accept any leaf rather than
     * being restricted to vendors.
     */
    @Test
    void postingDebitsEveryLineAndCreditsThePaymentAccount() {
        Voucher v = vouchers.createDraft(new VoucherService.VoucherInput(
                VoucherType.BPV, LocalDate.of(2026, 10, 20), null, null,
                "October payment run", null, null, bank.getId(), "000451", LocalDate.of(2026, 10, 22),
                List.of(new VoucherService.VoucherLineInput(vendor.getPayableAccount().getId(),
                                "Settle EMR-4471", new BigDecimal("5100.00"), BigDecimal.ZERO, null, null),
                        new VoucherService.VoucherLineInput(salaries.getId(),
                                "Watchman salary", new BigDecimal("2500.00"), BigDecimal.ZERO, null, null))));

        Voucher posted = vouchers.post(v.getId());
        JournalEntry e = tx.execute(s -> entries.findById(posted.getJournalId()).orElseThrow());

        assertThat(e.getDocType()).isEqualTo(JournalDocType.BPV);
        assertThat(e.getSourceType()).isEqualTo(JournalSourceType.VOUCHER);
        assertThat(e.getSourceId()).isEqualTo(posted.getId());
        assertThat(posted.getVoucherNumber()).startsWith("BPV-");
        assertThat(journalRows(e.getId()))
                .extracting(Row::accountId, Row::debit, Row::credit)
                .containsExactly(
                        tuple(vendor.getPayableAccount().getId(), new BigDecimal("5100.00"), new BigDecimal("0.00")),
                        tuple(salaries.getId(), new BigDecimal("2500.00"), new BigDecimal("0.00")),
                        tuple(bank.getId(), new BigDecimal("0.00"), new BigDecimal("7600.00")));
        assertTrialBalanceBalances();
    }

    /** The cheque number belongs on the bank line's narration — that is where a bank rec looks for it. */
    @Test
    void theChequeNumberReachesTheBankLineNarration() {
        Voucher v = vouchers.createDraft(new VoucherService.VoucherInput(
                VoucherType.BPV, LocalDate.of(2026, 10, 20), null, null, "x", null, null,
                bank.getId(), "000451", LocalDate.of(2026, 10, 22),
                List.of(new VoucherService.VoucherLineInput(salaries.getId(), null,
                        new BigDecimal("100.00"), BigDecimal.ZERO, null, null))));
        Voucher posted = vouchers.post(v.getId());
        assertThat(journalRows(posted.getJournalId()))
                .filteredOn(r -> r.accountId().equals(bank.getId()))
                .singleElement()
                .satisfies(r -> assertThat(r.narration()).isEqualTo("Cheque 000451"));
    }

    /**
     * Paying out of petty cash is the same document with a CASH leaf as the payment
     * account. The leaf hangs off "Cash Group" (A-02-05) rather than "Current
     * Assets" (A-02): {@code createLeaf} inherits the parent's sub-type, and only
     * a BANK or CASH sub-type is money that can actually leave.
     */
    @Test
    void cashPaymentsUseTheCashLeafAsThePaymentAccount() {
        Account cash = accounts.createLeaf("Petty Cash", accounts.getAccountByCode("A-02-05"), null);
        Voucher v = vouchers.createDraft(new VoucherService.VoucherInput(
                VoucherType.BPV, LocalDate.of(2026, 10, 20), null, null, "Petty cash", null, null,
                cash.getId(), null, null,
                List.of(new VoucherService.VoucherLineInput(salaries.getId(), null,
                        new BigDecimal("300.00"), BigDecimal.ZERO, null, null))));
        Voucher posted = vouchers.post(v.getId());
        assertThat(journalRows(posted.getJournalId()))
                .extracting(Row::accountId, Row::credit)
                .contains(tuple(cash.getId(), new BigDecimal("300.00")));
        assertTrialBalanceBalances();
    }

    /**
     * Controller ruling (Plan 4): a payment voucher line carries no VAT. Task 2
     * refuses it on the way in; this proves posting refuses it again on the way
     * out. The VAT is put on the row with SQL precisely because no service path
     * can produce it — and a BPV journal credits the payment account with the
     * <em>net</em>, so a VAT-bearing row would post a balanced entry in which the
     * VAT the clerk typed had simply evaporated.
     */
    @Test
    void aPaymentVoucherWithVatIsRefusedAtPost() {
        Voucher v = vouchers.createDraft(new VoucherService.VoucherInput(
                VoucherType.BPV, LocalDate.of(2026, 10, 20), null, null, "x", null, null,
                bank.getId(), null, null,
                List.of(new VoucherService.VoucherLineInput(salaries.getId(), null,
                        new BigDecimal("1000.00"), BigDecimal.ZERO, null, null))));
        jdbc.update("update voucher_lines set vat_rate = 5.00, vat_amount = 50.00 where voucher_id = ?", v.getId());

        assertThatThrownBy(() -> vouchers.post(v.getId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("A payment voucher line cannot carry VAT — record the VAT on the purchase invoice");
        assertThat(vouchers.get(v.getId()).getStatus()).isEqualTo(VoucherStatus.DRAFT);
        assertThat(voucherJournals(v.getId())).isEmpty();
    }

    /**
     * Money leaves from a bank or cash leaf, never from a receivable: the same rule
     * {@code ChequeService.requireSettlementAccount} applies to a cheque's debit
     * account. A receivable leaf is an active asset leaf, so the draft accepts it
     * and only posting can catch it.
     */
    @Test
    void aPaymentAccountThatIsNotBankOrCashIsRefusedAtPost() {
        Account receivable = accounts.createLeaf("Rental Receivable - Ocean",
                accounts.getAccountByCode("A-02-01"), null);
        Voucher v = vouchers.createDraft(new VoucherService.VoucherInput(
                VoucherType.BPV, LocalDate.of(2026, 10, 20), null, null, "x", null, null,
                receivable.getId(), null, null,
                List.of(new VoucherService.VoucherLineInput(salaries.getId(), null,
                        new BigDecimal("100.00"), BigDecimal.ZERO, null, null))));
        assertThatThrownBy(() -> vouchers.post(v.getId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("must be a bank or cash account");
    }

    /**
     * The document pair an accountant actually runs: the invoice raises the vendor's
     * balance by its gross and the payment clears it. Both halves through the real
     * services, asserted on the vendor ledger rather than on the journals, because
     * the ledger is what the vendor and the accountant argue over.
     */
    @Test
    void theInvoiceAndItsPaymentLeaveTheVendorLedgerFlat() {
        Voucher invoice = vouchers.post(vouchers.createDraft(new VoucherService.VoucherInput(
                VoucherType.PISR, LocalDate.of(2026, 10, 15), vendor.getId(), "EMR-4471",
                "October services", null, null, null, null, null,
                List.of(new VoucherService.VoucherLineInput(salaries.getId(), "Manpower",
                        new BigDecimal("5000.00"), new BigDecimal("5"), null, null)))).getId());
        assertThat(invoice.getStatus()).isEqualTo(VoucherStatus.POSTED);

        // 5,000.00 net + 5% = 250.00 VAT = 5,250.00 gross.
        AccountLedgerDTO afterInvoice = tx.execute(s -> ledger.vendorLedger(vendor.getId(), null, null));
        assertThat(afterInvoice.closingBalance()).isEqualByComparingTo("-5250.00");

        vouchers.post(vouchers.createDraft(new VoucherService.VoucherInput(
                VoucherType.BPV, LocalDate.of(2026, 10, 20), null, null, "Settle EMR-4471", null, null,
                bank.getId(), "000451", LocalDate.of(2026, 10, 20),
                List.of(new VoucherService.VoucherLineInput(vendor.getPayableAccount().getId(), "EMR-4471",
                        new BigDecimal("5250.00"), BigDecimal.ZERO, null, null)))).getId());

        AccountLedgerDTO afterPayment = tx.execute(s -> ledger.vendorLedger(vendor.getId(), null, null));
        assertThat(afterPayment.rows()).hasSize(2);
        assertThat(afterPayment.closingBalance()).isEqualByComparingTo("0.00");
        assertTrialBalanceBalances();
    }
}
