package com.datagami.rentaxis.core.service.cutover;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PACT trial-balance reader.
 *
 * <p>The shapes below are not invented: the client's real exports (a General
 * Ledger and a Chart of Accounts pulled from PACT) carry a banner block above the
 * table ("the organisation", then the report name), thousands separators,
 * {@code Dr}/{@code Cr} suffixes on amounts, and {@code Sub Total} / {@code Grand
 * Total} rows in the body as well as at the foot. An accountant saving the trial
 * balance as CSV gets the same furniture, so every one of those has a test here —
 * a banner line that reads as "expected 4 columns, got 1" would put three errors
 * on a perfectly good file and teach the accountant to ignore the error list.</p>
 */
class TrialBalanceCsvParserTest {

    private static TrialBalanceCsvParser.CsvParseResult parse(String csv) {
        return TrialBalanceCsvParser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parsesCodeNameDebitCredit() {
        var r = parse("""
                Account Code,Account Name,Debit,Credit
                166269,Rent Receivable - Tulip 7,15000.00,0.00
                145661,Rental Income Tulip 7,0.00,61000.00
                """);
        assertThat(r.problems()).isEmpty();
        assertThat(r.rows()).hasSize(2);
        assertThat(r.rows().get(0).code()).isEqualTo("166269");
        assertThat(r.rows().get(0).debit()).isEqualByComparingTo("15000.00");
        assertThat(r.rows().get(1).credit()).isEqualByComparingTo("61000.00");
    }

    /** PACT exports without a header when the report is saved rather than printed. */
    @Test
    void aFileWithNoHeaderRowStillParses() {
        var r = parse("166269,Rent Receivable - Tulip 7,15000.00,0.00\n");
        assertThat(r.problems()).isEmpty();
        assertThat(r.rows()).singleElement().satisfies(row ->
                assertThat(row.code()).isEqualTo("166269"));
    }

    /** PACT prints thousands separators, parentheses for credits and (AED) suffixes. */
    @Test
    void thousandsSeparatorsQuotesAndParenthesesAreUnderstood() {
        var r = parse("""
                Account Code,Account Name,Debit,Credit
                "166269","Rent Receivable - Tulip 7","1,015,000.00","0.00"
                "145661","Rental Income Tulip 7","0.00","(61,000.00)"
                """);
        assertThat(r.problems()).isEmpty();
        assertThat(r.rows().get(0).debit()).isEqualByComparingTo("1015000.00");
        assertThat(r.rows().get(1).credit()).isEqualByComparingTo("61000.00");
    }

    @Test
    void blankLinesAndTotalRowsAreSkippedNotReportedAsErrors() {
        var r = parse("""
                Account Code,Account Name,Debit,Credit
                166269,Rent Receivable - Tulip 7,15000.00,0.00

                ,,15000.00,15000.00
                """);
        assertThat(r.rows()).hasSize(1);
        assertThat(r.problems()).isEmpty();
    }

    @Test
    void anUnparseableAmountIsReportedWithItsLineNumberAndTheRowIsDropped() {
        var r = parse("""
                Account Code,Account Name,Debit,Credit
                166269,Rent Receivable,abc,0.00
                145661,Rental Income,0.00,100.00
                """);
        assertThat(r.rows()).hasSize(1);
        assertThat(r.problems()).singleElement().asString().contains("line 2").contains("abc");
    }

    /** Both sides filled is a PACT export artefact and must not become two journal lines. */
    @Test
    void aRowWithBothSidesIsNettedToOneSide() {
        var r = parse("""
                Account Code,Account Name,Debit,Credit
                166269,Rent Receivable,15000.00,3000.00
                """);
        assertThat(r.rows()).singleElement().satisfies(row -> {
            assertThat(row.debit()).isEqualByComparingTo("12000.00");
            assertThat(row.credit()).isEqualByComparingTo("0.00");
        });
    }

    // ------------------------------------------------------------------
    // shapes taken from the client's own PACT exports
    // ------------------------------------------------------------------

    /**
     * PACT writes its balances as "2,750.00Cr" / "19,000.00Dr" (see the client's
     * General Ledger export). The suffix is an explicit statement of side and wins
     * over the column it happens to sit in — a "Cr" figure parked in the debit
     * column is a credit, not a debit of the same size.
     */
    @Test
    void aDrOrCrSuffixDecidesTheSide() {
        var r = parse("""
                Account Code,Account Name,Debit,Credit
                145800,Security Deposit-Warsan,"2,750.00Cr",0.00
                145911,Rent Receivable LE BOULEVARD,"19,000.00 Dr",0.00
                125620,Advance Rent,0.00,"55,000.00 Cr"
                """);
        assertThat(r.problems()).isEmpty();
        assertThat(r.rows().get(0).credit()).isEqualByComparingTo("2750.00");
        assertThat(r.rows().get(0).debit()).isEqualByComparingTo("0.00");
        assertThat(r.rows().get(1).debit()).isEqualByComparingTo("19000.00");
        assertThat(r.rows().get(2).credit()).isEqualByComparingTo("55000.00");
    }

    /**
     * Every PACT report starts with the organisation's name and the report title,
     * each alone on its row. They are furniture, not broken data.
     */
    @Test
    void theReportBannerAboveTheTableIsNotAnError() {
        var r = parse("""
                the organisation
                Trial Balance as at 30-09-2026

                Account Code,Account Name,Debit,Credit
                166269,Rent Receivable - Tulip 7,15000.00,0.00
                """);
        assertThat(r.problems()).isEmpty();
        assertThat(r.rows()).singleElement().satisfies(row ->
                assertThat(row.code()).isEqualTo("166269"));
    }

    /** "Sub Total" and "Grand Total" rows carry figures but no account. */
    @Test
    void subTotalAndGrandTotalRowsAreSkipped() {
        var r = parse("""
                Account Code,Account Name,Debit,Credit
                166269,Rent Receivable - Tulip 7,15000.00,0.00
                Sub Total,,15000.00,0.00
                145661,Rental Income Tulip 7,0.00,15000.00
                Grand Total,,15000.00,15000.00
                """);
        assertThat(r.problems()).isEmpty();
        assertThat(r.rows()).hasSize(2);
    }

    /** Excel writes a UTF-8 BOM and CRLF line endings; neither may reach the account code. */
    @Test
    void aByteOrderMarkAndCrlfLineEndingsAreStripped() {
        var r = parse("﻿Account Code,Account Name,Debit,Credit\r\n166269,Rent Receivable,15000.00,0.00\r\n");
        assertThat(r.problems()).isEmpty();
        assertThat(r.rows()).singleElement().satisfies(row -> {
            assertThat(row.code()).isEqualTo("166269");
            assertThat(row.debit()).isEqualByComparingTo("15000.00");
        });
    }

    /** A short row that does carry a figure is real data in the wrong shape, so it is reported. */
    @Test
    void aShortRowCarryingAFigureIsReportedWithItsLineNumber() {
        var r = parse("""
                Account Code,Account Name,Debit,Credit
                166269,15000.00
                """);
        assertThat(r.rows()).isEmpty();
        assertThat(r.problems()).singleElement().asString().contains("line 2").contains("4 columns");
    }

    /** The line number travels with the row, so the service can name the file line of a duplicate. */
    @Test
    void everyRowRemembersTheLineItCameFrom() {
        var r = parse("""
                Account Code,Account Name,Debit,Credit
                166269,Rent Receivable,15000.00,0.00
                145661,Rental Income,0.00,15000.00
                """);
        assertThat(r.rows().get(0).lineNo()).isEqualTo(2);
        assertThat(r.rows().get(1).lineNo()).isEqualTo(3);
    }

    /** An em-dash or a bare hyphen is PACT's "nothing here", not a broken number. */
    @Test
    void anEmptyOrDashAmountIsZero() {
        var r = parse("""
                Account Code,Account Name,Debit,Credit
                166269,Rent Receivable,-,
                145661,Rental Income,,100.00
                """);
        assertThat(r.problems()).isEmpty();
        assertThat(r.rows().get(0).debit()).isEqualByComparingTo("0.00");
        assertThat(r.rows().get(0).credit()).isEqualByComparingTo("0.00");
        assertThat(r.rows().get(1).credit()).isEqualByComparingTo("100.00");
    }

    // ------------------------------------------------------------------
    // fix round 1 — a file wider than four columns
    // ------------------------------------------------------------------

    /**
     * Review minor 1. PACT can be asked for a trial balance with opening and closing
     * columns. Fixing debit/credit at positions 2 and 3 read *Opening* as Debit and
     * *Debit* as Credit, silently — the exact misread the short-row branch exists to
     * prevent. When a header row is there, the columns are found by name.
     */
    @Test
    void aWiderFileWithAHeaderIsReadByColumnName() {
        var r = parse("""
                Account Code,Account Name,Opening,Debit,Credit,Closing
                166269,Rent Receivable,1000.00,15000.00,0.00,16000.00
                145661,Rental Income,0.00,0.00,61000.00,61000.00
                """);
        assertThat(r.problems()).isEmpty();
        assertThat(r.rows().get(0).debit()).isEqualByComparingTo("15000.00");
        assertThat(r.rows().get(0).credit()).isEqualByComparingTo("0.00");
        assertThat(r.rows().get(1).credit()).isEqualByComparingTo("61000.00");
    }

    /** With no header there is nothing to go on, so the extra columns are reported once. */
    @Test
    void aWiderFileWithNoHeaderIsReportedOnceRatherThanMisread() {
        var r = parse("""
                166269,Rent Receivable,1000.00,15000.00,0.00,16000.00
                145661,Rental Income,0.00,0.00,61000.00,61000.00
                """);
        assertThat(r.problems()).singleElement().asString()
                .contains("6 columns").contains("code, name, debit, credit");
        assertThat(r.rows()).hasSize(2);
    }

    /** A header naming the columns in another order is still read correctly. */
    @Test
    void theDebitAndCreditColumnsAreFoundWhereverTheHeaderPutsThem() {
        var r = parse("""
                Account Name,Credit,Account Code,Debit
                Rent Receivable,0.00,166269,15000.00
                """);
        assertThat(r.problems()).isEmpty();
        assertThat(r.rows()).singleElement().satisfies(row -> {
            assertThat(row.code()).isEqualTo("166269");
            assertThat(row.name()).isEqualTo("Rent Receivable");
            assertThat(row.debit()).isEqualByComparingTo("15000.00");
        });
    }

    /** Amounts land at the scale the ledger stores, so the journal lines need no rounding pass. */
    @Test
    void amountsAreRoundedToTwoDecimalsHalfUp() {
        var r = parse("""
                Account Code,Account Name,Debit,Credit
                166269,Rent Receivable,15000.005,0.00
                """);
        assertThat(r.rows()).singleElement().satisfies(row ->
                assertThat(row.debit()).isEqualTo(new java.math.BigDecimal("15000.01")));
    }
}
