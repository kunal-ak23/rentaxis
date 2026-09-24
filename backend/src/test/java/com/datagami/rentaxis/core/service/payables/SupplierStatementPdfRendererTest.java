package com.datagami.rentaxis.core.service.payables;

import com.datagami.rentaxis.api.dto.ledger.AccountLedgerDTO;
import com.datagami.rentaxis.api.dto.ledger.LedgerRowDTO;
import com.datagami.rentaxis.api.dto.payables.AdvanceDTO;
import com.datagami.rentaxis.api.dto.payables.OpenItemDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
