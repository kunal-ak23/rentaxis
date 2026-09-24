package com.datagami.rentaxis.core.service.bank;

import com.datagami.rentaxis.domain.entity.BankStatementProfile;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;

/**
 * Statement parsing (finance-ops spec §3 import): the value parsers, order
 * detection, the running-balance guard, the line hash, and golden files for
 * each bank layout. All files are synthetic.
 */
class StatementParsingTest {

    static final UUID BANK = UUID.fromString("00000000-0000-0000-0000-00000000e123");

    static byte[] file(String name) throws Exception {
        try (InputStream in = StatementParsingTest.class.getResourceAsStream("/bank-statements/" + name)) {
            return in.readAllBytes();
        }
    }

    static BankStatementProfile profile(BankStatementProfile.FileKind kind, BankStatementProfile.AmountMode mode,
                                        int header, Map<String, String> cols) {
        BankStatementProfile p = new BankStatementProfile();
        p.setFileKind(kind);
        p.setAmountMode(mode);
        p.setHeaderRow(header);
        p.setFirstDataRow(header + 1);
        p.setColumns(new LinkedHashMap<>(cols));
        return p;
    }

    static BankStatementProfile enbd() {
        return profile(BankStatementProfile.FileKind.CSV, BankStatementProfile.AmountMode.SPLIT, 2, Map.of(
                "txnDate", "Transaction Date", "valueDate", "Value Date", "description", "Narration",
                "reference", "Reference", "debit", "Debit", "credit", "Credit", "balance", "Running Balance"));
    }

    // ------------------------------------------------------------------ values

    @Test
    void amountsParseParenthesesTrailingMinusAedAndThousands() {
        assertThat(StatementValues.amount("(1,234.50)").value()).isEqualByComparingTo("-1234.50");
        assertThat(StatementValues.amount("1,234.50-").value()).isEqualByComparingTo("-1234.50");
        assertThat(StatementValues.amount("AED 12,000").value()).isEqualByComparingTo("12000.00");
        assertThat(StatementValues.amount(" 2 050.00 ").value()).isEqualByComparingTo("2050.00");
        assertThat(StatementValues.amount("-5").value()).isEqualByComparingTo("-5.00");
        assertThat(StatementValues.amount("300.00 DR").flag()).isEqualTo("DR");
        assertThat(StatementValues.amount("300.00 cr").flag()).isEqualTo("CR");
        assertThat(StatementValues.amount(new BigDecimal("12.345")).value()).isEqualByComparingTo("12.35");
        assertThat(StatementValues.amount("  ")).isNull();
        assertThatThrownBy(() -> StatementValues.amount("12a")).isInstanceOf(NumberFormatException.class);
    }

    @Test
    void datesAreDayFirstAndReadExcelSerialsTextAndTwoDigitYears() {
        List<String> f = List.of("dd/MM/yyyy", "dd-MMM-yyyy", "dd/MM/yy");
        // Ambiguous: always day first.
        assertThat(StatementValues.date("03/09/2026", f)).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(StatementValues.date("09/03/2026", f)).isEqualTo(LocalDate.of(2026, 3, 9));
        assertThat(StatementValues.date("03-sep-2026", f)).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(StatementValues.date("03/09/26", f)).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(StatementValues.date("03/09/2026 00:00:00", f)).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(StatementValues.date(new BigDecimal("46268"), f)).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(StatementValues.date("2026-09-03", f)).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThatThrownBy(() -> StatementValues.date("31/02/2026", f)).isInstanceOf(DateTimeParseException.class);
        assertThatThrownBy(() -> StatementValues.date("Sept third", f)).isInstanceOf(DateTimeParseException.class);
    }

    @Test
    void theChequeNumberComesFromTheReferenceThenTheDescription() {
        Pattern six = Pattern.compile("\\b\\d{6}\\b");
        assertThat(StatementValues.chequeNo(six, "CHQ 118822", "CHQ DEP 000451")).isEqualTo("118822");
        assertThat(StatementValues.chequeNo(six, null, "CHQ DEP 000451")).isEqualTo("000451");
        assertThat(StatementValues.chequeNo(six, "TRF-7781", "TRF OUT 1234567")).isNull();
    }

