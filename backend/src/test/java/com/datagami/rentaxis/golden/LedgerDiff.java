package com.datagami.rentaxis.golden;

import com.datagami.rentaxis.api.dto.ledger.AccountLedgerDTO;
import com.datagami.rentaxis.api.dto.ledger.LedgerRowDTO;
import com.datagami.rentaxis.golden.GoldenLedgerFixture.GoldenRecognitionRow;
import com.datagami.rentaxis.golden.GoldenLedgerFixture.GoldenRow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.fail;

/**
 * Diffs a replayed ledger against a transcribed export and, when they differ, says so
 * the way an accountant reads it: the <em>first</em> differing row, both sides, field
 * by field, with the differing fields marked — then a few neighbouring rows for
 * orientation. A 60-row {@code assertThat(list).isEqualTo(list)} failure is unreadable
 * and is exactly what this replaces.
 *
 * <h2>What is compared</h2>
 * Date, doc type, particular, narration, debit, credit and running balance, row by row
 * and in order. Amounts compare by value, so 9000 and 9000.00 are the same number.
 * Journal entry numbers are <em>not</em> compared: the numbering sequence is ours, the
 * fixtures carry none.
 *
 * <h2>The narration rule (P5-R2)</h2>
 * On a {@code TCO} the export's Remarks cell is blank on every contract row, while our
 * posting keeps the charge type's name as a fallback narration — deliberately, because a
 * blank cell is worse for the reader than "Security Deposit". So for {@code TCO} only, a
 * narration matches when it is equal <em>or</em> the fixture's is blank. Every other doc
 * type compares exactly, notably {@code CIL}'s "Advance rent adjustment – Sep 2025"
 * (en dash), which is a product string we do want pinned to the character.
 */
public final class LedgerDiff {

    /** The one doc type whose blank fixture narration is tolerated — see the class javadoc. */
    private static final String TCO = "TCO";

    /** Rows of context printed either side of the differing row. */
    private static final int CONTEXT = 3;

    private LedgerDiff() {}

    /** One slice of the product's recognition schedule, adapted by the caller. */
    public record RecognitionRow(LocalDate periodStart, LocalDate periodEnd, int days, BigDecimal amount) {}

    /**
     * Asserts one account's replayed ledger equals the fixture's rows for that account.
     *
     * @param accountName the fixture's account name; every expected row must carry it
     */
    public static void assertAccountMatches(String accountName, List<GoldenRow> expected, AccountLedgerDTO actual) {
        for (GoldenRow e : expected) {
            if (!accountName.equals(e.account())) {
                throw new IllegalArgumentException("Golden rows for \"" + accountName
                        + "\" contain a row belonging to \"" + e.account() + "\"");
            }
        }
        List<LedgerRowDTO> got = actual.rows();
        int n = Math.max(expected.size(), got.size());
        for (int i = 0; i < n; i++) {
            GoldenRow e = at(expected, i);
            LedgerRowDTO a = at(got, i);
            if (e == null || a == null || !same(e, a)) {
                fail(ledgerMessage(accountName, actual, expected, got, i));
                return;
            }
        }
        if (!expected.isEmpty()) {
            BigDecimal closing = expected.get(expected.size() - 1).balance();
            if (closing.compareTo(actual.closingBalance()) != 0) {
                fail("Closing balance mismatch on account \"" + accountName + "\" (RentAxis: code "
                        + actual.accountCode() + " \"" + actual.accountName() + "\").\n"
                        + "Every row matched, so the opening balance differs.\n\n"
                        + "  expected (last row's running balance): " + plain(closing) + "\n"
                        + "  actual   (AccountLedgerDTO.closing)  : " + plain(actual.closingBalance()) + "\n"
                        + "  actual   (AccountLedgerDTO.opening)  : " + plain(actual.openingBalance()) + "\n");
            }
        }
    }

