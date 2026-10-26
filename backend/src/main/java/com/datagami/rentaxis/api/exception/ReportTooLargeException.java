package com.datagami.rentaxis.api.exception;

import com.datagami.rentaxis.core.service.report.grid.ExportFormat;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A report export over its format's row cap (D8). Answered with a 422 rather than the 400 of
 * other business rules: the request was well formed, the selection is just too big for the format.
 * {@code code} is {@code report.tooManyRowsPdf} for a PDF (Excel can take more) and
 * {@code report.tooManyRows} otherwise; {@code args} are {@code rows} and {@code cap}.
 */
public class ReportTooLargeException extends BusinessRuleViolationException {

    public static final String CODE_PDF = "report.tooManyRowsPdf";
    public static final String CODE = "report.tooManyRows";

    public ReportTooLargeException(ExportFormat format, long rows, int cap) {
        super(message(format, rows, cap), format == ExportFormat.PDF ? CODE_PDF : CODE, args(rows, cap));
    }

    private static String message(ExportFormat format, long rows, int cap) {
        return format == ExportFormat.PDF
                ? String.format(Locale.ROOT, "Too many rows for a PDF (%,d rows, the limit is %,d) — export Excel or narrow the filter", rows, cap)
                : String.format(Locale.ROOT, "Too many rows (%,d rows, the limit is %,d) — narrow the filter", rows, cap);
    }

    private static Map<String, Object> args(long rows, int cap) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", rows);
        m.put("cap", cap);
        return m;
    }
}