    @Test
    void theHashIsStableAndSeesEveryField() {
        LocalDate d = LocalDate.of(2026, 9, 15);
        String h = StatementValues.lineHash(BANK, d, d, new BigDecimal("-50"), "service  charge", null, new BigDecimal("347900"), 0);
        // Same line, spelt differently where normalisation says it is the same.
        assertThat(StatementValues.lineHash(BANK, d, d, new BigDecimal("-50.00"), "SERVICE CHARGE ", null,
                new BigDecimal("347900.00"), 0)).isEqualTo(h).hasSize(64);
        assertThat(StatementValues.lineHash(BANK, d, d, new BigDecimal("-50.00"), "SERVICE CHARGE", null,
                new BigDecimal("347900.00"), 1)).isNotEqualTo(h);
        assertThat(StatementValues.lineHash(BANK, d, d, new BigDecimal("-5.00"), "service charge", null,
                new BigDecimal("347900"), 0)).isNotEqualTo(h);
        assertThat(StatementValues.lineHash(BANK, d.plusDays(1), d, new BigDecimal("-50"), "service charge", null,
                new BigDecimal("347900"), 0)).isNotEqualTo(h);
        assertThat(StatementValues.lineHash(BANK, d, d, new BigDecimal("-50"), "service fee", null,
                new BigDecimal("347900"), 0)).isNotEqualTo(h);
        assertThat(StatementValues.lineHash(UUID.randomUUID(), d, d, new BigDecimal("-50"), "service charge", null,
                new BigDecimal("347900"), 0)).isNotEqualTo(h);
        assertThat(StatementValues.lineHash(BANK, d, null, new BigDecimal("-50"), "service charge", null,
                new BigDecimal("347900"), 0)).isNotEqualTo(h);
        assertThat(StatementValues.lineHash(BANK, d, d, new BigDecimal("-50"), "service charge", "R1",
                new BigDecimal("347900"), 0)).isNotEqualTo(h);
        assertThat(StatementValues.lineHash(BANK, d, d, new BigDecimal("-50"), "service charge", null,
                null, 0)).isNotEqualTo(h);
    }

    // ------------------------------------------------------------------ golden files

    @Test
    void enbdCsvIsNewestFirstSplitWithAnOpeningRow() throws Exception {
        StatementGrid g = new CsvStatementParser().read(file("enbd-september-2026.csv"), null, null);
        StatementMapper.Result r = StatementMapper.map(g, enbd());
        assertThat(r.errors()).isEmpty();
        assertThat(r.order()).isEqualTo(StatementMapper.Order.REVERSED);
        assertThat(r.rows()).extracting(StatementMapper.Row::amount).map(BigDecimal::toPlainString)
                .containsExactly("50000.00", "50000.00", "-2050.00", "-50.00", "-2.50", "-20000.00", "120.00");
        assertThat(r.rows().get(0).chequeNo()).isEqualTo("000451");
        assertThat(r.rows().get(5).chequeNo()).isEqualTo("000031");
        assertThat(r.rows().get(2).reference()).isEqualTo("TRF-7781");
        assertThat(r.openingBalance()).isEqualByComparingTo("250000.00");
        assertThat(r.closingBalance()).isEqualByComparingTo("328017.50");
        assertThat(r.rows().get(0).fileRow()).isEqualTo(9);
    }

    @Test
    void adcbCsvCarriesDrCrFlagsAndMonthNames() throws Exception {
        StatementGrid g = new CsvStatementParser().read(file("adcb-drcr.csv"), null, null);
        StatementMapper.Result r = StatementMapper.map(g, profile(BankStatementProfile.FileKind.CSV,
                BankStatementProfile.AmountMode.DRCR_FLAG, 1,
                Map.of("txnDate", "A", "description", "B", "amount", "C", "balance", "D")));
        assertThat(r.errors()).isEmpty();
        assertThat(r.order()).isEqualTo(StatementMapper.Order.FILE);
        assertThat(r.rows()).extracting(x -> x.amount().toPlainString()).containsExactly("1500.00", "-200.00", "-300.00");
        assertThat(r.rows().get(1).txnDate()).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(r.rows().get(2).chequeNo()).isEqualTo("000777");
        assertThat(r.openingBalance()).isEqualByComparingTo("10000.00");
    }