    /** Asserts the product's recognition schedule equals the per-day fixture, slice by slice. */
    public static void assertRecognitionMatches(List<GoldenRecognitionRow> expected, List<RecognitionRow> actual) {
        int n = Math.max(expected.size(), actual.size());
        for (int i = 0; i < n; i++) {
            GoldenRecognitionRow e = at(expected, i);
            RecognitionRow a = at(actual, i);
            if (e == null || a == null || !same(e, a)) {
                fail(recognitionMessage(expected, actual, i));
                return;
            }
        }
    }

    // ---------------------------------------------------------------- comparison

    private static boolean same(GoldenRow e, LedgerRowDTO a) {
        return e.entryDate().equals(a.entryDate())
                && e.docType().equals(nullToEmpty(a.docType()))
                && e.particular().equals(nullToEmpty(a.particular()))
                && narrationMatches(e, a)
                && e.debit().compareTo(a.debit()) == 0
                && e.credit().compareTo(a.credit()) == 0
                && e.balance().compareTo(a.balance()) == 0;
    }

    /** P5-R2: equal, or — on a TCO only — the export's Remarks cell was blank. */
    private static boolean narrationMatches(GoldenRow e, LedgerRowDTO a) {
        String want = nullToEmpty(e.narration());
        if (want.equals(nullToEmpty(a.narration()))) return true;
        return TCO.equals(e.docType()) && want.isBlank();
    }

    private static boolean same(GoldenRecognitionRow e, RecognitionRow a) {
        return e.periodStart().equals(a.periodStart())
                && e.periodEnd().equals(a.periodEnd())
                && e.days() == a.days()
                && e.amount().compareTo(a.amount()) == 0;
    }

    // ---------------------------------------------------------------- messages

    private static String ledgerMessage(String accountName, AccountLedgerDTO actual,
                                        List<GoldenRow> expected, List<LedgerRowDTO> got, int bad) {
        GoldenRow e = at(expected, bad);
        LedgerRowDTO a = at(got, bad);

        List<Field> fields = new ArrayList<>();
        fields.add(field("date", e == null ? null : e.entryDate().toString(), a == null ? null : a.entryDate().toString()));
        fields.add(field("doc type", e == null ? null : e.docType(), a == null ? null : nullToEmpty(a.docType())));
        fields.add(field("particular", e == null ? null : e.particular(), a == null ? null : nullToEmpty(a.particular())));
        fields.add(narrationField(e, a));
        fields.add(field("debit", e == null ? null : plain(e.debit()), a == null ? null : plain(a.debit())));
        fields.add(field("credit", e == null ? null : plain(e.credit()), a == null ? null : plain(a.credit())));
        fields.add(field("balance", e == null ? null : plain(e.balance()), a == null ? null : plain(a.balance())));

        StringBuilder sb = new StringBuilder();
        sb.append("Ledger mismatch on account \"").append(accountName).append("\" (RentAxis: code ")
                .append(actual.accountCode()).append(" \"").append(actual.accountName()).append("\").\n")
                .append("Row ").append(bad + 1).append(" differs. PACT (expected) has ").append(expected.size())
                .append(" rows; RentAxis (actual) has ").append(got.size()).append(".\n\n")
                .append(fieldTable("PACT (expected)", "RentAxis (actual)", fields))
                .append("\n").append(context(expected, bad));
        return sb.toString();
    }

    private static Field narrationField(GoldenRow e, LedgerRowDTO a) {
        String want = e == null ? null : nullToEmpty(e.narration());
        String have = a == null ? null : nullToEmpty(a.narration());
        boolean ok = e != null && a != null && narrationMatches(e, a);
        return new Field("narration", blankAware(want), blankAware(have), ok);
    }

