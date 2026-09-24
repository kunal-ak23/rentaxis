package com.datagami.rentaxis.core.service.voucher;

import com.datagami.rentaxis.api.dto.ledger.AccountLedgerDTO;
import com.datagami.rentaxis.api.dto.ledger.ReverseRequest;
import com.datagami.rentaxis.api.dto.ledger.TrialBalanceRowDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.VendorService;
import com.datagami.rentaxis.core.service.ledger.JournalService;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Amending a posted voucher: reversal + replacement (spec §10.1).
 *
 * <p>Read-backs go through {@link #tx} — the tenant filter is only enabled inside
 * a transaction, and {@code JournalLine.account} is LAZY.</p>
 */
@SpringBootTest
class VoucherAmendIT extends AbstractPostgresIT {

    @Autowired VoucherService vouchers;
    @Autowired AccountService accounts;
    @Autowired VendorService vendorService;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired LedgerQueryService ledger;
    @Autowired JournalService journals;
    @Autowired JournalEntryRepository entries;
    @Autowired JournalLineRepository lines;
    @Autowired TenantDefaultAccountMappingRepository defaults;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired TransactionTemplate tx;

    UUID tenantId;
    Account expense, inputVat;
    Vendor vendor;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("Amend-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        accounts.seedDefaultAccounts();
        expense = accounts.createLeaf("Repairs & Maintenance", accounts.getAccountByCode("D-01"), null);
        inputVat = accounts.createLeaf("VAT Receivable", accounts.getAccountByCode("A-02-04"), null);
        TenantDefaultAccountMapping m = new TenantDefaultAccountMapping();
        m.setRole(AccountRole.INPUT_VAT);
        m.setAccount(inputVat);
        defaults.save(m);
        Vendor v = new Vendor();
        v.setNameEn("Al Shirawi FM");
        v.setTrn("100765432100003");   // a PISR with input VAT needs it (finance-ops spec §2)
        vendor = vendorService.createVendor(v);
        fiscal.setBooksStartDate(LocalDate.of(2026, 10, 1));
        fiscal.lockThrough(LocalDate.of(2026, 9, 30));
    }

    @AfterEach void clear() { TenantContextHolder.clear(); }

    /** A one-line invoice at 5%: {@code amount} net, gross = amount × 1.05. */
    private VoucherService.VoucherInput invoice(String amount) {
        return invoiceOn(LocalDate.of(2026, 10, 12), amount);
    }

    private VoucherService.VoucherInput invoiceOn(LocalDate docDate, String amount) {
        return new VoucherService.VoucherInput(
                VoucherType.PISR, docDate, vendor.getId(), "ASF-11",
                "AC servicing", null, null, null, null, null,
                List.of(sharedLine(expense.getId(), "AC servicing",
                        new BigDecimal(amount), new BigDecimal("5"), null, null)));
    }

    private BigDecimal vendorBalance() {
        AccountLedgerDTO l = tx.execute(s -> ledger.vendorLedger(vendor.getId(), null, null));
        return l.closingBalance();
    }

    private List<BigDecimal> creditsOn(UUID entryId, UUID accountId) {
        return tx.execute(s -> lines.findByEntry_IdOrderByLineNoAsc(entryId).stream()
                .filter(l -> l.getAccount().getId().equals(accountId))
                .map(JournalLine::getCredit).toList());
    }

    private long entryCount() {
        Long n = tx.execute(s -> entries.count());
        return n == null ? 0L : n;
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
     * Spec §10.1: "Amend = reversal + new voucher." The original journal is never
     * edited (entries are immutable); the ledger keeps three documents — the original,
     * its mirror, and the corrected one — which is exactly what an auditor expects to
     * find when an invoice amount changes after posting.
     */
    @Test
    void amendingReversesTheOriginalAndPostsAReplacement() {
        Voucher original = vouchers.post(vouchers.createDraft(invoice("4000.00")).getId());
        UUID originalJournalId = original.getJournalId();

        Voucher replacement = vouchers.amend(original.getId(), LocalDate.of(2026, 10, 18),
                "Vendor re-issued at the correct rate", invoice("3600.00"));

        Voucher reloadedOriginal = vouchers.get(original.getId());
        assertThat(reloadedOriginal.getStatus()).isEqualTo(VoucherStatus.REVERSED);
        assertThat(reloadedOriginal.getJournalId()).isEqualTo(originalJournalId);

        JournalEntry originalEntry = tx.execute(s -> entries.findById(originalJournalId).orElseThrow());
        assertThat(originalEntry.getStatus()).isEqualTo(JournalStatus.REVERSED);
        assertThat(originalEntry.getReversedById()).isNotNull();

        JournalEntry mirror = tx.execute(s -> entries.findById(originalEntry.getReversedById()).orElseThrow());
        assertThat(mirror.getEntryDate()).isEqualTo(LocalDate.of(2026, 10, 18));
        assertThat(mirror.getNarration()).contains("Vendor re-issued at the correct rate");

        assertThat(replacement.getId()).isNotEqualTo(original.getId());
        assertThat(replacement.getStatus()).isEqualTo(VoucherStatus.POSTED);
        assertThat(replacement.getAmendedFromId()).isEqualTo(original.getId());
        // 3,600.00 net + 5% = 180.00 VAT = 3,780.00 gross.
        assertThat(creditsOn(replacement.getJournalId(), vendor.getPayableAccount().getId()))
                .singleElement()
                .satisfies(c -> assertThat(c).isEqualByComparingTo("3780.00"));
        assertTrialBalanceBalances();
    }

    /** The three journals net to the corrected figure — 4000+200, −4200, +3600+180. */
    @Test
    void theVendorsNetPayableAfterAmendingIsTheCorrectedGross() {
        Voucher original = vouchers.post(vouchers.createDraft(invoice("4000.00")).getId());
        assertThat(vendorBalance()).isEqualByComparingTo("-4200.00");

        vouchers.amend(original.getId(), LocalDate.of(2026, 10, 18), "correction", invoice("3600.00"));

        // Debit-positive, so a payable owed reads negative: 3,780.00 still due.
        assertThat(vendorBalance()).isEqualByComparingTo("-3780.00");
        assertThat(tx.execute(s -> ledger.vendorLedger(vendor.getId(), null, null)).rows()).hasSize(3);
        assertTrialBalanceBalances();
    }

    @Test
    void aDraftCannotBeAmended() {
        Voucher draft = vouchers.createDraft(invoice("4000.00"));
        assertThatThrownBy(() -> vouchers.amend(draft.getId(), LocalDate.of(2026, 10, 18), "x", invoice("100.00")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("DRAFT");
        assertThat(vouchers.get(draft.getId()).getStatus()).isEqualTo(VoucherStatus.DRAFT);
    }

    @Test
    void anAlreadyAmendedVoucherCannotBeAmendedAgain() {
        Voucher original = vouchers.post(vouchers.createDraft(invoice("4000.00")).getId());
        vouchers.amend(original.getId(), LocalDate.of(2026, 10, 18), "x", invoice("3600.00"));
        assertThatThrownBy(() -> vouchers.amend(original.getId(), LocalDate.of(2026, 10, 19), "y", invoice("3000.00")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("REVERSED");
    }

    /**
     * A reversal dated into a closed period is refused before anything is written —
     * not after the mirror entry has been built. Re-opening a filed period is what
     * the lock exists to prevent, and the amend path must not be a way round it.
     */
    @Test
    void aReversalDatedIntoALockedPeriodIsRefusedBeforeAnythingIsWritten() {
        Voucher original = vouchers.post(vouchers.createDraft(invoice("4000.00")).getId());

        assertThatThrownBy(() -> vouchers.amend(original.getId(), LocalDate.of(2026, 9, 20),
                "back-dated", invoice("3600.00")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("locked");

        assertThat(vouchers.get(original.getId()).getStatus()).isEqualTo(VoucherStatus.POSTED);
        assertThat(tx.execute(s -> entries.findById(original.getJournalId()).orElseThrow()).getStatus())
                .isEqualTo(JournalStatus.POSTED);
        assertThat(vendorBalance()).isEqualByComparingTo("-4200.00");
    }

    /**
     * Atomicity: the reversal and the replacement are one transaction. The
     * replacement here is dated into the locked period, so it is {@code post} that
     * fails — after the reversal has already been written. If that reversal
     * survived, the books would show the invoice cancelled and nothing raised in
     * its place, and the vendor's balance would silently drop to zero.
     */
    @Test
    void aFailedReplacementLeavesTheOriginalPostedAndUnreversed() {
        Voucher original = vouchers.post(vouchers.createDraft(invoice("4000.00")).getId());
        UUID originalJournalId = original.getJournalId();

        assertThatThrownBy(() -> vouchers.amend(original.getId(), LocalDate.of(2026, 10, 18),
                "correction", invoiceOn(LocalDate.of(2026, 9, 12), "3600.00")))
                .isInstanceOf(BusinessRuleViolationException.class);

        assertThat(vouchers.get(original.getId()).getStatus()).isEqualTo(VoucherStatus.POSTED);
        JournalEntry entry = tx.execute(s -> entries.findById(originalJournalId).orElseThrow());
        assertThat(entry.getStatus()).isEqualTo(JournalStatus.POSTED);
        assertThat(entry.getReversedById()).isNull();
        assertThat(vouchers.list(null, null, null, null, null, null, PageRequest.of(0, 25)).getContent())
                .as("no half-built replacement")
                .extracting(Voucher::getId).containsExactly(original.getId());
        assertThat(vendorBalance()).isEqualByComparingTo("-4200.00");
        assertTrialBalanceBalances();
    }

    /**
     * The journal behind a posted voucher may not be reversed through the journal
     * screen: that would leave the voucher reading POSTED with its vendor's payable
     * gone, and `amend` would then dead-end on "already reversed". The correction
     * for a voucher is Amend, and the refusal says so.
     */
    @Test
    void aPostedVouchersJournalCannotBeReversedBehindItsBack() {
        Voucher original = vouchers.post(vouchers.createDraft(invoice("4000.00")).getId());

        assertThatThrownBy(() -> journals.reverse(original.getJournalId(),
                new ReverseRequest(LocalDate.of(2026, 10, 18), "wrong amount")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("voucher");

        assertThat(vouchers.get(original.getId()).getStatus()).isEqualTo(VoucherStatus.POSTED);
        assertThat(tx.execute(s -> entries.findById(original.getJournalId()).orElseThrow()).getStatus())
                .isEqualTo(JournalStatus.POSTED);
        assertThat(vendorBalance()).isEqualByComparingTo("-4200.00");

        // …and the door that is open still works.
        Voucher replacement = vouchers.amend(original.getId(), LocalDate.of(2026, 10, 18), "wrong amount",
                invoice("3600.00"));
        assertThat(replacement.getStatus()).isEqualTo(VoucherStatus.POSTED);
        assertThat(vendorBalance()).isEqualByComparingTo("-3780.00");
    }

    /**
     * P0. Amending is two writes into the ledger, and {@code lockForWrite}'s
     * {@code EntityManager.find} is outside {@code TenantAspect}'s reach (the aspect
     * enables the Hibernate filter around {@code domain.repository..*} calls only,
     * and none has run in this transaction yet) — so the explicit tenant comparison
     * is the only guard here and is asserted rather than assumed.
     */
    @Test
    void tenantBCannotAmendTenantAsVoucher() {
        Voucher original = vouchers.post(vouchers.createDraft(invoice("4000.00")).getId());
        long entriesBefore = entryCount();

        LandlordOrg orgB = new LandlordOrg();
        orgB.setName("Amend-B-" + UUID.randomUUID());
        TenantContextHolder.setTenantId(orgRepo.save(orgB).getId());
        // The message matters: with the tenant comparison gone the call still fails,
        // but on "Journal entry not found" from the reversal's own repository lookup
        // — an accident of ordering rather than the guard this test is about.
        assertThatThrownBy(() -> vouchers.amend(original.getId(), LocalDate.of(2026, 10, 18), "x", invoice("100.00")))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Voucher not found");

        TenantContextHolder.setTenantId(tenantId);
        assertThat(vouchers.get(original.getId()).getStatus()).isEqualTo(VoucherStatus.POSTED);
        assertThat(tx.execute(s -> entries.findById(original.getJournalId()).orElseThrow()).getStatus())
                .isEqualTo(JournalStatus.POSTED);
        assertThat(entryCount()).as("no reversal written").isEqualTo(entriesBefore);
        assertThat(vendorBalance()).isEqualByComparingTo("-4200.00");
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
