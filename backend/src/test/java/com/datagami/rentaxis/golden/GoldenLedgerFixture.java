package com.datagami.rentaxis.golden;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads a general-ledger export transcribed as CSV — the "golden" side of the replay
 * tests. The fixtures are the client's own numbers; the loader deliberately does
 * nothing clever, so a failure always points at the product rather than at the parser.
 *
 * <h2>File format</h2>
 * <pre>
 * # Lines starting with '#' are comments: provenance, the deviations the fixture
 * # encodes, and the report total. Blank lines are skipped.
 * account,entry_date,doc_type,particular,narration,debit,credit,balance
 * Rent Receivable Sample Tower,2025-08-28,TCO,Advance Rent Sample Tower,,55000.00,0.00,55000.00
 * </pre>
 * The first non-comment, non-blank line is the column header and must be exactly
 * {@link #LEDGER_HEADER} (or {@link #RECOGNITION_HEADER} for a recognition schedule),
 * so a fixture written against a reordered format fails loudly instead of mapping
 * columns silently onto the wrong fields.
 *
 * <p>Row semantics:</p>
 * <ul>
 *   <li>{@code entry_date} is ISO {@code yyyy-MM-dd}.</li>
 *   <li>{@code doc_type} is a {@code JournalDocType} name (TCO, PDR, CRT, CBR, CIL …).</li>
 *   <li>{@code particular} is the counter account the row faces — one per row, as the
 *       export prints it, not the whole other side of the entry.</li>
 *   <li>{@code narration} is the export's Remarks cell; an empty field means blank.</li>
 *   <li>{@code debit} / {@code credit} are plain decimals, one of them 0.00.</li>
 *   <li>{@code balance} is the running balance after the row, signed debit-positive
 *       (negative = the export's {@code Cr}).</li>
 *   <li>Journal entry numbers are deliberately absent: the sequence is ours, not the
 *       source system's, and the comparator never compares them.</li>
 * </ul>
 *
 * <p>Fields never contain a comma or a quote — account names, doc types, narrations
 * and decimals only — so a plain split is correct and a CSV library is not. The
 * narrations do contain an en dash ("Advance rent adjustment – Sep 2025"), which is
 * why the file is read as UTF-8 and compared verbatim. A line whose field count is
 * wrong is refused by resource and line number rather than silently shifting columns.</p>
 *
 * <p>Fixture data is anonymised: only amounts, dates and the document sequence come
 * from the real exports.</p>
 */
public final class GoldenLedgerFixture {

    /** The only accepted column header of a ledger fixture. */
    public static final String LEDGER_HEADER =
            "account,entry_date,doc_type,particular,narration,debit,credit,balance";

    /** The only accepted column header of a recognition-schedule fixture. */
    public static final String RECOGNITION_HEADER = "period_start,period_end,days,amount";

    private GoldenLedgerFixture() {}

    /** One printed ledger row of the export. */
    public record GoldenRow(String account, LocalDate entryDate, String docType, String particular,
                            String narration, BigDecimal debit, BigDecimal credit, BigDecimal balance) {}

    /** One slice of a per-day rent recognition schedule. */
    public record GoldenRecognitionRow(LocalDate periodStart, LocalDate periodEnd, int days, BigDecimal amount) {}

    /** Every ledger row of the fixture, in file order. */
    public static List<GoldenRow> ledger(String resource) {
        List<GoldenRow> rows = new ArrayList<>();
        for (SourceLine line : dataLines(resource, LEDGER_HEADER)) {
            String[] c = split(resource, line, 8);
            rows.add(parse(resource, line, () -> new GoldenRow(
                    c[0], LocalDate.parse(c[1]), c[2], c[3], c[4],
                    new BigDecimal(c[5]), new BigDecimal(c[6]), new BigDecimal(c[7]))));
        }
        return List.copyOf(rows);
    }

    /** Account name -&gt; its rows, in the order the file lists them (which is the order we assert). */
    public static Map<String, List<GoldenRow>> ledgerByAccount(String resource) {
        Map<String, List<GoldenRow>> byAccount = new LinkedHashMap<>();
        for (GoldenRow r : ledger(resource)) {
            byAccount.computeIfAbsent(r.account(), k -> new ArrayList<>()).add(r);
        }
        byAccount.replaceAll((k, v) -> List.copyOf(v));
        return byAccount;
    }

    /** The per-day recognition schedule, in file order. */
    public static List<GoldenRecognitionRow> recognition(String resource) {
        List<GoldenRecognitionRow> rows = new ArrayList<>();
        for (SourceLine line : dataLines(resource, RECOGNITION_HEADER)) {
            String[] c = split(resource, line, 4);
            rows.add(parse(resource, line, () -> new GoldenRecognitionRow(
                    LocalDate.parse(c[0]), LocalDate.parse(c[1]),
                    Integer.parseInt(c[2]), new BigDecimal(c[3]))));
        }
        return List.copyOf(rows);
    }

    /** The export's "REPORT TOTAL" debit column. The sum of credits must equal it. */
    public static BigDecimal reportTotal(List<GoldenRow> rows) {
        return rows.stream().map(GoldenRow::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The credit column, so a test can assert the fixture itself balances before it blames the product. */
    public static BigDecimal creditTotal(List<GoldenRow> rows) {
        return rows.stream().map(GoldenRow::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** A data line and the physical line number it came from, so every complaint can name it. */
    private record SourceLine(int number, String text) {}

    private static List<SourceLine> dataLines(String resource, String expectedHeader) {
        InputStream in = GoldenLedgerFixture.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) throw new IllegalArgumentException("Golden fixture not on the test classpath: " + resource);
        List<SourceLine> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String text;
            int number = 0;
            boolean headerSeen = false;
            while ((text = r.readLine()) != null) {
                number++;
                if (text.isBlank() || text.startsWith("#")) continue;
                if (!headerSeen) {
                    headerSeen = true;
                    if (!expectedHeader.equals(text.strip())) {
                        throw new IllegalStateException(resource + " line " + number
                                + ": expected the column header \"" + expectedHeader + "\", got \"" + text + "\"");
                    }
                    continue;
                }
                out.add(new SourceLine(number, text));
            }
            if (!headerSeen) throw new IllegalStateException(resource + ": no column header, the file is all comments");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static String[] split(String resource, SourceLine line, int expected) {
        String[] c = line.text().split(",", -1);
        if (c.length != expected) {
            throw new IllegalStateException(resource + " line " + line.number() + ": expected " + expected
                    + " fields, got " + c.length + ": " + line.text());
        }
        return c;
    }

    /** Turns a parse failure into one that says which line of which fixture is wrong. */
    private static <T> T parse(String resource, SourceLine line, java.util.function.Supplier<T> build) {
        try {
            return build.get();
        } catch (RuntimeException e) {
            throw new IllegalStateException(resource + " line " + line.number() + ": " + e.getMessage()
                    + ": " + line.text(), e);
        }
    }
}
