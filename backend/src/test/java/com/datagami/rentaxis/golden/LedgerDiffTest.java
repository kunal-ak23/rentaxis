package com.datagami.rentaxis.golden;

import com.datagami.rentaxis.api.dto.ledger.AccountLedgerDTO;
import com.datagami.rentaxis.api.dto.ledger.LedgerRowDTO;
import com.datagami.rentaxis.golden.GoldenLedgerFixture.GoldenRecognitionRow;
import com.datagami.rentaxis.golden.GoldenLedgerFixture.GoldenRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The comparator is read by an accountant on the day a replay breaks, so both its
 * verdict and its wording are behaviour. Every failing case here asserts what the
 * message actually says.
 */
class LedgerDiffTest {

    private static final String ACCOUNT = "Rent Receivable Sample Tower";
    private static final String EN_DASH_NARRATION = "Advance rent adjustment – Sep 2025";

    // ---------------------------------------------------------------- agreement

    @Test
    void aLedgerThatMatchesTheFixtureRowForRowPasses() {
        List<GoldenRow> expected = threeRows();

        assertThatCode(() -> LedgerDiff.assertAccountMatches(ACCOUNT, expected, ledgerOf(replayOf(expected))))
                .doesNotThrowAnyException();
    }

    /** 9000 and 9000.00 are the same money; scale is a formatting choice, not a difference. */
    @Test
    void amountsCompareByValueNotByScale() {
        List<GoldenRow> expected = List.of(row("2025-08-28", "PDR", "PDC Receivable Sample Tower",
                "Rent - 1st Installment", "0.00", "9000.00", "-9000.00"));
        List<LedgerRowDTO> actual = List.of(amounts(replay(expected.get(0)), "0", "9000", "-9000"));

        assertThatCode(() -> LedgerDiff.assertAccountMatches(ACCOUNT, expected, ledgerOf(actual)))
                .doesNotThrowAnyException();
    }

