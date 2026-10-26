package com.datagami.rentaxis.core.service.report.grid;

import com.datagami.rentaxis.api.exception.ReportTooLargeException;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Renders one report file: picks the sink for the format, wraps it in a {@link ReportWriter} and
 * lets {@code feed} write the report ({@code begin} … {@code end}) into it. Runs after the
 * report's read-only transaction, from records already read.
 *
 * <p>A file is never silently short: a feed with more rows than {@code rowCap} is a
 * {@link ReportTooLargeException} (422, {@code report.tooManyRows*}) and no bytes. Reports count their
 * rows and call {@link ReportCaps#requireWithin} before reading them, so this is the backstop for a count
 * that was wrong.</p>
 */
public final class GridRenderer {

    private GridRenderer() {
    }

    /**
     * The file's bytes.
     *
     * @param style  the Excel profile; required for {@link ExportFormat#XLSX}, ignored otherwise
     * @param rowCap the most rows the file may hold; a feed with more is a {@link ReportTooLargeException}
     * @param feed   writes the whole report, {@code begin} through {@code end}
     */
    public static byte[] render(ExportFormat f, XlsxStyle style, ReportLabels.Bound labels, int rowCap,
                                Consumer<ReportWriter> feed) {
        Objects.requireNonNull(f, "format");
        Objects.requireNonNull(labels, "labels");
        Objects.requireNonNull(feed, "feed");
        return switch (f) {
            case XLSX -> {
                // try-with-resources: a feed that throws (or runs over the cap) still deletes SXSSF's temp files.
                try (XlsxGridSink sink = new XlsxGridSink(Objects.requireNonNull(style, "an xlsx needs a style"))) {
                    write(f, sink, labels, rowCap, feed);
                    yield sink.bytes();
                }
            }
            case PDF -> {
                PdfGridSink sink = new PdfGridSink();
                write(f, sink, labels, rowCap, feed);
                yield sink.bytes();
            }
            case CSV -> {
                CsvGridSink sink = new CsvGridSink(labels);
                write(f, sink, labels, rowCap, feed);
                yield sink.bytes();
            }
        };
    }

    /** Feeds the report into {@code sink}; a writer that dropped rows at the cap is refused before any bytes exist. */
    private static void write(ExportFormat f, ReportSink sink, ReportLabels.Bound labels, int rowCap,
                              Consumer<ReportWriter> feed) {
        ReportWriter w = new ReportWriter(sink, labels, rowCap);
        feed.accept(w);
        if (w.truncated()) {
            throw new ReportTooLargeException(f, w.rowsWritten() + w.rowsDropped(), rowCap);
        }
    }
}
