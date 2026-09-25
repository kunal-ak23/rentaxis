package com.datagami.rentaxis.core.service.voucher;

import com.datagami.rentaxis.api.dto.payables.OpenItemDTO;
import com.datagami.rentaxis.api.dto.payables.PayablesAgingDTO;
import com.datagami.rentaxis.api.dto.report.PropertyStatementDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.VendorService;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.service.payables.ApOpeningItemService;
import com.datagami.rentaxis.core.service.payables.PayablesService;
import com.datagami.rentaxis.core.service.report.statement.PropertyStatementService;
import com.datagami.rentaxis.core.service.voucher.VoucherAllocationService.AllocationInput;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.api.dto.payables.ApOpeningItemDTO;
import com.datagami.rentaxis.api.dto.payables.ApOpeningItemInputDTO;
import com.datagami.rentaxis.api.dto.payables.ApOpeningSummaryDTO;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Supplier AP core (finance-ops spec §2, PR 3a): terms and due dates, the TRN
 * rule, the duplicate-invoice guard, allocations and their lifecycle hooks,
 * opening items, payables aging and the statement pack's section 7.
 *
 * <p>The spec's worked example (Gulf AC and Al Noor, August–September 2026) is
 * replayed without the parts that arrive with PR 3b: the 20,000 payment to Gulf
 * AC is a current-dated transfer (so it credits the bank, not PDC payable), and
 * the payment run's BPV is posted by hand with the same two allocations. Every
 * figure of the spec's aging table as of 30/09 is asserted, with a tie-out Δ of 0.</p>
 */
@SpringBootTest
class SupplierApIT extends AbstractPostgresIT {

    @Autowired VoucherService vouchers;
    @Autowired VoucherAllocationService allocations;
    @Autowired PayablesService payables;
    @Autowired ApOpeningItemService openingItems;
    @Autowired PropertyStatementService statements;
    @Autowired AccountService accounts;
    @Autowired VendorService vendorService;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired PostingService posting;
    @Autowired JournalLineRepository lines;
    @Autowired VoucherAllocationRepository allocationRepo;
    @Autowired TenantDefaultAccountMappingRepository defaults;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.datagami.rentaxis.core.service.LandlordOrgService orgService;
    @Autowired UserRepository userRepo;
    @Autowired UserPropertyAssignmentRepository assignmentRepo;

    /** F15-11: the tenant-level clearing leaf a chart without A-02-06 is given on first use. */
    private UUID clearing() {
        return jdbc.queryForObject("select id from accounts where tenant_id = ? and code = 'A-02-06-001'", UUID.class,
                com.datagami.rentaxis.core.tenant.TenantContextHolder.getTenantId());
    }

