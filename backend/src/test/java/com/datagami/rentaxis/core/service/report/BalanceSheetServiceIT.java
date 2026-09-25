package com.datagami.rentaxis.core.service.report;

import com.datagami.rentaxis.api.dto.report.BalanceSheetDTO;
import com.datagami.rentaxis.api.dto.report.PropertyPnlDTO;
import com.datagami.rentaxis.api.dto.report.PropertyPnlDTO.Amount;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.VendorService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingRequest.Dimensions;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.voucher.VoucherService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static com.datagami.rentaxis.core.service.ledger.PostingRequest.cr;
import static com.datagami.rentaxis.core.service.ledger.PostingRequest.dr;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * F14-10: the balance sheet over the P&L worked example plus a deposit and a
 * collection. A = L + E on the report, the result of the open year in equity, and
 * a comparative date.
 */
@SpringBootTest
class BalanceSheetServiceIT extends AbstractPostgresIT {

    @Autowired BalanceSheetService service;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired AccountService accounts;
    @Autowired PropertyAccountService propertyAccounts;
    @Autowired PropertyService properties;
    @Autowired PostingService posting;
    @Autowired VoucherService vouchers;
    @Autowired VendorService vendorService;
    @Autowired AccountRepository accountRepo;
    @Autowired AccountResolver resolver;
    @Autowired PropertyPnlService pnlService;

    PropertyPnlFixture fx;

    @BeforeEach
    void setUp() {
        fx = new PropertyPnlFixture(orgRepo, accounts, propertyAccounts, properties, posting, vouchers, vendorService,
                accountRepo, resolver).tenant("BS-").workedExample();
        Dimensions d1 = Dimensions.ofProperty(fx.p1.getId());
        // A year's rent billed in advance, a deposit held, and a collection.
        fx.role(JournalDocType.TCO, LocalDate.of(2026, 8, 1), d1, AccountRole.RENT_RECEIVABLE, AccountRole.ADVANCE_RENT, "200000.00");
        fx.role(JournalDocType.TCO, LocalDate.of(2026, 8, 1), d1, AccountRole.RENT_RECEIVABLE, AccountRole.SECURITY_DEPOSIT, "5000.00");
        fx.role(JournalDocType.RCP, LocalDate.of(2026, 9, 20), d1, AccountRole.CASH, AccountRole.RENT_RECEIVABLE, "50000.00");
    }

    @AfterEach
    void clear() { TenantContextHolder.clear(); }

    private static Amount row(BalanceSheetDTO r, String rowKey, String column) {
        return r.sections().stream().flatMap(s -> s.groups().stream()).flatMap(g -> g.rows().stream())
                .filter(x -> x.key().equals(rowKey)).findFirst()
                .orElseThrow(() -> new AssertionError("no row " + rowKey)).cells().get(column);
    }

    private String retainedKey() {
        var re = accounts.getAccountByCode("F-03");
        return re.getReportLine() != null ? re.getReportLine() : re.getId().toString();
    }

    private static Amount total(BalanceSheetDTO r, String type) {
        return r.sections().stream().filter(s -> s.type().equals(type)).findFirst().orElseThrow().total().get(PropertyPnlDTO.TOTAL);
    }

    @Test
    void assetsEqualLiabilitiesPlusEquityWithTheOpenYearsResult() {
        BalanceSheetDTO r = service.balanceSheet(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 8, 31), null);
        String p1 = fx.p1.getId().toString();

        assertThat(r.ok()).isTrue();
        assertThat(r.ledgerImbalance()).isEqualByComparingTo("0");
        assertThat(r.check().get(PropertyPnlDTO.TOTAL).amount()).isEqualByComparingTo("0");
        // The receivable: 200,000 + 5,000 + 1,500 admin fee + 500 penalty − 50,000 collected.
        assertThat(row(r, "RENT_RECEIVABLE", p1).amount()).isEqualByComparingTo("157000.00");
        // Advance rent: 200,000 less two months recognised (2 × 82,191.78 + 2 × 2,000 parking).
        assertThat(row(r, "ADVANCE_RENT", p1).amount()).isEqualByComparingTo(
                new BigDecimal("200000.00").subtract(new BigDecimal("168383.56")));
        assertThat(row(r, "SECURITY_DEPOSIT", p1).amount()).isEqualByComparingTo("5000.00");
        // Comparative as at 31 August: nothing collected yet.
        assertThat(row(r, "RENT_RECEIVABLE", p1).prior()).isEqualByComparingTo("205000.00");