    private static String recognitionMessage(List<GoldenRecognitionRow> expected, List<RecognitionRow> actual, int bad) {
        GoldenRecognitionRow e = at(expected, bad);
        RecognitionRow a = at(actual, bad);

        List<Field> fields = List.of(
                field("period start", e == null ? null : e.periodStart().toString(), a == null ? null : a.periodStart().toString()),
                field("period end", e == null ? null : e.periodEnd().toString(), a == null ? null : a.periodEnd().toString()),
                field("days", e == null ? null : String.valueOf(e.days()), a == null ? null : String.valueOf(a.days())),
                field("amount", e == null ? null : plain(e.amount()), a == null ? null : plain(a.amount())));

        StringBuilder sb = new StringBuilder();
        sb.append("Recognition schedule mismatch.\n")
                .append("Row ").append(bad + 1).append(" differs. The per-day fixture has ").append(expected.size())
                .append(" slices; RentAxis has ").append(actual.size()).append(".\n\n")
                .append(fieldTable("per-day fixture", "RentAxis", fields))
                .append("\n").append(recognitionContext(expected, bad));
        return sb.toString();
    }

    private record Field(String label, String expected, String actual, boolean same) {}

    private static Field field(String label, String expected, String actual) {
        return new Field(label, expected, actual, expected != null && expected.equals(actual));
    }

    private static String fieldTable(String leftHeading, String rightHeading, List<Field> fields) {
        int left = leftHeading.length();
        for (Field f : fields) left = Math.max(left, text(f.expected()).length());
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("   %-12s %-" + left + "s   %s%n", "field", leftHeading, rightHeading));
        for (Field f : fields) {
            sb.append(String.format("%s %-12s %-" + left + "s   %s%n",
                    f.same() ? "  " : ">>", f.label(), text(f.expected()), text(f.actual())));
        }
        return sb.toString();
    }

    /** A window of the expected rows around the differing one, so the reader can place it. */
    private static String context(List<GoldenRow> expected, int bad) {
        if (expected.isEmpty()) return "PACT (expected) has no rows for this account.\n";
        int from = Math.max(0, bad - CONTEXT);
        int to = Math.min(expected.size() - 1, bad + CONTEXT);
        StringBuilder sb = new StringBuilder("PACT rows ").append(from + 1).append("-").append(to + 1)
                .append(" of ").append(expected.size()).append(", for orientation (>> is row ")
                .append(bad + 1).append("):\n")
                .append(String.format("   %4s %-10s %-4s %-30s %12s %12s %13s%n",
                        "#", "date", "doc", "particular", "debit", "credit", "balance"));
        for (int i = from; i <= to; i++) {
            GoldenRow r = expected.get(i);
            sb.append(String.format("%s %4d %-10s %-4s %-30s %12s %12s %13s%n",
                    i == bad ? ">>" : "  ", i + 1, r.entryDate(), r.docType(), trunc(r.particular(), 30),
                    plain(r.debit()), plain(r.credit()), plain(r.balance())));
        }
        return sb.toString();
    }

    private static String recognitionContext(List<GoldenRecognitionRow> expected, int bad) {
        if (expected.isEmpty()) return "The per-day fixture has no slices.\n";
        int from = Math.max(0, bad - CONTEXT);
        int to = Math.min(expected.size() - 1, bad + CONTEXT);
        StringBuilder sb = new StringBuilder("Fixture slices ").append(from + 1).append("-").append(to + 1)
                .append(" of ").append(expected.size()).append(", for orientation (>> is slice ")
                .append(bad + 1).append("):\n")
                .append(String.format("   %4s %-10s %-10s %5s %12s%n", "#", "from", "to", "days", "amount"));
        for (int i = from; i <= to; i++) {
            GoldenRecognitionRow r = expected.get(i);
            sb.append(String.format("%s %4d %-10s %-10s %5d %12s%n",
                    i == bad ? ">>" : "  ", i + 1, r.periodStart(), r.periodEnd(), r.days(), plain(r.amount())));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- small helpers

    private static <T> T at(List<T> list, int i) { return i < list.size() ? list.get(i) : null; }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    private static String plain(BigDecimal b) { return b == null ? "(null)" : b.toPlainString(); }

    /** A blank cell is a real value here, so it is printed as something the eye can see. */
    private static String blankAware(String s) { return s == null ? null : s.isEmpty() ? "(blank)" : s; }

    private static String text(String s) { return s == null ? "(no row)" : s; }

    private static String trunc(String s, int max) { return s.length() <= max ? s : s.substring(0, max - 1) + "…"; }
}
