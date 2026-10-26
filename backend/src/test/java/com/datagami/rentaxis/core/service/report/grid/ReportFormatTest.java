package com.datagami.rentaxis.core.service.report.grid;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ReportFormatTest {

    @Test
    void drCrIsPactsForm() {
        assertThat(ReportFormat.drCr(new BigDecimal("12345"))).isEqualTo("12,345.00Dr");
        assertThat(ReportFormat.drCr(new BigDecimal("-8000"))).isEqualTo("8,000.00Cr");
        assertThat(ReportFormat.drCr(BigDecimal.ZERO)).isEqualTo("0.00Dr");
        assertThat(ReportFormat.drCr(new BigDecimal("-0.004"))).isEqualTo("0.00Dr");
        assertThat(ReportFormat.money(new BigDecimal("1234567.891"))).isEqualTo("1,234,567.89");
        assertThat(ReportFormat.money(null)).isEmpty();
    }

    @Test
    void daysUseLatinDigitsAndEnglishMonths() {
        LocalDate d = LocalDate.of(2027, 4, 2);
        assertThat(ReportFormat.day(d, "dd-MM-yyyy")).isEqualTo("02-04-2027");
        assertThat(ReportFormat.day(d, "dd/MM/yyyy")).isEqualTo("02/04/2027");
        assertThat(ReportFormat.day(d, "dd/MMM/yyyy")).isEqualTo("02/Apr/2027");
        assertThat(ReportFormat.stamp(ZonedDateTime.parse("2026-10-26T06:05:00Z"))).isEqualTo("26/10/2026 10:05");
    }

    @Test
    void durationIsPactsSpellingWithTheTrailingSpace() {
        assertThat(ReportFormat.duration(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))).isEqualTo("1 Years ");
        assertThat(ReportFormat.duration(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 31))).isEqualTo("1 Years 1 Months ");
        assertThat(ReportFormat.duration(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 8))).isEqualTo("1 Years 8 Days");
        assertThat(ReportFormat.duration(LocalDate.of(2026, 3, 15), LocalDate.of(2026, 3, 15))).isEqualTo("1 Days");
    }

    @Test
    void cellTextStripsControlAndBidiAndTruncates() {
        // U+200F right-to-left mark, U+0007 bell, U+202E right-to-left override
        assertThat(ReportFormat.cellText("A‏b\u0007c‮d")).isEqualTo("Abcd");
        assertThat(ReportFormat.cellText("x".repeat(40_000))).hasSize(32_767);
        assertThat(ReportFormat.cellText(null)).isEmpty();
    }

    @Test
    void sheetNamesAreExcelSafe() {
        assertThat(ReportFormat.sheetName("Tenant [Ledger]: 2026/27?")).isEqualTo("Tenant Ledger 202627");
        assertThat(ReportFormat.sheetName("x".repeat(40))).hasSize(31);
        assertThat(ReportFormat.sheetName("  ")).isEqualTo("Report");
    }
}
