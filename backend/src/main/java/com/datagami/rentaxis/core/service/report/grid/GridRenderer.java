package com.datagami.rentaxis.core.service.report.grid;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Renders one report file: picks the sink for the format, wraps it in a {@link ReportWriter} and
 * lets {@code feed} write the report ({@code begin} … {@code end}) into it. Runs after the
 * report's read-only transaction, from records already read.
 */
public final class GridRenderer {

    private GridRenderer() {
    }

    /**
     * The file's bytes.
     *
     * @param style  the Excel profile; required for {@link ExportFormat#XLSX}, ignored otherwise
     * @param rowCap rows past this are dropped and the file is marked truncated
     * @param feed   writes the whole report, {@code begin} through {@code end}
     */
    public static byte[] render(ExportFormat f, XlsxStyle style, ReportLabels.Bound labels, int rowCap,
                                Consumer<ReportWriter> feed) {
        Objects.requireNonNull(f, "format");
        Objects.requireNonNull(labels, "labels");
        Objects.requireNonNull(feed, "feed");
        return switch (f) {
            case XLSX -> {
                // try-with-resources: a feed that throws still deletes SXSSF's temp files.
                try (XlsxGridSink sink = new XlsxGridSink(Objects.requireNonNull(style, "an xlsx needs a style"))) {
                    feed.accept(new ReportWriter(sink, labels, rowCap));
                    yield sink.bytes();
                }
            }
            case PDF -> {
                PdfGridSink sink = new PdfGridSink();
                feed.accept(new ReportWriter(sink, labels, rowCap));
                yield sink.bytes();
            }
            case CSV -> {
                CsvGridSink sink = new CsvGridSink(labels);
                feed.accept(new ReportWriter(sink, labels, rowCap));
                yield sink.bytes();
            }
        };
    }
}
