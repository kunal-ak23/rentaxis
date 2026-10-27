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
                 Set<LocalDate> unproven, Set<Integer> arrived, int moved, List<String> wrongTakes) { }

    private static Books importStatement(List<List<Bank>> files) {
        return importFiles(files.stream().map(FileSpec::plain).toList());
    }

    /** A statement file: its lines, and whether it carries explicit opening/closing balance rows. */
    /**
     * A statement file: its lines, the dates of its Opening- and Closing-balance rows
     * (null: none), and the day it was imported (which proves nothing: final review).
     * Whether the rows prove anything is the service's own decision (review P2-4).
     */
    record FileSpec(List<Bank> lines, LocalDate openingRowDate, LocalDate closingRowDate, LocalDate importDate) {
        static FileSpec plain(List<Bank> lines) {
            return new FileSpec(lines, null, null, null);
        }

        boolean openingProves() {
            return !lines.isEmpty() && StatementMapper.openingProves(openingRowDate, lines.get(0).date());
        }

        boolean closingProves() {
            return !lines.isEmpty() && StatementMapper.closingProves(closingRowDate, lines.get(lines.size() - 1).date());
        }
    }

    private static Books importFiles(List<FileSpec> specs) {
        Map<LocalDate, List<BankStatementImportService.StoredLine>> stored = new TreeMap<>();
        Map<LocalDate, BankStatementImportService.DayState> states = new HashMap<>();
        Set<Integer> arrived = new HashSet<>();
        int moved = 0;
        // S25-01: which bank line (index) each stored hash is, to check what a moved balance took.
        Map<String, Integer> identity = new HashMap<>();
        List<String> wrongTakes = new ArrayList<>();
        for (FileSpec spec : specs) {
            List<Bank> f = spec.lines();
            if (f.isEmpty() || !acceptedRows(f)) continue;
            f.forEach(l -> arrived.add(l.index()));
            List<StatementMapper.Row> rows = f.stream().map(Bank::row).toList();
            BankStatementImportService.Matched m = BankStatementImportService.match(BANK, rows, stored,
                    spec.openingProves(), spec.closingProves());
            // S25-01: lines taken at a moved balance carry the file's balance from now on.
            moved += m.moved().size();
            Set<Integer> inFile = new HashSet<>();
            f.forEach(l -> inFile.add(l.index()));
            for (int i = 0; i < rows.size(); i++) {
                String h = m.hashes().get(i);
                if (!m.duplicates().contains(h)) {
                    identity.put(h, f.get(i).index());
                } else if (m.moved().containsKey(h) && identity.get(h) != f.get(i).index() && !inFile.contains(identity.get(h))) {
                    // Taken at a moved balance for a stored line that is another bank line, not in this file.
                    wrongTakes.add(f.get(i).date() + " row " + f.get(i).index() + " taken for stored " + identity.get(h));
                }
            }
            stored.replaceAll((d, ls) -> ls.stream().map(l -> m.moved().containsKey(l.hash())
                    ? new BankStatementImportService.StoredLine(l.hash(), l.amount(), l.description(), m.moved().get(l.hash()))
                    : l).toList());
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
                        fileDays.lastKey(), spec.openingProves(), spec.closingProves());
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
        return new Books(lines, dates, unconfirmed, unproven, arrived, moved, wrongTakes);
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
     * not held against those days (S20-02); with a day unproven, only when the
     * day's total debits and credits typed from the bank's true lines agree with
     * the stored ones (review P2-3), and then as any unconfirmed day (typed
     * closing). A first draft let a proven day go on the lines' own closing: the
     * model found an order that chained round a line missing on another day.
     * Asserted: no finalize at an opening or closing other than the bank's, and
     * no finalize with a line any accepted file brought missing from the books —
     * even when the lost lines net to nothing (S20-01).</p>
     */
    @Test
    void repeatingLinesNeverFinalizeAWrongBalance() {
        Random rnd = new Random(9704);
        int finalized = 0, refused = 0, typed = 0, derivedOk = 0, unprovenRefused = 0, provenByTotals = 0;
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
            List<BankReconciliationService.SL> sl = new ArrayList<>();
            for (int i = 0; i < books.lines().size(); i++) {
                BankStatementImportService.StoredLine l = books.lines().get(i);
                sl.add(new BankReconciliationService.SL(UUID.randomUUID(), books.dates().get(i), i + 1, l.description(), null, null,
                        l.amount(), l.balance()));
            }
            if (!books.unproven().isEmpty()) {
                // Review P2-3: the user types each unproven day's total debits and credits
                // from the paper statement — the bank's true lines — and the service compares.
                List<BankReconciliationService.SL> bank = truth.stream().map(l -> new BankReconciliationService.SL(
                        UUID.randomUUID(), l.date(), l.index(), l.description(), null, null, l.amount(), l.balance())).toList();
                boolean allAgree = books.unproven().stream().allMatch(d -> {
                    BigDecimal[] paper = BankReconciliationService.dayTotals(bank, d);
                    return BankReconciliationService.totalsAgree(BankReconciliationService.dayTotals(sl, d), paper[0], paper[1]);
                });
                if (!allAgree) {
                    unprovenRefused++;
                    continue;
                }
                provenByTotals++;
            }
            // Days the totals proved have all their lines but no known order: like every
            // unconfirmed day, they need the typed closing (and opening).
            boolean confirmed = books.unconfirmed().isEmpty();
            if (!confirmed) typed++;
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
                    String what = String.format("trial %d (%s opening): truth %s, books %s, unconfirmed %s, unproven %s, files %s", trial,
                            derived ? "derived" : "known", truth, books.lines(), books.unconfirmed(), books.unproven(),
                            files.stream().map(f -> f.stream().map(Bank::index).toList()).toList());
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
        assertThat(unprovenRefused).as("unproven days whose paper totals disagree: refused").isGreaterThan(1_000);
        assertThat(provenByTotals).as("unproven days settled by their paper totals").isGreaterThan(1_000);
        assertThat(typed).as("days left unconfirmed (typed balances)").isGreaterThan(1_000);
        assertThat(derivedOk).as("statements finalized on the lines alone").isGreaterThan(500);
        assertThat(refused).as("refused").isGreaterThan(1_000);
        assertThat(finalized).as("finalized").isGreaterThan(1_000);
    }

    /**
     * Sim r22 S22-03: the statement's last lines are in no file — the only export stopped
     * during the last line-day, or a whole later day (14/09, after an export whose Closing
     * row is dated mid-period) never arrived. The stored lines chain, every day is
     * confirmed, and the lines' own closing is not the bank's at the period end (15/09).
     * Finalize as the service decides it: on the lines' closing only when an accepted file
     * that brought lines of the last line-day runs past that day or carries a Closing row
     * dated after the period end; otherwise on the paper closing typed by the user (the
     * bank's true one). Files are stretches in any order; each may carry an honest Closing
     * row dated anywhere its window could have ended: the line's own day, a later day
     * inside the period, the period end, or after it. Required: no finalize at a closing
     * other than the bank's. A day-level proof (a Closing row dated after the line's day)
     * finalizes wrong closings in the same model, and so does counting a row dated on the
     * period end.
     */
    @Test
    void aMissingLastLineNeverFinalizesOnTheLinesClosing() {
        Random rnd = new Random(2203);
        LocalDate periodEnd = LocalDate.of(2026, 9, 15);
        int finalizedOnLines = 0, typedClosing = 0, wrongDayProof = 0, wrongOnTheEnd = 0, trailingGaps = 0, midRows = 0;
        for (int trial = 0; trial < 60_000; trial++) {
            List<Bank> truth = new ArrayList<>(statement(rnd));
            if (truth.size() < 3) continue;
            if (rnd.nextBoolean()) {
                // A late day: 14/09, or the period end itself; one or two lines.
                LocalDate late = rnd.nextBoolean() ? periodEnd.minusDays(1) : periodEnd;
                for (int k = 0, n = 1 + rnd.nextInt(2); k < n; k++) {
                    Bank last = truth.get(truth.size() - 1);
                    BigDecimal a = k == 0 ? new BigDecimal("-105.00") : new BigDecimal("20.00");
                    truth.add(new Bank(truth.size(), late, a, a.signum() < 0 ? "FEE" : "CREDIT", last.balance().add(a)));
                }
            }
            BigDecimal trueClosing = truth.get(truth.size() - 1).balance();
            int missing = rnd.nextInt(3);
            List<Bank> received = truth.subList(0, Math.max(1, truth.size() - missing));
            if (received.size() < truth.size()) trailingGaps++;
            List<FileSpec> files = new ArrayList<>();
            int at = 0;
            while (at < received.size()) {
                int end = at + 1 + rnd.nextInt(received.size() - at);
                int start = at > 0 && rnd.nextBoolean() ? Math.max(0, at - 1 - rnd.nextInt(2)) : at;
                List<Bank> f = received.subList(start, end);
                Bank last = f.get(f.size() - 1);
                // Honest rows: the window ended during the row's date, so no bank line after the file's
                // last one is dated before it (lines later on that very day, or on the next line's
                // day, a window ending mid-day misses: a row proves only the days before its date).
                LocalDate next = last.index() + 1 < truth.size() ? truth.get(last.index() + 1).date() : null;
                List<LocalDate> honest = new ArrayList<>();
                honest.add(last.date());
                for (LocalDate d = last.date().plusDays(1); !d.isAfter(periodEnd.plusDays(2)); d = d.plusDays(1)) {
                    if (next == null || !d.isAfter(next)) honest.add(d);
                }
                LocalDate row = rnd.nextInt(3) == 0 ? null : honest.get(rnd.nextInt(honest.size()));
                if (row != null && row.isAfter(last.date()) && !row.isAfter(periodEnd)) midRows++;
                files.add(new FileSpec(f, null, row, null));
                at = end;
            }
            Collections.shuffle(files, rnd);
            Books books = importFiles(files);
            if (books.lines().isEmpty() || !books.unconfirmed().isEmpty()) continue;
            List<BankReconciliationService.SL> sl = new ArrayList<>();
            for (int i = 0; i < books.lines().size(); i++) {
                BankStatementImportService.StoredLine l = books.lines().get(i);
                sl.add(new BankReconciliationService.SL(UUID.randomUUID(), books.dates().get(i), i + 1, l.description(), null, null,
                        l.amount(), l.balance()));
            }
            BigDecimal movement = books.lines().stream().map(BankStatementImportService.StoredLine::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal linesClosing = books.lines().get(books.lines().size() - 1).balance();
            LocalDate lastDay = books.dates().get(books.dates().size() - 1);
            boolean proven = false, dayProof = false, onTheEnd = false;
            for (FileSpec f : files) {
                if (f.lines().isEmpty() || !acceptedRows(f.lines()) || f.lines().stream().noneMatch(l -> l.date().equals(lastDay))) continue;
                LocalDate fileLast = f.lines().get(f.lines().size() - 1).date();
                LocalDate row = f.closingRowDate();
                proven |= fileLast.isAfter(lastDay) || (row != null && row.isAfter(periodEnd));
                dayProof |= fileLast.isAfter(lastDay) || (row != null && row.isAfter(lastDay));
                onTheEnd |= fileLast.isAfter(lastDay) || (row != null && !row.isBefore(periodEnd));
            }
            boolean chainOk = BankReconciliationService.continuity(OPENING, linesClosing, movement, sl, DAYS[0]).ok();
            boolean wrong = chainOk && linesClosing.compareTo(trueClosing) != 0;
            if (wrong && dayProof) wrongDayProof++;
            if (wrong && onTheEnd) wrongOnTheEnd++;
            if (proven) {
                if (!chainOk) continue;
                finalizedOnLines++;
                assertThat(linesClosing).as("trial %d: truth %s, books %s, files %s — finalized on the lines' closing", trial,
                        truth, books.lines(), files.stream().map(f -> f.lines().stream().map(Bank::index).toList()
                                + "/" + f.closingRowDate()).toList()).isEqualByComparingTo(trueClosing);
            } else {
                // CLOSING fails: the user types the paper's closing; CONTINUITY decides on the bank's figure.
                typedClosing++;
            }
        }
        assertThat(trailingGaps).isGreaterThan(10_000);
        assertThat(midRows).as("Closing rows dated inside the period").isGreaterThan(5_000);
        assertThat(wrongDayProof).as("a day-level proof would finalize wrong closings").isGreaterThan(100);
        assertThat(wrongOnTheEnd).as("counting a row dated on the period end would too").isGreaterThan(0);
        assertThat(finalizedOnLines).as("finalized on the lines alone (proof held)").isGreaterThan(1_000);
        assertThat(typedClosing).as("asked for the paper closing").isGreaterThan(1_000);
    }

    /**
     * Review P2-4: time-window exports cut mid-day, each printing an Opening row dated
     * its first line's day and an agreeing Closing ("Available balance") row dated its
     * last line's day — half downloaded on the day the window ends, half imported a day
     * or more later (final review: the import date proves nothing about the window) — the
     * shape that reopened S20-01 when boundary rows were trusted on the bank's word. Repeating lines, 0–2 lines lost, windows in any order and overlapping; plus
     * the statement's true start (Opening row dated the day before: a real proof) and its
     * true end (Closing row dated the day after: a real proof). Finalize
     * as the service decides (typed day totals and balances from the bank's true lines).
     * Required: no wrong opening or closing, and no line a file brought lost.
     */
    @Test
    void timeWindowExportsWithBoundaryRowsNeverHideALine() {
        Random rnd = new Random(9707);
        int finalized = 0;
        for (int trial = 0; trial < 60_000; trial++) {
            List<Bank> truth = statement(rnd);
            if (truth.size() < 4) continue;
            int drops = rnd.nextInt(3);
            List<Bank> received = new ArrayList<>(truth);
            Set<Integer> dropped = new HashSet<>();
            while (dropped.size() < drops) dropped.add(1 + rnd.nextInt(truth.size() - 2));
            received.removeIf(l -> dropped.contains(l.index()));
            List<FileSpec> files = new ArrayList<>();
            for (int f = 0, n = 2 + rnd.nextInt(4); f < n; f++) {
                int a = rnd.nextInt(received.size());
                List<Bank> w = received.subList(a, a + 1 + rnd.nextInt(received.size() - a));
                // Final review: half are imported a day or more after their window ended.
                LocalDate imported = w.get(w.size() - 1).date().plusDays(rnd.nextBoolean() ? 0 : 1 + rnd.nextInt(5));
                files.add(new FileSpec(w, w.get(0).date(), w.get(w.size() - 1).date(), imported));
            }
            // The statement's true start, its Opening row dated the day before (a real proof),
            // cut mid-day; and its true end, its Closing row dated the day after.
            // (Both reach no further than the first / from the last lost line, so they are accepted:
            // the first and the last line of the statement always arrive, as in the tests above.)
            int firstGap = 0;
            while (firstGap < received.size() && received.get(firstGap).index() == firstGap) firstGap++;
            int lastGap = received.size() - 1;
            while (lastGap > 0 && received.get(lastGap).index() - received.get(lastGap - 1).index() == 1) lastGap--;
            List<Bank> head = received.subList(0, 1 + rnd.nextInt(Math.max(1, Math.min(firstGap, received.size()))));
            files.add(new FileSpec(head, DAYS[0].minusDays(1), head.get(head.size() - 1).date(),
                    head.get(head.size() - 1).date().plusDays(rnd.nextBoolean() ? 0 : 1 + rnd.nextInt(5))));
            int z = lastGap + rnd.nextInt(received.size() - lastGap);
            files.add(new FileSpec(received.subList(z, received.size()), received.get(z).date(), DAYS[2].plusDays(1),
                    DAYS[2].plusDays(1 + rnd.nextInt(5))));
            Collections.shuffle(files, rnd);
            Books books = importFiles(files);
            if (books.lines().isEmpty()) continue;
            List<BankReconciliationService.SL> sl = new ArrayList<>();
            for (int i = 0; i < books.lines().size(); i++) {
                BankStatementImportService.StoredLine l = books.lines().get(i);
                sl.add(new BankReconciliationService.SL(UUID.randomUUID(), books.dates().get(i), i + 1, l.description(), null, null,
                        l.amount(), l.balance()));
            }
            List<BankReconciliationService.SL> bank = truth.stream().map(l -> new BankReconciliationService.SL(
                    UUID.randomUUID(), l.date(), l.index(), l.description(), null, null, l.amount(), l.balance())).toList();
            if (!books.unproven().stream().allMatch(d -> {
                BigDecimal[] paper = BankReconciliationService.dayTotals(bank, d);
                return BankReconciliationService.totalsAgree(BankReconciliationService.dayTotals(sl, d), paper[0], paper[1]);
            })) continue;
            boolean confirmed = books.unconfirmed().isEmpty();
            BigDecimal trueClosing = truth.get(truth.size() - 1).balance();
            BigDecimal closing = confirmed ? books.lines().get(books.lines().size() - 1).balance() : trueClosing;
            BigDecimal movement = books.lines().stream().map(BankStatementImportService.StoredLine::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BankStatementImportService.StoredLine first = books.lines().get(0);
            for (boolean derived : new boolean[]{true, false}) {
                BigDecimal opening = derived && confirmed ? first.balance().subtract(first.amount()) : OPENING;
                if (!BankReconciliationService.continuity(opening, closing, movement, sl, DAYS[0],
                        confirmed ? Set.of() : books.unconfirmed(), books.unconfirmed()).ok()) continue;
                finalized++;
                String what = String.format("trial %d: truth %s, books %s, unconfirmed %s", trial, truth, books.lines(),
                        books.unconfirmed());
                assertThat(opening).as(what + " — wrong opening").isEqualByComparingTo(OPENING);
                assertThat(closing).as(what + " — wrong closing").isEqualByComparingTo(trueClosing);
                Map<String, Integer> have = new HashMap<>();
                for (int i = 0; i < books.lines().size(); i++) {
                    BankStatementImportService.StoredLine l = books.lines().get(i);
                    have.merge(books.dates().get(i) + "|" + l.amount() + "|" + l.description() + "|" + l.balance(), 1, Integer::sum);
                }
                for (Bank l : truth) {
                    if (!books.arrived().contains(l.index())) continue;
                    String k = l.date() + "|" + l.amount() + "|" + l.description() + "|" + l.balance();
                    long brought = truth.stream().filter(x -> books.arrived().contains(x.index())
                            && (x.date() + "|" + x.amount() + "|" + x.description() + "|" + x.balance()).equals(k)).count();
                    assertThat((long) have.getOrDefault(k, 0)).as(what + " — a line a file brought was lost: " + k)
                            .isGreaterThanOrEqualTo(brought);
                }
            }
        }
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

    // ------------------------------------------------------------------ review P2-3: friction

    /** 91 days: lines on 70% of days (varied narrations), and a fee / reversal / fee on 5%. */
    private static List<Bank> quarter(Random rnd) {
        List<Bank> out = new ArrayList<>();
        BigDecimal b = OPENING;
        LocalDate d = LocalDate.of(2026, 7, 1);
        for (int day = 0; day < 92; day++, d = d.plusDays(1)) {
            if (rnd.nextInt(100) < 70) {
                for (int i = 0, n = 1 + rnd.nextInt(5); i < n; i++) {
                    BigDecimal a = BigDecimal.valueOf((rnd.nextInt(400) - 200) * 5L + 1).setScale(2);
                    b = b.add(a);
                    out.add(new Bank(out.size(), d, a, "TXN " + out.size(), b));
                }
            }
            if (rnd.nextInt(100) < 5) {
                BigDecimal fee = new BigDecimal("-10.50");
                for (BigDecimal a : List.of(fee, fee.negate(), fee)) {
                    b = b.add(a);
                    out.add(new Bank(out.size(), d, a, a.signum() < 0 ? "SMS ALERT CHARGES" : "SMS ALERT CHARGES REVERSAL", b));
                }
            }
        }
        return out;
    }

    private static List<Bank> between(List<Bank> lines, LocalDate from, LocalDate to) {
        return lines.stream().filter(l -> !l.date().isBefore(from) && !l.date().isAfter(to)).toList();
    }

    /** What reconciling one calendar month takes after a habit's imports. */
    enum Effort { FREE, TYPED_CLOSING, TYPED_DAY_TOTALS, BLOCKED }

    private static Map<Effort, Integer> effort(List<Bank> truth, List<FileSpec> files) {
        Books books = importFiles(files);
        Map<Effort, Integer> out = new EnumMap<>(Effort.class);
        List<BankReconciliationService.SL> bank = truth.stream().map(l -> new BankReconciliationService.SL(
                UUID.randomUUID(), l.date(), l.index(), l.description(), null, null, l.amount(), l.balance())).toList();
        List<BankReconciliationService.SL> sl = new ArrayList<>();
        for (int i = 0; i < books.lines().size(); i++) {
            BankStatementImportService.StoredLine l = books.lines().get(i);
            sl.add(new BankReconciliationService.SL(UUID.randomUUID(), books.dates().get(i), i + 1, l.description(), null, null,
                    l.amount(), l.balance()));
        }
        for (int m = 7; m <= 9; m++) {
            LocalDate from = LocalDate.of(2026, m, 1), to = from.withDayOfMonth(from.lengthOfMonth());
            Set<LocalDate> unconfirmed = new HashSet<>(), unproven = new HashSet<>();
            books.unconfirmed().stream().filter(d -> !d.isBefore(from) && !d.isAfter(to)).forEach(unconfirmed::add);
            books.unproven().stream().filter(d -> !d.isBefore(from) && !d.isAfter(to)).forEach(unproven::add);
            Effort e;
            if (unproven.stream().anyMatch(d -> {
                BigDecimal[] paper = BankReconciliationService.dayTotals(bank, d);
                return !BankReconciliationService.totalsAgree(BankReconciliationService.dayTotals(sl, d), paper[0], paper[1]);
            })) e = Effort.BLOCKED;
            else if (!unproven.isEmpty()) e = Effort.TYPED_DAY_TOTALS;
            else if (!unconfirmed.isEmpty()) e = Effort.TYPED_CLOSING;
            else e = Effort.FREE;
            out.merge(e, 1, Integer::sum);
        }
        return out;
    }

    /**
     * Review P2-3 (5): what ordinary export habits cost per monthly reconciliation,
     * over 200 random quarters each — nothing missing, so every refusal would be false.
     * The bar: no habit is ever blocked (a spanning export is never the only way out).
     * The table goes to the PR body.
     */
    @Test
    void frictionByExportHabit() {
        Map<String, java.util.function.BiFunction<List<Bank>, Random, List<FileSpec>>> habits = new LinkedHashMap<>();
        habits.put("calendar months, no overlap", (t, r) -> months(t, 0, Rows.NONE));
        habits.put("calendar months, no overlap, balance rows dated outside the range", (t, r) -> months(t, 0, Rows.OUTSIDE));
        habits.put("weekly whole days, no overlap", (t, r) -> {
            List<FileSpec> out = new ArrayList<>();
            for (LocalDate d = LocalDate.of(2026, 7, 1); !d.isAfter(LocalDate.of(2026, 9, 30)); d = d.plusDays(7)) {
                out.add(FileSpec.plain(between(t, d, d.plusDays(6))));
            }
            return out;
        });
        habits.put("daily, end of day", (t, r) -> t.stream().map(Bank::date).distinct()
                .map(d -> FileSpec.plain(between(t, d, d))).toList());
        habits.put("the same month imported twice", (t, r) -> {
            List<FileSpec> out = new ArrayList<>(months(t, 0, Rows.NONE));
            out.addAll(months(t, 0, Rows.NONE));
            return out;
        });
        habits.put("months overlapping by one whole day", (t, r) -> months(t, 1, Rows.NONE));
        habits.put("months overlapping by one whole day, balance rows dated outside the range", (t, r) -> months(t, 1, Rows.OUTSIDE));
        habits.put("months overlapping by one whole day, balance rows dated on the range's days", (t, r) -> months(t, 1, Rows.INSIDE));
        habits.put("month export cut mid-day on its last day, next export from that day", (t, r) -> {
            List<FileSpec> out = new ArrayList<>();
            int start = 0;
            for (int m = 7; m <= 9; m++) {
                LocalDate last = LocalDate.of(2026, m, 1).withDayOfMonth(LocalDate.of(2026, m, 1).lengthOfMonth());
                List<Bank> upTo = t.stream().filter(l -> !l.date().isAfter(last)).toList();
                List<Bank> lastDay = between(t, last, last);
                int cut = upTo.size() - (lastDay.isEmpty() ? 0 : r.nextInt(lastDay.size() + 1));
                if (m == 9) cut = upTo.size();
                out.add(FileSpec.plain(t.subList(start, Math.max(start, cut))));
                int dayStart = upTo.size() - lastDay.size();
                start = Math.min(dayStart, cut);
            }
            return out;
        });
        habits.put("daily download mid-day, whole day again next morning", (t, r) -> {
            List<FileSpec> out = new ArrayList<>();
            for (LocalDate d : t.stream().map(Bank::date).distinct().toList()) {
                List<Bank> day = between(t, d, d);
                out.add(FileSpec.plain(day.subList(0, r.nextInt(day.size() + 1))));
                out.add(FileSpec.plain(day));
            }
            return out;
        });
        StringBuilder table = new StringBuilder("| export habit | free | typed closing | typed day totals + closing | blocked |\n|---|---:|---:|---:|---:|\n");
        for (var h : habits.entrySet()) {
            Map<Effort, Integer> total = new EnumMap<>(Effort.class);
            Random rnd = new Random(9706);
            for (int q = 0; q < 200; q++) {
                List<Bank> truth = quarter(rnd);
                effort(truth, h.getValue().apply(truth, rnd)).forEach((k, v) -> total.merge(k, v, Integer::sum));
            }
            int n = total.values().stream().mapToInt(Integer::intValue).sum();
            java.util.function.Function<Effort, String> pct = e -> String.format("%d%%", Math.round(100.0 * total.getOrDefault(e, 0) / n));
            table.append("| ").append(h.getKey()).append(" | ").append(pct.apply(Effort.FREE)).append(" | ")
                    .append(pct.apply(Effort.TYPED_CLOSING)).append(" | ").append(pct.apply(Effort.TYPED_DAY_TOTALS)).append(" | ")
                    .append(pct.apply(Effort.BLOCKED)).append(" |\n");
            assertThat(total.getOrDefault(Effort.BLOCKED, 0)).as(h.getKey() + " blocked").isZero();
        }
        System.out.println("FRICTION TABLE\n" + table);
    }

    /** How a date-range export dates its Opening/Closing-balance rows. */
    enum Rows { NONE, OUTSIDE, INSIDE }

    /**
     * Calendar-month exports, {@code overlapDays} back into the previous month,
     * downloaded the day after the range ends (so a Closing row, when printed, proves).
     */
    private static List<FileSpec> months(List<Bank> t, int overlapDays, Rows rows) {
        List<FileSpec> out = new ArrayList<>();
        for (int m = 7; m <= 9; m++) {
            LocalDate to = LocalDate.of(2026, m, 1).withDayOfMonth(LocalDate.of(2026, m, 1).lengthOfMonth());
            LocalDate from = m == 7 ? LocalDate.of(2026, m, 1) : LocalDate.of(2026, m, 1).minusDays(overlapDays);
            List<Bank> lines = between(t, from, to);
            LocalDate openingDate = switch (rows) {
                case NONE -> null;
                case OUTSIDE -> from.minusDays(1);
                case INSIDE -> from;
            };
            LocalDate closingDate = switch (rows) {
                case NONE -> null;
                case OUTSIDE -> to.plusDays(1);
                case INSIDE -> to;
            };
            out.add(new FileSpec(lines, openingDate, closingDate, to.plusDays(1)));
        }
        return out;
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

    // ------------------------------------------------------------------ sim r25 S25-01

    /** {@code history} with {@code extra} lines inserted (by position, each dated like the line before it), re-balanced. */
    private static List<Bank> backValued(List<Bank> history, Random rnd, int extra) {
        List<Bank> out = new ArrayList<>(history);
        for (int k = 0; k < extra; k++) {
            int at = rnd.nextInt(out.size() + 1);
            LocalDate d = at == 0 ? DAYS[0] : out.get(at - 1).date();
            BigDecimal a = BigDecimal.valueOf(AMOUNTS[rnd.nextInt(AMOUNTS.length)]).setScale(2);
            out.add(at, new Bank(1000 + k, d, a, a.signum() < 0 ? "FEE" : "CREDIT", null));
        }
        List<Bank> rebalanced = new ArrayList<>();
        BigDecimal b = OPENING;
        for (Bank l : out) {
            b = b.add(l.amount());
            rebalanced.add(new Bank(l.index(), l.date(), l.amount(), l.description(), b));
        }
        return rebalanced;
    }

    private static String key(LocalDate d, BigDecimal amount, String description) {
        return d + "|" + amount + "|" + description;
    }

    /**
     * Sim r25 S25-01, as a property: between exports the bank back-values a line (adds it
     * with an earlier date), so every later balance in its later exports moves.
     * Lines repeat ("FEE"/"CREDIT", four amounts). Stretches of the old history are imported,
     * then stretches of the new one — sometimes first the whole new history in one file whose
     * boundary rows prove its first and last days — by the service's own {@code match} (moved
     * balances taken over) and {@code settle}.
     *
     * <p>The bars: finalize as the service does with the paper's opening and closing typed
     * (the CLOSING gate) and, for unproven days, the paper's day totals; whenever it passes,
     * no line any file brought is missing from the books (counted by date, amount and
     * narration; a line in both histories is one line). Lines no file brought that net to
     * nothing stay invisible to anyone, as before. And the friction S25-01 was about: when the first export after the back-value
     * holds every day whole, no line is stored twice, then or after. (A part-day export at a
     * moved balance with no shift shown on a whole day still stores an identical line as a
     * separate one — the #97 P2-2 rule — and a later whole export cannot un-store it.)</p>
     */
    @Test
    void backValuedLinesAreNeverLostOrStoredTwice() {
        Random rnd = new Random(2501);
        int finalized = 0, refused = 0, moved = 0, whole = 0;
        for (int trial = 0; trial < 40_000; trial++) {
            List<Bank> old = statement(rnd);
            if (old.size() < 3) continue;
            // One back-valued line: every later balance moves by its (non-zero) amount. (Two that
            // net to nothing move no balance; a part re-import of a settled day then takes one
            // for an identical stored line exactly as before this change — S20-03, see the PR.)
            List<Bank> now = backValued(old, rnd, 1);
            BigDecimal trueClosing = now.get(now.size() - 1).balance();
            List<FileSpec> files = new ArrayList<>();
            for (int f = 0, n = 1 + rnd.nextInt(3); f < n; f++) {
                int a = rnd.nextInt(old.size());
                files.add(FileSpec.plain(old.subList(a, a + 1 + rnd.nextInt(old.size() - a))));
            }
            // Half the time the first export after the back-value is the whole new history, with
            // Opening/Closing rows dated outside it (every day held whole); later ones are stretches.
            boolean wholeFirst = rnd.nextBoolean();
            if (wholeFirst) files.add(new FileSpec(now, DAYS[0].minusDays(1), DAYS[2].plusDays(1), null));
            // Exports after the back-value are date ranges (whole days, as a bank exports them;
            // the service cannot tell): a time-window cut re-exporting part of a settled day
            // after the bank added a line to it is outside S25-01 (see the PR).
            for (int f = 0, n = rnd.nextInt(3); f < n; f++) {
                int a = rnd.nextInt(DAYS.length), z = a + rnd.nextInt(DAYS.length - a);
                List<Bank> range = now.stream().filter(l -> !l.date().isBefore(DAYS[a]) && !l.date().isAfter(DAYS[z])).toList();
                if (!range.isEmpty()) files.add(FileSpec.plain(range));
            }
            if (!wholeFirst && rnd.nextBoolean()) files.add(FileSpec.plain(now));
            Books books = importFiles(files);
            moved += books.moved();
            if (books.lines().isEmpty()) continue;
            Set<Integer> arrived = books.arrived();
            Map<String, Integer> have = new HashMap<>(), brought = new HashMap<>();
            for (int i = 0; i < books.lines().size(); i++) {
                BankStatementImportService.StoredLine l = books.lines().get(i);
                have.merge(key(books.dates().get(i), l.amount(), l.description()), 1, Integer::sum);
            }
            for (Bank l : now) if (arrived.contains(l.index())) brought.merge(key(l.date(), l.amount(), l.description()), 1, Integer::sum);
            for (Bank l : old) {
                if (arrived.contains(l.index()) && now.stream().noneMatch(x -> x.index() == l.index())) {
                    brought.merge(key(l.date(), l.amount(), l.description()), 1, Integer::sum);
                }
            }
            String what = String.format("trial %d: old %s, now %s, files %s, books %s %s, unconfirmed %s, unproven %s", trial,
                    old, now, files.stream().map(f -> f.lines().stream().map(Bank::index).toList()).toList(), books.lines(),
                    books.dates(), books.unconfirmed(), books.unproven());
            if (wholeFirst) {
                whole++;
                brought.forEach((k, n) -> assertThat(have.getOrDefault(k, 0)).as(what + " — stored twice: " + k).isLessThanOrEqualTo(n));
                have.forEach((k, n) -> assertThat(n).as(what + " — stored twice: " + k).isLessThanOrEqualTo(brought.getOrDefault(k, 0)));
            }
            List<BankReconciliationService.SL> sl = new ArrayList<>();
            for (int i = 0; i < books.lines().size(); i++) {
                BankStatementImportService.StoredLine l = books.lines().get(i);
                sl.add(new BankReconciliationService.SL(UUID.randomUUID(), books.dates().get(i), i + 1, l.description(), null, null,
                        l.amount(), l.balance()));
            }
            if (!books.unproven().isEmpty()) {
                List<BankReconciliationService.SL> bank = now.stream().map(l -> new BankReconciliationService.SL(
                        UUID.randomUUID(), l.date(), l.index(), l.description(), null, null, l.amount(), l.balance())).toList();
                boolean allAgree = books.unproven().stream().allMatch(d -> {
                    BigDecimal[] paper = BankReconciliationService.dayTotals(bank, d);
                    return BankReconciliationService.totalsAgree(BankReconciliationService.dayTotals(sl, d), paper[0], paper[1]);
                });
                if (!allAgree) {
                    refused++;
                    continue;
                }
            }
            // The paper's opening and closing typed (the CLOSING gate, S22-03): the lines must reach them.
            boolean confirmed = books.unconfirmed().isEmpty();
            BigDecimal movement = books.lines().stream().map(BankStatementImportService.StoredLine::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            Set<LocalDate> skipped = confirmed ? Set.of() : books.unconfirmed();
            if (BankReconciliationService.continuity(OPENING, trueClosing, movement, sl, DAYS[0], skipped, books.unconfirmed()).ok()) {
                finalized++;
                brought.forEach((k, n) -> assertThat(have.getOrDefault(k, 0))
                        .as(what + " — finalized with a line a file brought lost: " + k).isGreaterThanOrEqualTo(n));
            } else {
                refused++;
            }
        }
        assertThat(whole).as("whole re-exports of the new history").isGreaterThan(10_000);
        assertThat(moved).as("lines taken at a moved balance").isGreaterThan(1_000);
        assertThat(finalized).as("finalized").isGreaterThan(1_000);
        assertThat(refused).as("refused").isGreaterThan(1_000);
    }

    /**
     * Sim r25 S25-01, the new rule's own bar on any exports — time-window cuts included, one
     * or two back-valued lines: a row taken for a stored line at a moved balance is that very
     * bank line, or the stored one's own line is in the same file (identical lines trading
     * places), or — as with the exact rule on a part day (S20-01) — its day is left unproven,
     * so the paper's day totals expose a separate line taken. Never silently. (Judging a
     * first-date row by the shift after it did lose one silently here: a line inserted right
     * after an identical one, in a file starting at it, was taken for that one.)
     */
    @Test
    void aMovedBalanceNeverTakesASeparateLine() {
        Random rnd = new Random(2502);
        int moved = 0, guarded = 0;
        List<String> silent = new ArrayList<>();
        for (int trial = 0; trial < 40_000; trial++) {
            List<Bank> old = statement(rnd);
            if (old.size() < 3) continue;
            List<Bank> now = backValued(old, rnd, 1 + rnd.nextInt(2));
            List<FileSpec> files = new ArrayList<>();
            for (List<Bank> h : List.of(old, now)) {
                for (int f = 0, n = 1 + rnd.nextInt(3); f < n; f++) {
                    int a = rnd.nextInt(h.size());
                    files.add(FileSpec.plain(h.subList(a, a + 1 + rnd.nextInt(h.size() - a))));
                }
            }
            Books books = importFiles(files);
            moved += books.moved();
            for (String w : books.wrongTakes()) {
                if (books.unproven().contains(LocalDate.parse(w.substring(0, 10)))) guarded++;
                else silent.add(String.format("trial %d: %s; old %s, now %s, files %s", trial, w, old, now,
                        files.stream().map(f -> f.lines().stream().map(Bank::index).toList()).toList()));
            }
        }
        assertThat(silent).as("taken silently (%d moved takes, %d guarded by an unproven day)", moved, guarded).isEmpty();
        assertThat(moved).as("lines taken at a moved balance").isGreaterThan(1_000);
    }

    /** S25-01 on named examples: what {@code match} takes at a moved balance, and what it never takes. */
    @Test
    void movedBalanceExamples() {
        LocalDate d1 = DAYS[0], d2 = DAYS[1], d3 = DAYS[2];
        Map<LocalDate, List<BankStatementImportService.StoredLine>> stored = new TreeMap<>();
        stored.put(d2, List.of(new BankStatementImportService.StoredLine("S1", new BigDecimal("-10.50"), "SMS", new BigDecimal("989.50")),
                new BankStatementImportService.StoredLine("S2", new BigDecimal("-10.50"), "SMS", new BigDecimal("979.00"))));
        // A whole day (d2 between d1 and d3) re-exported 20.00 lower: both identical charges taken, nothing new.
        List<StatementMapper.Row> rows = List.of(
                new StatementMapper.Row(1, d1, d1, "X", null, null, new BigDecimal("-20.00"), new BigDecimal("980.00")),
                new StatementMapper.Row(2, d2, d2, "SMS", null, null, new BigDecimal("-10.50"), new BigDecimal("969.50")),
                new StatementMapper.Row(3, d2, d2, "SMS", null, null, new BigDecimal("-10.50"), new BigDecimal("959.00")),
                new StatementMapper.Row(4, d3, d3, "Y", null, null, new BigDecimal("5.00"), new BigDecimal("964.00")));
        BankStatementImportService.Matched m = BankStatementImportService.match(BANK, rows, stored);
        assertThat(m.hashes().subList(1, 3)).containsExactly("S1", "S2");
        assertThat(m.moved()).containsOnlyKeys("S1", "S2");
        assertThat(m.moved().get("S2")).isEqualByComparingTo("959.00");
        // A third identical charge on the whole day is a new line: the count stays the bank's.
        List<StatementMapper.Row> three = List.of(rows.get(0), rows.get(1), rows.get(2),
                new StatementMapper.Row(4, d2, d2, "SMS", null, null, new BigDecimal("-10.50"), new BigDecimal("948.50")),
                new StatementMapper.Row(5, d3, d3, "Y", null, null, new BigDecimal("5.00"), new BigDecimal("953.50")));
        m = BankStatementImportService.match(BANK, three, stored);
        assertThat(m.duplicates()).containsExactlyInAnyOrder("S1", "S2");
        assertThat(m.hashes().get(3)).isNotIn("S1", "S2");
        // A different reference or value date is a different line.
        List<StatementMapper.Row> other = List.of(rows.get(0),
                new StatementMapper.Row(2, d2, d1, "SMS", null, null, new BigDecimal("-10.50"), new BigDecimal("969.50")), rows.get(3));
        assertThat(BankStatementImportService.match(BANK, other, Map.of(d2, List.of(new BankStatementImportService.StoredLine(
                "S1", new BigDecimal("-10.50"), "SMS", new BigDecimal("989.50"), d2, null)))).duplicates()).isEmpty();
        // The file's last day (part of it): a moved balance is taken only at the shift the file shows before it.
        stored.put(d3, List.of(new BankStatementImportService.StoredLine("Y1", new BigDecimal("5.00"), "Y", new BigDecimal("984.00"))));
        m = BankStatementImportService.match(BANK, rows, stored);
        assertThat(m.duplicates()).as("Y moved by the same -20.00 as d2's charges").contains("Y1");
        stored.put(d3, List.of(new BankStatementImportService.StoredLine("Y1", new BigDecimal("5.00"), "Y", new BigDecimal("990.00"))));
        m = BankStatementImportService.match(BANK, rows, stored);
        assertThat(m.duplicates()).as("Y at another shift: a separate line").doesNotContain("Y1");
        // #97 P2-2 kept: a part day with nothing shown moved — an identical line at another balance is new.
        Map<LocalDate, List<BankStatementImportService.StoredLine>> part = Map.of(d2, List.of(
                new BankStatementImportService.StoredLine("F2", new BigDecimal("-5.00"), "FEE", new BigDecimal("985.00"))));
        List<StatementMapper.Row> head = List.of(
                new StatementMapper.Row(1, d1, d1, "FEE", null, null, new BigDecimal("-5.00"), new BigDecimal("995.00")),
                new StatementMapper.Row(2, d2, d2, "FEE", null, null, new BigDecimal("-5.00"), new BigDecimal("990.00")));
        assertThat(BankStatementImportService.match(BANK, head, part).duplicates()).isEmpty();
    }
}
