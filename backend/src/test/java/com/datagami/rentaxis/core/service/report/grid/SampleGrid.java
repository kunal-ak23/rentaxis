package com.datagami.rentaxis.core.service.report.grid;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A small tenant-ledger-shaped grid with synthetic data, shared by the writer and sink tests.
 *
 * <p>{@link #feed} writes one ACCOUNT band, one PARTY band, a zero opening, three rows (a TCO debit
 * of 60,000, a PDR credit of 9,500 with cheque columns, and a JV debit of 0.10 whose particular
 * looks like a formula), the party's Sub Total, closes the account without a subtotal, and ends.
 * The caller calls {@code begin(SampleGrid.meta(lang), SampleGrid.COLUMNS)} first.
 */
public final class SampleGrid {

    public static final UUID E1 = UUID.fromString("00000000-0000-0000-0000-0000000000e1");

    public static final List<ReportColumn> COLUMNS = List.of(
            ReportColumn.date("docDate", "col.docDate", 12, "dd-MM-yyyy"),
            ReportColumn.text("particular", "col.particular", 36),
            ReportColumn.money("debit", "col.debit", 14),
            ReportColumn.money("credit", "col.credit", 14),
            ReportColumn.balance("balance", "col.balance", 16, "debit", "credit"),
            ReportColumn.idText("chqNo", "col.chqNo", 12),
            ReportColumn.text("payeeBank", "col.payeeBank", 18),
            ReportColumn.integer("bdDays", "col.bdDays", 8, TotalRule.NONE),
            ReportColumn.date("convertedDate", "col.convertedDate", 14, "dd/MM/yyyy"),
            ReportColumn.idText("docNo", "col.docNo", 14, "journal"));

    public static final ZonedDateTime GENERATED_AT = ZonedDateTime.parse("2026-10-26T10:05+04:00[Asia/Dubai]");

    private SampleGrid() {
    }

    public static ReportMeta meta(String lang) {
        return new ReportMeta("sample", "tenantLedger.title", "Sample Org",
                List.of(new ReportFilter("filter.property", "Sample Tower")),
                LocalDate.of(2026, 9, 1), LocalDate.of(2027, 9, 30), null, GENERATED_AT, "Test User", lang);
    }

    public static void feed(ReportWriter w) {
        w.openGroup(1, Band.ACCOUNT, "Rent Receivable - Sample Tower");
        w.openGroup(2, Band.PARTY, "Tenant Name : Tenant A");
        w.opening(BigDecimal.ZERO);
        w.row(cells("docDate", LocalDate.of(2026, 9, 16), "particular", "Advance Rent - Sample Tower",
                "debit", new BigDecimal("60000"), "docNo", "TCO-26/1"), RowStyle.PLAIN, Map.of("entryId", E1));
        w.row(cells("docDate", LocalDate.of(2026, 9, 16), "particular", "PDC Receivable Sample Tower",
                "credit", new BigDecimal("9500"), "chqNo", "000201", "payeeBank", "Emirates NBD", "bdDays", 1,
                "convertedDate", LocalDate.of(2026, 10, 2), "docNo", "PDR-26/1"), RowStyle.PLAIN, Map.of());
        w.row(cells("docDate", LocalDate.of(2026, 9, 20), "particular", "=HYPERLINK(\"x\")",
                "debit", new BigDecimal("0.10"), "docNo", "JV-26/1"), RowStyle.PLAIN, Map.of());
        w.closeGroup("Sub Total");
        w.closeGroup(null);
        w.end();
    }

    /** A row's cells from alternating keys and values, in order; a {@code null} value is kept (and read as blank). */
    public static Map<String, Object> cells(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("keys and values must pair up");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            m.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return m;
    }
}
