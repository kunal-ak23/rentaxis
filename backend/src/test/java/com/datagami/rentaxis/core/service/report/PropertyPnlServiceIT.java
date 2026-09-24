package com.datagami.rentaxis.core.service.report;

import com.datagami.rentaxis.api.dto.ledger.AccountLedgerDTO;
import com.datagami.rentaxis.api.dto.report.PnlLinesDTO;
import com.datagami.rentaxis.api.dto.report.PropertyPnlDTO;
import com.datagami.rentaxis.api.dto.report.PropertyPnlDTO.Amount;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.VendorService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService.LedgerFilter;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingRequest.Dimensions;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.report.PnlAllocation.Basis;
import com.datagami.rentaxis.core.service.report.PnlPeriods.Compare;
import com.datagami.rentaxis.core.service.voucher.VoucherService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.VoucherType;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.core.service.ledger.PostingRequest.cr;
import static com.datagami.rentaxis.core.service.ledger.PostingRequest.dr;
import static com.datagami.rentaxis.core.service.report.PropertyPnlFixture.AUG_1;
import static com.datagami.rentaxis.core.service.report.PropertyPnlFixture.AUG_31;
import static com.datagami.rentaxis.core.service.report.PropertyPnlFixture.SEP_1;
import static com.datagami.rentaxis.core.service.report.PropertyPnlFixture.SEP_30;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Finance-ops spec §1: the per-property P&L, asserted cell by cell against the
 * spec's worked example (Marina Tower, September vs August 2026).
 */
@SpringBootTest
class PropertyPnlServiceIT extends AbstractPostgresIT {

    @Autowired PropertyPnlService service;
    @Autowired LedgerQueryService ledger;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired AccountService accounts;
    @Autowired PropertyAccountService propertyAccounts;
    @Autowired PropertyService properties;
    @Autowired PostingService posting;
    @Autowired VoucherService vouchers;
    @Autowired VendorService vendorService;
    @Autowired AccountRepository accountRepo;
    @Autowired AccountResolver resolver;

    PropertyPnlFixture fx;

    @BeforeEach
    void setUp() {
        fx = new PropertyPnlFixture(orgRepo, accounts, propertyAccounts, properties, posting, vouchers, vendorService,
                accountRepo, resolver).tenant("PNL-").workedExample();
    }

    @AfterEach
    void clear() { TenantContextHolder.clear(); }

    private static void money(Amount a, String amount, String prior, String delta) {
        assertThat(a.amount()).isEqualByComparingTo(amount);
        assertThat(a.prior()).isEqualByComparingTo(prior);
        assertThat(a.delta()).isEqualByComparingTo(delta);
    }

    private static Amount row(PropertyPnlDTO r, String rowKey, String column) {
        return r.groups().stream().flatMap(g -> g.rows().stream()).filter(x -> x.key().equals(rowKey))
                .findFirst().orElseThrow(() -> new AssertionError("no row " + rowKey)).cells().get(column);
    }

