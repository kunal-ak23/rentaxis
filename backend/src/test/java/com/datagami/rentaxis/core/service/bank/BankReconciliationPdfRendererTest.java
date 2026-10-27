package com.datagami.rentaxis.core.service.bank;

import com.datagami.rentaxis.api.dto.bank.BankRecDTOs;
import com.datagami.rentaxis.testsupport.PdfWords;
import com.datagami.rentaxis.testsupport.PdfWords.Word;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.PdfWords.x;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The reconciliation PDF: bilingual, RTL in Arabic, masked IBAN, escaped bank text (finance-ops spec §4). */
class BankReconciliationPdfRendererTest {

    private final BankReconciliationPdfRenderer renderer = new BankReconciliationPdfRenderer();

    private static BankRecDTOs.Reconciliation sample() {
        BigDecimal z = BigDecimal.ZERO;
        return new BankRecDTOs.Reconciliation(UUID.randomUUID(), UUID.randomUUID(), "Emirates Islamic 0123", "Emirates Islamic",
                BankReconciliationService.maskIban("AE070260000000000000123", null),
                List.of(new BankRecDTOs.Leaf(UUID.randomUUID(), "A-02-02-001", "Emirates Islamic - Marina Tower", null)),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "FINALIZED", true,
                new BigDecimal("250000.00"), new BigDecimal("328017.50"), false, new BigDecimal("78017.50"),
                new BigDecimal("335017.50"), new BigDecimal("250000.00"), z, new BigDecimal("15000.00"),
                new BigDecimal("8000.00"), z, z, z, new BigDecimal("335017.50"), new BigDecimal("335017.50"), z,
                List.of(new BankRecDTOs.RecItem("JOURNAL", UUID.randomUUID(), LocalDate.of(2026, 9, 30), "CRT-26/104",
                        "<script>alert(1)</script>", "000452", new BigDecimal("15000.00"), true)),
                List.of(new BankRecDTOs.RecItem("JOURNAL", UUID.randomUUID(), LocalDate.of(2026, 9, 29), "BPV-26/60",
                        "Cheque 000077", "000077", new BigDecimal("-8000.00"), false)),
                List.of(), List.of(), 1, Map.of("CREATED", 4, "AUTO_GROUP", 1), List.of(), false,
                Instant.parse("2026-10-01T06:00:00Z"), "Accountant", Instant.parse("2026-10-01T07:00:00Z"), "Admin",
                null, null, null, java.util.List.of(), null, null);
    }

    @Test
    void theArabicStatementIsRightToLeftWithLatinAmountsAndEscapedText() {
        String html = renderer.html(sample(), "ar");
        assertThat(html).contains("dir=\"rtl\"").contains("كشف التسوية البنكية").contains("335,017.50").contains("(8,000.00)");
        assertThat(html).contains("&lt;script&gt;").doesNotContain("<script>");
        assertThat(html).contains("••••••••0123").doesNotContain("AE070260000000000000123");
        assertThat(html).contains("مقاصة يدوية");
    }

    /** F14-48: an account without an IBAN is labelled as an account number; labels and values sit in their own cells. */
    @Test
    void anAccountNumberIsNotCalledAnIbanAndArabicLabelsAreSeparated() {
        assertThat(BankReconciliationService.maskIban(null, "0001")).isEqualTo("0001");
        assertThat(BankReconciliationService.maskIban("AE070260000000000000123", null)).startsWith("AE");
        String en = renderer.html(sample(), "en");
        assertThat(en).contains("IBAN:");
        assertThat(en).contains("<table class=\"meta\"><tr><td>Bank account:</td>");
    }

    /**
     * openhtmltopdf does not mirror a table for {@code dir="rtl"}: the Arabic statement writes its cells last column
     * first, so Date prints rightmost and Amount leftmost, a formula label sits right of its amount, and the header's
     * label-value tables sit against the right margin (A4 is 595pt wide).
     */
    @Test
    void anArabicPdfPrintsItsColumnsRightToLeft() throws IOException {
        List<Word> ar = PdfWords.on(renderer.render(sample(), "ar"), 1);
        // A deposit in transit: Date, Document, Details, Cheque, Amount from the right.
        List<Word> item = PdfWords.lineOf(ar, "CRT-26/104");
        assertThat(x(item, "30/09/2026")).isGreaterThan(x(item, "CRT-26/104"));
        assertThat(x(item, "CRT-26/104")).isGreaterThan(PdfWords.containing(item, "alert").x());
        assertThat(PdfWords.containing(item, "alert").x()).isGreaterThan(x(item, "000452"));
        assertThat(x(item, "000452")).isGreaterThan(x(item, "15,000.00"));
        // The header runs the same way: the Amount heading sits over the amounts.
        assertThat(PdfWords.leftmost(PdfWords.lineAbove(ar, PdfWords.first(ar, "CRT-26/104"))).x())
                .isCloseTo(x(item, "15,000.00"), within(1f));
        // A formula line: the label on the right, its amount on the left.
        List<Word> unpresented = PdfWords.lineOf(ar, "(8,000.00)");
        assertThat(PdfWords.leftmost(unpresented).text()).isEqualTo("(8,000.00)");
        assertThat(PdfWords.rightmost(unpresented).arabic()).isTrue();
        // The bank line: its label rightmost, the pair against the right margin.
        List<Word> bank = PdfWords.lineOf(ar, "Islamic");
        assertThat(PdfWords.rightmost(bank).arabic()).isTrue();
        assertThat(x(bank, "Emirates")).isGreaterThan(300f);
    }

    @Test
    void anEnglishPdfKeepsItsColumnsLeftToRight() throws IOException {
        List<Word> en = PdfWords.on(renderer.render(sample(), "en"), 1);
        List<Word> item = PdfWords.lineOf(en, "CRT-26/104");
        assertThat(x(item, "30/09/2026")).isLessThan(x(item, "CRT-26/104"));
        assertThat(x(item, "000452")).isLessThan(x(item, "15,000.00"));
        assertThat(PdfWords.rightmost(PdfWords.lineOf(en, "(8,000.00)")).text()).isEqualTo("(8,000.00)");
        List<Word> bank = PdfWords.lineOf(en, "Islamic");
        assertThat(PdfWords.leftmost(bank).text()).isEqualTo("Bank");
        assertThat(x(bank, "Emirates")).isLessThan(300f);
    }

    @Test
    void theEnglishStatementRendersToAPdf() {
        String html = renderer.html(sample(), "en");
        assertThat(html).contains("dir=\"ltr\"").contains("Balance per bank statement at").contains("Finalized by Admin");
        byte[] pdf = renderer.render(sample(), "en");
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }
}