    /** The product leaves particular and narration null on an unpaired, unnarrated line. */
    @Test
    void aNullParticularOrNarrationFromTheProductComparesAsBlank() {
        List<GoldenRow> expected = List.of(row("2025-08-28", "PDR", "", "", "0.00", "9000.00", "-9000.00"));
        List<LedgerRowDTO> actual = List.of(narration(particular(replay(expected.get(0)), null), null));

        assertThatCode(() -> LedgerDiff.assertAccountMatches(ACCOUNT, expected, ledgerOf(actual)))
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- the narration rule (P5-R2)

    /**
     * The export's Remarks column is blank on contract rows; our TCO keeps the charge
     * type's name instead, deliberately. That one deviation is tolerated.
     */
    @Test
    void aBlankFixtureNarrationIsAllowedOnATco() {
        List<GoldenRow> expected = List.of(row("2025-08-28", "TCO", "Security Deposit Sample Tower",
                "", "2750.00", "0.00", "2750.00"));
        List<LedgerRowDTO> actual = List.of(narration(replay(expected.get(0)), "Security Deposit"));

        assertThatCode(() -> LedgerDiff.assertAccountMatches(ACCOUNT, expected, ledgerOf(actual)))
                .doesNotThrowAnyException();
    }

    /** Tolerating a blank cell is not tolerating a wrong one. */
    @Test
    void aTcoNarrationTheFixtureDoesCarryIsStillComparedExactly() {
        List<GoldenRow> expected = List.of(row("2025-08-28", "TCO", "Security Deposit Sample Tower",
                "Deposit per contract", "2750.00", "0.00", "2750.00"));
        List<LedgerRowDTO> actual = List.of(narration(replay(expected.get(0)), "Security Deposit"));

        assertThatThrownBy(() -> LedgerDiff.assertAccountMatches(ACCOUNT, expected, ledgerOf(actual)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(">> narration")
                .hasMessageContaining("Deposit per contract")
                .hasMessageContaining("Security Deposit");
    }

    /**
     * Every other doc type compares exactly — notably CIL, whose narration is our own
     * string, en dash and all.
     */
    @Test
    void aBlankFixtureNarrationIsNotAllowedOnAnythingButATco() {
        List<GoldenRow> expected = List.of(row("2025-09-30", "CIL", "Rental Income Sample Tower",
                "", "3917.81", "0.00", "3917.81"));
        List<LedgerRowDTO> actual = List.of(narration(replay(expected.get(0)), EN_DASH_NARRATION));

        assertThatThrownBy(() -> LedgerDiff.assertAccountMatches(ACCOUNT, expected, ledgerOf(actual)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(">> narration")
                .hasMessageContaining("(blank)")
                .hasMessageContaining(EN_DASH_NARRATION);
    }

    @Test
    void aCilNarrationWithAHyphenInsteadOfAnEnDashFails() {
        List<GoldenRow> expected = List.of(row("2025-09-30", "CIL", "Rental Income Sample Tower",
                EN_DASH_NARRATION, "3917.81", "0.00", "3917.81"));
        List<LedgerRowDTO> actual = List.of(narration(replay(expected.get(0)),
                "Advance rent adjustment - Sep 2025"));

        assertThatThrownBy(() -> LedgerDiff.assertAccountMatches(ACCOUNT, expected, ledgerOf(actual)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(">> narration");
    }

    // ---------------------------------------------------------------- the failure message

    /**
     * The mutation this harness exists to catch: one amount out by a cent. The message
     * has to name the account, the date, the doc type and both amounts, or the
     * accountant cannot tell which row of their export to look at.
     */
    @Test
    void anAmountOutByACentNamesTheAccountTheDateTheDocTypeAndBothAmounts() {
        List<GoldenRow> expected = threeRows();
        List<LedgerRowDTO> actual = new ArrayList<>(replayOf(expected));
        actual.set(1, amounts(actual.get(1), "0.00", "9300.01", "-6550.01"));

        assertThatThrownBy(() -> LedgerDiff.assertAccountMatches(ACCOUNT, expected, ledgerOf(actual)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(ACCOUNT)
                .hasMessageContaining("Row 2 differs")
                .hasMessageContaining("2025-08-28")
                .hasMessageContaining("PDR")
                .hasMessageContaining("9300.00")
                .hasMessageContaining("9300.01")
                .hasMessageContaining(">> credit")
                .hasMessageContaining(">> balance");
    }

    /** One screenful: the first differing row and a little context, not the whole ledger. */
    @Test
    void theMessageStaysOnOneScreenEvenForALongLedger() {
        List<GoldenRow> expected = new ArrayList<>();
        BigDecimal running = BigDecimal.ZERO;
        for (int i = 1; i <= 40; i++) {
            running = running.add(new BigDecimal("100.00"));
            expected.add(row(String.format("2025-09-%02d", i % 28 + 1), "CIL",
                    "Rental Income Sample Tower", EN_DASH_NARRATION,
                    "100.00", "0.00", running.toPlainString()));
        }
        List<LedgerRowDTO> actual = new ArrayList<>(replayOf(expected));
        actual.set(20, particular(actual.get(20), "Advance Rent Sample Tower"));

        String message = messageOf(() -> LedgerDiff.assertAccountMatches(ACCOUNT, expected, ledgerOf(actual)));

        assertThat(message.lines()).hasSizeLessThanOrEqualTo(25);
        assertThat(message).contains("Row 21 differs").contains(">> particular");
    }

    @Test
    void aRowTheProductNeverPostedIsReportedAsAMissingRow() {
        List<GoldenRow> expected = threeRows();
        List<LedgerRowDTO> actual = replayOf(expected).subList(0, 2);

        assertThatThrownBy(() -> LedgerDiff.assertAccountMatches(ACCOUNT, expected, ledgerOf(actual)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("PACT (expected) has 3 rows; RentAxis (actual) has 2")
                .hasMessageContaining("Row 3 differs")
                .hasMessageContaining("(no row)");
    }

    @Test
    void aRowTheExportDoesNotHaveIsReportedTheSameWay() {
        List<GoldenRow> expected = threeRows().subList(0, 2);
        List<LedgerRowDTO> actual = replayOf(threeRows());

        assertThatThrownBy(() -> LedgerDiff.assertAccountMatches(ACCOUNT, expected, ledgerOf(actual)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("PACT (expected) has 2 rows; RentAxis (actual) has 3")
                .hasMessageContaining("(no row)");
    }

    @Test
    void aParticularNamingTheWrongCounterAccountIsNamed() {
        List<GoldenRow> expected = threeRows();
        List<LedgerRowDTO> actual = new ArrayList<>(replayOf(expected));
        actual.set(0, particular(actual.get(0), "Advance Rent Sample Tower / Admin Fee Sample Tower"));

        assertThatThrownBy(() -> LedgerDiff.assertAccountMatches(ACCOUNT, expected, ledgerOf(actual)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(">> particular")
                .hasMessageContaining("Advance Rent Sample Tower / Admin Fee Sample Tower");
    }

    /** Every row can match and the account still start in the wrong place. */
    @Test
    void anOpeningBalanceThatShiftsTheAccountIsCaughtAfterTheRows() {
        List<GoldenRow> expected = threeRows();
        List<LedgerRowDTO> actual = replayOf(expected);
        AccountLedgerDTO shifted = new AccountLedgerDTO(UUID.randomUUID(), "105590", ACCOUNT, "ASSET",
                new BigDecimal("500.00"), actual, new BigDecimal("2750.00"), new BigDecimal("9300.00"),
                new BigDecimal("-6050.00"), false);

        assertThatThrownBy(() -> LedgerDiff.assertAccountMatches(ACCOUNT, expected, shifted))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Closing balance mismatch")
                .hasMessageContaining("-6550.00")
                .hasMessageContaining("-6050.00")
                .hasMessageContaining("500.00");
    }

    /** Handing the comparator another account's rows is a test bug, not a ledger failure. */
    @Test
    void fixtureRowsBelongingToAnotherAccountAreRefused() {
        List<GoldenRow> expected = threeRows();

        assertThatThrownBy(() -> LedgerDiff.assertAccountMatches("Advance Rent Sample Tower", expected,
                ledgerOf(replayOf(expected))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Advance Rent Sample Tower")
                .hasMessageContaining(ACCOUNT);
    }

    // ---------------------------------------------------------------- recognition

    @Test
    void aRecognitionScheduleThatMatchesTheFixturePasses() {
        List<GoldenRecognitionRow> expected = GoldenLedgerFixture.recognition("golden/sample-recognition.csv");

        assertThatCode(() -> LedgerDiff.assertRecognitionMatches(expected, slicesOf(expected)))
                .doesNotThrowAnyException();
    }

    @Test
    void aSliceWithTheWrongDayCountAndAmountIsNamedWithBothSides() {
        List<GoldenRecognitionRow> expected = GoldenLedgerFixture.recognition("golden/sample-recognition.csv");
        List<LedgerDiff.RecognitionRow> actual = new ArrayList<>(slicesOf(expected));
        actual.set(1, new LedgerDiff.RecognitionRow(LocalDate.of(2025, 10, 1), LocalDate.of(2025, 10, 31),
                30, new BigDecimal("483.87")));

        assertThatThrownBy(() -> LedgerDiff.assertRecognitionMatches(expected, actual))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Row 2 differs")
                .hasMessageContaining(">> days")
                .hasMessageContaining(">> amount")
                .hasMessageContaining("500.00")
                .hasMessageContaining("483.87");
    }

    @Test
    void aScheduleThatStopsShortIsReportedAsAMissingSlice() {
        List<GoldenRecognitionRow> expected = GoldenLedgerFixture.recognition("golden/sample-recognition.csv");

        assertThatThrownBy(() -> LedgerDiff.assertRecognitionMatches(expected, slicesOf(expected).subList(0, 2)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("fixture has 3 slices; RentAxis has 2")
                .hasMessageContaining("(no row)");
    }

    // ---------------------------------------------------------------- fixtures for these tests

    /** Three rows of one account: the contract, its first cheque registration, and the clearing. */
    private static List<GoldenRow> threeRows() {
        return List.of(
                row("2025-08-28", "TCO", "Security Deposit Sample Tower", "", "2750.00", "0.00", "2750.00"),
                row("2025-08-28", "PDR", "PDC Receivable Sample Tower", "Rent - 1st Installment",
                        "0.00", "9300.00", "-6550.00"),
                row("2025-09-03", "CRT", "Sample Bank - Sample Tower", "Rent - 1st Installment",
                        "0.00", "0.00", "-6550.00"));
    }

    private static GoldenRow row(String date, String docType, String particular, String narration,
                                 String debit, String credit, String balance) {
        return new GoldenRow(ACCOUNT, LocalDate.parse(date), docType, particular, narration,
                new BigDecimal(debit), new BigDecimal(credit), new BigDecimal(balance));
    }

    /** What a perfect replay returns for a fixture row — the baseline every mutation starts from. */
    private static LedgerRowDTO replay(GoldenRow r) {
        return new LedgerRowDTO(UUID.randomUUID(), "JV-25/0001", r.entryDate(), r.docType(), r.particular(),
                r.narration(), r.debit(), r.credit(), r.balance(), null, null, null, null, null);
    }

    private static List<LedgerRowDTO> replayOf(List<GoldenRow> rows) {
        return rows.stream().map(LedgerDiffTest::replay).toList();
    }

    private static LedgerRowDTO amounts(LedgerRowDTO r, String debit, String credit, String balance) {
        return new LedgerRowDTO(r.entryId(), r.entryNumber(), r.entryDate(), r.docType(), r.particular(),
                r.narration(), new BigDecimal(debit), new BigDecimal(credit), new BigDecimal(balance),
                r.propertyId(), r.unitId(), r.leaseId(), r.renterId(), r.chequeId());
    }

    private static LedgerRowDTO particular(LedgerRowDTO r, String particular) {
        return new LedgerRowDTO(r.entryId(), r.entryNumber(), r.entryDate(), r.docType(), particular,
                r.narration(), r.debit(), r.credit(), r.balance(),
                r.propertyId(), r.unitId(), r.leaseId(), r.renterId(), r.chequeId());
    }

    private static LedgerRowDTO narration(LedgerRowDTO r, String narration) {
        return new LedgerRowDTO(r.entryId(), r.entryNumber(), r.entryDate(), r.docType(), r.particular(),
                narration, r.debit(), r.credit(), r.balance(),
                r.propertyId(), r.unitId(), r.leaseId(), r.renterId(), r.chequeId());
    }

    private static List<LedgerDiff.RecognitionRow> slicesOf(List<GoldenRecognitionRow> rows) {
        return rows.stream().map(r -> new LedgerDiff.RecognitionRow(
                r.periodStart(), r.periodEnd(), r.days(), r.amount())).toList();
    }

    private static AccountLedgerDTO ledgerOf(List<LedgerRowDTO> rows) {
        BigDecimal dr = rows.stream().map(LedgerRowDTO::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cr = rows.stream().map(LedgerRowDTO::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal closing = rows.isEmpty() ? BigDecimal.ZERO : rows.get(rows.size() - 1).balance();
        return new AccountLedgerDTO(UUID.randomUUID(), "105590", ACCOUNT, "ASSET",
                BigDecimal.ZERO, rows, dr, cr, closing, false);
    }

    private static String messageOf(Runnable failing) {
        try {
            failing.run();
        } catch (AssertionError e) {
            return e.getMessage();
        }
        throw new AssertionError("expected the comparator to fail");
    }
}
