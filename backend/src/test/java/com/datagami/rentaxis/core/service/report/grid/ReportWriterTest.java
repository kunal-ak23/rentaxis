package com.datagami.rentaxis.core.service.report.grid;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.datagami.rentaxis.core.service.report.grid.SampleGrid.cells;
import static java.math.BigDecimal.ZERO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportWriterTest {

    private static final ReportLabels LABELS = new ReportLabels();

    private RecordingSink sink;
    private ReportWriter w;

    @BeforeEach
    void setUp() {
        sink = new RecordingSink();
        w = new ReportWriter(sink, LABELS.bind("en"), 50_000);
    }

    private static Map<String, Object> debit(String v) {
        return cells("debit", new BigDecimal(v));
    }

    private static Map<String, Object> credit(String v) {
        return cells("credit", new BigDecimal(v));
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void runningBalanceStartsAtTheOpeningWhichIsNeverSummed() {
        w.begin(SampleGrid.meta("en"), SampleGrid.COLUMNS);
        w.openGroup(1, Band.ACCOUNT, "Rent Receivable");
        w.openGroup(2, Band.PARTY, "Tenant A");
        w.opening(d("100"));
        w.row(debit("50"), RowStyle.PLAIN, Map.of());
        w.row(credit("30"), RowStyle.PLAIN, Map.of());
        w.closeGroup("Sub Total");
        w.closeGroup(null);
        w.end();

        assertThat(sink.rowBalances()).containsExactly(d("150.00"), d("120.00"));
        assertThat(sink.subtotal(2)).containsEntry("debit", d("50.00")).containsEntry("credit", d("30.00"))
                .containsEntry("balance", d("120.00"));
        assertThat(sink.openings()).containsExactly("2|Balance brought forward|100|false");
    }

    @Test
    void nestedGroupsRollUpAndTheReportTotalSumsClosings() {
        w.begin(SampleGrid.meta("en"), SampleGrid.COLUMNS);
        w.openGroup(1, Band.ACCOUNT, "Rent Receivable");
        w.openGroup(2, Band.PARTY, "Tenant A");
        w.opening(ZERO);
        w.row(debit("100"), RowStyle.PLAIN, Map.of());
        w.closeGroup("Sub Total");
        w.openGroup(2, Band.PARTY, "Tenant B");
        w.opening(d("-20"));
        w.row(credit("10"), RowStyle.PLAIN, Map.of());
        w.closeGroup("Sub Total");
        w.closeGroup("Sub Total (RR)");
        w.end();

        assertThat(sink.subtotal(1)).containsEntry("debit", d("100.00")).containsEntry("credit", d("10.00"))
                .containsEntry("balance", d("70.00"));
        assertThat(sink.subtotalLabel(1)).isEqualTo("Sub Total (RR)");
        assertThat(sink.total()).containsEntry("debit", d("100.00")).containsEntry("credit", d("10.00"))
                .containsEntry("balance", d("70.00"));
        assertThat(sink.totalLabel()).isEqualTo("REPORT TOTAL");
        assertThat(sink.rowLevels()).containsExactly(2, 2);
    }

    @Test
    void aNullLabelClosesWithoutASubtotalRowButStillRollsUp() {
        w.begin(SampleGrid.meta("en"), SampleGrid.COLUMNS);
        w.openGroup(1, Band.ACCOUNT, "Rent Receivable");
        w.openGroup(2, Band.PARTY, "Tenant A");
        w.opening(d("5"));
        w.row(debit("100"), RowStyle.PLAIN, Map.of());
        w.closeGroup("Sub Total");
        w.closeGroup(null);
        w.end();

        assertThat(sink.subtotal(1)).isNull();
        assertThat(sink.subtotals()).hasSize(1);
        assertThat(sink.total()).containsEntry("debit", d("100.00")).containsEntry("balance", d("105.00"));
    }

    @Test
    void serialRestartsPerGroupAndCountTotalsCount() {
        List<ReportColumn> columns = List.of(
                ReportColumn.serial("sn", "col.docNo", 4),
                ReportColumn.text("particular", "col.particular", 20),
                ReportColumn.integer("lines", "col.bdDays", 6, TotalRule.COUNT),
                ReportColumn.money("debit", "col.debit", 12));
        w.begin(SampleGrid.meta("en"), columns);
        w.openGroup(1, Band.GROUP, "Group A");
        w.row(cells("particular", "x"), RowStyle.PLAIN, Map.of());
        w.row(cells("particular", "y"), RowStyle.HIGHLIGHT, Map.of());
        w.closeGroup("Sub Total");
        w.openGroup(1, Band.GROUP, "Group B");
        w.row(cells("particular", "z"), RowStyle.PLAIN, Map.of());
        w.closeGroup("Sub Total");
        w.end();

        assertThat(sink.rows()).extracting(r -> r.get("sn")).containsExactly(1, 2, 1);
        assertThat(sink.rowStyles()).containsExactly(RowStyle.PLAIN, RowStyle.HIGHLIGHT, RowStyle.PLAIN);
        assertThat(sink.subtotal(1)).containsEntry("lines", 2L).containsEntry("debit", d("0.00")).doesNotContainKey("sn");
        assertThat(sink.total()).containsEntry("lines", 3L).doesNotContainKey("sn").doesNotContainKey("particular");
    }

    @Test
    void anAuthoritativeCountThatDisagreesIsCaughtWhenTheGridHasACountColumn() {
        List<ReportColumn> columns = List.of(
                ReportColumn.serial("sn", "col.docNo", 4),
                ReportColumn.text("particular", "col.particular", 20),
                ReportColumn.integer("lines", "col.bdDays", 6, TotalRule.COUNT),
                ReportColumn.money("debit", "col.debit", 12));
        w.begin(SampleGrid.meta("en"), columns);
        w.openGroup(1, Band.GROUP, "Group A");
        w.row(cells("particular", "x"), RowStyle.PLAIN, Map.of());
        w.row(cells("particular", "y"), RowStyle.PLAIN, Map.of());

        // the sums agree (debit 0) and the grid has no BALANCE column, so only the count can disagree
        assertThatThrownBy(() -> w.closeGroup("Sub Total", new GroupTotals(Map.of("debit", ZERO), 3, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Report totals disagree")
                .hasMessageContaining("Group A")
                .hasMessageContaining("count");
    }

    @Test
    void aRowBalanceIsAlwaysAtCentsEvenWithoutAnOpeningOrAmounts() {
        w.begin(SampleGrid.meta("en"), SampleGrid.COLUMNS);
        w.openGroup(1, Band.PARTY, "Tenant A");
        w.row(cells("particular", "Note only"), RowStyle.PLAIN, Map.of());
        w.closeGroup("Sub Total");
        w.end();

        assertThat(sink.rowBalances()).containsExactly(d("0.00"));
        assertThat(sink.rowBalances().get(0).scale()).isEqualTo(2);
    }

    @Test
    void authoritativeTotalsMustAgreeWhenTheGroupWasWrittenWhole() {
        w.begin(SampleGrid.meta("en"), SampleGrid.COLUMNS);
        w.openGroup(1, Band.PARTY, "Tenant A");
        w.opening(ZERO);
        w.row(debit("50"), RowStyle.PLAIN, Map.of());

        assertThatThrownBy(() -> w.closeGroup("Sub Total", GroupTotals.debitCredit(ZERO, d("51"), ZERO)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Report totals disagree")
                .hasMessageContaining("Tenant A");
    }

    @Test
    void aClosingThatDisagreesIsCaughtAndAgreeingTotalsArePrinted() {
        w.begin(SampleGrid.meta("en"), SampleGrid.COLUMNS);
        w.openGroup(1, Band.PARTY, "Tenant A");
        w.opening(d("10"));
        w.row(debit("50"), RowStyle.PLAIN, Map.of());
        assertThatThrownBy(() -> w.closeGroup("Sub Total", GroupTotals.debitCredit(ZERO, d("50"), ZERO)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Report totals disagree");

        RecordingSink ok = new RecordingSink();
        ReportWriter agreeing = new ReportWriter(ok, LABELS.bind("en"), 50_000);
        agreeing.begin(SampleGrid.meta("en"), SampleGrid.COLUMNS);
        agreeing.openGroup(1, Band.PARTY, "Tenant A");
        agreeing.opening(d("10"));
        agreeing.row(debit("50"), RowStyle.PLAIN, Map.of());
        agreeing.closeGroup("Sub Total", GroupTotals.debitCredit(d("10"), d("50"), ZERO));
        agreeing.end(GroupTotals.debitCredit(d("10"), d("50"), ZERO));
        assertThat(ok.subtotal(1)).containsEntry("debit", d("50.00")).containsEntry("balance", d("60.00"));
        assertThat(ok.total()).containsEntry("debit", d("50.00")).containsEntry("credit", d("0.00"))
                .containsEntry("balance", d("60.00"));

        RecordingSink wrong = new RecordingSink();
        ReportWriter whole = new ReportWriter(wrong, LABELS.bind("en"), 50_000);
        whole.begin(SampleGrid.meta("en"), SampleGrid.COLUMNS);
        whole.openGroup(1, Band.PARTY, "Tenant A");
        whole.opening(ZERO);
        whole.row(debit("50"), RowStyle.PLAIN, Map.of());
        whole.closeGroup("Sub Total");
        assertThatThrownBy(() -> whole.end(GroupTotals.debitCredit(ZERO, d("40"), ZERO)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Report totals disagree");
    }

    @Test
    void aTruncatedGroupPrintsTheAuthoritativeTotalsWithoutTheCheck() {
        ReportWriter capped = new ReportWriter(sink, LABELS.bind("en"), 1);
        capped.begin(SampleGrid.meta("en"), SampleGrid.COLUMNS);
        capped.openGroup(1, Band.PARTY, "Tenant A");
        capped.opening(ZERO);
        capped.row(debit("50"), RowStyle.PLAIN, Map.of());
        capped.row(debit("50"), RowStyle.PLAIN, Map.of());
        capped.closeGroup("Sub Total", GroupTotals.debitCredit(ZERO, d("100"), ZERO));
        capped.end(GroupTotals.debitCredit(ZERO, d("100"), ZERO));

        assertThat(sink.rows()).hasSize(1);
        assertThat(sink.subtotal(1)).containsEntry("debit", d("100.00")).containsEntry("balance", d("100.00"));
        assertThat(sink.total()).containsEntry("debit", d("100.00"));
        assertThat(sink.endTruncated()).isTrue();
    }

    @Test
    void aContinuedGroupPrintsItsCarriedBalanceAndTheAuthoritativeTotals() {
        w.markPartial();
        w.begin(SampleGrid.meta("en"), SampleGrid.COLUMNS);
        w.continueGroup(2, Band.PARTY, "Tenant A", d("500"));
        w.row(debit("10"), RowStyle.PLAIN, Map.of());
        w.closeGroup("Sub Total", GroupTotals.debitCredit(ZERO, d("999"), BigDecimal.ONE));
        w.end();

        assertThat(sink.bands()).containsExactly("2|PARTY|Tenant A (continued)|true");
        assertThat(sink.openings()).containsExactly("2|Balance carried forward|500|true");
        assertThat(sink.rowBalances()).containsExactly(d("510.00"));
        assertThat(sink.subtotal(2)).containsEntry("debit", d("999.00")).containsEntry("credit", d("1.00"))
                .containsEntry("balance", d("998.00"));
        // what a subtotal prints is what rolls up, so the REPORT TOTAL equals the printed subtotals
        assertThat(sink.total()).containsEntry("debit", d("999.00")).containsEntry("balance", d("998.00"));
        assertThat(sink.endContinues()).isFalse();
    }

    @Test
    void aContinuedGroupWithoutABalanceHasNoOpeningRow() {
        w.markPartial();
        w.begin(SampleGrid.meta("en"), SampleGrid.COLUMNS);
        w.continueGroup(1, Band.ACCOUNT, "Rent Receivable", null);
        w.closeGroup(null);
        w.end();

        assertThat(sink.bands()).containsExactly("1|ACCOUNT|Rent Receivable (continued)|true");
        assertThat(sink.openings()).isEmpty();
    }

    @Test
    void endWithAnOpenGroupIsRefusedUnlessPartial() {
        w.begin(SampleGrid.meta("en"), SampleGrid.COLUMNS);
        w.openGroup(1, Band.PARTY, "Tenant A");
        w.opening(ZERO);
        w.row(debit("10"), RowStyle.PLAIN, Map.of());
        assertThatThrownBy(w::end).isInstanceOf(IllegalStateException.class).hasMessageContaining("Tenant A");

        RecordingSink page = new RecordingSink();
        ReportWriter partial = new ReportWriter(page, LABELS.bind("en"), 50_000);
        partial.markPartial();
        partial.begin(SampleGrid.meta("en"), SampleGrid.COLUMNS);
        partial.openGroup(1, Band.PARTY, "Tenant A");
        partial.opening(ZERO);
        partial.row(debit("10"), RowStyle.PLAIN, Map.of());
        partial.end();

        assertThat(page.ended()).isTrue();
        assertThat(page.endTruncated()).isFalse();
        assertThat(page.endContinues()).isTrue();
        assertThat(page.subtotals()).isEmpty();
        // the open group is not rolled up into the page's computed total
        assertThat(page.total()).containsEntry("debit", d("0.00")).containsEntry("balance", d("0.00"));
    }

    @Test
    void aPartialPageThatStopsInsideAGroupPrintsTheAuthoritativeReportTotalWithoutTheCheck() {
        w.markPartial();
        w.begin(SampleGrid.meta("en"), SampleGrid.COLUMNS);
        w.openGroup(1, Band.PARTY, "Tenant A");
        w.opening(ZERO);
        w.row(debit("10"), RowStyle.PLAIN, Map.of());

        // the open group is popped without rolling up, so the computed REPORT TOTAL is 0 and would
        // disagree with the whole report's SQL totals if they were checked
        w.end(GroupTotals.debitCredit(ZERO, d("999"), ZERO));

        assertThat(sink.ended()).isTrue();
        assertThat(sink.total()).containsEntry("debit", d("999.00")).containsEntry("credit", d("0.00"))
                .containsEntry("balance", d("999.00"));
        assertThat(sink.endContinues()).isTrue();
        assertThat(sink.endTruncated()).isFalse();
    }

    @Test
    void truncatesAtTheRowCap() {
        ReportWriter capped = new ReportWriter(sink, LABELS.bind("en"), 2);
        capped.begin(SampleGrid.meta("en"), SampleGrid.COLUMNS);
        capped.openGroup(1, Band.PARTY, "Tenant A");
        capped.opening(ZERO);
        capped.row(debit("10"), RowStyle.PLAIN, Map.of());
        capped.row(debit("10"), RowStyle.PLAIN, Map.of());
        capped.row(debit("10"), RowStyle.PLAIN, Map.of());
        capped.closeGroup("Sub Total");
        capped.end();

        assertThat(sink.rows()).hasSize(2);
        assertThat(capped.truncated()).isTrue();
        assertThat(capped.rowsWritten()).isEqualTo(2);
        assertThat(sink.subtotal(1)).containsEntry("debit", d("20.00"));
        assertThat(sink.endTruncated()).isTrue();
        assertThat(sink.endContinues()).isFalse();
    }

    @Test
    void bigDecimalSumsAreExact() {
        w.begin(SampleGrid.meta("en"), SampleGrid.COLUMNS);
        w.openGroup(1, Band.PARTY, "Tenant A");
        for (int i = 0; i < 10_000; i++) {
            w.row(debit("0.10"), RowStyle.PLAIN, Map.of());
        }
        w.closeGroup("Sub Total");
        w.end();

        assertThat(((BigDecimal) sink.subtotal(1).get("debit")).toPlainString()).isEqualTo("1000.00");
        assertThat(sink.rowBalances().get(9_999).toPlainString()).isEqualTo("1000.00");
        assertThat(w.rowsWritten()).isEqualTo(10_000);
    }

    @Test
    void moneyCellsAreRoundedToCentsBeforeTheyAreSummed() {
        w.begin(SampleGrid.meta("en"), SampleGrid.COLUMNS);
        w.openGroup(1, Band.PARTY, "Tenant A");
        w.row(debit("0.105"), RowStyle.PLAIN, Map.of());
        w.row(debit("0.105"), RowStyle.PLAIN, Map.of());
        w.row(cells("debit", new BigDecimal("7"), "credit", null), RowStyle.PLAIN, Map.of());
        w.closeGroup("Sub Total");
        w.end();

        assertThat(sink.rows()).extracting(r -> r.get("debit")).containsExactly(d("0.11"), d("0.11"), d("7.00"));
        assertThat(sink.rows().get(2)).doesNotContainKey("credit");
        assertThat(sink.subtotal(1)).containsEntry("debit", d("7.22")).containsEntry("balance", d("7.22"));
    }

    @Test
    void anEmptyReportStillHasAReportTotal() {
        w.begin(SampleGrid.meta("en"), SampleGrid.COLUMNS);
        w.end();

        assertThat(sink.total()).containsEntry("debit", d("0.00")).containsEntry("credit", d("0.00"))
                .containsEntry("balance", d("0.00"));
        assertThat(sink.subtotals()).isEmpty();
        assertThat(sink.endTruncated()).isFalse();
        assertThat(sink.events()).containsExactly("begin", "total", "end");
    }

    @Test
    void beginResolvesTheHeaderInTheReportLanguage() {
        w.begin(SampleGrid.meta("en"), SampleGrid.COLUMNS);
        GridHeader h = sink.header();
        assertThat(h.reportKey()).isEqualTo("sample");
        assertThat(h.title()).isEqualTo("Tenant Ledger");
        assertThat(h.orgName()).isEqualTo("Sample Org");
        assertThat(h.metaLines()).containsExactly("01/09/2026 – 30/09/2027", "Property: Sample Tower");
        assertThat(h.generatedLine()).isEqualTo("Generated 26/10/2026 10:05 by Test User");
        assertThat(h.columns()).isEqualTo(SampleGrid.COLUMNS);
        assertThat(h.columnLabels()).containsExactly("Doc Date", "Particular", "Debit", "Credit", "Balance", "Chq No",
                "Payee Bank", "BDDays", "ConvertedDate", "Doc No");
        assertThat(h.lang()).isEqualTo("en");

        RecordingSink ar = new RecordingSink();
        ReportMeta asOf = new ReportMeta("sample", "tenantLedger.title", "Sample Org", List.of(), null, null,
                LocalDate.of(2027, 9, 30), SampleGrid.GENERATED_AT, null, "ar");
        new ReportWriter(ar, LABELS.bind("ar"), 10).begin(asOf, SampleGrid.COLUMNS);
        assertThat(ar.header().title()).isEqualTo("دفتر أستاذ المستأجر");
        assertThat(ar.header().metaLines()).containsExactly("كما في 30/09/2027");
        assertThat(ar.header().generatedLine()).endsWith("—");
        assertThat(ar.header().columnLabels().get(0)).isEqualTo("تاريخ المستند");
        assertThat(ar.header().lang()).isEqualTo("ar");
    }

    @Test
    void rowsAreCheckedAgainstTheColumns() {
        w.begin(SampleGrid.meta("en"), SampleGrid.COLUMNS);
        w.openGroup(1, Band.PARTY, "Tenant A");
        assertThatThrownBy(() -> w.row(cells("nope", "x"), RowStyle.PLAIN, Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nope");
        assertThatThrownBy(() -> w.row(cells("balance", d("1")), RowStyle.PLAIN, Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("balance");
        assertThatThrownBy(() -> w.row(cells("debit", 5), RowStyle.PLAIN, Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("debit");
        assertThatThrownBy(() -> w.row(cells("docDate", "2026-09-16"), RowStyle.PLAIN, Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("docDate");
        assertThat(w.rowsWritten()).isZero();
    }

    @Test
    void eventsMustComeInAValidOrder() {
        assertThatThrownBy(() -> w.openGroup(1, Band.PARTY, "Tenant A")).isInstanceOf(IllegalStateException.class);
        w.begin(SampleGrid.meta("en"), SampleGrid.COLUMNS);
        assertThatThrownBy(() -> w.closeGroup("Sub Total")).isInstanceOf(IllegalStateException.class);
        w.openGroup(2, Band.PARTY, "Tenant A");
        assertThatThrownBy(() -> w.openGroup(2, Band.PARTY, "Tenant B")).isInstanceOf(IllegalStateException.class);
        w.row(debit("1"), RowStyle.PLAIN, Map.of());
        assertThatThrownBy(() -> w.opening(ZERO)).isInstanceOf(IllegalStateException.class);
        w.closeGroup("Sub Total");
        w.end();
        assertThatThrownBy(w::end).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void columnsCheckTheirOwnShape() {
        assertThatThrownBy(() -> ReportColumn.date("d", "col.docDate", 12, "yyyy-MM-dd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportColumn("b", "col.balance", Kind.BALANCE, 16, TotalRule.CLOSING_BALANCE,
                false, null, null, "credit", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportColumn("b", "col.balance", Kind.BALANCE, 16, TotalRule.SUM,
                false, null, "debit", "credit", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReportColumn.idText("x", "col.docNo", 12, "website"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ReportColumn.integer("bdDays", "col.bdDays", 8, TotalRule.NONE).hiddenColumn().hidden()).isTrue();

        List<ReportColumn> balanceWithoutMoney = List.of(ReportColumn.balance("balance", "col.balance", 16, "debit", "credit"));
        assertThatThrownBy(() -> w.begin(SampleGrid.meta("en"), balanceWithoutMoney))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("debit");
    }
}
