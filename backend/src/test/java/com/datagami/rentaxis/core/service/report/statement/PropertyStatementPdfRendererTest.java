package com.datagami.rentaxis.core.service.report.statement;

import com.datagami.rentaxis.api.dto.report.PropertyStatementDTO;
import com.datagami.rentaxis.api.dto.report.PropertyStatementDTO.Figure;
import com.datagami.rentaxis.api.dto.report.PropertyStatementDTO.Section;
import com.datagami.rentaxis.api.dto.report.PropertyStatementDTO.Table;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PropertyStatementPdfRendererTest {

    private final PropertyStatementPdfRenderer renderer = new PropertyStatementPdfRenderer();

    private static PropertyStatementDTO pack(String propertyName, String vendor) {
        Section collected = new Section("collected", 3, "LEDGER",
                List.of(Figure.of("collected", new BigDecimal("-1234.50"))),
                List.of(new Table("byMode", List.of("mode", "amount"), List.of(List.of("PDC", new BigDecimal("30000.00"))))),
                List.of());
        Section expenses = new Section("expensesIncurred", 6, "LEDGER", List.of(),
                List.of(new Table("documents", List.of("entryNumber", "vendor", "net"),
                        List.of(List.of("PISR-1", vendor, new BigDecimal("7200.00"))))),
                List.of("inputVatHeaderProperty"));
        return new PropertyStatementDTO(UUID.randomUUID(), propertyName, "برج المارينا", "DUBAI",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), List.of(collected, expenses),
                new PropertyStatementDTO.Footer(false, null, Instant.parse("2026-09-30T08:00:00Z"), "<b>Kunal</b>"));
    }

    @Test
    void userTextIsEscapedAndNothingExternalIsReferenced() {
        String html = renderer.html(pack("Marina <img src=http://169.254.169.254/>", "Emrill & \"Sons\""), "en");
        assertThat(html).doesNotContain("<img").contains("&lt;img").contains("Emrill &amp; &quot;Sons&quot;")
                .contains("&lt;b&gt;Kunal&lt;/b&gt;").doesNotContain("<b>Kunal");
        assertThat(html).contains("dir=\"ltr\"").contains("-1,234.50").contains("30,000.00").contains("Cheque")
                .contains("Provisional");
    }

    @Test
    void arabicIsRightToLeftWithLatinDigits() {
        String html = renderer.html(pack("Marina Tower", "Emrill"), "ar");
        assertThat(html).contains("<html lang=\"ar\" dir=\"rtl\"").contains("برج المارينا").contains("كشف حساب العقار")
                .contains("30,000.00").contains("شيك");
    }

    @Test
    void rendersAPdfInBothLanguages() {
        for (String lang : List.of("en", "ar")) {
            byte[] pdf = renderer.render(pack("Marina Tower", "Emrill"), lang);
            assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        }
    }

    @Test
    void csvQuotesAndDefusesFormulas() {
        String csv = new String(ReportCsv.statement(pack("=HYPERLINK(\"x\")", "a,b"), "en"), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿").contains("\"'=HYPERLINK(\"\"x\"\")\"").contains("\"a,b\"").contains("-1234.50");
    }
}
