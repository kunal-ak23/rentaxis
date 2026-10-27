package com.datagami.rentaxis.core.service.report.grid;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.datagami.rentaxis.core.service.report.grid.SampleGrid.cells;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvGridSinkTest {

    private static final ReportLabels LABELS = new ReportLabels();

    @Test
    void signedBalancesWithADrCrColumn() {
        String csv = render("en", SampleGrid.COLUMNS, SampleGrid::feed);
        assertThat(csv).startsWith("﻿");
        List<String> lines = csv.substring(1).lines().toList();
        assertThat(lines.get(0)).isEqualTo("Doc Date,Particular,Debit,Credit,Balance,Dr/Cr,Chq No,Payee Bank,BDDays,ConvertedDate,Doc No");
        assertThat(lines).anySatisfy(l -> assertThat(l).startsWith("16-09-2026,Advance Rent - Sample Tower,60000.00,,60000.00,Dr,"));
        assertThat(lines).anySatisfy(l -> assertThat(l).startsWith("REPORT TOTAL,"));
    }

    @Test
    void everyLineHasTheSameWidthAndEveryEventItsPlace() {
        String csv = render("en", SampleGrid.COLUMNS, SampleGrid::feed);
        assertThat(csv).contains("\r\n");
        List<String> lines = csv.substring(1).lines().toList();
        assertThat(lines).containsExactly(
                "Doc Date,Particular,Debit,Credit,Balance,Dr/Cr,Chq No,Payee Bank,BDDays,ConvertedDate,Doc No",
                "Rent Receivable - Sample Tower,,,,,,,,,,",
                "Tenant Name : Tenant A,,,,,,,,,,",
                "Balance brought forward,,,,0.00,Dr,,,,,",
                "16-09-2026,Advance Rent - Sample Tower,60000.00,,60000.00,Dr,,,,,TCO-26/1",
                "16-09-2026,PDC Receivable Sample Tower,,9500.00,50500.00,Dr,000201,Emirates NBD,1,02/10/2026,PDR-26/1",
                "20-09-2026,\"'=HYPERLINK(\"\"x\"\")\",0.10,,50500.10,Dr,,,,,JV-26/1",
                "Sub Total,,60000.10,9500.00,50500.10,Dr,,,,,",
                "REPORT TOTAL,,60000.10,9500.00,50500.10,Dr,,,,,");
        lines.forEach(l -> assertThat(fields(l)).as(l).hasSize(11));
    }

    @Test
    void guardsFormulas() {
        String csv = render("en", SampleGrid.COLUMNS, SampleGrid::feed);
        assertThat(csv).contains("\"'=HYPERLINK(\"\"x\"\")\"");
        // A bidi mark in front of a formula does not hide it from the guard.
        String hidden = render("en", SampleGrid.COLUMNS, w -> {
            w.openGroup(1, Band.PARTY, "‏=Tenant");
            w.row(cells("particular", "‏@SUM(1)", "payeeBank", "+Bank\u0007"), RowStyle.PLAIN, Map.of());
            w.closeGroup(null);
            w.end();
        });
        assertThat(hidden).contains("'=Tenant,").contains(",'@SUM(1),").contains(",'+Bank,").doesNotContain("‏");
    }

    @Test
    void aCreditBalanceIsNegativeAndReadsCr() {
        String csv = render("en", SampleGrid.COLUMNS, w -> {
            w.openGroup(1, Band.PARTY, "Tenant B");
            w.opening(new BigDecimal("-8000"));
            w.row(cells("docDate", LocalDate.of(2027, 4, 2), "credit", new BigDecimal("12.5")), RowStyle.HIGHLIGHT, Map.of());
            w.closeGroup("Sub Total");
            w.end();
        });
        List<String> lines = csv.substring(1).lines().toList();
        assertThat(lines).contains("Balance brought forward,,,,-8000.00,Cr,,,,,",
                "02-04-2027,,,12.50,-8012.50,Cr,,,,,",
                "Sub Total,,0.00,12.50,-8012.50,Cr,,,,,",
                "REPORT TOTAL,,0.00,12.50,-8012.50,Cr,,,,,");
    }

    @Test
    void hiddenColumnsAreLeftOutAndOtherKindsArePlain() {
        List<ReportColumn> cols = List.of(
                ReportColumn.serial("sn", "col.docNo", 4),
                ReportColumn.date("day", "col.docDate", 12, "dd/MMM/yyyy"),
                ReportColumn.text("particular", "col.particular", 20).hiddenColumn(),
                ReportColumn.integer("days", "col.bdDays", 6, TotalRule.SUM),
                ReportColumn.integer("n", "col.chqNo", 6, TotalRule.COUNT),
                ReportColumn.money("debit", "col.debit", 12));
        String csv = render("en", cols, w -> {
            w.openGroup(1, Band.GROUP, "G");
            w.row(cells("day", LocalDate.of(2027, 4, 2), "particular", "secret", "days", 3, "debit", new BigDecimal("1")),
                    RowStyle.PLAIN, Map.of());
            w.row(cells("days", 4), RowStyle.PLAIN, Map.of());
            w.closeGroup("Sub Total");
            w.end();
        });
        assertThat(csv.substring(1).lines().toList()).containsExactly(
                "Doc No,Doc Date,BDDays,Chq No,Debit",
                "G,,,,",
                "1,02/Apr/2027,3,,1.00",
                "2,,4,,",
                "Sub Total,,7,2,1.00",
                "REPORT TOTAL,,7,2,1.00");
        assertThat(csv).doesNotContain("secret");
    }

    @Test
    void arabicHeadersKeepTheLatinDrCr() {
        String csv = render("ar", SampleGrid.COLUMNS, SampleGrid::feed);
        List<String> lines = csv.substring(1).lines().toList();
        assertThat(lines.get(0)).startsWith("تاريخ المستند,البيان,مدين,دائن,الرصيد,Dr/Cr,");
        assertThat(lines).contains("إجمالي التقرير,,60000.10,9500.00,50500.10,Dr,,,,,");
    }

    @Test
    void anEmptyGridHasTheHeaderAndAZeroTotal() {
        String csv = render("en", SampleGrid.COLUMNS, ReportWriter::end);
        assertThat(csv.substring(1).lines().toList()).containsExactly(
                "Doc Date,Particular,Debit,Credit,Balance,Dr/Cr,Chq No,Payee Bank,BDDays,ConvertedDate,Doc No",
                "REPORT TOTAL,,0.00,0.00,0.00,Dr,,,,,");
    }

    @Test
    void bytesBeforeTheEndIsRefused() {
        CsvGridSink sink = new CsvGridSink(LABELS.bind("en"));
        assertThatThrownBy(sink::bytes).isInstanceOf(IllegalStateException.class);
    }

    private static String render(String lang, List<ReportColumn> columns, Consumer<ReportWriter> feed) {
        ReportLabels.Bound labels = LABELS.bind(lang);
        CsvGridSink sink = new CsvGridSink(labels);
        ReportWriter w = new ReportWriter(sink, labels, 50_000);
        w.begin(SampleGrid.meta(lang), columns);
        feed.accept(w);
        return new String(sink.bytes(), StandardCharsets.UTF_8);
    }

    /** The fields of one RFC 4180 line. */
    private static List<String> fields(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
