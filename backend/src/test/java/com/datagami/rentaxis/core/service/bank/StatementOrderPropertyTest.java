package com.datagami.rentaxis.core.service.bank;

import com.datagami.rentaxis.api.dto.bank.BankRecDTOs;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #97 review P1-1, as a property: however a day's statement reaches the books —
 * in several files, some of them back-filling lines into a day already
 * imported — a line missing from it is never hidden from the finalize check.
 *
 * <p>Each trial draws a day of 3–9 lines from a few small amounts, so the
 * balance often passes through the same value twice (the case that defeated
 * re-ordering by balance), drops one line that has a later line after it, and
 * imports 1–4 random contiguous stretches of what is left. An import is
 * modelled as the service does it: a file whose own running balance breaks is
 * refused ({@code StatementMapper}); duplicates are skipped; the day's order is
 * {@link BankStatementImportService#dayOrder}. Files arrive in the bank's time
 * order: a file either shares a line with what is stored or lies after it (a
 * fuller re-export may reach back). The stored lines are then checked by the
 * reconciliation's own {@link BankReconciliationService#continuity} with the
 * known opening and the closing derived from the last stored line.</p>
 *
 * <p>Two things are asserted: the stored order is always the bank's own order of
 * the lines that arrived (nothing is re-ordered by guesswork), and whenever those
 * lines in the bank's order show a gap, the finalize check refuses. Lines that
 * never arrived and net to zero, or a missing last line, cannot be seen from the
 * lines by anyone (pre-#97 as well); a typed closing catches the latter.</p>
 */
class StatementOrderPropertyTest {

    static final LocalDate DAY = LocalDate.of(2026, 9, 10);
    static final BigDecimal OPENING = new BigDecimal("1000.00");
    static final int[] AMOUNTS = {-10, -5, 5, 10};

    record Line(int index, BigDecimal amount, BigDecimal balance) {
        String hash() {
            return "L" + index;
        }
    }

    private static List<Line> day(Random rnd) {
        int n = 3 + rnd.nextInt(7);
        List<Line> out = new ArrayList<>();
        BigDecimal b = OPENING;
        for (int i = 0; i < n; i++) {
            BigDecimal a = BigDecimal.valueOf(AMOUNTS[rnd.nextInt(AMOUNTS.length)]).setScale(2);
            b = b.add(a);
            out.add(new Line(i, a, b));
        }
        return out;
    }

    /** What the import would accept: a file whose own running balance holds line to line. */
    private static boolean accepted(List<Line> file) {
        for (int i = 1; i < file.size(); i++) {
            if (file.get(i - 1).balance().add(file.get(i).amount()).compareTo(file.get(i).balance()) != 0) return false;
        }
        return true;
    }

    /** Imports {@code files} in turn; returns the stored lines of the day in seq order. */
    private static List<Line> importAll(List<List<Line>> files) {
        Map<String, Line> byHash = new HashMap<>();
        List<String> stored = new ArrayList<>();
        for (List<Line> f : files) {
            if (!accepted(f)) continue;
            int maxStored = stored.stream().mapToInt(h -> byHash.get(h).index()).max().orElse(-1);
            boolean overlaps = f.stream().anyMatch(l -> stored.contains(l.hash()));
            if (!overlaps && f.get(0).index() < maxStored) continue;   // outside the model: not in time order
            f.forEach(l -> byHash.put(l.hash(), l));
            List<String> order = BankStatementImportService.dayOrder(stored, f.stream().map(Line::hash).toList());
            stored.clear();
            stored.addAll(order);
        }
        return stored.stream().map(byHash::get).toList();
    }

    private static BankRecDTOs.Check check(List<Line> stored) {
        List<BankReconciliationService.SL> sl = new ArrayList<>();
        for (int i = 0; i < stored.size(); i++) {
            Line l = stored.get(i);
            sl.add(new BankReconciliationService.SL(UUID.randomUUID(), DAY, i + 1, l.hash(), null, null, l.amount(), l.balance()));
        }
        BigDecimal movement = stored.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal closing = stored.isEmpty() ? null : stored.get(stored.size() - 1).balance();
        return BankReconciliationService.continuity(OPENING, closing, movement, sl, DAY.withDayOfMonth(1));
    }

    private static List<List<Line>> stretches(Random rnd, List<Line> received) {
        int m = 1 + rnd.nextInt(4);
        List<List<Line>> files = new ArrayList<>();
        for (int f = 0; f < m; f++) {
            int s = rnd.nextInt(received.size());
            int e = s + rnd.nextInt(received.size() - s);
            files.add(received.subList(s, e + 1));
        }
        return files;
    }

    @Test
    void aDroppedLineIsNeverHidden() {
        Random rnd = new Random(9701);
        int checked = 0, refused = 0;
        for (int trial = 0; trial < 20_000; trial++) {
            List<Line> truth = day(rnd);
            int k = rnd.nextInt(truth.size() - 1);              // a later line exists
            List<Line> received = new ArrayList<>(truth);
            received.remove(k);
            List<Line> stored = importAll(stretches(rnd, received));
            if (stored.isEmpty()) continue;
            // The import never guesses: the stored order is the bank's own order of those lines…
            List<Line> trueOrder = stored.stream().sorted(Comparator.comparingInt(Line::index)).toList();
            assertThat(stored).as("trial %d: truth %s", trial, truth).containsExactlyElementsOf(trueOrder);
            // …so whatever the bank's order shows about a gap, the finalize check shows too.
            if (check(trueOrder).ok()) continue;   // e.g. only lines netting to zero never arrived: invisible to anyone
            checked++;
            BankRecDTOs.Check c = check(stored);
            assertThat(c.ok()).as("trial %d: truth %s, stored %s — a missing line was hidden", trial, truth, stored).isFalse();
            refused++;
        }
        assertThat(checked).as("enough statements whose gap the lines can show").isGreaterThan(3_000);
        assertThat(refused).isEqualTo(checked);
    }

    /** The other side: a day back-filled by a fuller file finalizes, whatever came before it. */
    @Test
    void aDayCompletedByAFullerFileAlwaysPasses() {
        Random rnd = new Random(9702);
        for (int trial = 0; trial < 5_000; trial++) {
            List<Line> truth = day(rnd);
            List<List<Line>> files = new ArrayList<>(stretches(rnd, truth));
            files.add(truth);                                    // the full statement, last
            List<Line> stored = importAll(files);
            assertThat(stored).as("trial %d", trial).containsExactlyElementsOf(truth);
            assertThat(check(stored).ok()).as("trial %d: %s", trial, check(stored).message()).isTrue();
        }
    }
    /** The rules of {@link BankStatementImportService#dayOrder} on named examples. */
    @Test
    void dayOrderExamples() {
        // A fuller file covering the stored lines: the day takes the file's order.
        assertThat(BankStatementImportService.dayOrder(List.of("A", "C"), List.of("B", "A", "M", "C")))
                .containsExactly("B", "A", "M", "C");
        // Stored lines outside the file's stretch keep their place around it.
        assertThat(BankStatementImportService.dayOrder(List.of("P", "A", "C", "S"), List.of("A", "M", "C")))
                .containsExactly("P", "A", "M", "C", "S");
        // No line in common: the new lines follow (the review P1-1 case).
        assertThat(BankStatementImportService.dayOrder(List.of("ONE"), List.of("REFUND", "THREE")))
                .containsExactly("ONE", "REFUND", "THREE");
        // The file leaves out a stored line inside its stretch: it is not the authority; nothing moves.
        assertThat(BankStatementImportService.dayOrder(List.of("A", "B", "C"), List.of("X", "A", "C")))
                .containsExactly("A", "B", "C", "X");
    }
}