    @Test
    void aBalanceBreakRefusesTheFileAndNamesTheRow() throws Exception {
        StatementGrid g = new CsvStatementParser().read(file("balance-break.csv"), null, null);
        StatementMapper.Result r = StatementMapper.map(g, profile(BankStatementProfile.FileKind.CSV,
                BankStatementProfile.AmountMode.SPLIT, 1, Map.of("txnDate", "Date", "description", "Description",
                        "debit", "Debit", "credit", "Credit", "balance", "Balance")));
        assertThat(r.errors()).containsExactly("Row 4: balance 3,000.00 does not follow 900.00 + 2,050.00");
    }

    @Test
    void twoIdenticalChargesWithNoBalanceStayTwoLines() throws Exception {
        StatementGrid g = new CsvStatementParser().read(file("two-charges-no-balance.csv"), null, null);
        StatementMapper.Result r = StatementMapper.map(g, profile(BankStatementProfile.FileKind.CSV,
                BankStatementProfile.AmountMode.SIGNED, 1, Map.of("txnDate", "Date", "description", "Description",
                        "amount", "Amount")));
        assertThat(r.errors()).isEmpty();
        assertThat(r.hasBalance()).isFalse();
        List<String> h = BankStatementImportService.hashes(BANK, r.rows());
        assertThat(h).doesNotHaveDuplicates().hasSize(3);
        // Stable across reads: a re-import finds the same three hashes.
        assertThat(BankStatementImportService.hashes(BANK, StatementMapper.map(g, profile(BankStatementProfile.FileKind.CSV,
                BankStatementProfile.AmountMode.SIGNED, 1, Map.of("txnDate", "Date", "description", "Description",
                        "amount", "Amount"))).rows())).isEqualTo(h);
    }

    @Test
    void eiXlsxHasDateCellsSignedAmountsAndOpeningAndClosingRows() throws Exception {
        byte[] bytes = eiWorkbook();
        StatementGrid g = new XlsxStatementParser().read(bytes, "Statement", null);
        assertThat(g.sheetNames()).containsExactly("Statement");
        StatementMapper.Result r = StatementMapper.map(g, profile(BankStatementProfile.FileKind.XLSX,
                BankStatementProfile.AmountMode.SIGNED, 3, Map.of("txnDate", "Date", "valueDate", "Value",
                        "description", "Details", "amount", "Amount", "balance", "Balance")));
        assertThat(r.errors()).isEmpty();
        assertThat(r.rows()).hasSize(3);
        assertThat(r.rows().get(0).txnDate()).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(r.rows().get(1).valueDate()).isEqualTo(LocalDate.of(2026, 9, 4));
        assertThat(r.rows()).extracting(x -> x.amount().toPlainString()).containsExactly("50000.00", "-52.50", "120.00");
        assertThat(r.openingBalance()).isEqualByComparingTo("250000.00");
        assertThat(r.closingBalance()).isEqualByComparingTo("300067.50");
    }

    /** An Emirates Islamic-style workbook: a title, a blank row, headers on row 3, date cells, opening/closing rows. */
    static byte[] eiWorkbook() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Statement");
            CellStyle date = wb.createCellStyle();
            date.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("dd/mm/yyyy"));
            s.createRow(0).createCell(0).setCellValue("Account statement AE07 0260 ...0123 (synthetic)");
            Row h = s.createRow(2);
            String[] head = {"Date", "Value", "Details", "Amount", "Balance"};
            for (int i = 0; i < head.length; i++) h.createCell(i).setCellValue(head[i]);
            Row o = s.createRow(3);
            o.createCell(2).setCellValue("Opening Balance");
            o.createCell(4).setCellValue(250000);
            Object[][] rows = {
                    {LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 3), "CHQ DEP 000451", 50000.0, 300000.0},
                    {LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 4), "SERVICE CHARGE INCL VAT", -52.5, 299947.5},
                    {LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 30), "CREDIT INTEREST", 120.0, 300067.5},
            };
            for (int i = 0; i < rows.length; i++) {
                Row r = s.createRow(4 + i);
                for (int c = 0; c < 2; c++) {
                    Cell cell = r.createCell(c);
                    cell.setCellValue((LocalDate) rows[i][c]);
                    cell.setCellStyle(date);
                }
                r.createCell(2).setCellValue((String) rows[i][2]);
                r.createCell(3).setCellValue((Double) rows[i][3]);
                r.createCell(4).setCellValue((Double) rows[i][4]);
            }
            Row c = s.createRow(8);
            c.createCell(2).setCellValue("Closing Balance");
            c.createCell(4).setCellValue(300067.5);
            wb.write(out);
            return out.toByteArray();
        }
    }
}
