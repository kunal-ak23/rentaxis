package com.datagami.rentaxis.core.service.payables;

import com.datagami.rentaxis.api.dto.ledger.AccountLedgerDTO;
import com.datagami.rentaxis.api.dto.ledger.LedgerRowDTO;
import com.datagami.rentaxis.api.dto.payables.AdvanceDTO;
import com.datagami.rentaxis.api.dto.payables.OpenItemDTO;
import com.datagami.rentaxis.testsupport.PdfWords;
import com.datagami.rentaxis.testsupport.PdfWords.Word;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.PdfWords.x;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The supplier statement of account (finance-ops spec §2, S11), English and Arabic. */
class SupplierStatementPdfRendererTest {

    private final SupplierStatementPdfRenderer renderer = new SupplierStatementPdfRenderer();

    private SupplierStatementPdfRenderer.Statement statement() {
        UUID e = UUID.randomUUID();
        AccountLedgerDTO ledger = new AccountLedgerDTO(UUID.randomUUID(), "B-01-04-001", "Gulf AC", "LIABILITY",
                new BigDecimal("-3550.00"),
                List.of(new LedgerRowDTO(e, "BPV-26/55", LocalDate.of(2026, 9, 10), "BPV", "Bank", "TRF-7781 <script>",
                        new BigDecimal("2050.00"), BigDecimal.ZERO, new BigDecimal("-1500.00"), null, null, null, null, null)),
                new BigDecimal("2050.00"), BigDecimal.ZERO, new BigDecimal("-1500.00"), false);
        OpenItemDTO item = new OpenItemDTO("PISR", UUID.randomUUID(), UUID.randomUUID(), "Gulf AC", "PISR-26/21", "INV-7790",
                LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 20), LocalDate.of(2026, 9, 19), 11, "D1_30",
                new BigDecimal("2100.00"), new BigDecimal("600.00"), new BigDecimal("1500.00"), "PART_PAID", null);
        AdvanceDTO advance = new AdvanceDTO(UUID.randomUUID(), UUID.randomUUID(), "Gulf AC", "BPV-26/60", LocalDate.of(2026, 9, 25),
                "TRANSFER", "TRF-9", new BigDecimal("500.00"), BigDecimal.ZERO, new BigDecimal("500.00"));
        return new SupplierStatementPdfRenderer.Statement("Gulf AC Services LLC", "الخليج لخدمات التكييف", "100123456700003",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), ledger, List.of(item), List.of(advance),
                Instant.parse("2026-09-30T08:00:00Z"), "Kunal");
    }

    @Test
    void theEnglishStatementCarriesTheLedgerTheOpenItemsAndTheNet() {
        String html = renderer.html(statement(), "en");
        assertThat(html).contains("dir=\"ltr\"", "Statement of account", "Gulf AC Services LLC", "100123456700003",
                "01/09/2026 – 30/09/2026", "3,550.00 Cr", "1,500.00 Cr", "INV-7790", "PISR-26/21", "19/09/2026",
                "BPV-26/60", "Net owed");
        // 1,500 open less the 500 advance.
        assertThat(html).contains("<span>1,000.00</span>");
        // User-typed text is escaped.
        assertThat(html).contains("TRF-7781 &lt;script&gt;").doesNotContain("<script>");
    }

    @Test
    void theArabicStatementIsRightToLeftWithLatinDigits() {
        String html = renderer.html(statement(), "ar");
        assertThat(html).contains("dir=\"rtl\"", "كشف حساب المورد", "الخليج لخدمات التكييف", "1,500.00 دائن");
        assertThat(html).contains("text-align: right");
    }

    /** Tutorial 19: the account activity as CSV beside the PDF, in both languages. */
    @Test
    void theStatementAlsoExportsAsCsv() {
        String en = new String(renderer.csv(statement(), "en"), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(en).startsWith("\uFEFF");
        assertThat(en).contains("Gulf AC Services LLC", "2026-09-01 – 2026-09-30", "Opening balance", "\"3,550.00 Cr\"",
                "2026-09-10", "BPV-26/55", "2050.00", "Closing balance", "\"1,500.00 Cr\"");
        String ar = new String(renderer.csv(statement(), "ar"), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(ar).contains("الخليج لخدمات التكييف", "الرصيد الافتتاحي", "1,500.00 دائن");
    }

    /**
     * openhtmltopdf does not mirror a table for {@code dir="rtl"}: the Arabic statement writes its cells last column
     * first, so Date prints rightmost and Balance leftmost, and the opening, closing and total labels span the
     * rightmost columns, clear of the amounts on their left.
     */
    @Test
    void anArabicPdfPrintsItsColumnsRightToLeft() throws IOException {
        List<Word> ar = PdfWords.on(renderer.render(statement(), "ar"), 1);
        // A ledger row: Date, Entry, Doc, Details, Debit, Credit, Balance from the right.
        List<Word> row = PdfWords.lineOf(ar, "BPV-26/55");
        Word balance = PdfWords.containing(row, "1,500.00");
        assertThat(x(row, "10/09/2026")).isGreaterThan(x(row, "BPV-26/55"));
        assertThat(x(row, "BPV-26/55")).isGreaterThan(x(row, "BPV"));
        assertThat(x(row, "BPV")).isGreaterThan(x(row, "2,050.00"));
        assertThat(x(row, "2,050.00")).isGreaterThan(balance.x());
        // The header runs the same way: the Balance heading sits over the balances.
        assertThat(PdfWords.leftmost(PdfWords.lineAbove(ar, PdfWords.first(ar, "BPV-26/55"))).x())
                .isCloseTo(balance.x(), within(1f));
        // The opening label is the rightmost cell, not the balance.
        Word rightOfOpening = PdfWords.rightmost(PdfWords.lineOf(ar, PdfWords.containing(ar, "3,550.00")));
        assertThat(rightOfOpening.arabic()).isTrue();
        assertThat(rightOfOpening.text()).doesNotContain("3,550.00");
        // The closing line: balance, then the debit total, then the label at the right.
        List<Word> closing = PdfWords.lineOf(ar, PdfWords.all(ar, "2,050.00").get(1));
        assertThat(PdfWords.containing(closing, "1,500.00").x()).isLessThan(x(closing, "2,050.00"));
        assertThat(PdfWords.rightmost(closing).arabic()).isTrue();
        // An open item: Invoice, Entry, Invoice date, Due date, Days overdue, Amount, Paid, Open from the right.
        List<Word> item = PdfWords.lineOf(ar, "INV-7790");
        assertThat(x(item, "INV-7790")).isGreaterThan(x(item, "PISR-26/21"));
        assertThat(x(item, "PISR-26/21")).isGreaterThan(x(item, "20/08/2026"));
        assertThat(x(item, "19/09/2026")).isGreaterThan(x(item, "11"));
        assertThat(x(item, "2,100.00")).isGreaterThan(x(item, "600.00"));
        assertThat(PdfWords.leftmost(item).text()).isEqualTo("1,500.00");
        // An advance, and the net owed: text on the right, amounts on the left.
        List<Word> advance = PdfWords.lineOf(ar, "BPV-26/60");
        assertThat(x(advance, "BPV-26/60")).isGreaterThan(x(advance, "25/09/2026"));
        List<Word> net = PdfWords.lineOf(ar, "1,000.00");
        assertThat(PdfWords.leftmost(net).text()).isEqualTo("1,000.00");
        assertThat(PdfWords.rightmost(net).arabic()).isTrue();
    }

    @Test
    void anEnglishPdfKeepsItsColumnsLeftToRight() throws IOException {
        List<Word> en = PdfWords.on(renderer.render(statement(), "en"), 1);
        List<Word> row = PdfWords.lineOf(en, "BPV-26/55");
        assertThat(x(row, "10/09/2026")).isLessThan(x(row, "BPV-26/55"));
        assertThat(x(row, "BPV")).isLessThan(x(row, "2,050.00"));
        assertThat(x(row, "2,050.00")).isLessThan(x(row, "1,500.00"));
        assertThat(PdfWords.leftmost(PdfWords.lineOf(en, PdfWords.all(en, "2,050.00").get(1))).text()).isEqualTo("Closing");
        assertThat(PdfWords.rightmost(PdfWords.lineOf(en, "1,000.00")).text()).isEqualTo("1,000.00");
    }

    @Test
    void itRendersAPdf() {
        byte[] pdf = renderer.render(statement(), "ar");
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    void aTruncatedLedgerPrintsThePeriodsRealClosingAndNamesTheGapToOpenItems() {
        SupplierStatementPdfRenderer.Statement base = statement();
        AccountLedgerDTO g = base.ledger();
        AccountLedgerDTO truncated = new AccountLedgerDTO(g.accountId(), g.accountCode(), g.accountName(), g.accountType(),
                g.openingBalance(), g.rows(), g.totalDebit(), g.totalCredit(), g.closingBalance(), true);
        SupplierStatementPdfRenderer.Statement s = new SupplierStatementPdfRenderer.Statement(base.vendorName(),
                base.vendorNameAr(), base.vendorTrn(), base.from(), base.to(), truncated, new BigDecimal("-1250.00"),
                base.openItems(), base.advances(), base.generatedAt(), base.generatedBy());
        String html = renderer.html(s, "en");
        // The closing row is the balance read on its own, not the last running balance (1,500 Cr).
        assertThat(html).contains("<span>1,250.00 Cr</span>", "the closing balance is the whole period");
        // Owed per the ledger 1,250; net of open items and advances 1,000; the 250 gap is named.
        assertThat(html).contains("Owed per the ledger (closing balance)", "<span>1,250.00</span>", "<span>250.00</span>",
                "journal voucher");
    }
}
