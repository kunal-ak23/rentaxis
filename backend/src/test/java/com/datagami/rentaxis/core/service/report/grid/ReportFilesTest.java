package com.datagami.rentaxis.core.service.report.grid;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportFilesTest {

    @Test
    void namesTheFileTheRfc5987Way() {
        ResponseEntity<byte[]> r = ReportFiles.response(new byte[]{1}, ExportFormat.XLSX, "tenant-ledger-2026-09-01-2027-09-30.xlsx");
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).containsExactly(1);
        assertThat(r.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .startsWith("attachment").contains("filename*=UTF-8''tenant-ledger-2026-09-01-2027-09-30.xlsx");
        assertThat(r.getHeaders().getContentType().toString())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(r.getHeaders().getContentLength()).isEqualTo(1);
        assertThat(ReportFiles.response(new byte[]{1}, ExportFormat.PDF, "دفتر.pdf").getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("filename*=UTF-8''%D8%AF%D9%81%D8%AA%D8%B1.pdf");
    }

    @Test
    void eachFormatHasItsExtensionAndMediaType() {
        assertThat(ExportFormat.of("xlsx")).isEqualTo(ExportFormat.XLSX);
        assertThat(ExportFormat.of("pdf")).isEqualTo(ExportFormat.PDF);
        assertThat(ExportFormat.of("csv")).isEqualTo(ExportFormat.CSV);
        assertThat(ExportFormat.CSV.ext()).isEqualTo("csv");
        assertThat(ExportFormat.PDF.mediaType().toString()).isEqualTo("application/pdf");
        assertThat(ExportFormat.CSV.mediaType().toString()).isEqualTo("text/csv;charset=UTF-8");
        assertThat(ReportFiles.response(new byte[0], ExportFormat.CSV, "a.csv").getHeaders().getContentType().getCharset())
                .hasToString("UTF-8");
    }

    @Test
    void anythingElseIsRefused() {
        assertThatThrownBy(() -> ExportFormat.of("xls")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExportFormat.of("XLSX")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExportFormat.of("json")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExportFormat.of(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
