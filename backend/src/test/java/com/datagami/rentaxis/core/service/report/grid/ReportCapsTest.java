package com.datagami.rentaxis.core.service.report.grid;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.ReportTooLargeException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportCapsTest {

    @Test
    void defaultsAreTheD8Caps() {
        ReportCaps caps = new ReportCaps(50_000, 50_000, 5_000);
        caps.requireWithin(ExportFormat.PDF, 5_000);
        caps.requireWithin(ExportFormat.XLSX, 50_000);
        caps.requireWithin(ExportFormat.CSV, 0);
        assertThatThrownBy(() -> caps.requireWithin(ExportFormat.PDF, 5_001)).isInstanceOfSatisfying(ReportTooLargeException.class, e -> {
            assertThat(e).isInstanceOf(BusinessRuleViolationException.class);
            assertThat(e.getCode()).isEqualTo("report.tooManyRowsPdf");
            assertThat(e.getArgs()).containsEntry("rows", 5_001L).containsEntry("cap", 5_000);
            assertThat(e.getMessage())
                    .isEqualTo("Too many rows for a PDF (5,001 rows, the limit is 5,000) — export Excel or narrow the filter");
        });
        assertThatThrownBy(() -> caps.requireWithin(ExportFormat.XLSX, 50_001)).isInstanceOfSatisfying(ReportTooLargeException.class, e -> {
            assertThat(e.getCode()).isEqualTo("report.tooManyRows");
            assertThat(e.getMessage()).isEqualTo("Too many rows (50,001 rows, the limit is 50,000) — narrow the filter");
        });
        assertThatThrownBy(() -> caps.requireWithin(ExportFormat.CSV, 50_001))
                .isInstanceOfSatisfying(ReportTooLargeException.class, e -> assertThat(e.getCode()).isEqualTo("report.tooManyRows"));
        assertThat(caps.screenPageRows()).isEqualTo(200);
    }

    @Test
    void eachFormatReadsItsOwnCap() {
        ReportCaps caps = new ReportCaps(30, 20, 10);
        assertThat(caps.maxRows(ExportFormat.XLSX)).isEqualTo(30);
        assertThat(caps.maxRows(ExportFormat.CSV)).isEqualTo(20);
        assertThat(caps.maxRows(ExportFormat.PDF)).isEqualTo(10);
        caps.requireWithin(ExportFormat.XLSX, 30);
        assertThatThrownBy(() -> caps.requireWithin(ExportFormat.CSV, 21)).isInstanceOf(ReportTooLargeException.class);
        assertThatThrownBy(() -> caps.requireWithin(ExportFormat.PDF, 11)).isInstanceOf(ReportTooLargeException.class);
    }

    @Test
    void aCapBelowOneIsAConfigurationError() {
        assertThatThrownBy(() -> new ReportCaps(0, 50_000, 5_000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportCaps(50_000, 50_000, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
