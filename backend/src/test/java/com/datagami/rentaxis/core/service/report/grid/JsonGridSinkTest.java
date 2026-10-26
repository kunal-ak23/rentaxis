package com.datagami.rentaxis.core.service.report.grid;

import com.datagami.rentaxis.api.dto.report.ReportGridDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static com.datagami.rentaxis.core.service.report.grid.SampleGrid.E1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonGridSinkTest {

    private static final ReportLabels LABELS = new ReportLabels();

    @Test
    void itemsCarryDecimalStringsIsoDatesAndRefs() {
        JsonGridSink json = new JsonGridSink(ReportGridDTO.Page.whole(3, 1));
        ReportWriter w = new ReportWriter(json, LABELS.bind("en"), 50_000);
        w.begin(SampleGrid.meta("en"), SampleGrid.COLUMNS);
        SampleGrid.feed(w);
        ReportGridDTO g = json.result();

        assertThat(g.items()).extracting(ReportGridDTO.Item::type)
                .containsExactly("band", "band", "opening", "row", "row", "row", "subtotal", "total");
        assertThat(g.items().get(3).cells()).containsEntry("docDate", "2026-09-16").containsEntry("debit", "60000.00")
                .containsEntry("balance", "60000.00").doesNotContainKey("credit");
        assertThat(g.items().get(4).cells()).containsEntry("balance", "50500.00").containsEntry("chqNo", "000201")
                .containsEntry("bdDays", "1").containsEntry("convertedDate", "2026-10-02");
        assertThat(g.items().get(3).refs()).containsEntry("entryId", E1.toString());
        assertThat(g.columns()).extracting(ReportGridDTO.Column::label).startsWith("Doc Date", "Particular", "Debit");
        assertThat(g.columns().get(9).link()).isEqualTo("journal");
        assertThat(g.meta().title()).isEqualTo("Tenant Ledger");
    }

    @Test
    void bandsOpeningsAndTotalsHaveTheirOwnShape() {
        JsonGridSink json = new JsonGridSink(ReportGridDTO.Page.whole(3, 1));
        ReportWriter w = new ReportWriter(json, LABELS.bind("en"), 50_000);
        w.begin(SampleGrid.meta("en"), SampleGrid.COLUMNS);
        SampleGrid.feed(w);
        ReportGridDTO g = json.result();

        ReportGridDTO.Item account = g.items().get(0);
        assertThat(account.level()).isEqualTo(1);
        assertThat(account.band()).isEqualTo("ACCOUNT");
        assertThat(account.label()).isEqualTo("Rent Receivable - Sample Tower");
        assertThat(account.continued()).isFalse();
        assertThat(account.cells()).isEmpty();
        assertThat(account.refs()).isEmpty();
        assertThat(account.style()).isEqualTo("PLAIN");

        ReportGridDTO.Item opening = g.items().get(2);
        assertThat(opening.level()).isEqualTo(2);
        assertThat(opening.label()).isEqualTo("Balance brought forward");
        assertThat(opening.carriedForward()).isFalse();
        assertThat(opening.cells()).containsExactly(Map.entry("balance", "0.00"));

        ReportGridDTO.Item formula = g.items().get(5);
        assertThat(formula.cells()).containsEntry("particular", "=HYPERLINK(\"x\")").containsEntry("debit", "0.10")
                .containsEntry("balance", "50500.10");
        assertThat(formula.label()).isNull();
        assertThat(formula.band()).isNull();

        ReportGridDTO.Item subtotal = g.items().get(6);
        assertThat(subtotal.level()).isEqualTo(2);
        assertThat(subtotal.label()).isEqualTo("Sub Total");
        assertThat(subtotal.cells()).containsExactly(Map.entry("debit", "60000.10"), Map.entry("credit", "9500.00"),
                Map.entry("balance", "50500.10"));

        ReportGridDTO.Item total = g.items().get(7);
        assertThat(total.level()).isZero();
        assertThat(total.label()).isEqualTo("REPORT TOTAL");
        assertThat(total.cells()).containsEntry("balance", "50500.10");

        assertThat(g.meta().reportKey()).isEqualTo("sample");
        assertThat(g.meta().orgName()).isEqualTo("Sample Org");
        assertThat(g.meta().lines()).containsExactly("01/09/2026 – 30/09/2027", "Property: Sample Tower");
        assertThat(g.meta().generated()).isEqualTo("Generated 26/10/2026 10:05 by Test User");
        assertThat(g.meta().lang()).isEqualTo("en");
        assertThat(g.columns().get(4)).isEqualTo(new ReportGridDTO.Column("balance", "Balance", "BALANCE", false, null));
        assertThat(g.page()).isEqualTo(new ReportGridDTO.Page(0, 1, 0, 3, 1, 0, 0, false, false));
    }

    @Test
    void aPartialPageCarriesTheContinuedBandsAndTheEndFlags() {
        JsonGridSink json = new JsonGridSink(new ReportGridDTO.Page(1, 3, 5, 12, 1, 0, 0, false, false));
        ReportWriter w = new ReportWriter(json, LABELS.bind("en"), 50_000);
        w.markPartial();
        w.begin(SampleGrid.meta("en"), SampleGrid.COLUMNS.stream()
                .map(c -> c.key().equals("bdDays") ? c.hiddenColumn() : c).toList());
        w.continueGroup(1, Band.ACCOUNT, "Rent Receivable - Sample Tower", null);
        w.continueGroup(2, Band.PARTY, "Tenant Name : Tenant A", new BigDecimal("-8000"));
        w.row(SampleGrid.cells("debit", new BigDecimal("10")), RowStyle.HIGHLIGHT, Map.of());
        w.end();
        ReportGridDTO g = json.result();

        assertThat(g.items().get(0).continued()).isTrue();
        assertThat(g.items().get(1).label()).isEqualTo("Tenant Name : Tenant A (continued)");
        assertThat(g.items().get(2).carriedForward()).isTrue();
        assertThat(g.items().get(2).cells()).containsEntry("balance", "-8000.00");
        assertThat(g.items().get(3).style()).isEqualTo("HIGHLIGHT");
        assertThat(g.items().get(3).cells()).containsEntry("balance", "-7990.00");
        assertThat(g.columns().get(7).hidden()).isTrue();
        assertThat(g.page()).isEqualTo(new ReportGridDTO.Page(1, 3, 5, 12, 1, 0, 0, false, true));
    }

    @Test
    void thereIsNoResultBeforeTheEnd() {
        JsonGridSink json = new JsonGridSink(ReportGridDTO.Page.whole(0, 0));
        ReportWriter w = new ReportWriter(json, LABELS.bind("en"), 50_000);
        w.begin(SampleGrid.meta("en"), SampleGrid.COLUMNS);
        assertThatThrownBy(json::result).isInstanceOf(IllegalStateException.class);
        w.end();
        assertThat(json.result().items()).extracting(ReportGridDTO.Item::type).containsExactly("total");
        assertThat(json.result().page()).isEqualTo(new ReportGridDTO.Page(0, 1, 0, 0, 0, 0, -1, false, false));
    }
}
