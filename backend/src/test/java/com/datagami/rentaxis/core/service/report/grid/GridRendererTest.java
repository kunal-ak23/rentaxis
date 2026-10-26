package com.datagami.rentaxis.core.service.report.grid;

import com.datagami.rentaxis.api.exception.ReportTooLargeException;
import org.apache.poi.util.DefaultTempFileCreationStrategy;
import org.apache.poi.util.TempFile;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GridRendererTest {

    private static final ReportLabels LABELS = new ReportLabels();

    private static final Consumer<ReportWriter> SAMPLE = w -> {
        w.begin(SampleGrid.meta("en"), SampleGrid.COLUMNS);
        SampleGrid.feed(w);
    };

    @Test
    void everyFormatRendersTheSampleGrid() throws IOException {
        byte[] xlsx = GridRenderer.render(ExportFormat.XLSX, XlsxStyle.TENANT_LEDGER, LABELS.bind("en"), 50_000, SAMPLE);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            XSSFSheet s = wb.getSheetAt(0);
            assertThat(s.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Doc Date");
            // The style profile the caller chose reaches the sink: the Tenant Ledger header is white.
            assertThat(s.getRow(1).getCell(0).getCellStyle().getFillForegroundXSSFColor().getARGBHex()).isEqualTo("FFFFFFFF");
        }

        byte[] pdf = GridRenderer.render(ExportFormat.PDF, XlsxStyle.TENANT_LEDGER, LABELS.bind("en"), 50_000, SAMPLE);
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");

        String csv = new String(GridRenderer.render(ExportFormat.CSV, XlsxStyle.TENANT_LEDGER, LABELS.bind("en"), 50_000, SAMPLE),
                StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿");
        assertThat(csv.substring(1).lines().toList())
                .contains("REPORT TOTAL,,60000.10,9500.00,50500.10,Dr,,,,,");
    }

    @Test
    void theLabelsReachTheWriter() {
        String csv = new String(GridRenderer.render(ExportFormat.CSV, XlsxStyle.PACT, LABELS.bind("ar"), 3, w -> {
            w.begin(SampleGrid.meta("ar"), SampleGrid.COLUMNS);
            SampleGrid.feed(w);
        }), StandardCharsets.UTF_8);
        List<String> lines = csv.substring(1).lines().toList();
        assertThat(lines.get(0)).startsWith("تاريخ المستند,");
        // Exactly at the cap is a whole file.
        assertThat(lines).anySatisfy(l -> assertThat(l).contains("TCO-26/1"))
                .anySatisfy(l -> assertThat(l).contains("PDR-26/1"))
                .anySatisfy(l -> assertThat(l).contains("JV-26/1"));
    }

    @Test
    void aFileOverTheRowCapIsRefusedNeverCutShort() {
        // The sample feeds three rows: a cap of two would have dropped the JV and printed a short file.
        for (ExportFormat f : ExportFormat.values()) {
            assertThatThrownBy(() -> GridRenderer.render(f, XlsxStyle.PACT, LABELS.bind("en"), 2, SAMPLE))
                    .as(f.name())
                    .isInstanceOfSatisfying(ReportTooLargeException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(f == ExportFormat.PDF
                                ? ReportTooLargeException.CODE_PDF : ReportTooLargeException.CODE);
                        assertThat(e.getArgs()).containsEntry("rows", 3L).containsEntry("cap", 2);
                    });
        }
    }

    @Test
    void anXlsxNeedsAStyleButTheOtherFormatsDoNot() {
        assertThatThrownBy(() -> GridRenderer.render(ExportFormat.XLSX, null, LABELS.bind("en"), 50_000, SAMPLE))
                .isInstanceOf(NullPointerException.class);
        assertThat(GridRenderer.render(ExportFormat.CSV, null, LABELS.bind("en"), 50_000, SAMPLE)).isNotEmpty();
    }

    @Test
    void aFeedThatFailsLeavesNoTempFilesBehind(@TempDir Path dir) throws IOException {
        TempFile.setTempFileCreationStrategy(new DefaultTempFileCreationStrategy(dir.toFile()));
        try {
            assertThatThrownBy(() -> GridRenderer.render(ExportFormat.XLSX, XlsxStyle.PACT, LABELS.bind("en"), 50_000, w -> {
                w.begin(SampleGrid.meta("en"), SampleGrid.COLUMNS);
                w.openGroup(1, Band.PARTY, "Tenant A");
                throw new IllegalStateException("read failed");
            })).isInstanceOf(IllegalStateException.class).hasMessage("read failed");
            // A workbook refused at the row cap is closed unwritten too.
            assertThatThrownBy(() -> GridRenderer.render(ExportFormat.XLSX, XlsxStyle.PACT, LABELS.bind("en"), 1, SAMPLE))
                    .isInstanceOf(ReportTooLargeException.class);
            try (var files = Files.walk(dir)) {
                assertThat(files.filter(Files::isRegularFile)).isEmpty();
            }
        } finally {
            TempFile.setTempFileCreationStrategy(new DefaultTempFileCreationStrategy());
        }
    }
}