    @Test
    void workedExampleEveryCellNoiVarianceAndCheck() {
        PropertyPnlDTO r = service.pnl(SEP_1, SEP_30, null, Compare.PREVIOUS, Basis.NONE);
        String p1 = fx.p1.getId().toString();

        assertThat(r.priorFrom()).isEqualTo(AUG_1);
        assertThat(r.priorTo()).isEqualTo(AUG_31);
        money(row(r, "RENTAL_INCOME", p1), "82191.78", "82191.78", "0.00");
        money(row(r, "ADMIN_FEE", p1), "1500.00", "0.00", "1500.00");
        money(row(r, "PARKING_INCOME", p1), "2000.00", "2000.00", "0.00");
        money(row(r, "CHEQUE_RETURN_PENALTY", p1), "500.00", "0.00", "500.00");
        money(r.income().get(p1), "86191.78", "84191.78", "2000.00");
        money(row(r, "EXP_REPAIRS_MAINTENANCE", p1), "0.00", "1000.00", "-1000.00");
        money(row(r, "EXP_CLEANING", p1), "3000.00", "400.00", "2600.00");
        money(row(r, "EXP_UTILITIES", p1), "4200.00", "0.00", "4200.00");
        money(r.expenses().get(p1), "7200.00", "1400.00", "5800.00");
        money(r.noi().get(p1), "78991.78", "82791.78", "-3800.00");
        assertThat(r.noi().get(p1).deltaPct()).isEqualByComparingTo("-4.59");
        // A base of zero has no percentage.
        assertThat(row(r, "ADMIN_FEE", p1).deltaPct()).isNull();

        // Palm had no activity: its column is there, all zero.
        String p2 = fx.p2.getId().toString();
        assertThat(r.columns()).extracting(PropertyPnlDTO.Column::key).contains(p2, "UNASSIGNED", "TOTAL");
        assertThat(r.noi().get(p2).amount()).isEqualByComparingTo("0");

        // Unassigned holds the shared bank items; Total = Σ properties + Unassigned.
        assertThat(row(r, fx.bankCharges.getId().toString(), "UNASSIGNED").amount()).isEqualByComparingTo("50.00");
        assertThat(row(r, fx.bankInterest.getId().toString(), "UNASSIGNED").amount()).isEqualByComparingTo("120.00");
        assertThat(r.noi().get("UNASSIGNED").amount()).isEqualByComparingTo("70.00");
        assertThat(r.noi().get("TOTAL").amount()).isEqualByComparingTo("79061.78");

        assertThat(r.check().ok()).isTrue();
        assertThat(r.check().difference()).isEqualByComparingTo("0");
        assertThat(r.check().ledgerNet()).isEqualByComparingTo("79061.78");
        assertThat(r.dataQuality().lineAccountPropertyMismatches()).isZero();
        assertThat(r.allocation()).isNull();
    }

    @Test
    void rowsAreGroupedUnderTheLevelTwoGroups() {
        PropertyPnlDTO r = service.pnl(SEP_1, SEP_30, null, Compare.NONE, Basis.NONE);
        assertThat(r.groups()).extracting(PropertyPnlDTO.Group::code).containsExactly("C-01", "C-02", "D-01", "D-02");
        assertThat(r.income().get(fx.p1.getId().toString()).prior()).isNull();
    }

    @Test
    void theLineWinsOverTheAccountAndTheFooterCountsIt() {
        // A cleaning bill for Palm posted to Marina's cleaning leaf.
        UUID marinaCleaning = fx.leaf(fx.p1, "EXP_CLEANING");
        posting.post(new PostingRequest(JournalDocType.JV, LocalDate.of(2026, 9, 20), "misposted", Dimensions.none(),
                JournalSourceType.MANUAL, null, null, List.of(
                dr(marinaCleaning, new BigDecimal("250.00")).withDims(Dimensions.ofProperty(fx.p2.getId())),
                cr(AccountRole.CASH, new BigDecimal("250.00")))));

        PropertyPnlDTO r = service.pnl(SEP_1, SEP_30, null, Compare.NONE, Basis.NONE);
        assertThat(row(r, "EXP_CLEANING", fx.p2.getId().toString()).amount()).isEqualByComparingTo("250.00");
        assertThat(row(r, "EXP_CLEANING", fx.p1.getId().toString()).amount()).isEqualByComparingTo("3000.00");
        assertThat(r.dataQuality().lineAccountPropertyMismatches()).isEqualTo(1);
        assertThat(r.check().ok()).isTrue();

        // A leaf with no line dimension still belongs to its property (the coalesce).
        posting.post(new PostingRequest(JournalDocType.JV, LocalDate.of(2026, 9, 21), "no dimension", Dimensions.none(),
                JournalSourceType.MANUAL, null, null, List.of(dr(marinaCleaning, new BigDecimal("10.00")),
                cr(AccountRole.CASH, new BigDecimal("10.00")))));
        PropertyPnlDTO again = service.pnl(SEP_1, SEP_30, null, Compare.NONE, Basis.NONE);
        assertThat(row(again, "EXP_CLEANING", fx.p1.getId().toString()).amount()).isEqualByComparingTo("3010.00");
        assertThat(again.noi().get("UNASSIGNED").amount()).isEqualByComparingTo("70.00");
    }

