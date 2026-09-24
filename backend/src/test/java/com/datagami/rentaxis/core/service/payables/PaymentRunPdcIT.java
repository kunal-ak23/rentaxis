package com.datagami.rentaxis.core.service.payables;

import com.datagami.rentaxis.api.dto.payables.*;
import com.datagami.rentaxis.api.dto.report.PropertyStatementDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.VendorService;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.service.report.PropertyPnlService;
import com.datagami.rentaxis.core.service.report.statement.PropertyStatementService;
import com.datagami.rentaxis.core.service.voucher.VoucherAllocationService;
import com.datagami.rentaxis.core.service.voucher.VoucherAllocationService.AllocationInput;
import com.datagami.rentaxis.core.service.voucher.VoucherService;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Payment runs and PDC payable (finance-ops spec §2, PR 3b).
 *
 * <p>The worked example is replayed in full: {@code BPV-26/50} pays Gulf AC by PDC
 * 000031 dated 28/09 (held in PDC payable), the run pays INV-7781 in full and
 * INV-7790 in part by transfer on 10/09, and 000031 is presented on 28/09. The
 * app clock is pinned to 30/09/2026 so that presentation is not in the future.</p>
 */
@SpringBootTest
@Import(PaymentRunPdcIT.FixedClockConfig.class)
class PaymentRunPdcIT extends AbstractPostgresIT {

