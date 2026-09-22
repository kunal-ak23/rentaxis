package com.datagami.rentaxis.golden;

import com.datagami.rentaxis.golden.GoldenLedgerFixture.GoldenRecognitionRow;
import com.datagami.rentaxis.golden.GoldenLedgerFixture.GoldenRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The loader is the one thing in the golden harness that must never be interesting:
 * a replay failure has to mean the ledger is wrong, not that the fixture was read wrong.
 * These tests pin the format and, above all, the complaints.
 */
class GoldenLedgerFixtureTest {

    private static final String LEDGER = "golden/sample-ledger.csv";
    private static final String RECOGNITION = "golden/sample-recognition.csv";

    @Test
    void readsEveryDataLineAndSkipsCommentsBlanksAndTheHeader() {
        List<GoldenRow> rows = GoldenLedgerFixture.ledger(LEDGER);

        assertThat(rows).hasSize(8);
        assertThat(rows.get(0)).isEqualTo(new GoldenRow("Rent Receivable Sample Tower",
                LocalDate.of(2025, 8, 28), "TCO", "Advance Rent Sample Tower", "",
                new BigDecimal("12000.00"), new BigDecimal("0.00"), new BigDecimal("12000.00")));
    }

    /** The export's Remarks cell is blank on contract rows; an empty field must stay empty, not null. */
    @Test
    void anEmptyNarrationFieldLoadsAsAnEmptyString() {
        assertThat(GoldenLedgerFixture.ledger(LEDGER).get(0).narration()).isEmpty();
    }

    /** A credit balance is the export's "Cr", transcribed as a negative number. */
    @Test
    void balancesAreSignedDebitPositive() {
        assertThat(GoldenLedgerFixture.ledger(LEDGER).get(2).balance()).isEqualByComparingTo("-12000.00");
    }

    /**
     * G-7: the loader splits on a comma and the product's own narrations carry an en dash,
     * never a comma. If that ever changes, this row is the first thing to break.
     */
    @Test
    void anEnDashNarrationSurvivesTheCommaSplitVerbatim() {
        assertThat(GoldenLedgerFixture.ledger(LEDGER))
                .extracting(GoldenRow::narration)
                .contains("Advance rent adjustment – Sep 2025");
    }

    @Test
    void groupsByAccountInFirstAppearanceOrderKeepingEachAccountsOwnRowOrder() {
        Map<String, List<GoldenRow>> byAccount = GoldenLedgerFixture.ledgerByAccount(LEDGER);

        assertThat(byAccount.keySet()).containsExactly("Rent Receivable Sample Tower",
                "Advance Rent Sample Tower", "PDC Receivable Sample Tower", "Rental Income Sample Tower");
        assertThat(byAccount.get("Rent Receivable Sample Tower")).extracting(GoldenRow::balance)
                .containsExactly(new BigDecimal("12000.00"), new BigDecimal("9000.00"), new BigDecimal("6000.00"));
    }

    /** The report total is the acceptance figure; a fixture that does not balance is a bad transcription. */
    @Test
    void reportTotalIsTheDebitColumnAndTheCreditColumnEqualsIt() {
        List<GoldenRow> rows = GoldenLedgerFixture.ledger(LEDGER);

        assertThat(GoldenLedgerFixture.reportTotal(rows)).isEqualByComparingTo("19000.00");
        assertThat(GoldenLedgerFixture.creditTotal(rows)).isEqualByComparingTo(GoldenLedgerFixture.reportTotal(rows));
    }

    @Test
    void readsARecognitionSchedule() {
        List<GoldenRecognitionRow> rows = GoldenLedgerFixture.recognition(RECOGNITION);

        assertThat(rows).hasSize(3);
        assertThat(rows.get(2)).isEqualTo(new GoldenRecognitionRow(
                LocalDate.of(2025, 11, 1), LocalDate.of(2025, 11, 4), 4, new BigDecimal("300.00")));
    }

    /** G-7: a dropped column shifts every field after it, so the loader must name the line. */
    @Test
    void refusesALineWithTheWrongFieldCountByLineNumber() {
        assertThatThrownBy(() -> GoldenLedgerFixture.ledger("golden/sample-malformed-ledger.csv"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("golden/sample-malformed-ledger.csv line 5")
                .hasMessageContaining("expected 8 fields, got 7");
    }

    @Test
    void refusesAHeaderWhoseColumnsAreNotTheFormatsColumns() {
        assertThatThrownBy(() -> GoldenLedgerFixture.ledger("golden/sample-bad-header-ledger.csv"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("golden/sample-bad-header-ledger.csv line 3")
                .hasMessageContaining("expected the column header");
    }

    @Test
    void namesTheLineWhenAFieldWillNotParse() {
        assertThatThrownBy(() -> GoldenLedgerFixture.ledger("golden/sample-unparseable-ledger.csv"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("golden/sample-unparseable-ledger.csv line 4")
                .hasMessageContaining("28-08-2025");
    }

    @Test
    void saysSoWhenTheFixtureIsNotOnTheClasspath() {
        assertThatThrownBy(() -> GoldenLedgerFixture.ledger("golden/no-such-fixture.csv"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not on the test classpath");
    }
}