    @Test
    void theDrillDownListsEveryLineTheCellSums() {
        List<UUID> cleaning = List.of(fx.leaf(fx.p1, "EXP_CLEANING"), fx.leaf(fx.p2, "EXP_CLEANING"));
        // A line on Marina's leaf with no dimension: the line-only GL filter misses it, the effective one does not.
        posting.post(new PostingRequest(JournalDocType.JV, LocalDate.of(2026, 9, 21), "no dimension", Dimensions.none(),
                JournalSourceType.MANUAL, null, null, List.of(dr(cleaning.getFirst(), new BigDecimal("10.00")),
                cr(AccountRole.CASH, new BigDecimal("10.00")))));

        PnlLinesDTO lines = service.lines(SEP_1, SEP_30, fx.p1.getId().toString(), "EXP_CLEANING", null, null);
        assertThat(lines.lines()).hasSize(2);
        assertThat(lines.totalDebit().subtract(lines.totalCredit())).isEqualByComparingTo("3010.00");

        LedgerFilter lineOnly = new LedgerFilter(SEP_1, SEP_30, fx.p1.getId(), null, null, null, false);
        LedgerFilter effective = new LedgerFilter(SEP_1, SEP_30, fx.p1.getId(), null, null, null, true);
        AccountLedgerDTO narrow = ledger.accountLedger(cleaning.getFirst(), lineOnly);
        AccountLedgerDTO wide = ledger.accountLedger(cleaning.getFirst(), effective);
        assertThat(narrow.rows()).hasSize(1);
        assertThat(wide.rows()).hasSize(2);
        assertThat(wide.totalDebit()).isEqualByComparingTo("3010.00");
        assertThat(ledger.generalLedger(List.of(), effective)).extracting(AccountLedgerDTO::accountId).contains(cleaning.getFirst());

        // NOI of Unassigned (no key): the two shared bank items.
        PnlLinesDTO unassigned = service.lines(SEP_1, SEP_30, "UNASSIGNED", null, null, null);
        assertThat(unassigned.lines()).hasSize(2);
        assertThat(service.lines(SEP_1, SEP_30, "TOTAL", "EXP_CLEANING", null, null).lines()).hasSize(2);
        // A group subtotal, by group id: Direct Expense in September is AN-311, DEWA and the 10.00.
        PropertyPnlDTO r = service.pnl(SEP_1, SEP_30, null, Compare.NONE, Basis.NONE);
        UUID directExpense = r.groups().stream().filter(g -> g.code().equals("D-01")).findFirst().orElseThrow().groupId();
        PnlLinesDTO group = service.lines(SEP_1, SEP_30, fx.p1.getId().toString(), null, directExpense, null);
        assertThat(group.totalDebit().subtract(group.totalCredit())).isEqualByComparingTo("7210.00");
    }

