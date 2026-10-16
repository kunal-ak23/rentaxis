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
    /** A day after imports in any order: its stored lines in seq order, and whether its order is confirmed. */
    record Day(List<Line> stored, boolean confirmed) { }

    /** Imports {@code files} in the given order, as {@link BankStatementImportService#settle} decides; none skipped. */
    private static Day importAnyOrder(List<List<Line>> files) {
        Map<String, Line> byHash = new HashMap<>();
        List<String> stored = new ArrayList<>();
        boolean confirmed = true;                                // no row: confirmed
        BankStatementImportService.DayState state = BankStatementImportService.DayState.UNKNOWN;
        for (List<Line> f : files) {
            if (!accepted(f)) continue;
            f.forEach(l -> byHash.put(l.hash(), l));
            BankStatementImportService.Settled s = BankStatementImportService.settle(stored, f.stream().map(Line::hash).toList(),
                    BankStatementImportService.Pos.SINGLE, state);
            if (s == null) continue;
            stored = new ArrayList<>(s.order());
            state = s.state();
            confirmed = s.confirmed();
        }
        return new Day(stored.stream().map(byHash::get).toList(), confirmed);
    }

    /**
     * #97 review P2-1: extracts of a day imported in <em>any</em> order, the case
     * the time-order test above leaves out. The first and the last line of the day
     * always arrive (else no one could tell), one line in between never does, and
     * 1–4 random stretches plus a stretch from the first line and one to the last
     * are imported in a shuffled order.
     *
     * <p>Finalize is modelled as the service does it: a confirmed day needs only
     * the lines (known opening, closing from the last stored line); an unconfirmed
     * day ({@code LINE_ORDER}) needs the closing typed from the paper statement,
     * which is the bank's true closing. Asserted: a finalize never goes through at
     * a closing other than the bank's.</p>
     */
    @Test
    void importsInAnyOrderNeverFinalizeAWrongClosing() {
        Random rnd = new Random(9703);
        int finalized = 0, refused = 0, typed = 0;
        for (int trial = 0; trial < 50_000; trial++) {
            List<Line> truth = day(rnd);
            BigDecimal trueClosing = truth.get(truth.size() - 1).balance();
            int k = 1 + rnd.nextInt(truth.size() - 2);           // neither the first nor the last line
            List<Line> received = new ArrayList<>(truth);
            received.remove(k);
            List<List<Line>> files = new ArrayList<>(stretches(rnd, received));
            files.add(received.subList(0, 1 + rnd.nextInt(k)));                        // from the first line
            files.add(received.subList(k + rnd.nextInt(received.size() - k), received.size())); // to the last
            Collections.shuffle(files, rnd);
            Day d = importAnyOrder(files);
            if (d.stored().isEmpty()) continue;
            if (d.confirmed()) {
                // Confirmed: the last file held the whole stored day, so it is in the bank's order.
                List<Line> trueOrder = d.stored().stream().sorted(Comparator.comparingInt(Line::index)).toList();
                assertThat(d.stored()).as("trial %d: confirmed but not in the bank's order", trial).containsExactlyElementsOf(trueOrder);
            }
            BigDecimal closing = d.confirmed() ? d.stored().get(d.stored().size() - 1).balance() : trueClosing;
            if (!d.confirmed()) typed++;
            List<BankReconciliationService.SL> sl = new ArrayList<>();
            for (int i = 0; i < d.stored().size(); i++) {
                Line l = d.stored().get(i);
                sl.add(new BankReconciliationService.SL(UUID.randomUUID(), DAY, i + 1, l.hash(), null, null, l.amount(), l.balance()));
            }
            BigDecimal movement = d.stored().stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean passes = BankReconciliationService.continuity(OPENING, closing, movement, sl, DAY.withDayOfMonth(1)).ok();
            if (passes) {
                finalized++;
                assertThat(closing).as("trial %d: truth %s, stored %s (confirmed %s) finalized at a wrong closing",
                        trial, truth, d.stored(), d.confirmed()).isEqualByComparingTo(trueClosing);
            } else {
                refused++;
            }
        }
        assertThat(refused).as("statements with a missing line refused").isGreaterThan(20_000);
        assertThat(typed).as("unconfirmed days met").isGreaterThan(1_000);
        assertThat(finalized + refused).isGreaterThan(40_000);
    }

    // ------------------------------------------------------------------ #97 review P2-2

    static final UUID BANK = UUID.fromString("00000000-0000-0000-0000-000000000097");
    static final LocalDate[] DAYS = {LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 11)};

    /** A line of a several-day statement whose narration is only "FEE" or "CREDIT": lines repeat. */
    record Bank(int index, LocalDate date, BigDecimal amount, String description, BigDecimal balance) {
        StatementMapper.Row row() {
            return new StatementMapper.Row(index, date, date, description, null, null, amount, balance);
        }
    }

    private static List<Bank> statement(Random rnd) {
        List<Bank> out = new ArrayList<>();
        BigDecimal b = OPENING;
        for (LocalDate d : DAYS) {
            int n = 1 + rnd.nextInt(5);
            for (int i = 0; i < n; i++) {
                BigDecimal a = BigDecimal.valueOf(AMOUNTS[rnd.nextInt(AMOUNTS.length)]).setScale(2);
                b = b.add(a);
                out.add(new Bank(out.size(), d, a, a.signum() < 0 ? "FEE" : "CREDIT", b));
            }
        }
        return out;
    }

    private static boolean acceptedRows(List<Bank> file) {
        for (int i = 1; i < file.size(); i++) {
            if (file.get(i - 1).balance().add(file.get(i).amount()).compareTo(file.get(i).balance()) != 0) return false;
        }
        return true;
    }

    /**
     * The bank account's stored lines (with their dates) after importing {@code files}
     * in turn, exactly as the service decides; its unconfirmed and unproven days; and
     * the bank's lines that arrived in some accepted file.
     */
    record Books(List<BankStatementImportService.StoredLine> lines, List<LocalDate> dates, Set<LocalDate> unconfirmed,
                 Set<LocalDate> unproven, Set<Integer> arrived) { }

    private static Books importStatement(List<List<Bank>> files) {
        Map<LocalDate, List<BankStatementImportService.StoredLine>> stored = new TreeMap<>();
        Map<LocalDate, BankStatementImportService.DayState> states = new HashMap<>();
        Set<Integer> arrived = new HashSet<>();
        for (List<Bank> f : files) {
            if (f.isEmpty() || !acceptedRows(f)) continue;
            f.forEach(l -> arrived.add(l.index()));
            List<StatementMapper.Row> rows = f.stream().map(Bank::row).toList();
            BankStatementImportService.Matched m = BankStatementImportService.match(BANK, rows, stored);
            TreeMap<LocalDate, List<Integer>> fileDays = new TreeMap<>();
            for (int i = 0; i < rows.size(); i++) fileDays.computeIfAbsent(rows.get(i).txnDate(), k -> new ArrayList<>()).add(i);
            for (Map.Entry<LocalDate, List<Integer>> day : fileDays.entrySet()) {
                List<BankStatementImportService.StoredLine> have = stored.getOrDefault(day.getKey(), List.of());
                Map<String, BankStatementImportService.StoredLine> byHash = new HashMap<>();
                have.forEach(l -> byHash.put(l.hash(), l));
                List<String> fileHashes = new ArrayList<>();
                for (int i : day.getValue()) {
                    String h = m.hashes().get(i);
                    fileHashes.add(h);
                    if (!m.duplicates().contains(h)) {
                        Bank l = f.get(i);
                        byHash.put(h, new BankStatementImportService.StoredLine(h, l.amount(), l.description(), l.balance()));
                    }
                }
                BankStatementImportService.Pos pos = BankStatementImportService.Pos.of(day.getKey(), fileDays.firstKey(),
                        fileDays.lastKey());
                BankStatementImportService.Settled s = BankStatementImportService.settle(
                        have.stream().map(BankStatementImportService.StoredLine::hash).toList(), fileHashes, pos,
                        states.getOrDefault(day.getKey(), BankStatementImportService.DayState.UNKNOWN));
                if (s == null) continue;
                stored.put(day.getKey(), s.order().stream().map(byHash::get).toList());
                states.put(day.getKey(), s.state());
            }
        }
        List<BankStatementImportService.StoredLine> lines = new ArrayList<>();
        List<LocalDate> dates = new ArrayList<>();
        stored.forEach((d, ls) -> ls.forEach(l -> { lines.add(l); dates.add(d); }));
        Set<LocalDate> unconfirmed = new HashSet<>(), unproven = new HashSet<>();
        states.forEach((d, st) -> {
            if (!st.confirmed()) unconfirmed.add(d);
            if (!st.confirmed() && st.unproven()) unproven.add(d);
        });
        return new Books(lines, dates, unconfirmed, unproven, arrived);
    }

    /**
     * #97 review P2-2: a statement over three days whose lines repeat — only "FEE"
     * and "CREDIT", four amounts, and a balance that keeps coming back, so whole
     * lines (date, amount, narration and running balance) recur within a day. One
     * or two lines never arrive (never the first or the last); the rest arrives as
     * random stretches, one from the first line and one to the last, imported in
     * any order, de-duplicated and settled by the service's own
     * {@code match}/{@code settle}.
     *
     * <p>Finalize as the service does it: with every day confirmed, on the lines'
     * own opening (derived, or known) and closing; with any day unconfirmed
     * ({@code LINE_ORDER}), on the bank's typed opening and closing, the chain
     * not held against those days (S20-02); with any day unproven, not at all.
     * Asserted: no finalize at an opening or closing other than the bank's, and
     * no finalize with a line any accepted file brought missing from the books —
     * even when the lost lines net to nothing (S20-01).</p>
     */
    @Test
    void repeatingLinesNeverFinalizeAWrongBalance() {
        Random rnd = new Random(9704);
        int finalized = 0, refused = 0, typed = 0, derivedOk = 0, unprovenRefused = 0;
        for (int trial = 0; trial < 60_000; trial++) {
            List<Bank> truth = statement(rnd);
            if (truth.size() < 4) continue;
            BigDecimal trueClosing = truth.get(truth.size() - 1).balance();
            int drops = rnd.nextInt(3);
            List<Bank> received = new ArrayList<>(truth);
            Set<Integer> dropped = new HashSet<>();
            while (dropped.size() < drops) dropped.add(1 + rnd.nextInt(truth.size() - 2));
            received.removeIf(l -> dropped.contains(l.index()));
            int firstGap = 0;
            while (firstGap < received.size() && received.get(firstGap).index() == firstGap) firstGap++;
            int lastGap = received.size() - 1;
            while (lastGap > 0 && received.get(lastGap).index() - received.get(lastGap - 1).index() == 1) lastGap--;
            List<List<Bank>> files = new ArrayList<>();
            for (int f = 0, n = 2 + rnd.nextInt(4); f < n; f++) {
                int a = rnd.nextInt(received.size());
                files.add(received.subList(a, a + 1 + rnd.nextInt(received.size() - a)));
            }
            files.add(received.subList(0, 1 + rnd.nextInt(Math.max(1, Math.min(firstGap, received.size())))));
            files.add(received.subList(lastGap + rnd.nextInt(received.size() - lastGap), received.size()));
            Collections.shuffle(files, rnd);
            Books books = importStatement(files);
            if (books.lines().isEmpty()) continue;
            boolean confirmed = books.unconfirmed().isEmpty();
            if (!books.unproven().isEmpty()) {
                // LINE_ORDER fails whatever is typed: a file holding the whole day is needed.
                unprovenRefused++;
                continue;
            }
            if (!confirmed) typed++;
            List<BankReconciliationService.SL> sl = new ArrayList<>();
            for (int i = 0; i < books.lines().size(); i++) {
                BankStatementImportService.StoredLine l = books.lines().get(i);
                sl.add(new BankReconciliationService.SL(UUID.randomUUID(), books.dates().get(i), i + 1, l.description(), null, null,
                        l.amount(), l.balance()));
            }
            BigDecimal movement = books.lines().stream().map(BankStatementImportService.StoredLine::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BankStatementImportService.StoredLine first = books.lines().get(0);
            BigDecimal derivedOpening = first.balance().subtract(first.amount());
            BigDecimal closing = confirmed ? books.lines().get(books.lines().size() - 1).balance() : trueClosing;
            // S20-02: with the paper's balances typed, the unconfirmed days' order is not held against them.
            Set<LocalDate> skipped = confirmed ? Set.of() : books.unconfirmed();
            for (boolean derived : new boolean[]{true, false}) {
                BigDecimal opening = derived && confirmed ? derivedOpening : OPENING;
                boolean passes = BankReconciliationService.continuity(opening, closing, movement, sl, DAYS[0], skipped,
                        books.unconfirmed()).ok();
                if (passes) {
                    finalized++;
                    if (derived && confirmed) derivedOk++;
                    String what = String.format("trial %d (%s opening): truth %s, books %s, unconfirmed %s", trial,
                            derived ? "derived" : "known", truth, books.lines(), books.unconfirmed());
                    assertThat(opening).as(what + " — wrong opening").isEqualByComparingTo(OPENING);
                    assertThat(closing).as(what + " — wrong closing").isEqualByComparingTo(trueClosing);
                    // S20-01: every bank line some accepted file brought is in the books, even when
                    // the lost ones would net to nothing. Identical lines are counted, not placed.
                    Map<String, Integer> have = new HashMap<>();
                    for (int i = 0; i < books.lines().size(); i++) {
                        BankStatementImportService.StoredLine l = books.lines().get(i);
                        have.merge(books.dates().get(i) + "|" + l.amount() + "|" + l.description() + "|" + l.balance(), 1, Integer::sum);
                    }
                    Map<String, Integer> brought = new HashMap<>();
                    for (Bank l : truth) {
                        if (books.arrived().contains(l.index())) {
                            brought.merge(l.date() + "|" + l.amount() + "|" + l.description() + "|" + l.balance(), 1, Integer::sum);
                        }
                    }
                    brought.forEach((k, n) -> assertThat(have.getOrDefault(k, 0))
                            .as(what + " — a line a file brought was silently lost: " + k).isGreaterThanOrEqualTo(n));
                } else {
                    refused++;
                }
            }
        }
        assertThat(unprovenRefused).as("unproven statements refused").isGreaterThan(1_000);
        assertThat(typed).as("days left unconfirmed (typed balances)").isGreaterThan(1_000);
        assertThat(derivedOk).as("statements finalized on the lines alone").isGreaterThan(500);
        assertThat(refused).as("refused").isGreaterThan(1_000);
        assertThat(finalized).as("finalized").isGreaterThan(1_000);
    }

    /**
     * #97 review P2-2: exact re-imports stay idempotent — a statement imported
     * again in pieces (or whole, in a different file) adds nothing, however much
     * its lines repeat.
     */
    @Test
    void reImportingTheSameLinesAddsNothing() {
        Random rnd = new Random(9705);
        for (int trial = 0; trial < 5_000; trial++) {
            List<Bank> truth = statement(rnd);
            Books once = importStatement(List.of(truth));
            List<List<Bank>> pieces = new ArrayList<>();
            for (int f = 0, n = 1 + rnd.nextInt(4); f < n; f++) {
                int a = rnd.nextInt(truth.size());
                pieces.add(truth.subList(a, a + 1 + rnd.nextInt(truth.size() - a)));
            }
            pieces.add(0, truth);
            pieces.add(truth);
            Books again = importStatement(pieces);
            assertThat(again.lines()).as("trial %d", trial).hasSize(truth.size());
            assertThat(again.lines().stream().map(BankStatementImportService.StoredLine::balance).toList())
                    .as("trial %d", trial).containsExactlyElementsOf(once.lines().stream()
                            .map(BankStatementImportService.StoredLine::balance).toList());
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