    static final LocalDate TODAY = LocalDate.of(2026, 9, 30);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            ZoneId dubai = ZoneId.of("Asia/Dubai");
            return Clock.fixed(TODAY.atTime(10, 0).atZone(dubai).toInstant(), dubai);
        }
    }

    @Autowired VoucherService vouchers;
    @Autowired VoucherAllocationService allocations;
    @Autowired PaymentRunService runs;
    @Autowired IssuedChequeService cheques;
    @Autowired PayablesService payables;
    @Autowired PropertyStatementService statements;
    @Autowired PropertyPnlService pnl;
    @Autowired AccountService accounts;
    @Autowired VendorService vendorService;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired PostingService posting;
    @Autowired JournalLineRepository lines;
    @Autowired JournalEntryRepository entries;
    @Autowired VoucherRepository voucherRepo;
    @Autowired VoucherAllocationRepository allocationRepo;
    @Autowired IssuedChequeRepository issuedRepo;
    @Autowired TenantDefaultAccountMappingRepository defaults;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.datagami.rentaxis.core.service.LandlordOrgService orgService;

    static final LocalDate AUG_1 = LocalDate.of(2026, 8, 1);
    static final LocalDate AUG_15 = LocalDate.of(2026, 8, 15);
    static final LocalDate AUG_20 = LocalDate.of(2026, 8, 20);
    static final LocalDate AUG_31 = LocalDate.of(2026, 8, 31);
    static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);
    static final LocalDate SEP_5 = LocalDate.of(2026, 9, 5);
    static final LocalDate SEP_10 = LocalDate.of(2026, 9, 10);
    static final LocalDate SEP_27 = LocalDate.of(2026, 9, 27);
    static final LocalDate SEP_28 = LocalDate.of(2026, 9, 28);
    static final LocalDate SEP_30 = LocalDate.of(2026, 9, 30);

    UUID tenantId;
    Property p1, p2;
    Account rmP1, cleaningP1, rmP2, securityP2, inputVat, bank, pdcPayable;
    Vendor gulf, alNoor;

    @BeforeEach
    void setUp() {
        tenantId = newTenant("RUN-");
        p1 = property("Marina Tower");
        p2 = property("Palm Residence");
        Account d01 = accounts.getAccountByCode("D-01");
        rmP1 = accounts.createLeaf("Repairs & Maintenance - Marina Tower", d01, p1.getId());
        cleaningP1 = accounts.createLeaf("Cleaning - Marina Tower", d01, p1.getId());
        rmP2 = accounts.createLeaf("Repairs & Maintenance - Palm Residence", d01, p2.getId());
        securityP2 = accounts.createLeaf("Security - Palm Residence", d01, p2.getId());
        inputVat = accounts.createLeaf("VAT Receivable", accounts.getAccountByCode("A-02-04"), null);
        mapDefault(AccountRole.INPUT_VAT, inputVat);
        bank = accounts.createLeaf("Emirates Islamic - Marina Tower", accounts.getAccountByCode("A-02-02"), null);
        pdcPayable = accounts.getAccountByCode("B-02-001");
        // What PropertyAccountService.seed does for a real tenant (defaultIfMissing).
        mapDefault(AccountRole.PDC_PAYABLE, pdcPayable);
        gulf = vendor("Gulf AC Services LLC", "100123456700003", "AE070331234567890123456");
        alNoor = vendor("Al Noor Cleaning", "100765432100003", "AE460260001015555555501");
        fiscal.setBooksStartDate(AUG_1);
    }

    @AfterEach void clear() { TenantContextHolder.clear(); }

    // ------------------------------------------------------------------ fixtures

    private UUID newTenant(String prefix) {
        LandlordOrg org = new LandlordOrg();
        org.setName(prefix + UUID.randomUUID());
        UUID id = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(id);
        accounts.seedDefaultAccounts();
        return id;
    }

    private Property property(String name) {
        Property p = new Property();
        p.setNameEn(name);
        p.setEmirate(Emirate.DUBAI);
        return propertyRepo.save(p);
    }

    private Vendor vendor(String name, String trn, String iban) {
        Vendor v = new Vendor();
        v.setNameEn(name);
        v.setTrn(trn);
        v.setIban(iban);
        v.setPaymentTermsDays(30);
        return vendorService.createVendor(v);
    }

    private void mapDefault(AccountRole role, Account a) {
        TenantDefaultAccountMapping m = new TenantDefaultAccountMapping();
        m.setRole(role);
        m.setAccount(a);
        defaults.save(m);
    }

    private static VoucherService.VoucherLineInput line(Account a, String amount, String vat, Property p) {
        return new VoucherService.VoucherLineInput(a.getId(), a.getName(), new BigDecimal(amount), new BigDecimal(vat),
                p.getId(), null);
    }

    private Voucher pisr(Vendor v, String invoiceNumber, LocalDate date, VoucherService.VoucherLineInput... ls) {
        return vouchers.post(vouchers.createDraft(new VoucherService.VoucherInput(VoucherType.PISR, date, v.getId(),
                invoiceNumber, invoiceNumber, null, null, null, null, null, List.of(ls))).getId());
    }

    private VoucherService.VoucherInput payment(Vendor v, LocalDate date, String amount, VoucherPaymentMethod method,
                                                String chequeNumber, LocalDate chequeDate) {
        return new VoucherService.VoucherInput(VoucherType.BPV, date, v.getId(), null, "Payment", null, null,
                bank.getId(), chequeNumber, chequeDate,
                List.of(new VoucherService.VoucherLineInput(v.getPayableAccount().getId(), "Settlement",
                        new BigDecimal(amount), BigDecimal.ZERO, null, null)),
                null, null, method, method == VoucherPaymentMethod.TRANSFER ? "TRF" : null);
    }

    private Voucher pdc(Vendor v, LocalDate date, String amount, String chequeNumber, LocalDate chequeDate,
                        AllocationInput... allocs) {
        return vouchers.post(vouchers.createDraft(payment(v, date, amount, VoucherPaymentMethod.CHEQUE, chequeNumber,
                chequeDate)).getId(), List.of(allocs));
    }

    private Voucher transfer(Vendor v, LocalDate date, String amount, AllocationInput... allocs) {
        return vouchers.post(vouchers.createDraft(payment(v, date, amount, VoucherPaymentMethod.TRANSFER, null, null))
                .getId(), List.of(allocs));
    }

    private static AllocationInput to(Voucher invoice, String amount) {
        return new AllocationInput(invoice.getId(), null, new BigDecimal(amount));
    }

    private static PaymentRunInputDTO.Item pay(Voucher invoice, String amount) {
        return new PaymentRunInputDTO.Item(invoice.getId(), null, new BigDecimal(amount), true);
    }

    private PaymentRunDTO run(LocalDate date, VoucherPaymentMethod method, LocalDate chequeDate, String firstCheque,
                              PaymentRunInputDTO.Item... items) {
        return runs.create(new PaymentRunInputDTO(date, bank.getId(), method, chequeDate, firstCheque, null, List.of(items)));
    }

    record Row(UUID accountId, BigDecimal debit, BigDecimal credit) { }

    private List<Row> journal(UUID entryId) {
        return tx.execute(s -> lines.findByEntry_IdOrderByLineNoAsc(entryId).stream()
                .map(l -> new Row(l.getAccount().getId(), l.getDebit(), l.getCredit())).toList());
    }

    /** Σ(debit − credit) on a leaf up to and including {@code asOf}. */
    private BigDecimal balance(Account a, LocalDate asOf) {
        return jdbc.queryForObject("""
                select coalesce(sum(l.debit - l.credit), 0) from journal_lines l join journal_entries e on e.id = l.journal_entry_id
                where l.account_id = ? and e.entry_date <= ?""", BigDecimal.class, a.getId(), asOf);
    }

    private IssuedCheque chequeOf(Voucher v) {
        return issuedRepo.findByVoucherId(v.getId()).get(0);
    }

    private List<Voucher> bpvsOfRun(UUID runId) {
        return tx.execute(s -> voucherRepo.findAll().stream().filter(v -> runId.equals(v.getPaymentRunId())).toList());
    }

    private OpenItemDTO item(Vendor v, String invoiceNumber) {
        return payables.vendorItems(v.getId()).stream().filter(i -> invoiceNumber.equals(i.invoiceNumber()))
                .findFirst().orElseThrow();
    }

    private PayablesAgingDTO.VendorRow row(PayablesAgingDTO a, Vendor v) {
        return a.rows().stream().filter(r -> r.vendorId().equals(v.getId())).findFirst().orElseThrow();
    }

    private static PropertyStatementDTO.Section section(PropertyStatementDTO s, String key) {
        return s.sections().stream().filter(x -> x.key().equals(key)).findFirst().orElseThrow();
    }

    private static BigDecimal figure(PropertyStatementDTO s, String section, String key) {
        return section(s, section).figures().stream().filter(f -> f.key().equals(key)).findFirst().orElseThrow().amount();
    }

    // ------------------------------------------------------------------ worked example

    @Test
    void theWorkedExampleWithThePdcTheRunAndThePresentation() {
        Voucher inv7702 = pisr(gulf, "INV-7702", AUG_1, line(rmP2, "19047.62", "5", p2));
        Voucher inv7781 = pisr(gulf, "INV-7781", AUG_1, line(rmP1, "1000.00", "5", p1), line(cleaningP1, "400.00", "0", p1));
        Voucher inv7790 = pisr(gulf, "INV-7790", AUG_20, line(securityP2, "2000.00", "5", p2));

        // Row 4: BPV-26/50, PDC 000031 dated 28/09, issued 15/08.
        Voucher bpv50 = pdc(gulf, AUG_15, "20000.00", "000031", SEP_28, to(inv7702, "20000.00"));
        assertThat(journal(bpv50.getJournalId())).containsExactly(
                new Row(gulf.getPayableAccount().getId(), new BigDecimal("20000.00"), new BigDecimal("0.00")),
                new Row(pdcPayable.getId(), new BigDecimal("0.00"), new BigDecimal("20000.00")));
        String narration = tx.execute(s -> lines.findByEntry_IdOrderByLineNoAsc(bpv50.getJournalId()).get(1).getNarration());
        assertThat(narration).isEqualTo("PDC 000031 dated 28/09/2026 on Emirates Islamic - Marina Tower");
        IssuedCheque c31 = chequeOf(bpv50);
        assertThat(c31.getStatus()).isEqualTo(IssuedCheque.Status.ISSUED);
        assertThat(c31.getAmount()).isEqualByComparingTo("20000.00");
        assertThat(c31.getBankAccountId()).isEqualTo(bank.getId());
        assertThat(c31.getChequeDate()).isEqualTo(SEP_28);
        assertThat(item(gulf, "INV-7702").status()).isEqualTo("PAID");

        // Row 5.
        pisr(alNoor, "AN-311", SEP_5, line(cleaningP1, "3000.00", "5", p1));

        // Row 6: the run, due by 30/09, transfer from EI – Marina; INV-7790 in part (the rest is disputed).
        PaymentRunDTO draft = run(SEP_10, VoucherPaymentMethod.TRANSFER, null, null,
                pay(inv7781, "1450.00"), pay(inv7790, "600.00"));
        assertThat(draft.runNumber()).matches("PR-26/\\d+");
        PaymentRunPreviewDTO preview = runs.preview(draft.id());
        assertThat(preview.postable()).isTrue();
        assertThat(preview.problems()).isEmpty();
        assertThat(preview.vendors()).singleElement().satisfies(vp -> {
            assertThat(vp.vendorName()).isEqualTo("Gulf AC Services LLC");
            assertThat(vp.netPayment()).isEqualByComparingTo("2050.00");
            assertThat(vp.advanceApplied()).isEqualByComparingTo("0.00");
            assertThat(vp.postDated()).isFalse();
            assertThat(vp.journal()).extracting(PaymentRunPreviewDTO.JournalLine::accountName,
                    PaymentRunPreviewDTO.JournalLine::debit, PaymentRunPreviewDTO.JournalLine::credit).containsExactly(
                    tuple(gulf.getPayableAccount().getName(), new BigDecimal("2050.00"), new BigDecimal("0.00")),
                    tuple("Emirates Islamic - Marina Tower", new BigDecimal("0.00"), new BigDecimal("2050.00")));
        });
        PaymentRunDTO posted = runs.post(draft.id());
        assertThat(posted.status()).isEqualTo("POSTED");
        Voucher bpv55 = bpvsOfRun(draft.id()).get(0);
        assertThat(bpvsOfRun(draft.id())).hasSize(1);
        assertThat(journal(bpv55.getJournalId())).containsExactly(
                new Row(gulf.getPayableAccount().getId(), new BigDecimal("2050.00"), new BigDecimal("0.00")),
                new Row(bank.getId(), new BigDecimal("0.00"), new BigDecimal("2050.00")));
        assertThat(bpv55.getPaymentReference()).isEqualTo(draft.runNumber());
        assertThat(bpv55.getDocDate()).isEqualTo(SEP_10);
        assertThat(allocationRepo.findByPaymentVoucherIdAndReleasedOnIsNull(bpv55.getId()))
                .extracting(a -> a.getAmount().toPlainString(), VoucherAllocation::getPaymentRunId)
                .containsExactlyInAnyOrder(tuple("1450.00", draft.id()), tuple("600.00", draft.id()));
        assertThat(item(gulf, "INV-7781").status()).isEqualTo("PAID");
        assertThat(item(gulf, "INV-7790").status()).isEqualTo("PART_PAID");
        assertThat(item(gulf, "INV-7790").open()).isEqualByComparingTo("1500.00");

        // Aging as of 31/08: Gulf owes 1,450 + 2,100 (INV-7702 is settled by the PDC); Δ 0.
        PayablesAgingDTO.Figures aug = row(payables.aging(AUG_31, null, null), gulf).figures();
        assertThat(aug.current()).isEqualByComparingTo("3550.00");
        assertThat(aug.ledgerBalance()).isEqualByComparingTo("3550.00");
        assertThat(aug.delta()).isEqualByComparingTo("0.00");
        // At 31/08 000031 is outstanding and ties to PDC_PAYABLE; the bank has not moved.
        assertThat(balance(pdcPayable, AUG_31)).isEqualByComparingTo("-20000.00");
        assertThat(balance(bank, AUG_31)).isEqualByComparingTo("0.00");
        assertThat(cheques.list(IssuedCheque.Status.ISSUED, null, null, null, false))
                .extracting(IssuedChequeDTO::chequeNumber).containsExactly("000031");

        // Statement pack, August, P2: the PDC counts in section 7 when allocated, and the note says so.
        PropertyStatementDTO augP2 = statements.statement(p2.getId(), AUG_1, AUG_31, null);
        assertThat(figure(augP2, "expensesPaid", "allocatedPaid")).isEqualByComparingTo("20000.00");
        assertThat(section(augP2, "expensesPaid").notes()).contains("pdcCountedWhenIssued");

        // Row 7: 000031 presented on 28/09.
        List<?> pnlBefore = pnl.cells(AUG_1, SEP_30, List.of(p1.getId(), p2.getId()));
        IssuedChequeDTO presented = cheques.present(c31.getId(), SEP_28);
        assertThat(presented.status()).isEqualTo("PRESENTED");
        assertThat(presented.bpcNumber()).matches("BPC-26/\\d+");
        IssuedCheque after = issuedRepo.findById(c31.getId()).orElseThrow();
        JournalEntry bpc = entries.findById(after.getBpcJournalId()).orElseThrow();
        assertThat(bpc.getDocType()).isEqualTo(JournalDocType.BPC);
        assertThat(bpc.getEntryDate()).isEqualTo(SEP_28);
        assertThat(bpc.getSourceType()).isEqualTo(JournalSourceType.ISSUED_CHEQUE);
        assertThat(bpc.getSourceId()).isEqualTo(c31.getId());
        assertThat(journal(bpc.getId())).containsExactly(
                new Row(pdcPayable.getId(), new BigDecimal("20000.00"), new BigDecimal("0.00")),
                new Row(bank.getId(), new BigDecimal("0.00"), new BigDecimal("20000.00")));
        // BPC touches neither the P&L nor the payables aging.
        assertThat(pnl.cells(AUG_1, SEP_30, List.of(p1.getId(), p2.getId()))).isEqualTo(pnlBefore);

        // The bank drops by 20,000 on 28/09, not on 15/08.
        assertThat(balance(bank, SEP_27)).isEqualByComparingTo("-2050.00");
        assertThat(balance(bank, SEP_28)).isEqualByComparingTo("-22050.00");
        assertThat(balance(pdcPayable, SEP_30)).isEqualByComparingTo("0.00");

        // Aging as of 30/09: the spec's table.
        PayablesAgingDTO sep = payables.aging(SEP_30, null, null);
        assertThat(row(sep, alNoor).figures().current()).isEqualByComparingTo("3150.00");
        assertThat(row(sep, gulf).figures().d1to30()).isEqualByComparingTo("1500.00");
        assertThat(sep.totals().openTotal()).isEqualByComparingTo("4650.00");
        assertThat(sep.totals().ledgerBalance()).isEqualByComparingTo("4650.00");
        assertThat(sep.totals().delta()).isEqualByComparingTo("0.00");
        // No issued PDC outstanding at 30/09; the register ties to PDC_PAYABLE.
        IssuedChequeSummaryDTO summary = cheques.summary();
        assertThat(summary.outstandingTotal()).isEqualByComparingTo("0.00");
        assertThat(summary.pdcPayableBalance()).isEqualByComparingTo("0.00");
        assertThat(summary.difference()).isEqualByComparingTo("0.00");
        // September, P2: INV-7790's 600 paid by transfer; no PDC note.
        PropertyStatementDTO sepP2 = statements.statement(p2.getId(), SEP_1, SEP_30, null);
        assertThat(figure(sepP2, "expensesPaid", "allocatedPaid")).isEqualByComparingTo("600.00");
        assertThat(section(sepP2, "expensesPaid").notes()).doesNotContain("pdcCountedWhenIssued");
    }

    // ------------------------------------------------------------------ the PDC branch

    @Test
    void onlyAChequeDatedAfterTheVoucherGoesToPdcPayable() {
        Voucher inv = pisr(gulf, "INV-B", AUG_1, line(rmP1, "900.00", "0", p1));
        // Same-day cheque: credits the bank, no register row.
        Voucher sameDay = pdc(gulf, AUG_15, "100.00", "000001", AUG_15);
        assertThat(journal(sameDay.getJournalId()).get(1).accountId()).isEqualTo(bank.getId());
        assertThat(issuedRepo.findByVoucherId(sameDay.getId())).isEmpty();
        // A transfer with a later "cheque date" is not a cheque.
        VoucherService.VoucherInput t = payment(gulf, AUG_15, "100.00", VoucherPaymentMethod.TRANSFER, null, SEP_10);
        Voucher transfer = vouchers.post(vouchers.createDraft(t).getId());
        assertThat(journal(transfer.getJournalId()).get(1).accountId()).isEqualTo(bank.getId());
        assertThat(issuedRepo.findByVoucherId(transfer.getId())).isEmpty();
        // One day later: PDC payable.
        Voucher later = pdc(gulf, AUG_15, "100.00", "000002", AUG_15.plusDays(1), to(inv, "100.00"));
        assertThat(journal(later.getJournalId()).get(1).accountId()).isEqualTo(pdcPayable.getId());
        assertThat(chequeOf(later).getStatus()).isEqualTo(IssuedCheque.Status.ISSUED);
    }

    @Test
    void aChequeNumberIsIssuedOncePerBankLeaf() {
        pdc(gulf, AUG_15, "100.00", "000050", SEP_10);
        assertThatThrownBy(() -> pdc(alNoor, AUG_20, "200.00", " 000050 ", SEP_10))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Cheque 000050 on Emirates Islamic - Marina Tower is already issued");
    }

    // ------------------------------------------------------------------ present / unpresent / cancel

    @Test
    void presentUnpresentAndCancelFollowTheStateMachineAndLeavePdcPayableAtZero() {
        Voucher inv = pisr(gulf, "INV-S", AUG_1, line(rmP1, "5000.00", "0", p1));
        Voucher v = pdc(gulf, AUG_15, "5000.00", "000070", SEP_10, to(inv, "5000.00"));
        UUID id = chequeOf(v).getId();

        // Before its date, or in the future: refused.
        assertThatThrownBy(() -> cheques.present(id, SEP_5))
                .hasMessageContaining("a cheque cannot be presented before 10/09/2026".replace("a cheque", "A cheque"));
        assertThatThrownBy(() -> cheques.present(id, TODAY.plusDays(1))).hasMessageContaining("in the future");
        // Unpresenting an ISSUED cheque: refused.
        assertThatThrownBy(() -> cheques.unpresent(id, SEP_10, "returned")).hasMessageContaining("only a presented cheque");

        assertThat(cheques.present(id, SEP_10).status()).isEqualTo("PRESENTED");
        assertThatThrownBy(() -> cheques.present(id, SEP_10)).hasMessageContaining("already presented on 10/09/2026");
        assertThatThrownBy(() -> cheques.cancel(id, SEP_10, "stop")).hasMessageContaining("unpresent it before cancelling");
        // The payment cannot be amended away under a presented cheque either.
        assertThatThrownBy(() -> vouchers.amend(v.getId(), SEP_10, "fix",
                payment(gulf, AUG_15, "5000.00", VoucherPaymentMethod.CHEQUE, "000071", SEP_10)))
                .hasMessageContaining("unpresent it before reversing");
        assertThatThrownBy(() -> cheques.unpresent(id, SEP_10, " ")).hasMessageContaining("Give a reason");
        assertThatThrownBy(() -> cheques.unpresent(id, SEP_5, "returned")).hasMessageContaining("cannot be returned before");

        IssuedChequeDTO back = cheques.unpresent(id, LocalDate.of(2026, 9, 12), "insufficient funds");
        assertThat(back.status()).isEqualTo("ISSUED");
        assertThat(back.presentedOn()).isNull();
        // The BPC is reversed, not deleted.
        assertThat(jdbc.queryForObject("select count(*) from journal_entries where tenant_id = ? and doc_type = 'BPC'",
                Long.class, tenantId)).isEqualTo(2L);

        IssuedChequeDTO cancelled = cheques.cancel(id, LocalDate.of(2026, 9, 15), "stopped");
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.cancelledOn()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(voucherRepo.findById(v.getId()).orElseThrow().getStatus()).isEqualTo(VoucherStatus.REVERSED);
        // Allocations released: the invoice is open again (spec §2 lifecycle hooks).
        assertThat(allocations.liveOnInvoice(inv.getId(), null)).isEqualByComparingTo("0.00");
        assertThat(item(gulf, "INV-S").status()).isEqualTo("OPEN");
        assertThatThrownBy(() -> cheques.cancel(id, SEP_30, "again")).hasMessageContaining("already cancelled");
        assertThatThrownBy(() -> cheques.present(id, SEP_30)).hasMessageContaining("is cancelled");

        // PDC payable back to zero; the bank moved only with the BPC and its reversal, net nothing.
        assertThat(balance(pdcPayable, SEP_30)).isEqualByComparingTo("0.00");
        assertThat(balance(bank, SEP_10)).isEqualByComparingTo("-5000.00");
        assertThat(balance(bank, SEP_30)).isEqualByComparingTo("0.00");
        assertThat(balance(gulf.getPayableAccount(), SEP_30)).isEqualByComparingTo("-5000.00");
        // The number is free again once cancelled.
        pdc(gulf, SEP_28, "5000.00", "000070", SEP_30);
    }

    @Test
    void amendingAPdcPaymentCancelsItsChequeAndTheReplacementIssuesAgain() {
        Voucher v = pdc(gulf, AUG_15, "800.00", "000080", SEP_10);
        Voucher fresh = vouchers.amend(v.getId(), AUG_20, "wrong date",
                payment(gulf, AUG_15, "800.00", VoucherPaymentMethod.CHEQUE, "000080", SEP_28));
        assertThat(issuedRepo.findByVoucherId(v.getId())).singleElement()
                .satisfies(c -> assertThat(c.getStatus()).isEqualTo(IssuedCheque.Status.CANCELLED));
        assertThat(chequeOf(fresh).getChequeDate()).isEqualTo(SEP_28);
        assertThat(chequeOf(fresh).getStatus()).isEqualTo(IssuedCheque.Status.ISSUED);
        assertThat(balance(pdcPayable, SEP_30)).isEqualByComparingTo("-800.00");
    }

    // ------------------------------------------------------------------ cut-over cheques

    @Test
    void openingChequesAreCheckedAgainstTheObBalanceAndPresentOrCancelLikeAnyOther() {
        // PACT's outstanding PDCs arrive as an OB credit on PDC_PAYABLE.
        posting.post(new PostingRequest(JournalDocType.OB, LocalDate.of(2026, 7, 31), "Opening balances", null,
                JournalSourceType.OPENING_BALANCE, null, null, List.of(
                        PostingRequest.dr(accounts.getAccountByCode("F-02").getId(), new BigDecimal("7000.00")),
                        PostingRequest.cr(pdcPayable.getId(), new BigDecimal("7000.00")))));
        IssuedChequeDTO a = cheques.createOpening(new OpeningIssuedChequeInputDTO(gulf.getId(), bank.getId(), "000901",
                SEP_5, new BigDecimal("4000.00")));
        assertThat(a.opening()).isTrue();
        assertThat(cheques.summary().openingDifference()).isEqualByComparingTo("3000.00");
        IssuedChequeDTO b = cheques.createOpening(new OpeningIssuedChequeInputDTO(alNoor.getId(), bank.getId(), "000902",
                SEP_10, new BigDecimal("3000.00")));
        IssuedChequeSummaryDTO s = cheques.summary();
        assertThat(s.openingTotal()).isEqualByComparingTo("7000.00");
        assertThat(s.openingBalance()).isEqualByComparingTo("7000.00");
        assertThat(s.openingDifference()).isEqualByComparingTo("0.00");
        assertThat(s.difference()).isEqualByComparingTo("0.00");
        assertThat(s.duePresentCount()).isEqualTo(2);
        assertThatThrownBy(() -> cheques.createOpening(new OpeningIssuedChequeInputDTO(gulf.getId(), bank.getId(),
                "000901", SEP_5, BigDecimal.ONE))).hasMessageContaining("already issued");

        cheques.present(a.id(), SEP_10);
        assertThat(balance(bank, SEP_30)).isEqualByComparingTo("-4000.00");
        IssuedChequeDTO bc = cheques.cancel(b.id(), SEP_28, "vendor asked for transfer");
        assertThat(bc.status()).isEqualTo("CANCELLED");
        // Mirror of the missing BPV: Dr PDC_PAYABLE / Cr the vendor.
        assertThat(balance(alNoor.getPayableAccount(), SEP_30)).isEqualByComparingTo("-3000.00");
        assertThat(balance(pdcPayable, SEP_30)).isEqualByComparingTo("0.00");
        assertThatThrownBy(() -> cheques.deleteOpening(a.id())).hasMessageContaining("only an ISSUED cut-over cheque");
    }

    // ------------------------------------------------------------------ payment runs

    @Test
    void oneInvalidVendorMeansNoVoucherIsPosted() {
        Voucher g = pisr(gulf, "INV-G1", AUG_1, line(rmP1, "1000.00", "0", p1));
        Voucher n = pisr(alNoor, "AN-1", AUG_1, line(cleaningP1, "500.00", "0", p1));
        PaymentRunDTO r = run(SEP_10, VoucherPaymentMethod.TRANSFER, null, null, pay(g, "1000.00"), pay(n, "500.00"));
        jdbc.update("update vendors set is_active = false where id = ?", gulf.getId());
        assertThat(runs.preview(r.id()).problems()).extracting(PaymentRunPreviewDTO.Problem::code).contains("VENDOR_INACTIVE");
        assertThatThrownBy(() -> runs.post(r.id())).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Gulf AC Services LLC is inactive");
        assertThat(bpvsOfRun(r.id())).isEmpty();
        assertThat(allocations.liveOnInvoice(n.getId(), null)).isEqualByComparingTo("0.00");
        assertThat(runs.get(r.id()).status()).isEqualTo("DRAFT");
    }

    @Test
    void aFailureAfterTheFirstVendorRollsEveryVendorBack() {
        Voucher g = pisr(gulf, "INV-G2", AUG_1, line(rmP1, "1000.00", "0", p1));
        Voucher n = pisr(alNoor, "AN-2", AUG_1, line(cleaningP1, "500.00", "0", p1));
        PaymentRunDTO r = run(SEP_10, VoucherPaymentMethod.TRANSFER, null, null, pay(g, "1000.00"), pay(n, "500.00"));
        // Al Noor posts first (name order); Gulf's payable leaf is inactive, which only its voucher discovers.
        jdbc.update("update accounts set is_active = false where id = ?", gulf.getPayableAccount().getId());
        assertThat(runs.preview(r.id()).postable()).isTrue();
        assertThatThrownBy(() -> runs.post(r.id())).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("inactive");
        assertThat(bpvsOfRun(r.id())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from vouchers where tenant_id = ? and doc_type = 'BPV'",
                Long.class, tenantId)).isZero();
        assertThat(allocations.liveOnInvoice(n.getId(), null)).isEqualByComparingTo("0.00");
        assertThat(runs.get(r.id()).status()).isEqualTo("DRAFT");
    }

    @Test
    void aDoubleSubmittedPostPostsOnce() throws Exception {
        Voucher g = pisr(gulf, "INV-D", AUG_1, line(rmP1, "1000.00", "0", p1));
        Voucher n = pisr(alNoor, "AN-D", AUG_1, line(cleaningP1, "500.00", "0", p1));
        PaymentRunDTO r = run(SEP_10, VoucherPaymentMethod.TRANSFER, null, null, pay(g, "1000.00"), pay(n, "500.00"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    TenantContextHolder.setTenantId(tenantId);
                    try {
                        go.await(10, TimeUnit.SECONDS);
                        return runs.post(r.id()).status();
                    } finally {
                        TenantContextHolder.clear();
                    }
                }));
            }
            go.countDown();
            for (Future<String> f : results) assertThat(f.get(60, TimeUnit.SECONDS)).isEqualTo("POSTED");
        } finally {
            pool.shutdownNow();
        }
        // And once more, in sequence.
        assertThat(runs.post(r.id()).status()).isEqualTo("POSTED");
        assertThat(bpvsOfRun(r.id())).hasSize(2);
        assertThat(allocations.liveOnInvoice(g.getId(), null)).isEqualByComparingTo("1000.00");
        assertThat(allocations.liveOnInvoice(n.getId(), null)).isEqualByComparingTo("500.00");
    }

    @Test
    void aRunRacingAManualPaymentOnTheSameInvoiceFailsReValidation() throws Exception {
        Voucher g = pisr(gulf, "INV-R", AUG_1, line(rmP1, "1000.00", "0", p1));
        PaymentRunDTO r = run(SEP_10, VoucherPaymentMethod.TRANSFER, null, null, pay(g, "1000.00"));
        Voucher manual = transfer(gulf, SEP_5, "400.00");
        CountDownLatch allocated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> t1 = pool.submit(() -> {
                TenantContextHolder.setTenantId(tenantId);
                try {
                    tx.executeWithoutResult(st -> {
                        allocations.allocate(manual.getId(), g.getId(), null, new BigDecimal("400.00"), null);
                        allocated.countDown();
                        try { release.await(20, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new RuntimeException(e); }
                    });
                } finally {
                    TenantContextHolder.clear();
                }
                return null;
            });
            assertThat(allocated.await(20, TimeUnit.SECONDS)).isTrue();
            Future<String> t2 = pool.submit(() -> {
                TenantContextHolder.setTenantId(tenantId);
                try {
                    runs.post(r.id());
                    return "posted";
                } catch (BusinessRuleViolationException e) {
                    return "refused: " + e.getMessage();
                } finally {
                    TenantContextHolder.clear();
                }
            });
            Thread.sleep(500);
            assertThat(t2.isDone()).as("the run waits on the invoice's row lock").isFalse();
            release.countDown();
            t1.get(30, TimeUnit.SECONDS);
            assertThat(t2.get(30, TimeUnit.SECONDS))
                    .contains("refused:").contains("INV-R").contains("has 600.00 open now, less than the 1,000.00 selected");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(bpvsOfRun(r.id())).isEmpty();
        assertThat(allocations.liveOnInvoice(g.getId(), null)).isEqualByComparingTo("400.00");
    }

    @Test
    void aRunAppliesTheVendorsAdvanceFirst() {
        Voucher advance = transfer(gulf, SEP_1, "500.00");
        Voucher inv = pisr(gulf, "INV-A", AUG_20, line(rmP1, "1450.00", "0", p1));
        PaymentRunDTO r = run(SEP_10, VoucherPaymentMethod.TRANSFER, null, null, pay(inv, "1450.00"));
        assertThat(runs.candidates(null, gulf.getId(), null, true, null).advances()).singleElement()
                .satisfies(a -> assertThat(a.unallocated()).isEqualByComparingTo("500.00"));
        PaymentRunPreviewDTO p = runs.preview(r.id());
        assertThat(p.vendors()).singleElement().satisfies(vp -> {
            assertThat(vp.advanceApplied()).isEqualByComparingTo("500.00");
            assertThat(vp.netPayment()).isEqualByComparingTo("950.00");
        });
        runs.post(r.id());
        Voucher bpv = bpvsOfRun(r.id()).get(0);
        assertThat(journal(bpv.getJournalId()).get(0).debit()).isEqualByComparingTo("950.00");
        // The advance's allocation: no journal, tagged with the run, dated on the run's date.
        assertThat(allocationRepo.findByPaymentVoucherIdAndReleasedOnIsNull(advance.getId())).singleElement()
                .satisfies(a -> {
                    assertThat(a.getAmount()).isEqualByComparingTo("500.00");
                    assertThat(a.getPaymentRunId()).isEqualTo(r.id());
                    assertThat(a.getAllocatedOn()).isEqualTo(SEP_10);
                });
        assertThat(item(gulf, "INV-A").status()).isEqualTo("PAID");
        // Nothing owed and nothing advanced: Gulf has no aging row at all.
        assertThat(payables.aging(SEP_30, null, null).rows()).noneMatch(x -> x.vendorId().equals(gulf.getId()));
    }

    @Test
    void aChequeRunNumbersByVendorNameAndHoldsPostDatedChequesInPdcPayable() {
        Voucher g = pisr(gulf, "INV-C", AUG_1, line(rmP1, "1000.00", "0", p1));
        Voucher n = pisr(alNoor, "AN-C", AUG_1, line(cleaningP1, "500.00", "0", p1));
        PaymentRunDTO r = run(SEP_10, VoucherPaymentMethod.CHEQUE, SEP_28, "000099", pay(g, "1000.00"), pay(n, "500.00"));
        PaymentRunPreviewDTO p = runs.preview(r.id());
        assertThat(p.vendors()).extracting(PaymentRunPreviewDTO.VendorPayment::vendorName,
                PaymentRunPreviewDTO.VendorPayment::chequeNumber, PaymentRunPreviewDTO.VendorPayment::postDated)
                .containsExactly(tuple("Al Noor Cleaning", "000099", true), tuple("Gulf AC Services LLC", "000100", true));
        assertThat(p.vendors().get(0).journal().get(1).accountCode()).isEqualTo("B-02-001");
        runs.post(r.id());
        List<Voucher> bpvs = bpvsOfRun(r.id());
        assertThat(bpvs).hasSize(2).allSatisfy(v -> {
            assertThat(journal(v.getJournalId()).get(1).accountId()).isEqualTo(pdcPayable.getId());
            assertThat(chequeOf(v).getStatus()).isEqualTo(IssuedCheque.Status.ISSUED);
        });
        assertThat(balance(bank, SEP_30)).isEqualByComparingTo("0.00");
        assertThat(balance(pdcPayable, SEP_30)).isEqualByComparingTo("-1500.00");
    }

    @Test
    void thePreviewListsEveryProblemAtOnce() {
        Voucher g = pisr(gulf, "INV-P", AUG_1, line(rmP1, "1000.00", "0", p1));
        Voucher n = pisr(alNoor, "AN-P", AUG_1, line(cleaningP1, "500.00", "0", p1));
        PaymentRunDTO r = run(AUG_20, VoucherPaymentMethod.CHEQUE, AUG_31, "000200", pay(g, "1000.00"), pay(n, "500.00"));
        // Cheque 000201 (Gulf's) is already out; Al Noor is deactivated; INV-P is partly paid; August is locked.
        pdc(alNoor, AUG_15, "10.00", "000201", SEP_10);
        transfer(gulf, AUG_15, "300.00", to(g, "300.00"));
        jdbc.update("update vendors set is_active = false where id = ?", alNoor.getId());
        fiscal.lockThrough(AUG_31);
        PaymentRunPreviewDTO p = runs.preview(r.id());
        assertThat(p.postable()).isFalse();
        assertThat(p.problems()).extracting(PaymentRunPreviewDTO.Problem::code)
                .contains("DATE_LOCKED", "VENDOR_INACTIVE", "OPEN_CHANGED", "CHEQUE_TAKEN");
        assertThatThrownBy(() -> runs.post(r.id())).hasMessageContaining("locked period")
                .hasMessageContaining("inactive").hasMessageContaining("already issued");
    }

    @Test
    void aTransferRunWarnsOfAMissingIbanButStillPosts() {
        jdbc.update("update vendors set iban = null where id = ?", gulf.getId());
        Voucher g = pisr(gulf, "INV-I", AUG_1, line(rmP1, "1000.00", "0", p1));
        PaymentRunDTO r = run(SEP_10, VoucherPaymentMethod.TRANSFER, null, null, pay(g, "1000.00"));
        PaymentRunPreviewDTO p = runs.preview(r.id());
        assertThat(p.problems()).singleElement().satisfies(x -> {
            assertThat(x.code()).isEqualTo("NO_IBAN");
            assertThat(x.severity()).isEqualTo("WARNING");
        });
        assertThat(p.postable()).isTrue();
        assertThat(runs.post(r.id()).status()).isEqualTo("POSTED");
    }

    @Test
    void theBankFileEscapesFormulaeAndListsOnlyPaymentsStillPosted() {
        Vendor evil = vendor("=HYPERLINK(\"http://x\",\"pay\")", "100765432100099", "+971500000000");
        Voucher e = pisr(evil, "E-1", AUG_1, line(rmP1, "100.00", "0", p1));
        Voucher g = pisr(gulf, "INV-F", AUG_1, line(rmP1, "200.00", "0", p1));
        PaymentRunDTO r = run(SEP_10, VoucherPaymentMethod.TRANSFER, null, null, pay(e, "100.00"), pay(g, "200.00"));
        assertThatThrownBy(() -> runs.bankFile(r.id())).hasMessageContaining("once the run is posted");
        runs.post(r.id());
        String csv = new String(runs.bankFile(r.id()), StandardCharsets.UTF_8);
        assertThat(csv).contains("\"'=HYPERLINK(\"\"http://x\"\",\"\"pay\"\")\"").contains(",'+971500000000,")
                .contains("AE070331234567890123456").contains(",200.00,");
        assertThat(csv).doesNotContain(",=HYPERLINK").doesNotContain(",+971");
        // Reversing one vendor's voucher takes it out of the file; the run stays POSTED.
        Voucher gulfBpv = bpvsOfRun(r.id()).stream().filter(v -> v.getVendor().getId().equals(gulf.getId())).findFirst().orElseThrow();
        vouchers.reversePayment(gulfBpv.getId(), SEP_28, "paid twice");
        assertThat(new String(runs.bankFile(r.id()), StandardCharsets.UTF_8)).doesNotContain("AE070331234567890123456");
        assertThat(runs.get(r.id()).status()).isEqualTo("POSTED");
        assertThat(runs.get(r.id()).items()).anySatisfy(i -> assertThat(i.bpvStatus()).isEqualTo("REVERSED"));
    }

    @Test
    void aDraftRunCanBeEditedCancelledOrDeletedButNotAfterPosting() {
        Voucher g = pisr(gulf, "INV-E", AUG_1, line(rmP1, "1000.00", "0", p1));
        Voucher n = pisr(alNoor, "AN-E", AUG_1, line(cleaningP1, "500.00", "0", p1));
        PaymentRunDTO r = run(SEP_10, VoucherPaymentMethod.TRANSFER, null, null, pay(g, "1000.00"));
        PaymentRunDTO other = run(SEP_10, VoucherPaymentMethod.TRANSFER, null, null, pay(g, "400.00"));
        // The other draft flags INV-E as held.
        assertThat(runs.candidates(null, null, null, true, other.id()).items())
                .filteredOn(c -> c.item().invoiceNumber().equals("INV-E")).singleElement()
                .satisfies(c -> assertThat(c.draftRuns()).containsExactly(r.runNumber()));
        PaymentRunDTO edited = runs.update(r.id(), new PaymentRunInputDTO(SEP_10, bank.getId(),
                VoucherPaymentMethod.TRANSFER, null, null, "Sept run", List.of(pay(g, "900.00"), pay(n, "500.00"))));
        assertThat(edited.total()).isEqualByComparingTo("1400.00");
        assertThat(edited.vendorCount()).isEqualTo(2);
        assertThat(runs.cancel(other.id()).status()).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> runs.post(other.id())).hasMessageContaining("only a DRAFT run");
        runs.post(r.id());
        assertThatThrownBy(() -> runs.update(r.id(), new PaymentRunInputDTO(SEP_10, bank.getId(),
                VoucherPaymentMethod.TRANSFER, null, null, null, List.of(pay(g, "1.00")))))
                .hasMessageContaining("only a DRAFT run");
        assertThatThrownBy(() -> runs.delete(r.id())).hasMessageContaining("only a DRAFT run");
        PaymentRunDTO third = run(SEP_10, VoucherPaymentMethod.TRANSFER, null, null, pay(g, "100.00"));
        runs.delete(third.id());
        assertThatThrownBy(() -> runs.get(third.id())).isInstanceOf(NotFoundException.class);
        // Header rules.
        assertThatThrownBy(() -> run(SEP_10, VoucherPaymentMethod.CHEQUE, null, "CHQ", pay(g, "1.00")))
                .hasMessageContaining("must end in digits");
        assertThatThrownBy(() -> run(SEP_10, VoucherPaymentMethod.CHEQUE, SEP_5, "1", pay(g, "1.00")))
                .hasMessageContaining("cannot be before the payment date");
        assertThatThrownBy(() -> run(SEP_10, VoucherPaymentMethod.CASH, null, null, pay(g, "1.00")))
                .hasMessageContaining("paid from a cash account");
        assertThatThrownBy(() -> run(SEP_10, VoucherPaymentMethod.TRANSFER, null, null, pay(g, "1.00"), pay(g, "2.00")))
                .hasMessageContaining("selected twice");
    }

    @Test
    void chequeNumbersCountUpKeepingTheirWidth() {
        assertThat(PaymentRunService.increment("000031")).isEqualTo("000032");
        assertThat(PaymentRunService.increment("CHQ-099")).isEqualTo("CHQ-100");
        assertThat(PaymentRunService.increment("9")).isEqualTo("10");
    }

    // ------------------------------------------------------------------ tenancy

    @Test
    void anotherTenantSeesNoRunsOrChequesAndCannotActOnThem() {
        Voucher g = pisr(gulf, "INV-T", AUG_1, line(rmP1, "1000.00", "0", p1));
        PaymentRunDTO r = run(SEP_10, VoucherPaymentMethod.TRANSFER, null, null, pay(g, "1000.00"));
        UUID chequeId = chequeOf(pdc(gulf, AUG_15, "10.00", "000300", SEP_10)).getId();
        newTenant("RUN-B-");
        assertThat(runs.list()).isEmpty();
        assertThat(cheques.list(null, null, null, null, false)).isEmpty();
        assertThatThrownBy(() -> runs.get(r.id())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> runs.post(r.id())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> runs.preview(r.id())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> cheques.present(chequeId, SEP_10)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> cheques.cancel(chequeId, SEP_10, "x")).isInstanceOf(NotFoundException.class);
        // A run in B naming A's invoice: not found.
        Account bankB = accounts.createLeaf("Bank B", accounts.getAccountByCode("A-02-02"), null);
        assertThatThrownBy(() -> runs.create(new PaymentRunInputDTO(SEP_10, bankB.getId(), VoucherPaymentMethod.TRANSFER,
                null, null, null, List.of(pay(g, "1.00"))))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aTenantWithRunsAndIssuedChequesCanBeDeleted() {
        Voucher g = pisr(gulf, "INV-X", AUG_1, line(rmP1, "1000.00", "0", p1));
        Voucher n = pisr(alNoor, "AN-X", AUG_1, line(cleaningP1, "500.00", "0", p1));
        runs.post(run(SEP_10, VoucherPaymentMethod.CHEQUE, SEP_28, "000400", pay(g, "1000.00")).id());
        run(SEP_10, VoucherPaymentMethod.TRANSFER, null, null, pay(n, "500.00"));
        cheques.present(jdbc.queryForObject("select id from issued_cheques where tenant_id = ?", UUID.class, tenantId), SEP_28);
        cheques.createOpening(new OpeningIssuedChequeInputDTO(alNoor.getId(), bank.getId(), "000990", SEP_5, BigDecimal.TEN));
        for (String table : List.of("payment_runs", "payment_run_items", "issued_cheques")) {
            assertThat(jdbc.queryForObject("select count(*) from " + table + " where tenant_id = ?", Long.class, tenantId))
                    .as(table).isPositive();
        }
        String name = orgRepo.findById(tenantId).orElseThrow().getName();
        TenantContextHolder.clear();
        orgService.deleteTenant(tenantId, name);
        for (String table : List.of("payment_runs", "payment_run_items", "issued_cheques", "voucher_allocations", "vouchers")) {
            assertThat(jdbc.queryForObject("select count(*) from " + table + " where tenant_id = ?", Long.class, tenantId))
                    .as("rows surviving in %s", table).isZero();
        }
    }
}