    @Test
    void allocationIsReportOnlyAndTiesToUnassignedExactly() {
        // Three-way split of a cost that does not divide evenly.
        UUID p3 = fx.property("Creek View").getId();
        posting.post(new PostingRequest(JournalDocType.JV, LocalDate.of(2026, 9, 30), "Audit fee", Dimensions.none(),
                JournalSourceType.MANUAL, null, null, List.of(dr(fx.bankCharges.getId(), new BigDecimal("100.00")),
                cr(AccountRole.CASH, new BigDecimal("100.00")))));
        PropertyPnlDTO r = service.pnl(SEP_1, SEP_30, null, Compare.NONE, Basis.EQUAL);
        // Unassigned: 150 of charges, 120 of interest: a net cost of 30.00.
        assertThat(r.allocation().unassignedCost()).isEqualByComparingTo("30.00");
        Map<String, BigDecimal> alloc = r.allocation().allocated();
        assertThat(alloc.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("30.00");
        assertThat(alloc.get(p3.toString())).isEqualByComparingTo("10.00");
        assertThat(r.allocation().allocatedToOthers()).isEqualByComparingTo("0.00");
        assertThat(r.allocation().noiAfter().get(fx.p1.getId().toString())).isEqualByComparingTo("78981.78");

        // By rent: only Marina earns rent, so Marina takes all of it.
        PropertyPnlDTO byRent = service.pnl(SEP_1, SEP_30, null, Compare.NONE, Basis.RENT);
        assertThat(byRent.allocation().basisUsed()).isEqualTo("RENT");
        assertThat(byRent.allocation().allocated().get(fx.p1.getId().toString())).isEqualByComparingTo("30.00");
        // By units: nobody has units, so the spread falls back to equal and says so.
        PropertyPnlDTO byUnits = service.pnl(SEP_1, SEP_30, null, Compare.NONE, Basis.UNITS);
        assertThat(byUnits.allocation().basisUsed()).isEqualTo("EQUAL");

        // Nothing was posted: the P&L and its check are unchanged by the toggle.
        assertThat(r.noi().get("TOTAL").amount()).isEqualByComparingTo(
                service.pnl(SEP_1, SEP_30, null, Compare.NONE, Basis.NONE).noi().get("TOTAL").amount());
    }

    @Test
    void aSubsetCarriesOnlyItsOwnShareOfTheSharedCosts() {
        fx.property("Creek View");
        posting.post(new PostingRequest(JournalDocType.JV, LocalDate.of(2026, 9, 30), "Audit fee", Dimensions.none(),
                JournalSourceType.MANUAL, null, null, List.of(dr(fx.bankCharges.getId(), new BigDecimal("100.00")),
                cr(AccountRole.CASH, new BigDecimal("100.00")))));
        String p1 = fx.p1.getId().toString();
        // Equal over all three properties: Marina's third of 30.00, the rest to the others.
        PropertyPnlDTO one = service.pnl(SEP_1, SEP_30, List.of(fx.p1.getId()), Compare.NONE, Basis.EQUAL);
        assertThat(one.allocation().allocated()).containsOnlyKeys(p1);
        assertThat(one.allocation().allocated().get(p1)).isEqualByComparingTo("10.00");
        assertThat(one.allocation().allocatedToOthers()).isEqualByComparingTo("20.00");
        // By rent, Palm (no rent) shown alone takes nothing; Marina elsewhere takes it all.
        PropertyPnlDTO palm = service.pnl(SEP_1, SEP_30, List.of(fx.p2.getId()), Compare.NONE, Basis.RENT);
        assertThat(palm.allocation().allocated().get(fx.p2.getId().toString())).isEqualByComparingTo("0.00");
        assertThat(palm.allocation().allocatedToOthers()).isEqualByComparingTo("30.00");
    }

    /** Re-review N5: a deleted property keeps its column (its lines outlive it) but takes no share. */
    @Test
    void aDeletedPropertyTakesNoShareOfTheSharedCosts() {
        UUID gone = UUID.randomUUID();   // lines with a property whose row no longer exists
        // journal_entries.property_id has a foreign key; the line dimension has none,
        // which is how lines outlive a property row.
        posting.post(new PostingRequest(JournalDocType.JV, LocalDate.of(2026, 9, 15), "old building",
                Dimensions.none(), JournalSourceType.MANUAL, null, null, List.of(
                dr(AccountRole.CASH, new BigDecimal("100.00")),
                cr(fx.bankInterest.getId(), new BigDecimal("100.00")).withDims(Dimensions.ofProperty(gone)))));
        PropertyPnlDTO r = service.pnl(SEP_1, SEP_30, null, Compare.NONE, Basis.EQUAL);
        assertThat(r.columns()).extracting(PropertyPnlDTO.Column::name).anyMatch(n -> n.startsWith("Deleted property"));
        assertThat(r.allocation().allocated().get(gone.toString())).isEqualByComparingTo("0.00");
        // The 70.00 net income of Unassigned (a cost of −70.00) splits over Marina and Palm only.
        assertThat(r.allocation().allocated().get(fx.p1.getId().toString())).isEqualByComparingTo("-35.00");
        assertThat(r.allocation().allocatedToOthers()).isEqualByComparingTo("0.00");
    }

    @Test
    void theCheckRowComparesTheDisplayedTotalWithTheLedgerForTheSameScope() {
        fx.role(JournalDocType.CIL, SEP_30, Dimensions.ofProperty(fx.p2.getId()),
                AccountRole.ADVANCE_RENT, AccountRole.RENTAL_INCOME, "1000.00");
        PropertyPnlDTO all = service.pnl(SEP_1, SEP_30, null, Compare.NONE, Basis.NONE);
        assertThat(all.noi().get("TOTAL").amount()).isEqualByComparingTo("80061.78");
        assertThat(all.check().ok()).isTrue();
        // Marina alone: Total = Marina + Unassigned, and the ledger side is scoped the same way.
        PropertyPnlDTO one = service.pnl(SEP_1, SEP_30, List.of(fx.p1.getId()), Compare.NONE, Basis.NONE);
        assertThat(one.noi().get("TOTAL").amount()).isEqualByComparingTo("79061.78");
        assertThat(one.check().ledgerNet()).isEqualByComparingTo("79061.78");
        assertThat(one.check().ok()).isTrue();
        // A total that does not tie is flagged with its difference.
        PropertyPnlDTO.Check broken = PropertyPnlService.checkOf(new BigDecimal("79061.78"), new BigDecimal("80061.78"));
        assertThat(broken.ok()).isFalse();
        assertThat(broken.difference()).isEqualByComparingTo("-1000.00");
    }

    @Test
    void aSharedLineStaysOffTheHeadersProperty() {
        VoucherService.VoucherLineInput shared = new VoucherService.VoucherLineInput(
                fx.bankCharges.getId(), "audit fee", new BigDecimal("40.00"), BigDecimal.ZERO, null, null, true);
        VoucherService.VoucherLineInput own = new VoucherService.VoucherLineInput(
                fx.leaf(fx.p1, "EXP_SECURITY"), "guards", new BigDecimal("60.00"), BigDecimal.ZERO, null, null, false);
        UUID id = vouchers.createDraft(new VoucherService.VoucherInput(VoucherType.PISR, SEP_30, fx.vendor.getId(),
                "SH-1", "shared", fx.p1.getId(), null, null, null, null, List.of(shared, own))).getId();
        vouchers.post(id);
        assertThat(vouchers.get(id).getLines()).extracting(com.datagami.rentaxis.domain.entity.VoucherLine::getPropertyId)
                .containsExactly(null, fx.p1.getId());
        PropertyPnlDTO r = service.pnl(SEP_1, SEP_30, null, Compare.NONE, Basis.NONE);
        assertThat(row(r, fx.bankCharges.getId().toString(), "UNASSIGNED").amount()).isEqualByComparingTo("90.00");
        assertThat(row(r, fx.bankCharges.getId().toString(), fx.p1.getId().toString()).amount()).isEqualByComparingTo("0.00");
        assertThat(row(r, "EXP_SECURITY", fx.p1.getId().toString()).amount()).isEqualByComparingTo("60.00");
    }

    @Test
    void anotherTenantsIdenticalBooksAreNeverSummedIn() {
        UUID tenantA = fx.tenantId;
        UUID p1a = fx.p1.getId();
        PropertyPnlFixture b = new PropertyPnlFixture(orgRepo, accounts, propertyAccounts, properties, posting, vouchers,
                vendorService, accountRepo, resolver).tenant("PNL-B-").workedExample();

        PropertyPnlDTO rb = service.pnl(SEP_1, SEP_30, null, Compare.NONE, Basis.NONE);
        assertThat(rb.noi().get("TOTAL").amount()).isEqualByComparingTo("79061.78");
        assertThat(rb.columns()).extracting(PropertyPnlDTO.Column::propertyId).doesNotContain(p1a);

        TenantContextHolder.setTenantId(tenantA);
        PropertyPnlDTO ra = service.pnl(SEP_1, SEP_30, null, Compare.NONE, Basis.NONE);
        assertThat(ra.noi().get("TOTAL").amount()).isEqualByComparingTo("79061.78");
        assertThat(ra.check().ledgerNet()).isEqualByComparingTo("79061.78");
        assertThat(ra.columns()).extracting(PropertyPnlDTO.Column::propertyId).doesNotContain(b.p1.getId());
        // Naming the other tenant's property is a 404, not an empty column.
        assertThatThrownBy(() -> service.pnl(SEP_1, SEP_30, List.of(b.p1.getId()), Compare.NONE, Basis.NONE))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.lines(SEP_1, SEP_30, b.p1.getId().toString(), null, null, null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void comparisonShapes() {
        PropertyPnlDTO quarter = service.pnl(LocalDate.of(2026, 7, 1), SEP_30, null, Compare.PREVIOUS, Basis.NONE);
        assertThat(quarter.priorFrom()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(quarter.priorTo()).isEqualTo(LocalDate.of(2026, 6, 30));
        PropertyPnlDTO lastYear = service.pnl(SEP_1, SEP_30, null, Compare.LAST_YEAR, Basis.NONE);
        assertThat(lastYear.priorFrom()).isEqualTo(LocalDate.of(2025, 9, 1));
        assertThat(lastYear.noi().get(fx.p1.getId().toString()).deltaPct()).isNull();
    }

    @Test
    void refusesARangeBackwardsOrWithoutAnOrganisation() {
        assertThatThrownBy(() -> service.pnl(SEP_30, SEP_1, null, Compare.NONE, Basis.NONE))
                .isInstanceOf(BusinessRuleViolationException.class);
        TenantContextHolder.clear();
        assertThatThrownBy(() -> service.pnl(SEP_1, SEP_30, null, Compare.NONE, Basis.NONE))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void cellsAreTheOwnerSeam() {
        List<PnlCell> cells = service.cells(SEP_1, SEP_30, List.of(fx.p1.getId()));
        assertThat(cells).allMatch(c -> fx.p1.getId().equals(c.propertyId()));
        BigDecimal noi = cells.stream().map(c -> c.credit().subtract(c.debit())).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(noi).isEqualByComparingTo("78991.78");
        assertThat(cells).extracting(PnlCell::reportLine).contains("RENTAL_INCOME", "EXP_UTILITIES");
    }

    @Test
    void voucherLinesMustNameAPropertyOrSayShared() {
        VoucherService.VoucherLineInput blank = new VoucherService.VoucherLineInput(
                fx.bankCharges.getId(), "bank fee", new BigDecimal("25.00"), BigDecimal.ZERO, null, null, false);
        assertThatThrownBy(() -> vouchers.createDraft(new VoucherService.VoucherInput(VoucherType.PISR, SEP_30,
                fx.vendor.getId(), "X-1", "x", null, null, null, null, null, List.of(blank))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Shared / head office");

        VoucherService.VoucherLineInput shared = new VoucherService.VoucherLineInput(
                fx.bankCharges.getId(), "bank fee", new BigDecimal("25.00"), BigDecimal.ZERO, null, null, true);
        assertThat(vouchers.createDraft(new VoucherService.VoucherInput(VoucherType.PISR, SEP_30,
                fx.vendor.getId(), "X-2", "x", null, null, null, null, null, List.of(shared))).getId()).isNotNull();

        // A property on the header, or a property-bound leaf, is enough.
        VoucherService.VoucherLineInput onHeader = new VoucherService.VoucherLineInput(
                fx.bankCharges.getId(), "bank fee", new BigDecimal("25.00"), BigDecimal.ZERO, null, null, false);
        assertThat(vouchers.createDraft(new VoucherService.VoucherInput(VoucherType.PISR, SEP_30,
                fx.vendor.getId(), "X-3", "x", fx.p1.getId(), null, null, null, null, List.of(onHeader))).getId()).isNotNull();
        VoucherService.VoucherLineInput boundLeaf = new VoucherService.VoucherLineInput(
                fx.leaf(fx.p2, "EXP_SECURITY"), "guards", new BigDecimal("25.00"), BigDecimal.ZERO, null, null, false);
        assertThat(vouchers.createDraft(new VoucherService.VoucherInput(VoucherType.PISR, SEP_30,
                fx.vendor.getId(), "X-4", "x", null, null, null, null, null, List.of(boundLeaf))).getId()).isNotNull();
    }
}
