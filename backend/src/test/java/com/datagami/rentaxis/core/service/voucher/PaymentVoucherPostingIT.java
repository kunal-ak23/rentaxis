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
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

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
class PaymentVoucherPostingIT extends AbstractPostgresIT {

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
    Vendor vendor, otherVendor;

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
        v.setTrn("100123456700003");   // a PISR with input VAT needs it (finance-ops spec §2)
        vendor = vendorService.createVendor(v);
        Vendor other = new Vendor();
        other.setNameEn("Al Shirawi FM");
        otherVendor = vendorService.createVendor(other);
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
                VoucherType.BPV, LocalDate.of(2026, 10, 20), vendor.getId(), null,
                "October payment run", null, null, bank.getId(), "000451", LocalDate.of(2026, 10, 20),
                List.of(sharedLine(vendor.getPayableAccount().getId(),
                                "Settle EMR-4471", new BigDecimal("5100.00"), BigDecimal.ZERO, null, null),
                        sharedLine(salaries.getId(),
                                "Watchman salary", new BigDecimal("2500.00"), BigDecimal.ZERO, null, null))));

        // F14-42: the till is empty in this fixture; the user confirmed the overdraft.
        Voucher posted = vouchers.post(v.getId(), List.of(), null, VoucherService.PostOptions.of(null, true));
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
                bank.getId(), "000451", LocalDate.of(2026, 10, 20),
                List.of(sharedLine(salaries.getId(), null,
                        new BigDecimal("100.00"), BigDecimal.ZERO, null, null))));
        // F14-42: the till is empty in this fixture; the user confirmed the overdraft.
        Voucher posted = vouchers.post(v.getId(), List.of(), null, VoucherService.PostOptions.of(null, true));
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
    /** F14-42: a cash payment may not take the till below zero unless the user confirms it. */
    @Test
    void aCashPaymentThatWouldOverdrawTheTillIsRefusedUntilConfirmed() {
        Account cash = accounts.createLeaf("Petty Cash", accounts.getAccountByCode("A-02-05"), null);
        Voucher v = vouchers.createDraft(new VoucherService.VoucherInput(
                VoucherType.BPV, LocalDate.of(2026, 10, 20), null, null, "Petty cash", null, null,
                cash.getId(), null, null,
                List.of(sharedLine(salaries.getId(), null, new BigDecimal("300.00"), BigDecimal.ZERO, null, null))));
        assertThatThrownBy(() -> vouchers.post(v.getId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Petty Cash holds 0.00 on 20/10/2026; paying 300.00 from it would leave it at -300.00");
        assertThat(vouchers.post(v.getId(), List.of(), null, VoucherService.PostOptions.of(null, true)).getStatus())
                .isEqualTo(VoucherStatus.POSTED);
    }

    /** F14-42: a line paid to the payment account itself moves nothing and is refused. */
    @Test
    void aLinePaidToThePaymentAccountItselfIsRefused() {
        assertThatThrownBy(() -> vouchers.post(vouchers.createDraft(new VoucherService.VoucherInput(
                VoucherType.BPV, LocalDate.of(2026, 10, 20), null, null, "Neutralise", null, null,
                bank.getId(), null, null,
                List.of(sharedLine(bank.getId(), null, new BigDecimal("0.01"), BigDecimal.ZERO, null, null)))).getId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("the account the payment is made from");
    }

    @Test
    void cashPaymentsUseTheCashLeafAsThePaymentAccount() {
        Account cash = accounts.createLeaf("Petty Cash", accounts.getAccountByCode("A-02-05"), null);
        Voucher v = vouchers.createDraft(new VoucherService.VoucherInput(
                VoucherType.BPV, LocalDate.of(2026, 10, 20), null, null, "Petty cash", null, null,
                cash.getId(), null, null,
                List.of(sharedLine(salaries.getId(), null,
                        new BigDecimal("300.00"), BigDecimal.ZERO, null, null))));
        // F14-42: the till is empty in this fixture; the user confirmed the overdraft.
        Voucher posted = vouchers.post(v.getId(), List.of(), null, VoucherService.PostOptions.of(null, true));
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
                List.of(sharedLine(salaries.getId(), null,
                        new BigDecimal("1000.00"), BigDecimal.ZERO, null, null))));
        jdbc.update("update voucher_lines set vat_rate = 5.00, vat_amount = 50.00 where voucher_id = ?", v.getId());

        assertThatThrownBy(() -> vouchers.post(v.getId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("A payment voucher line cannot carry VAT — record the VAT on the purchase invoice");
        assertThat(vouchers.get(v.getId()).getStatus()).isEqualTo(VoucherStatus.DRAFT);
        assertThat(voucherJournals(v.getId())).isEmpty();
    }

    /**
     * A rate with no amount behind it is the same refusal (review M-2): the journal
     * would credit the bank with the net and charge no VAT at all, while the
     * document on screen says it charged 5%.
     */
    @Test
    void aPaymentVoucherCarryingOnlyAVatRateIsAlsoRefusedAtPost() {
        Voucher v = vouchers.createDraft(new VoucherService.VoucherInput(
                VoucherType.BPV, LocalDate.of(2026, 10, 20), null, null, "x", null, null,
                bank.getId(), null, null,
                List.of(sharedLine(salaries.getId(), null,
                        new BigDecimal("1000.00"), BigDecimal.ZERO, null, null))));
        jdbc.update("update voucher_lines set vat_rate = 5.00 where voucher_id = ?", v.getId());

        assertThatThrownBy(() -> vouchers.post(v.getId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("A payment voucher line cannot carry VAT — record the VAT on the purchase invoice");
        assertThat(voucherJournals(v.getId())).isEmpty();
    }

    /**
     * Money leaves from a bank or cash leaf, never from a receivable: the same rule
     * {@code ChequeService.requireSettlementAccount} applies to a cheque's debit
     * account.
     *
     * <p>Task 5 moved this check into {@code validate()} as well (a clerk should
     * not be able to save a draft the server will always refuse later — see
     * {@code VoucherDraftIT#aBpvPaymentAccountThatIsNotBankOrCashIsRejectedAtDraftTime}),
     * so a receivable leaf can no longer reach {@code post()} through the service —
     * {@code createDraft} itself would now refuse it. This test plants the invalid
     * account directly in the row instead, to prove {@code requirePostable} still
     * catches a draft that reached this state some other way (SQL, a restored
     * backup, a future code path that skips {@code validate()}).
     */
    @Test
    void aPaymentAccountThatIsNotBankOrCashIsRefusedAtPost() {
        Account receivable = accounts.createLeaf("Rental Receivable - Ocean",
                accounts.getAccountByCode("A-02-01"), null);
        Voucher v = vouchers.createDraft(new VoucherService.VoucherInput(
                VoucherType.BPV, LocalDate.of(2026, 10, 20), null, null, "x", null, null,
                bank.getId(), null, null,
                List.of(sharedLine(salaries.getId(), null,
                        new BigDecimal("100.00"), BigDecimal.ZERO, null, null))));
        jdbc.update("update vouchers set payment_account_id = ? where id = ?", receivable.getId(), v.getId());
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
                List.of(sharedLine(salaries.getId(), "Manpower",
                        new BigDecimal("5000.00"), new BigDecimal("5"), null, null)))).getId());
        assertThat(invoice.getStatus()).isEqualTo(VoucherStatus.POSTED);

        // 5,000.00 net + 5% = 250.00 VAT = 5,250.00 gross.
        AccountLedgerDTO afterInvoice = tx.execute(s -> ledger.vendorLedger(vendor.getId(), null, null));
        assertThat(afterInvoice.closingBalance()).isEqualByComparingTo("-5250.00");

        vouchers.post(vouchers.createDraft(new VoucherService.VoucherInput(
                VoucherType.BPV, LocalDate.of(2026, 10, 20), vendor.getId(), null, "Settle EMR-4471", null, null,
                bank.getId(), "000451", LocalDate.of(2026, 10, 20),
                List.of(sharedLine(vendor.getPayableAccount().getId(), "EMR-4471",
                        new BigDecimal("5250.00"), BigDecimal.ZERO, null, null)))).getId());

        AccountLedgerDTO afterPayment = tx.execute(s -> ledger.vendorLedger(vendor.getId(), null, null));
        assertThat(afterPayment.rows()).hasSize(2);
        assertThat(afterPayment.closingBalance()).isEqualByComparingTo("0.00");

        // The vendor who was not paid has not moved.
        AccountLedgerDTO untouched = tx.execute(s -> ledger.vendorLedger(otherVendor.getId(), null, null));
        assertThat(untouched.rows()).isEmpty();
        assertThat(untouched.closingBalance()).isEqualByComparingTo("0.00");
        assertTrialBalanceBalances();
    }

    // ---- a payment voucher pays the vendor it names (review I-1) ----

    private VoucherService.VoucherInput payment(UUID vendorId, UUID lineAccountId) {
        return new VoucherService.VoucherInput(
                VoucherType.BPV, LocalDate.of(2026, 10, 20), vendorId, null, "Payment run", null, null,
                bank.getId(), null, null,
                List.of(sharedLine(lineAccountId, "Settlement",
                        new BigDecimal("1000.00"), BigDecimal.ZERO, null, null)));
    }

    /**
     * A voucher headed "paid to Emrill" whose line debits Al Shirawi's payable moves
     * Al Shirawi's ledger and leaves Emrill's untouched — the journal balances, the
     * payments list says one thing and the vendor ledger another, and nothing in the
     * ledger can ever reveal which of the two is lying. The refusal names both
     * vendors because the clerk has to know which end to correct.
     */
    @Test
    void aPaymentVoucherCannotSettleAnotherVendorsPayableAtDraftTime() {
        assertThatThrownBy(() -> vouchers.createDraft(
                payment(vendor.getId(), otherVendor.getPayableAccount().getId())))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Al Shirawi FM")
                .hasMessageContaining("Emrill Services LLC");
    }

    /** The edit path must not reopen the hole the create path closes. */
    @Test
    void theSameMismatchIsRefusedOnUpdateDraft() {
        Voucher v = vouchers.createDraft(payment(vendor.getId(), salaries.getId()));
        assertThatThrownBy(() -> vouchers.updateDraft(v.getId(),
                payment(vendor.getId(), otherVendor.getPayableAccount().getId())))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Al Shirawi FM");
    }

    /**
     * A payable line with no vendor on the header is the same mistake wearing a
     * different hat: the payments list would show no vendor at all while a vendor's
     * balance moved.
     */
    @Test
    void aPayableLineWithoutAVendorOnTheVoucherIsRefused() {
        assertThatThrownBy(() -> vouchers.createDraft(payment(null, vendor.getPayableAccount().getId())))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Emrill Services LLC");
    }

    /**
     * Re-asserted at post for a row that reached the table another way — here the
     * header vendor is swapped with SQL after a legitimate draft was saved.
     */
    @Test
    void aMismatchedVendorIsRefusedAtPost() {
        Voucher v = vouchers.createDraft(payment(vendor.getId(), vendor.getPayableAccount().getId()));
        jdbc.update("update vouchers set vendor_id = ? where id = ?", otherVendor.getId(), v.getId());

        assertThatThrownBy(() -> vouchers.post(v.getId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Emrill Services LLC");
        assertThat(vouchers.get(v.getId()).getStatus()).isEqualTo(VoucherStatus.DRAFT);
        assertThat(voucherJournals(v.getId())).isEmpty();
    }

    /**
     * Nothing in the schema stops two vendors pointing at one payable leaf
     * (`vendors.payable_account_id` carries no unique constraint, and
     * `VendorService.updateVendor` will accept a leaf another vendor already uses).
     * When that happens, a voucher naming either co-owner is settling exactly the
     * account its own vendor is settled through — the question the rule asks is
     * "is this line my vendor's payable account?", not "is my vendor the only
     * vendor who answers to it?".
     */
    @Test
    void twoVendorsSharingOnePayableAccountMayStillBePaid() {
        UUID shared = vendor.getPayableAccount().getId();
        jdbc.update("update vendors set payable_account_id = ? where id = ?", shared, otherVendor.getId());

        Voucher posted = vouchers.post(vouchers.createDraft(payment(vendor.getId(), shared)).getId());

        assertThat(posted.getStatus()).isEqualTo(VoucherStatus.POSTED);
        assertThat(journalRows(posted.getJournalId()))
                .extracting(Row::accountId, Row::debit)
                .contains(tuple(shared, new BigDecimal("1000.00")));
    }

    /** An expense line on a BPV is untouched by the rule — spec §10.2's "any leaf" still holds. */
    @Test
    void aPaymentWithNoPayableLineNeedsNoVendor() {
        Voucher posted = vouchers.post(vouchers.createDraft(payment(null, salaries.getId())).getId());
        assertThat(posted.getStatus()).isEqualTo(VoucherStatus.POSTED);
    }

    /**
     * A line marked Shared / head office (finance-ops spec §1): this fixture's
     * expense and income leaves carry no property, and the voucher rule now asks
     * such a line to say it is shared. The rule itself is pinned in
     * PropertyPnlServiceIT.voucherLinesMustNameAPropertyOrSayShared.
     */
    private static VoucherService.VoucherLineInput sharedLine(java.util.UUID accountId, String description,
            java.math.BigDecimal amount, java.math.BigDecimal vatRate, java.util.UUID propertyId, java.util.UUID unitId) {
        return new VoucherService.VoucherLineInput(accountId, description, amount, vatRate, propertyId, unitId, true);
    }
}
