package com.datagami.rentaxis.core.service.report.grid;

import com.datagami.rentaxis.api.exception.ReportTooLargeException;
import com.datagami.rentaxis.core.util.Search;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The row caps of D8: a screen page holds at most {@link Search#MAX_PAGE_SIZE} rows, an Excel or
 * CSV file 50,000 and a PDF 5,000 (each overridable under {@code rentaxis.reports.max-rows.*}).
 * A report counts its rows before reading them and calls {@link #requireWithin}, so an export over
 * its cap is a 422 and never a half-built file.
 */
@Component
public class ReportCaps {

    private final int xlsx;
    private final int csv;
    private final int pdf;

    public ReportCaps(@Value("${rentaxis.reports.max-rows.xlsx:50000}") int xlsx,
                      @Value("${rentaxis.reports.max-rows.csv:50000}") int csv,
                      @Value("${rentaxis.reports.max-rows.pdf:5000}") int pdf) {
        this.xlsx = requirePositive(xlsx, "xlsx");
        this.csv = requirePositive(csv, "csv");
        this.pdf = requirePositive(pdf, "pdf");
    }

    /** The most rows one screen page shows. */
    public int screenPageRows() {
        return Search.MAX_PAGE_SIZE;
    }

    /** The most rows a file of this format may hold. */
    public int maxRows(ExportFormat f) {
        return switch (f) {
            case XLSX -> xlsx;
            case CSV -> csv;
            case PDF -> pdf;
        };
    }

    /** A {@link ReportTooLargeException} (422) when {@code rows} is over the format's cap. */
    public void requireWithin(ExportFormat f, long rows) {
        int cap = maxRows(f);
        if (rows > cap) {
            throw new ReportTooLargeException(f, rows, cap);
        }
    }

    private static int requirePositive(int cap, String format) {
        if (cap < 1) {
            throw new IllegalArgumentException("rentaxis.reports.max-rows." + format + " must be at least 1, not " + cap);
        }
        return cap;
    }
}