        // Equity carries the year's result: the P&L's NOI for Aug + Sep (the fiscal year starts in January).
        BigDecimal result = new BigDecimal("82791.78").add(new BigDecimal("78991.78"))
                .add(new BigDecimal("120.00")).subtract(new BigDecimal("50.00"));
        assertThat(r.currentYearResult().get(PropertyPnlDTO.TOTAL).amount()).isEqualByComparingTo(result);
        assertThat(r.earlierYearsResult().get(PropertyPnlDTO.TOTAL).amount()).isEqualByComparingTo("0");
        assertThat(total(r, "ASSET").amount()).isEqualByComparingTo(r.liabilitiesAndEquity().get(PropertyPnlDTO.TOTAL).amount());
        assertThat(total(r, "ASSET").prior()).isEqualByComparingTo(r.liabilitiesAndEquity().get(PropertyPnlDTO.TOTAL).prior());
    }

    @Test
    void anEarlierYearNotClosedShowsApartFromTheCurrentYear() {
        Dimensions d1 = Dimensions.ofProperty(fx.p1.getId());
        fx.role(JournalDocType.PEN, LocalDate.of(2025, 11, 3), d1, AccountRole.RENT_RECEIVABLE, AccountRole.RENT_PENALTY, "700.00");
        BalanceSheetDTO r = service.balanceSheet(LocalDate.of(2026, 9, 30), null, null);
        assertThat(r.earlierYearsResult().get(PropertyPnlDTO.TOTAL).amount()).isEqualByComparingTo("700.00");
        assertThat(r.ok()).isTrue();
        // Comparative columns are absent when no date is asked for.
        assertThat(r.check().get(PropertyPnlDTO.TOTAL).prior()).isNull();
    }

    @Test
    void theCheckIsAssetsLessLiabilitiesAndEquity() {
        BalanceSheetDTO r = service.balanceSheet(LocalDate.of(2026, 9, 30), null, null);
        BigDecimal assets = total(r, "ASSET").amount();
        BigDecimal le = r.liabilitiesAndEquity().get(PropertyPnlDTO.TOTAL).amount();
        assertThat(assets.subtract(le)).isEqualByComparingTo(r.check().get(PropertyPnlDTO.TOTAL).amount());
    }

    @Test
    void pdfAndCsvRenderInBothLanguagesAndTheCsvIsInjectionSafe() {
        var evil = accounts.createLeaf("=cmd|' /C calc'!A0", accounts.getAccountByCode("B-01"), null);
        posting.post(new PostingRequest(JournalDocType.JV, LocalDate.of(2026, 9, 1), "x", Dimensions.none(),
                JournalSourceType.MANUAL, null, null, List.of(dr(AccountRole.CASH, new BigDecimal("10.00")),
                cr(evil.getId(), new BigDecimal("10.00")))));
        BalanceSheetDTO r = service.balanceSheet(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 8, 31), null);
        byte[] pdfEn = FinancialReportExport.balanceSheetPdf(r, "en");
        byte[] pdfAr = FinancialReportExport.balanceSheetPdf(r, "ar");
        assertThat(new String(pdfEn, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(new String(pdfAr, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        String csvAr = new String(FinancialReportExport.balanceSheetCsv(r, "ar"), StandardCharsets.UTF_8);
        assertThat(csvAr).contains("الميزانية العمومية").contains("إجمالي الأصول");
        String csvEn = new String(FinancialReportExport.balanceSheetCsv(r, "en"), StandardCharsets.UTF_8);
        assertThat(csvEn).contains("'=cmd").doesNotContain(",=cmd");

        PropertyPnlDTO pl = pnlService.pnl(PropertyPnlFixture.SEP_1, PropertyPnlFixture.SEP_30, null,
                PnlPeriods.Compare.PREVIOUS, PnlAllocation.Basis.NONE);
        for (String lang : List.of("en", "ar")) {
            assertThat(new String(FinancialReportExport.pnlPdf(pl, lang, true), 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
            assertThat(new String(FinancialReportExport.pnlPdf(pl, lang, false), 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        }
        String company = new String(FinancialReportExport.companyPnlCsv(pl, "ar"), StandardCharsets.UTF_8);
        assertThat(company).contains("قائمة دخل الشركة").contains("79061.78");
        String html = ReportPdf.html(new ReportPdf.Doc("ar", "<b>x</b>", List.of(), List.of(), List.of(), false));
        assertThat(html).contains("dir=\"rtl\"").contains("&lt;b&gt;").doesNotContain("<b>x");
    }

    @Test
    void aScopedReportHasNoTotalAndAClosedYearMovesItsResultToRetainedEarnings() {
        // A YEC-like closing entry for 2026 (dated year end) moves the result to Retained Earnings.
        BigDecimal result = service.balanceSheet(LocalDate.of(2026, 12, 31), null, null)
                .currentYearResult().get(PropertyPnlDTO.TOTAL).amount();
        posting.post(new PostingRequest(JournalDocType.YEC, LocalDate.of(2026, 12, 31), "close", Dimensions.none(),
                JournalSourceType.MANUAL, null, null, List.of(
                        dr(accounts.getAccountByCode("C-02-001").getId(), result),
                        cr(AccountRole.RETAINED_EARNINGS, result))));
        BalanceSheetDTO r = service.balanceSheet(LocalDate.of(2026, 12, 31), null, null);
        assertThat(r.ok()).isTrue();
        assertThat(row(r, retainedKey(), PropertyPnlDTO.TOTAL).amount()).isEqualByComparingTo(result);
        assertThat(r.currentYearResult().get(PropertyPnlDTO.TOTAL).amount()).isEqualByComparingTo("0");
    }
}