    static final LocalDate AUG_1 = LocalDate.of(2026, 8, 1);
    static final LocalDate AUG_15 = LocalDate.of(2026, 8, 15);
    static final LocalDate AUG_20 = LocalDate.of(2026, 8, 20);
    static final LocalDate AUG_31 = LocalDate.of(2026, 8, 31);
    static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);
    static final LocalDate SEP_5 = LocalDate.of(2026, 9, 5);
    static final LocalDate SEP_10 = LocalDate.of(2026, 9, 10);
    static final LocalDate SEP_30 = LocalDate.of(2026, 9, 30);

    UUID tenantId;
    Property p1, p2;
    Account rmP1, cleaningP1, rmP2, securityP2, inputVat, bank, cash;
    Vendor gulf, alNoor;

    @BeforeEach
    void setUp() {
        tenantId = newTenant("AP-");
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
        cash = accounts.getAccountByCode("A-02-05-001");
        gulf = vendor("Gulf AC Services LLC", "100123456700003");
        alNoor = vendor("Al Noor Cleaning", "100765432100003");
        fiscal.setBooksStartDate(AUG_1);   // locked through 31/07
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

    private Vendor vendor(String name, String trn) {
        Vendor v = new Vendor();
        v.setNameEn(name);
        v.setTrn(trn);
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

    private VoucherService.VoucherInput pisrInput(Vendor v, String invoiceNumber, LocalDate date,
                                                  VoucherService.VoucherLineInput... ls) {
        return new VoucherService.VoucherInput(VoucherType.PISR, date, v.getId(), invoiceNumber, invoiceNumber,
                null, null, null, null, null, List.of(ls));
    }

    private Voucher pisr(Vendor v, String invoiceNumber, LocalDate date, VoucherService.VoucherLineInput... ls) {
        return vouchers.post(vouchers.createDraft(pisrInput(v, invoiceNumber, date, ls)).getId());
    }

    private VoucherService.VoucherInput bpvInput(Vendor v, LocalDate date, String amount, String reference) {
        return new VoucherService.VoucherInput(VoucherType.BPV, date, v.getId(), null, "Payment " + reference,
                null, null, bank.getId(), null, null,
                List.of(new VoucherService.VoucherLineInput(v.getPayableAccount().getId(), "Settlement",
                        new BigDecimal(amount), BigDecimal.ZERO, null, null)),
                null, null, VoucherPaymentMethod.TRANSFER, reference);
    }

    private Voucher bpv(Vendor v, LocalDate date, String amount, String reference, AllocationInput... allocs) {
        return vouchers.post(vouchers.createDraft(bpvInput(v, date, amount, reference)).getId(), List.of(allocs));
    }

    private static AllocationInput to(Voucher invoice, String amount) {
        return new AllocationInput(invoice.getId(), null, new BigDecimal(amount));
    }

    record Row(UUID accountId, BigDecimal debit, BigDecimal credit, UUID propertyId) { }

    private List<Row> journal(Voucher v) {
        return tx.execute(s -> lines.findByEntry_IdOrderByLineNoAsc(v.getJournalId()).stream()
                .map(l -> new Row(l.getAccount().getId(), l.getDebit(), l.getCredit(), l.getPropertyId())).toList());
    }

    private OpenItemDTO item(List<OpenItemDTO> items, String invoiceNumber) {
        return items.stream().filter(i -> invoiceNumber.equals(i.invoiceNumber())).findFirst().orElseThrow();
    }

    private PayablesAgingDTO.VendorRow row(PayablesAgingDTO a, Vendor v) {
        return a.rows().stream().filter(r -> r.vendorId().equals(v.getId())).findFirst().orElseThrow();
    }

    /** The spec's worked example, rows 1–6, with PR 3b's PDC and run replaced as the class Javadoc says. */
    record Example(Voucher inv7702, Voucher inv7781, Voucher inv7790, Voucher bpv50, Voucher an311, Voucher bpv55) { }

    private Example workedExample() {
        Voucher inv7702 = pisr(gulf, "INV-7702", AUG_1, line(rmP2, "19047.62", "5", p2));
        Voucher inv7781 = pisr(gulf, "INV-7781", AUG_1, line(rmP1, "1000.00", "5", p1), line(cleaningP1, "400.00", "0", p1));
        Voucher inv7790 = pisr(gulf, "INV-7790", AUG_20, line(securityP2, "2000.00", "5", p2));
        Voucher bpv50 = bpv(gulf, AUG_15, "20000.00", "TRF-7702", to(inv7702, "20000.00"));
        Voucher an311 = pisr(alNoor, "AN-311", SEP_5, line(cleaningP1, "3000.00", "5", p1));
        Voucher bpv55 = bpv(gulf, SEP_10, "2050.00", "TRF-7781", to(inv7781, "1450.00"), to(inv7790, "600.00"));
        return new Example(inv7702, inv7781, inv7790, bpv50, an311, bpv55);
    }

    // ------------------------------------------------------------------ worked example

    /**
     * F14-40: a supplier credit note reduces the invoice it is allocated to and its
     * input VAT; unallocated, it is a credit like an advance. Aging agrees with the ledger.
     */
    @Test
    void aSupplierCreditNoteReducesTheInvoiceAndItsInputVat() {
        Voucher inv = pisr(gulf, "GC-R14-0905", SEP_5, line(rmP1, "4000.00", "5", p1));
        VoucherService.VoucherInput cn = new VoucherService.VoucherInput(VoucherType.PCN, SEP_10, gulf.getId(),
                "CN-210", "Credit note CN-210", null, null, null, null, null,
                List.of(line(rmP1, "200.00", "5", p1)));
        Voucher pcn = vouchers.post(vouchers.createDraft(cn).getId(), List.of(to(inv, "210.00")));

        assertThat(pcn.getVoucherNumber()).startsWith("PCN-");
        assertThat(journal(pcn)).extracting(Row::accountId, Row::debit, Row::credit).containsExactlyInAnyOrder(
                org.assertj.core.api.Assertions.tuple(rmP1.getId(), new BigDecimal("0.00"), new BigDecimal("200.00")),
                org.assertj.core.api.Assertions.tuple(inputVat.getId(), new BigDecimal("0.00"), new BigDecimal("10.00")),
                org.assertj.core.api.Assertions.tuple(gulf.getPayableAccount().getId(), new BigDecimal("210.00"), new BigDecimal("0.00")),
                // F15-11: the note's expense is on P1, its payable and VAT on head office — cleared per property.
                org.assertj.core.api.Assertions.tuple(clearing(), new BigDecimal("200.00"), new BigDecimal("0.00")),
                org.assertj.core.api.Assertions.tuple(clearing(), new BigDecimal("0.00"), new BigDecimal("200.00")));
        OpenItemDTO open = item(payables.vendorItems(gulf.getId()), "GC-R14-0905");
        assertThat(open.open()).isEqualByComparingTo("3990.00");
        PayablesAgingDTO.Figures f = row(payables.aging(SEP_10, gulf.getId(), null), gulf).figures();
        assertThat(f.ledgerBalance()).isEqualByComparingTo("3990.00");
        assertThat(f.delta()).isEqualByComparingTo("0.00");

        // Unallocated, a credit note is a credit on the vendor, like an advance.
        Voucher loose = vouchers.post(vouchers.createDraft(new VoucherService.VoucherInput(VoucherType.PCN, SEP_10,
                gulf.getId(), "CN-211", "Credit note CN-211", null, null, null, null, null,
                List.of(line(rmP1, "100.00", "0", p1)))).getId(), List.of());
        assertThat(payables.advancesNow(gulf.getId())).anyMatch(a -> a.paymentId().equals(loose.getId())
                && a.unallocated().compareTo(new BigDecimal("100.00")) == 0);
        assertThat(row(payables.aging(SEP_10, gulf.getId(), null), gulf).figures().delta()).isEqualByComparingTo("0.00");
    }

    /**
     * R1 P2-1: the owner statement's "expenses paid" counts cash only. Invoice 1,050,
     * credit note 210 and payment 840 against it: 840 paid. The credit is netted in
     * the expense (the PCN credits the expense line), never shown as paid.
     */
    @Test
    void aCreditNoteIsNotCountedAsExpensesPaid() {
        Voucher inv = pisr(gulf, "GC-1050", SEP_5, line(rmP1, "1000.00", "5", p1));
        vouchers.post(vouchers.createDraft(new VoucherService.VoucherInput(VoucherType.PCN, SEP_10, gulf.getId(),
                "CN-210B", "Credit note", null, null, null, null, null,
                List.of(line(rmP1, "200.00", "5", p1)))).getId(), List.of(to(inv, "210.00")));
        bpv(gulf, SEP_10, "840.00", "TRF-840", to(inv, "840.00"));
        assertThat(paid(p1, SEP_1, SEP_30, "allocatedPaid")).isEqualByComparingTo("840.00");
    }

    @Test
    void theWorkedExampleEndToEnd() {
        Example x = workedExample();

        // Journals: PostingService wrote each one; allocations wrote none.
        assertThat(journal(x.inv7702())).extracting(Row::accountId, Row::debit, Row::credit).containsExactly(
                tuple(rmP2.getId(), new BigDecimal("19047.62"), new BigDecimal("0.00")),
                tuple(inputVat.getId(), new BigDecimal("952.38"), new BigDecimal("0.00")),
                tuple(gulf.getPayableAccount().getId(), new BigDecimal("0.00"), new BigDecimal("20000.00")));
        assertThat(journal(x.inv7781())).extracting(Row::accountId, Row::debit, Row::credit).containsExactly(
                tuple(rmP1.getId(), new BigDecimal("1000.00"), new BigDecimal("0.00")),
                tuple(cleaningP1.getId(), new BigDecimal("400.00"), new BigDecimal("0.00")),
                tuple(inputVat.getId(), new BigDecimal("50.00"), new BigDecimal("0.00")),
                tuple(gulf.getPayableAccount().getId(), new BigDecimal("0.00"), new BigDecimal("1450.00")));
        assertThat(journal(x.inv7790())).extracting(Row::accountId, Row::debit, Row::credit).containsExactly(
                tuple(securityP2.getId(), new BigDecimal("2000.00"), new BigDecimal("0.00")),
                tuple(inputVat.getId(), new BigDecimal("100.00"), new BigDecimal("0.00")),
                tuple(gulf.getPayableAccount().getId(), new BigDecimal("0.00"), new BigDecimal("2100.00")));
        assertThat(journal(x.an311())).extracting(Row::accountId, Row::debit, Row::credit).containsExactly(
                tuple(cleaningP1.getId(), new BigDecimal("3000.00"), new BigDecimal("0.00")),
                tuple(inputVat.getId(), new BigDecimal("150.00"), new BigDecimal("0.00")),
                tuple(alNoor.getPayableAccount().getId(), new BigDecimal("0.00"), new BigDecimal("3150.00")));
        assertThat(journal(x.bpv55())).extracting(Row::accountId, Row::debit, Row::credit).containsExactly(
                tuple(gulf.getPayableAccount().getId(), new BigDecimal("2050.00"), new BigDecimal("0.00")),
                tuple(bank.getId(), new BigDecimal("0.00"), new BigDecimal("2050.00")));
        // The header property follows the lines: every line of INV-7781 names P1.
        assertThat(journal(x.inv7781())).allSatisfy(r -> assertThat(r.propertyId()).isEqualTo(p1.getId()));

        // Due dates: supplier date + 30.
        assertThat(x.inv7702().getDueDate()).isEqualTo(AUG_31);
        assertThat(x.inv7790().getDueDate()).isEqualTo(LocalDate.of(2026, 9, 19));
        assertThat(x.an311().getDueDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(x.bpv55().getPaymentReference()).isEqualTo("TRF-7781");

        // Allocation states, now.
        List<OpenItemDTO> gulfItems = payables.vendorItems(gulf.getId());
        assertThat(item(gulfItems, "INV-7702").status()).isEqualTo("PAID");
        assertThat(item(gulfItems, "INV-7781").status()).isEqualTo("PAID");
        assertThat(item(gulfItems, "INV-7790").status()).isEqualTo("PART_PAID");
        assertThat(item(gulfItems, "INV-7790").open()).isEqualByComparingTo("1500.00");
        assertThat(allocations.liveOnPayment(x.bpv55().getId())).isEqualByComparingTo("2050.00");

        // Aging as of 31/08: INV-7781 due today is current, INV-7790 not yet due; INV-7702 paid.
        PayablesAgingDTO aug = payables.aging(AUG_31, null, null);
        assertThat(aug.rows()).extracting(PayablesAgingDTO.VendorRow::vendorName).containsExactly("Gulf AC Services LLC");
        PayablesAgingDTO.Figures g31 = row(aug, gulf).figures();
        assertThat(g31.current()).isEqualByComparingTo("3550.00");
        assertThat(g31.d1to30()).isEqualByComparingTo("0.00");
        assertThat(g31.openTotal()).isEqualByComparingTo("3550.00");
        assertThat(g31.ledgerBalance()).isEqualByComparingTo("3550.00");
        assertThat(g31.delta()).isEqualByComparingTo("0.00");

        // Aging as of 30/09: the spec's table.
        PayablesAgingDTO sep = payables.aging(SEP_30, null, null);
        assertThat(sep.rows()).extracting(PayablesAgingDTO.VendorRow::vendorName)
                .containsExactly("Al Noor Cleaning", "Gulf AC Services LLC");
        PayablesAgingDTO.Figures n = row(sep, alNoor).figures();
        assertThat(n.current()).isEqualByComparingTo("3150.00");
        assertThat(n.openTotal()).isEqualByComparingTo("3150.00");
        assertThat(n.ledgerBalance()).isEqualByComparingTo("3150.00");
        assertThat(n.delta()).isEqualByComparingTo("0.00");
        PayablesAgingDTO.Figures g = row(sep, gulf).figures();
        assertThat(g.current()).isEqualByComparingTo("0.00");
        assertThat(g.d1to30()).isEqualByComparingTo("1500.00");
        assertThat(g.advances()).isEqualByComparingTo("0.00");
        assertThat(g.openTotal()).isEqualByComparingTo("1500.00");
        assertThat(g.ledgerBalance()).isEqualByComparingTo("1500.00");
        assertThat(g.delta()).isEqualByComparingTo("0.00");
        assertThat(item(row(sep, gulf).items(), "INV-7790").daysOverdue()).isEqualTo(11);
        assertThat(sep.totals().current()).isEqualByComparingTo("3150.00");
        assertThat(sep.totals().d1to30()).isEqualByComparingTo("1500.00");
        assertThat(sep.totals().openTotal()).isEqualByComparingTo("4650.00");
        assertThat(sep.totals().ledgerBalance()).isEqualByComparingTo("4650.00");
        assertThat(sep.totals().delta()).isEqualByComparingTo("0.00");

        // Reproducible: asking again about 31/08 after September's activity gives August's answer.
        assertThat(row(payables.aging(AUG_31, null, null), gulf).figures().current()).isEqualByComparingTo("3550.00");

        // Property filter: P2's share only, no vendor-level columns.
        PayablesAgingDTO p2Aging = payables.aging(SEP_30, null, p2.getId());
        assertThat(p2Aging.vendorLevel()).isFalse();
        assertThat(p2Aging.rows()).singleElement().satisfies(r -> {
            assertThat(r.figures().d1to30()).isEqualByComparingTo("1500.00");
            assertThat(r.figures().advances()).isNull();
            assertThat(r.figures().delta()).isNull();
        });
    }

    @Test
    void sectionSevenPaysEachPropertyItsShareOfTheAllocations() {
        workedExample();
        // September: INV-7781 (all P1) 1,450 and INV-7790 (all P2) 600 were allocated.
        assertThat(paid(p1, SEP_1, SEP_30, "allocatedPaid")).isEqualByComparingTo("1450.00");
        assertThat(paid(p2, SEP_1, SEP_30, "allocatedPaid")).isEqualByComparingTo("600.00");
        // August: the 20,000 to INV-7702 (P2).
        assertThat(paid(p2, AUG_1, AUG_31, "allocatedPaid")).isEqualByComparingTo("20000.00");
        assertThat(paid(p1, AUG_1, AUG_31, "allocatedPaid")).isEqualByComparingTo("0.00");
        // Section 9 subtracts it.
        PropertyStatementDTO s = statements.statement(p2.getId(), SEP_1, SEP_30, null);
        assertThat(figure(s, "netCash", "expensesPaid")).isEqualByComparingTo("600.00");
    }

    @Test
    void sectionSevenSplitsAMultiPropertyInvoiceByGrossShareAndListsAdvances() {
        // 1,050 on P1 (1,000 + 50 VAT) and 420 on P2 (400 + 20 VAT): gross 1,470.
        Voucher inv = pisr(gulf, "INV-9001", SEP_1, line(rmP1, "1000.00", "5", p1), line(rmP2, "400.00", "5", p2));
        bpv(gulf, SEP_5, "735.00", "TRF-9001", to(inv, "735.00"));
        assertThat(paid(p1, SEP_1, SEP_30, "allocatedPaid")).isEqualByComparingTo("525.00");
        assertThat(paid(p2, SEP_1, SEP_30, "allocatedPaid")).isEqualByComparingTo("210.00");
        // An unallocated payment is listed, not added.
        bpv(alNoor, SEP_10, "500.00", "TRF-ADV");
        PropertyStatementDTO s = statements.statement(p1.getId(), SEP_1, SEP_30, null);
        assertThat(figure(s, "expensesPaid", "unallocatedPayments")).isEqualByComparingTo("500.00");
        assertThat(figure(s, "expensesPaid", "paid")).isEqualByComparingTo("525.00");
        assertThat(s.sections().stream().filter(x -> x.key().equals("expensesPaid")).findFirst().orElseThrow().notes())
                .contains("unallocatedNotAttributable");
    }

    private BigDecimal paid(Property p, LocalDate from, LocalDate to, String figure) {
        return figure(statements.statement(p.getId(), from, to, null), "expensesPaid", figure);
    }

    private static BigDecimal figure(PropertyStatementDTO s, String section, String key) {
        return s.sections().stream().filter(x -> x.key().equals(section)).findFirst().orElseThrow()
                .figures().stream().filter(f -> f.key().equals(key)).findFirst().orElseThrow().amount();
    }

    // ------------------------------------------------------------------ allocation rules

    @Test
    void overAllocationIsRefusedForAnInvoiceAndForAPayment() {
        Voucher inv = pisr(gulf, "INV-1", AUG_1, line(rmP1, "1000.00", "0", p1));
        // Invoice side: 1,000 gross, 1,200 offered.
        Voucher big = bpv(gulf, AUG_15, "1500.00", "TRF-1");
        assertThatThrownBy(() -> allocations.allocate(big.getId(), inv.getId(), null, new BigDecimal("1200.00"), null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("more than its gross of 1,000.00");
        // Payment side: 300 paid, 400 offered.
        Voucher small = bpv(gulf, AUG_15, "300.00", "TRF-2");
        assertThatThrownBy(() -> allocations.allocate(small.getId(), inv.getId(), null, new BigDecimal("400.00"), null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("more than the 300.00 it paid");
        // And at post: two lines of the same invoice cannot exceed it either.
        assertThatThrownBy(() -> bpv(gulf, AUG_15, "1100.00", "TRF-3", to(inv, "1100.00")))
                .isInstanceOf(BusinessRuleViolationException.class);
        // Exactly the gross is fine.
        allocations.allocate(big.getId(), inv.getId(), null, new BigDecimal("1000.00"), null);
        assertThat(payables.vendorItems(gulf.getId()).get(0).status()).isEqualTo("PAID");
    }

    @Test
    void aPaymentToAnotherVendorOrADraftCannotBeAllocated() {
        Voucher inv = pisr(gulf, "INV-2", AUG_1, line(rmP1, "500.00", "0", p1));
        Voucher other = bpv(alNoor, AUG_15, "500.00", "TRF-N");
        assertThatThrownBy(() -> allocations.allocate(other.getId(), inv.getId(), null, new BigDecimal("100"), null))
                .hasMessageContaining("different vendors");
        Voucher draft = vouchers.createDraft(bpvInput(gulf, AUG_15, "500.00", "TRF-D"));
        assertThatThrownBy(() -> allocations.allocate(draft.getId(), inv.getId(), null, new BigDecimal("100"), null))
                .hasMessageContaining("only a POSTED payment");
    }

    @Test
    void anAdvanceIsAppliedToALaterInvoiceWithNoJournal() {
        Voucher advance = bpv(gulf, AUG_15, "5000.00", "TRF-ADV");
        PayablesAgingDTO before = payables.aging(AUG_20, null, null);
        assertThat(row(before, gulf).figures().advances()).isEqualByComparingTo("5000.00");
        assertThat(row(before, gulf).figures().ledgerBalance()).isEqualByComparingTo("-5000.00");
        assertThat(row(before, gulf).figures().delta()).isEqualByComparingTo("0.00");

        Voucher inv = pisr(gulf, "INV-3", AUG_20, line(rmP1, "2000.00", "5", p1));
        long journalsBefore = jdbc.queryForObject("select count(*) from journal_entries where tenant_id = ?", Long.class, tenantId);
        allocations.allocate(advance.getId(), inv.getId(), null, new BigDecimal("2100.00"), null);
        assertThat(jdbc.queryForObject("select count(*) from journal_entries where tenant_id = ?", Long.class, tenantId))
                .isEqualTo(journalsBefore);

        PayablesAgingDTO after = payables.aging(AUG_31, null, null);
        assertThat(row(after, gulf).figures().openTotal()).isEqualByComparingTo("0.00");
        assertThat(row(after, gulf).figures().advances()).isEqualByComparingTo("2900.00");
        assertThat(row(after, gulf).figures().delta()).isEqualByComparingTo("0.00");
        // Allocated on the invoice date (the later of the two).
        assertThat(allocationRepo.findByInvoiceVoucherIdAndReleasedOnIsNull(inv.getId()))
                .singleElement().satisfies(a -> assertThat(a.getAllocatedOn()).isEqualTo(AUG_20));
    }

    @Test
    void concurrentAllocationsCannotOverAllocateOneInvoice() throws Exception {
        Voucher inv = pisr(gulf, "INV-4", AUG_1, line(rmP1, "1500.00", "0", p1));
        Voucher a = bpv(gulf, AUG_15, "1000.00", "TRF-A");
        Voucher b = bpv(gulf, AUG_15, "1000.00", "TRF-B");
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (Voucher pay : List.of(a, b)) {
                results.add(pool.submit(() -> {
                    TenantContextHolder.setTenantId(tenantId);
                    try {
                        barrier.await(10, TimeUnit.SECONDS);
                        allocations.allocate(pay.getId(), inv.getId(), null, new BigDecimal("1000.00"), null);
                        return "ok";
                    } catch (BusinessRuleViolationException e) {
                        return "refused";
                    } finally {
                        TenantContextHolder.clear();
                    }
                }));
            }
            List<String> outcomes = new ArrayList<>();
            for (Future<String> f : results) outcomes.add(f.get(30, TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder("ok", "refused");
        } finally {
            pool.shutdownNow();
        }
        assertThat(allocations.liveOnInvoice(inv.getId(), null)).isEqualByComparingTo("1000.00");
    }

    // ------------------------------------------------------------------ lock interaction

    @Test
    void anAllocationCannotBeDatedIntoOrReleasedFromALockedPeriod() {
        Voucher inv = pisr(gulf, "INV-5", AUG_1, line(rmP1, "1000.00", "0", p1));
        Voucher pay = bpv(gulf, AUG_15, "1000.00", "TRF-5", to(inv, "400.00"));
        fiscal.lockThrough(AUG_31);

        // Dated into the lock: refused.
        assertThatThrownBy(() -> allocations.allocate(pay.getId(), inv.getId(), null, new BigDecimal("100"), AUG_20))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("books are locked through 2026-08-31");
        // No date given: the first open day, not the payment date.
        var fresh = allocations.allocate(pay.getId(), inv.getId(), null, new BigDecimal("100"), null);
        assertThat(fresh.getAllocatedOn()).isEqualTo(SEP_1);

        // The 400 allocated on 15/08 is inside the lock: it cannot be released.
        UUID locked = allocationRepo.findByPaymentVoucherIdAndReleasedOnIsNull(pay.getId()).stream()
                .filter(x -> x.getAllocatedOn().equals(AUG_15)).findFirst().orElseThrow().getId();
        assertThatThrownBy(() -> allocations.release(locked, "wrong invoice"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("inside the locked period");
        // The one dated after the lock can.
        allocations.release(fresh.getId(), "wrong invoice");
        assertThat(allocations.liveOnInvoice(inv.getId(), null)).isEqualByComparingTo("400.00");
        // August's aging is what it was when August was locked.
        assertThat(item(row(payables.aging(AUG_31, null, null), gulf).items(), "INV-5").open())
                .isEqualByComparingTo("600.00");
    }

    // ------------------------------------------------------------------ lifecycle hooks

    @Test
    void amendingAPaymentReleasesItsAllocations() {
        Voucher inv1 = pisr(gulf, "INV-7781", AUG_1, line(rmP1, "1450.00", "0", p1));
        Voucher inv2 = pisr(gulf, "INV-7790", AUG_20, line(securityP2, "2100.00", "0", p2));
        Voucher pay = bpv(gulf, SEP_1, "2050.00", "TRF-1", to(inv1, "1450.00"), to(inv2, "600.00"));

        Voucher replacement = vouchers.amend(pay.getId(), SEP_10, "wrong amount",
                bpvInput(gulf, SEP_1, "1000.00", "TRF-1b"), List.of());
        assertThat(allocationRepo.findByPaymentVoucherIdAndReleasedOnIsNull(pay.getId())).isEmpty();
        assertThat(allocationRepo.findTouching(pay.getId())).allSatisfy(a -> {
            assertThat(a.getReleasedOn()).isEqualTo(SEP_10);
            assertThat(a.getReleaseReason()).contains("amended");
        });
        List<OpenItemDTO> items = payables.vendorItems(gulf.getId());
        assertThat(item(items, "INV-7781").status()).isEqualTo("OPEN");
        assertThat(item(items, "INV-7790").status()).isEqualTo("OPEN");
        // The replacement is an advance; aging still ties.
        assertThat(allocations.liveOnPayment(replacement.getId())).isEqualByComparingTo("0");
        assertThat(row(payables.aging(SEP_30, null, null), gulf).figures().delta()).isEqualByComparingTo("0.00");
        // As of 05/09 (before the reversal) the original payment still settled both.
        assertThat(item(row(payables.aging(SEP_5, null, null), gulf).items(), "INV-7790").open())
                .isEqualByComparingTo("1500.00");
    }

    @Test
    void amendingAnInvoiceCarriesItsAllocationsCappedAtTheNewGross() {
        Voucher inv = pisr(gulf, "INV-7790", AUG_20, line(securityP2, "2000.00", "5", p2));
        Voucher pay = bpv(gulf, SEP_1, "600.00", "TRF-600", to(inv, "600.00"));

        // Down to 1,800 gross: the 600 carries, 1,200 open.
        Voucher r1 = vouchers.amend(inv.getId(), SEP_5, "credit note",
                pisrInput(gulf, "INV-7790", AUG_20, line(securityP2, "1714.29", "5", p2)));
        assertThat(allocations.grossOf(r1.getId(), null)).isEqualByComparingTo("1800.00");
        assertThat(allocations.liveOnInvoice(r1.getId(), null)).isEqualByComparingTo("600.00");
        assertThat(allocations.liveOnInvoice(inv.getId(), null)).isEqualByComparingTo("0");

        // Down to 500: 500 carries, 100 is released and becomes an advance.
        Voucher r2 = vouchers.amend(r1.getId(), SEP_10, "second credit note",
                pisrInput(gulf, "INV-7790", AUG_20, line(securityP2, "500.00", "0", p2)));
        assertThat(allocations.liveOnInvoice(r2.getId(), null)).isEqualByComparingTo("500.00");
        assertThat(allocations.liveOnPayment(pay.getId())).isEqualByComparingTo("500.00");
        assertThat(payables.advancesNow(gulf.getId())).singleElement()
                .satisfies(a -> assertThat(a.unallocated()).isEqualByComparingTo("100.00"));
        PayablesAgingDTO.Figures f = row(payables.aging(SEP_30, null, null), gulf).figures();
        assertThat(f.openTotal()).isEqualByComparingTo("0.00");
        assertThat(f.advances()).isEqualByComparingTo("100.00");
        assertThat(f.delta()).isEqualByComparingTo("0.00");
    }

    // ------------------------------------------------------------------ duplicate guard

    @Test
    void aDuplicateInvoiceIsRefusedUnderNormalisation() {
        Voucher first = pisr(gulf, "INV-7781", AUG_1, line(rmP1, "100.00", "0", p1));
        assertThat(first.getInvoiceNoNorm()).isEqualTo("INV7781");
        assertThatThrownBy(() -> vouchers.createDraft(pisrInput(gulf, " inv 7781 ", AUG_15, line(rmP1, "100.00", "0", p1))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("is already posted as " + first.getVoucherNumber());
        // The same number from another vendor is another invoice.
        assertThat(pisr(alNoor, "INV-7781", AUG_1, line(cleaningP1, "100.00", "0", p1)).getStatus())
                .isEqualTo(VoucherStatus.POSTED);
    }

    @Test
    void aDraftSavedBeforeTheOriginalWasPostedIsRefusedAtPost() {
        Voucher draft = vouchers.createDraft(pisrInput(gulf, "INV-50", AUG_1, line(rmP1, "100.00", "0", p1)));
        pisr(gulf, "INV 50", AUG_1, line(rmP1, "100.00", "0", p1));
        assertThatThrownBy(() -> vouchers.post(draft.getId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("already posted");
    }

    @Test
    void theIndexItselfRefusesASecondPostedRowAndGrandfatheredRowsAreExempt() {
        Voucher a = pisr(gulf, "INV-60", AUG_1, line(rmP1, "100.00", "0", p1));
        Voucher b = pisr(gulf, "INV-61", AUG_1, line(rmP1, "100.00", "0", p1));
        // Past the service: the unique index.
        assertThatThrownBy(() -> jdbc.update("update vouchers set invoice_no_norm = 'INV60' where id = ?", b.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        // A grandfathered duplicate (changeset 110's backfill) is outside it.
        jdbc.update("update vouchers set invoice_no_norm = 'INV60', duplicate_grandfathered = true where id = ?", b.getId());
        // Scoped to this tenant: the suite shares one database, and another class's INV60 must not count.
        assertThat(jdbc.queryForObject("select count(*) from vouchers where tenant_id = ? and invoice_no_norm = 'INV60' and status = 'POSTED'",
                Long.class, tenantId)).isEqualTo(2);
        assertThat(a.isDuplicateGrandfathered()).isFalse();
    }

    @Test
    void amendingAnInvoiceUnderItsOwnNumberFlushesTheReversalFirst() {
        Voucher inv = pisr(gulf, "INV-70", AUG_1, line(rmP1, "100.00", "0", p1));
        Voucher r = vouchers.amend(inv.getId(), AUG_15, "typo", pisrInput(gulf, "INV-70", AUG_1, line(rmP1, "110.00", "0", p1)));
        assertThat(r.getStatus()).isEqualTo(VoucherStatus.POSTED);
        assertThat(jdbc.queryForList("select status from vouchers where tenant_id = ? and invoice_no_norm = 'INV70' order by created_at",
                String.class, tenantId)).containsExactly("REVERSED", "POSTED");
    }

    @Test
    void theChangesetNormalisationMatchesJava() {
        for (String raw : List.of("INV-7781", " inv 7781 ", "Inv\t77-81", "a-b c", "---", "  ", "فاتورة-12")) {
            // Not "ß": Postgres upper() gives "ẞ" under ICU, Java keeps "ß". Harmless for
            // real invoice numbers (PR #351 review P3-10), so it is left out on purpose.
            String sql = jdbc.queryForObject("select NULLIF(regexp_replace(upper(?), '[[:space:]-]', '', 'g'), '')",
                    String.class, raw);
            assertThat(VoucherService.normaliseInvoiceNumber(raw)).as(raw).isEqualTo(sql);
        }
    }

    // ------------------------------------------------------------------ TRN rule

    @Test
    void inputVatWithoutASupplierTrnIsRefusedAtDraftAndAtPost() {
        Vendor noTrn = vendor("Handyman Co", null);
        assertThatThrownBy(() -> vouchers.createDraft(pisrInput(noTrn, "H-1", AUG_1, line(rmP1, "100.00", "5", p1))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Input VAT needs the supplier's TRN from their tax invoice. Add the TRN to Handyman Co "
                        + "or post the invoice without VAT.");
        // Without VAT it is fine.
        assertThat(pisr(noTrn, "H-2", AUG_1, line(rmP1, "100.00", "0", p1)).getStatus()).isEqualTo(VoucherStatus.POSTED);
        // A draft saved while the TRN was there is refused at post once it is gone.
        Voucher draft = vouchers.createDraft(pisrInput(gulf, "G-1", AUG_1, line(rmP1, "100.00", "5", p1)));
        jdbc.update("update vendors set trn = null where id = ?", gulf.getId());
        assertThatThrownBy(() -> vouchers.post(draft.getId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Input VAT needs the supplier's TRN");
    }

    @Test
    void aVendorTrnIsFifteenDigits() {
        Vendor v = new Vendor();
        v.setNameEn("Bad TRN");
        v.setTrn("10012345");
        assertThatThrownBy(() -> vendorService.createVendor(v))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("15 digits");
        Vendor spaced = new Vendor();
        spaced.setNameEn("Spaced TRN");
        // A TRN no other vendor of this tenant carries (F14-43 refuses a duplicate).
        spaced.setTrn("100 1234 5670 0099");
        assertThat(vendorService.createVendor(spaced).getTrn()).isEqualTo("100123456700099");
    }

    // ------------------------------------------------------------------ payment method

    @Test
    void thePaymentMethodMustMatchThePaymentAccount() {
        VoucherService.VoucherInput cashFromBank = new VoucherService.VoucherInput(VoucherType.BPV, AUG_15, gulf.getId(),
                null, "x", null, null, bank.getId(), null, null,
                List.of(new VoucherService.VoucherLineInput(gulf.getPayableAccount().getId(), "x", new BigDecimal("10"),
                        BigDecimal.ZERO, null, null)), null, null, VoucherPaymentMethod.CASH, null);
        assertThatThrownBy(() -> vouchers.createDraft(cashFromBank)).hasMessageContaining("cash account");
        VoucherService.VoucherInput transferFromCash = new VoucherService.VoucherInput(VoucherType.BPV, AUG_15, gulf.getId(),
                null, "x", null, null, cash.getId(), null, null,
                List.of(new VoucherService.VoucherLineInput(gulf.getPayableAccount().getId(), "x", new BigDecimal("10"),
                        BigDecimal.ZERO, null, null)), null, null, VoucherPaymentMethod.TRANSFER, null);
        assertThatThrownBy(() -> vouchers.createDraft(transferFromCash)).hasMessageContaining("bank account");
        // Inferred when not given: cash leaf → CASH, a cheque number → CHEQUE.
        VoucherService.VoucherInput inferred = new VoucherService.VoucherInput(VoucherType.BPV, AUG_15, gulf.getId(),
                null, "x", null, null, cash.getId(), null, null,
                List.of(new VoucherService.VoucherLineInput(gulf.getPayableAccount().getId(), "x", new BigDecimal("10"),
                        BigDecimal.ZERO, null, null)));
        assertThat(vouchers.createDraft(inferred).getPaymentMethod()).isEqualTo(VoucherPaymentMethod.CASH);
    }

    // ------------------------------------------------------------------ opening items

    @Test
    void openingItemsAreAllocatableAndCheckedAgainstTheOpeningBalance() {
        // Cut-over: Gulf AC owed 3,000 at 31/07, as an OB line on its payable leaf.
        posting.post(new PostingRequest(JournalDocType.OB, LocalDate.of(2026, 7, 31), "Opening balances",
                PostingRequest.Dimensions.none(), JournalSourceType.OPENING_BALANCE, null, null, List.of(
                        PostingRequest.dr(accounts.getAccountByCode("A-02-05-001").getId(), new BigDecimal("3000.00")),
                        PostingRequest.cr(gulf.getPayableAccount().getId(), new BigDecimal("3000.00")))));
        ApOpeningItemDTO o = openingItems.create(new ApOpeningItemInputDTO(gulf.getId(), "OLD-1",
                LocalDate.of(2026, 6, 15), null, new BigDecimal("2000.00"), p1.getId()));
        assertThat(o.dueDate()).isEqualTo(LocalDate.of(2026, 7, 15));
        ApOpeningSummaryDTO summary = openingItems.summary(null);
        assertThat(summary.vendors()).singleElement().satisfies(c -> {
            assertThat(c.itemsTotal()).isEqualByComparingTo("2000.00");
            assertThat(c.openingBalance()).isEqualByComparingTo("3000.00");
            assertThat(c.difference()).isEqualByComparingTo("1000.00");
        });
        openingItems.create(new ApOpeningItemInputDTO(gulf.getId(), "OLD-2", LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31), new BigDecimal("1000.00"), null));
        assertThat(openingItems.summary(null).vendors().get(0).difference()).isEqualByComparingTo("0.00");

        // A payment settles the opening item like a PISR.
        Voucher pay = bpv(gulf, AUG_15, "2000.00", "TRF-OB",
                new AllocationInput(null, o.id(), new BigDecimal("2000.00")));
        assertThat(allocations.liveOnInvoice(null, o.id())).isEqualByComparingTo("2000.00");
        assertThatThrownBy(() -> openingItems.delete(o.id())).hasMessageContaining("allocated");
        PayablesAgingDTO.Figures f = row(payables.aging(AUG_31, null, null), gulf).figures();
        assertThat(f.openTotal()).isEqualByComparingTo("1000.00");
        assertThat(f.d31to60()).isEqualByComparingTo("1000.00");   // OLD-2 due 31/07: 31 days at 31/08
        assertThat(f.ledgerBalance()).isEqualByComparingTo("1000.00");
        assertThat(f.delta()).isEqualByComparingTo("0.00");
        // The property share of the opening item goes to its header property.
        assertThat(paid(p1, AUG_1, AUG_31, "allocatedPaid")).isEqualByComparingTo("2000.00");
        assertThat(pay.getStatus()).isEqualTo(VoucherStatus.POSTED);
    }

    // ------------------------------------------------------------------ tenant isolation

    @Test
    void anotherTenantsAllocationsAreInvisibleAndItsInvoicesAreNotFound() {
        Voucher inv = pisr(gulf, "INV-1", AUG_1, line(rmP1, "1000.00", "0", p1));
        Voucher pay = bpv(gulf, AUG_15, "1000.00", "TRF-1", to(inv, "600.00"));
        UUID tenantA = tenantId;

        UUID tenantB = newTenant("AP-B-");
        Property pb = property("B Tower");
        Account rmB = accounts.createLeaf("Repairs & Maintenance - B Tower", accounts.getAccountByCode("D-01"), pb.getId());
        Vendor vb = vendor("B Vendor", "100000000000003");
        fiscal.setBooksStartDate(AUG_1);
        Voucher invB = vouchers.post(vouchers.createDraft(new VoucherService.VoucherInput(VoucherType.PISR, AUG_1,
                vb.getId(), "B-1", "b", null, null, null, null, null,
                List.of(new VoucherService.VoucherLineInput(rmB.getId(), "b", new BigDecimal("500"), BigDecimal.ZERO,
                        pb.getId(), null)))).getId());

        // B sees none of A's allocations, items or aging.
        assertThat(allocations.ofVoucher(pay.getId())).isEmpty();
        assertThat(payables.aging(SEP_30, null, null).rows()).extracting(PayablesAgingDTO.VendorRow::vendorName)
                .containsExactly("B Vendor");
        assertThatThrownBy(() -> payables.vendorItems(gulf.getId())).isInstanceOf(NotFoundException.class);

        // Back in A: B's invoice id is not found.
        TenantContextHolder.setTenantId(tenantA);
        assertThatThrownBy(() -> allocations.allocate(pay.getId(), invB.getId(), null, new BigDecimal("100"), null))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> bpv(gulf, AUG_15, "100.00", "TRF-X", to(invB, "100.00")))
                .isInstanceOf(NotFoundException.class);
        assertThat(tenantB).isNotEqualTo(tenantA);
    }

    // ------------------------------------------------------------------ purge

    @Test
    void aTenantWithAllocationsAndOpeningItemsCanBeDeleted() {
        workedExample();
        ApOpeningItemDTO o = openingItems.create(new ApOpeningItemInputDTO(alNoor.getId(), "OLD-9",
                LocalDate.of(2026, 7, 1), null, new BigDecimal("100.00"), null));
        bpv(alNoor, SEP_10, "100.00", "TRF-OLD", new AllocationInput(null, o.id(), new BigDecimal("100.00")));
        for (String table : List.of("voucher_allocations", "ap_opening_items")) {
            assertThat(jdbc.queryForObject("select count(*) from " + table + " where tenant_id = ?", Long.class, tenantId))
                    .as(table).isPositive();
        }
        String name = orgRepo.findById(tenantId).orElseThrow().getName();
        TenantContextHolder.clear();
        orgService.deleteTenant(tenantId, name);
        for (String table : List.of("voucher_allocations", "ap_opening_items", "vouchers", "vendors")) {
            assertThat(jdbc.queryForObject("select count(*) from " + table + " where tenant_id = ?", Long.class, tenantId))
                    .as("rows surviving in %s", table).isZero();
        }
    }

    // ------------------------------------------------------------------ PR #351 review fixes

    @Test
    void aSupplierDateAfterThePostingDateIsRefusedAtDraftAndAtPost() {
        VoucherService.VoucherInput late = new VoucherService.VoucherInput(VoucherType.PISR, SEP_30, gulf.getId(), "INV-L",
                "x", null, null, null, null, null, List.of(line(rmP1, "1000.00", "5", p1)),
                LocalDate.of(2026, 10, 2), null, null, null);
        assertThatThrownBy(() -> vouchers.createDraft(late))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("The supplier's invoice date cannot be after the posting date");
        // A draft that reached the table another way is refused at post as well.
        Voucher draft = vouchers.createDraft(pisrInput(gulf, "INV-L2", SEP_30, line(rmP1, "1000.00", "5", p1)));
        jdbc.update("update vouchers set supplier_invoice_date = '2026-10-02' where id = ?", draft.getId());
        assertThatThrownBy(() -> vouchers.post(draft.getId()))
                .hasMessage("The supplier's invoice date cannot be after the posting date");
        // On or before the posting date is fine; the due date follows it.
        Voucher ok = vouchers.post(vouchers.createDraft(new VoucherService.VoucherInput(VoucherType.PISR, SEP_30,
                gulf.getId(), "INV-L3", "x", null, null, null, null, null, List.of(line(rmP1, "10.00", "0", p1)),
                SEP_5, null, null, null)).getId());
        assertThat(ok.getDueDate()).isEqualTo(LocalDate.of(2026, 10, 5));
    }

    @Test
    void amendingAPaymentCarriesItsAllocationsTrimmedToTheNewAmount() {
        Voucher inv1 = pisr(gulf, "INV-7781", AUG_1, line(rmP1, "1450.00", "0", p1));
        Voucher inv2 = pisr(gulf, "INV-7790", AUG_20, line(securityP2, "2100.00", "0", p2));
        Voucher pay = bpv(gulf, SEP_1, "2050.00", "TRF-1", to(inv1, "1450.00"), to(inv2, "600.00"));

        // No allocations given: they carry, oldest first, trimmed to the 1,800 now paid.
        Voucher r = vouchers.amend(pay.getId(), SEP_10, "bank charged less",
                bpvInput(gulf, SEP_1, "1800.00", "TRF-1"));
        assertThat(allocations.liveOnPayment(r.getId())).isEqualByComparingTo("1800.00");
        assertThat(allocations.liveOnInvoice(inv1.getId(), null)).isEqualByComparingTo("1450.00");
        assertThat(allocations.liveOnInvoice(inv2.getId(), null)).isEqualByComparingTo("350.00");
        assertThat(allocationRepo.findByPaymentVoucherIdAndReleasedOnIsNull(r.getId()))
                .allSatisfy(a -> assertThat(a.getAllocatedOn()).isEqualTo(SEP_10));
        assertThat(row(payables.aging(SEP_30, null, null), gulf).figures().delta()).isEqualByComparingTo("0.00");
    }

    @Test
    void pastDateAgingAfterAPaymentAmendDoesNotShowAnInvoicePaidTwice() {
        Voucher inv = pisr(gulf, "INV-1000", AUG_1, line(rmP1, "1000.00", "0", p1));
        Voucher pay = bpv(gulf, AUG_15, "1000.00", "TRF-A", to(inv, "1000.00"));
        // Amended on 20/09 with a replacement dated back to 15/08 that settles the same invoice.
        vouchers.amend(pay.getId(), LocalDate.of(2026, 9, 20), "reference typo",
                bpvInput(gulf, AUG_15, "1000.00", "TRF-B"), List.of(to(inv, "1000.00")));
        // As of 31/08 the original still settles it; the replacement is an advance until 20/09.
        PayablesAgingDTO aug = payables.aging(AUG_31, null, null);
        assertThat(row(aug, gulf).items()).isEmpty();
        assertThat(row(aug, gulf).figures().advances()).isEqualByComparingTo("1000.00");
        assertThat(row(aug, gulf).figures().delta()).isEqualByComparingTo("0.00");
        // August's section 7 pays it once; September's does not pay it again.
        assertThat(paid(p1, AUG_1, AUG_31, "allocatedPaid")).isEqualByComparingTo("1000.00");
        assertThat(paid(p1, SEP_1, SEP_30, "allocatedPaid")).isEqualByComparingTo("0.00");
        // By 30/09 nothing is owed and nothing is advanced: the vendor has no row.
        assertThat(payables.aging(SEP_30, null, null).rows()).isEmpty();
    }

    @Test
    void aGrandfatheredDuplicateIsAmendedOnlyToANewNumber() {
        Voucher first = pisr(gulf, "INV-G", AUG_1, line(rmP1, "100.00", "0", p1));
        Voucher second = pisr(gulf, "INV-G2", AUG_1, line(rmP1, "100.00", "0", p1));
        jdbc.update("update vouchers set invoice_number = 'INV-G', invoice_no_norm = 'INVG', duplicate_grandfathered = true"
                + " where id = ?", second.getId());
        assertThatThrownBy(() -> vouchers.amend(second.getId(), AUG_15, "fix",
                pisrInput(gulf, "INV-G", AUG_1, line(rmP1, "90.00", "0", p1))))
                .hasMessageContaining("grandfathered duplicate of " + first.getVoucherNumber())
                .hasMessageContaining("amend it to a corrected invoice number");
        assertThat(vouchers.amend(second.getId(), AUG_15, "fix",
                pisrInput(gulf, "INV-G-B", AUG_1, line(rmP1, "90.00", "0", p1))).getStatus()).isEqualTo(VoucherStatus.POSTED);
    }

    @Test
    void anAllocationCannotBeDatedInTheFuture() {
        Voucher inv = pisr(gulf, "INV-F", AUG_1, line(rmP1, "100.00", "0", p1));
        Voucher pay = bpv(gulf, AUG_15, "100.00", "TRF-F");
        LocalDate future = VoucherAllocationService.today().plusDays(5);
        assertThatThrownBy(() -> allocations.allocate(pay.getId(), inv.getId(), null, new BigDecimal("10"), future))
                .hasMessageContaining("in the future");
    }

    @Test
    void anOpeningItemEditWaitsForARacingAllocationAndThenSeesIt() throws Exception {
        ApOpeningItemDTO o = openingItems.create(new ApOpeningItemInputDTO(gulf.getId(), "OLD-R",
                LocalDate.of(2026, 7, 1), null, new BigDecimal("1000.00"), null));
        Voucher pay = bpv(gulf, AUG_15, "800.00", "TRF-R");
        CountDownLatch allocated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> t1 = pool.submit(() -> {
                TenantContextHolder.setTenantId(tenantId);
                try {
                    tx.executeWithoutResult(st -> {
                        allocations.allocate(pay.getId(), null, o.id(), new BigDecimal("800.00"), null);
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
                    openingItems.update(o.id(), new ApOpeningItemInputDTO(gulf.getId(), "OLD-R",
                            LocalDate.of(2026, 7, 1), null, new BigDecimal("300.00"), null));
                    return "updated";
                } catch (BusinessRuleViolationException e) {
                    return "refused: " + e.getMessage();
                } finally {
                    TenantContextHolder.clear();
                }
            });
            // The edit is blocked on the item's row lock while the allocation is open.
            Thread.sleep(500);
            assertThat(t2.isDone()).isFalse();
            release.countDown();
            t1.get(30, TimeUnit.SECONDS);
            assertThat(t2.get(30, TimeUnit.SECONDS)).startsWith("refused: Payments of 800.00");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select amount from ap_opening_items where id = ?", BigDecimal.class, o.id()))
                .isEqualByComparingTo("1000.00");
    }

    @Test
    void anOpeningItemKeepsItsDatesOnceSettledAndFreezesInsideTheLock() {
        ApOpeningItemDTO o = openingItems.create(new ApOpeningItemInputDTO(gulf.getId(), "OLD-D",
                LocalDate.of(2026, 7, 1), null, new BigDecimal("500.00"), null));
        Voucher pay = bpv(gulf, AUG_15, "200.00", "TRF-D", new AllocationInput(null, o.id(), new BigDecimal("200.00")));
        assertThatThrownBy(() -> openingItems.update(o.id(), new ApOpeningItemInputDTO(gulf.getId(), "OLD-D",
                LocalDate.of(2026, 8, 20), null, new BigDecimal("500.00"), null)))
                .hasMessageContaining("cannot be later than that");
        fiscal.lockThrough(AUG_31);
        assertThatThrownBy(() -> openingItems.update(o.id(), new ApOpeningItemInputDTO(gulf.getId(), "OLD-D",
                LocalDate.of(2026, 7, 1), null, new BigDecimal("450.00"), null)))
                .hasMessageContaining("locked period");
        // The property decides whose section 7 the settlement lands in: frozen too (re-review N4).
        assertThatThrownBy(() -> openingItems.update(o.id(), new ApOpeningItemInputDTO(gulf.getId(), "OLD-D",
                LocalDate.of(2026, 7, 1), o.dueDate(), new BigDecimal("500.00"), p1.getId())))
                .hasMessageContaining("locked period");
        // A change that moves no figure (the invoice number's spelling) is still allowed.
        assertThat(openingItems.update(o.id(), new ApOpeningItemInputDTO(gulf.getId(), "OLD-D1",
                LocalDate.of(2026, 7, 1), o.dueDate(), new BigDecimal("500.00"), null)).invoiceNumber())
                .isEqualTo("OLD-D1");
        assertThat(pay.getStatus()).isEqualTo(VoucherStatus.POSTED);
    }

    @Test
    void aPropertyManagerSeesOnlyUnallocatedPaymentsThatNameTheirProperty() {
        // One advance names P1 on its line, one names nothing.
        Voucher named = vouchers.post(vouchers.createDraft(new VoucherService.VoucherInput(VoucherType.BPV, SEP_5,
                gulf.getId(), null, "P1 advance", null, null, bank.getId(), null, null,
                List.of(new VoucherService.VoucherLineInput(gulf.getPayableAccount().getId(), "adv",
                        new BigDecimal("300.00"), BigDecimal.ZERO, p1.getId(), null)),
                null, null, VoucherPaymentMethod.TRANSFER, "TRF-P1")).getId());
        bpv(alNoor, SEP_10, "500.00", "TRF-ANY");

        // An admin sees both.
        assertThat(figure(statements.statement(p1.getId(), SEP_1, SEP_30, null), "expensesPaid", "unallocatedPayments"))
                .isEqualByComparingTo("800.00");
        // A manager assigned to P1 sees only the one that names P1.
        User pm = new User();
        pm.setEmail("pm-" + UUID.randomUUID() + "@t.io");
        pm.setName("PM");
        pm.setRole(UserRole.PROPERTY_MANAGER);
        pm.setStatus(UserStatus.ACTIVE);
        pm.setPasswordHash("x");
        pm.setTenantId(tenantId);
        pm = userRepo.save(pm);
        UserPropertyAssignment assignment = new UserPropertyAssignment();
        assignment.setUserId(pm.getId());
        assignment.setPropertyId(p1.getId());
        assignmentRepo.save(assignment);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        pm.getId().toString(), null,
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_PROPERTY_MANAGER"))));
        try {
            PropertyStatementDTO s = statements.statement(p1.getId(), SEP_1, SEP_30, null);
            assertThat(figure(s, "expensesPaid", "unallocatedPayments")).isEqualByComparingTo("300.00");
            List<List<Object>> rows = s.sections().stream().filter(x -> x.key().equals("expensesPaid")).findFirst()
                    .orElseThrow().tables().stream().filter(t -> t.key().equals("unallocated")).findFirst().orElseThrow().rows();
            assertThat(rows).singleElement().satisfies(r -> assertThat(r.get(1)).isEqualTo(named.getVoucherNumber()));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    // ------------------------------------------------------------------ PR #351 re-review N1–N5

    @Test
    void aPaymentReappliedAfterAReleaseIsDatedNoEarlierThanTheReleaseSoPastAgingIsUnchanged() {
        Voucher i1 = pisr(gulf, "INV-N1A", AUG_1, line(rmP1, "1000.00", "0", p1));
        Voucher i2 = pisr(gulf, "INV-N1B", AUG_1, line(rmP1, "1000.00", "0", p1));
        Voucher pay = bpv(gulf, AUG_15, "1000.00", "TRF-N1", to(i1, "1000.00"));
        UUID first = allocationRepo.findByPaymentVoucherIdAndReleasedOnIsNull(pay.getId()).get(0).getId();
        LocalDate today = VoucherAllocationService.today();
        allocations.release(first, "should have gone to INV-N1B");

        // An explicit date before the release is refused; no date takes the release date.
        assertThatThrownBy(() -> allocations.allocate(pay.getId(), i2.getId(), null, new BigDecimal("1000.00"), AUG_20))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("before the last release on this payment or invoice (" + today + ")");
        VoucherAllocation again = allocations.allocate(pay.getId(), i2.getId(), null, new BigDecimal("1000.00"), null);
        assertThat(again.getAllocatedOn()).isEqualTo(today);

        // As of 31/08 the payment still settles INV-N1A only: INV-N1B is open, nothing is paid twice,
        // there is no negative advance and the tie-out holds.
        PayablesAgingDTO aug = payables.aging(AUG_31, null, null);
        assertThat(row(aug, gulf).items()).extracting(OpenItemDTO::invoiceNumber).containsExactly("INV-N1B");
        assertThat(item(row(aug, gulf).items(), "INV-N1B").open()).isEqualByComparingTo("1000.00");
        assertThat(row(aug, gulf).figures().advances()).isEqualByComparingTo("0.00");
        assertThat(row(aug, gulf).figures().delta()).isEqualByComparingTo("0.00");
        // August's section 7 paid 1,000 once.
        assertThat(paid(p1, AUG_1, AUG_31, "allocatedPaid")).isEqualByComparingTo("1000.00");

        // The invoice side too: another payment re-settling INV-N1A dates after INV-N1A's release.
        Voucher pay2 = bpv(gulf, AUG_20, "1000.00", "TRF-N1C");
        assertThat(allocations.allocate(pay2.getId(), i1.getId(), null, new BigDecimal("1000.00"), null).getAllocatedOn())
                .isEqualTo(today);
    }

    @Test
    void aGrandfatheredDuplicateKeepsItsNumberOnceTheProtectedInvoiceIsGone() {
        Voucher first = pisr(gulf, "INV-GX", AUG_1, line(rmP1, "100.00", "0", p1));
        Voucher second = pisr(gulf, "INV-GX2", AUG_1, line(rmP1, "100.00", "0", p1));
        jdbc.update("update vouchers set invoice_number = 'INV-GX', invoice_no_norm = 'INVGX', duplicate_grandfathered = true"
                + " where id = ?", second.getId());
        // The protected invoice is renumbered: nothing holds INV-GX any more.
        vouchers.amend(first.getId(), AUG_15, "renumber", pisrInput(gulf, "INV-GX-OLD", AUG_1, line(rmP1, "100.00", "0", p1)));
        Voucher fixed = vouchers.amend(second.getId(), AUG_15, "fix amount",
                pisrInput(gulf, "INV-GX", AUG_1, line(rmP1, "90.00", "0", p1)));
        assertThat(fixed.getStatus()).isEqualTo(VoucherStatus.POSTED);
        assertThat(fixed.isDuplicateGrandfathered()).isFalse();
        assertThat(fixed.getInvoiceNoNorm()).isEqualTo("INVGX");
    }

    @Test
    void aReleasedAllocationInsideTheLockStillFreezesTheOpeningItem() {
        ApOpeningItemDTO o = openingItems.create(new ApOpeningItemInputDTO(gulf.getId(), "OLD-N4",
                LocalDate.of(2026, 7, 1), null, new BigDecimal("500.00"), null));
        Voucher pay = bpv(gulf, AUG_15, "200.00", "TRF-N4", new AllocationInput(null, o.id(), new BigDecimal("200.00")));
        // Released (by the payment's amend, dated in September), then August is locked.
        vouchers.amend(pay.getId(), SEP_5, "wrong item", bpvInput(gulf, AUG_15, "200.00", "TRF-N4B"), List.of());
        assertThat(allocations.liveOnInvoice(null, o.id())).isEqualByComparingTo("0.00");
        fiscal.lockThrough(AUG_31);
        // August's aging counted the 200 against this item; its amount cannot move now.
        assertThatThrownBy(() -> openingItems.update(o.id(), new ApOpeningItemInputDTO(gulf.getId(), "OLD-N4",
                LocalDate.of(2026, 7, 1), null, new BigDecimal("150.00"), null)))
                .hasMessageContaining("locked period");
    }

    @Test
    void aFutureDatedPaymentAllocatesOnItsOwnDateAndNoLater() {
        LocalDate ahead = VoucherAllocationService.today().plusDays(5);
        Voucher inv = pisr(gulf, "INV-N5", AUG_1, line(rmP1, "300.00", "0", p1));
        Voucher inv2 = pisr(gulf, "INV-N5B", AUG_1, line(rmP1, "300.00", "0", p1));
        Voucher pay = bpv(gulf, ahead, "600.00", "TRF-N5", to(inv, "300.00"));
        // The default date on post is the payment's own date.
        assertThat(allocationRepo.findByPaymentVoucherIdAndReleasedOnIsNull(pay.getId()))
                .singleElement().satisfies(a -> assertThat(a.getAllocatedOn()).isEqualTo(ahead));
        // An explicit date: the payment's date is accepted, a day later is refused.
        assertThatThrownBy(() -> allocations.allocate(pay.getId(), inv2.getId(), null, new BigDecimal("100"), ahead.plusDays(1)))
                .hasMessageContaining("in the future").hasMessageContaining("the documents allow " + ahead);
        assertThat(allocations.allocate(pay.getId(), inv2.getId(), null, new BigDecimal("100"), ahead).getAllocatedOn())
                .isEqualTo(ahead);
    }
}
