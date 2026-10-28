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

    /** #441 ruling A on (the service); off only to measure the friction it adds. */
    static boolean OPTION_A = true;

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
                 Set<LocalDate> unproven, Set<Integer> arrived, int moved, List<String> wrongTakes, Set<LocalDate> copies,
                 Map<String, Integer> swallowed, List<String> genuineRemoved, int marked,
                 boolean backValueSeen, Set<LocalDate> everUnproven) {
        /**
         * #441 review: whether the paper's day totals settle every unproven day, as the service
         * decides — never a day holding a kept look-alike (changeset 183): there a copy can
         * stand in for a lost line, so only a statement holding the day whole settles it.
         */
        boolean totalsSettle(List<BankReconciliationService.SL> bank, List<BankReconciliationService.SL> sl) {
            return unproven.stream().allMatch(d -> {
                if (copies.contains(d)) return false;
                BigDecimal[] paper = BankReconciliationService.dayTotals(bank, d);
                return BankReconciliationService.totalsAgree(BankReconciliationService.dayTotals(sl, d), paper[0], paper[1]);
            });
        }
    }

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
        return importFiles(specs, false);
    }

    /**
     * As above; {@code deleteShownCopies}: then the user follows the check after a current last
     * file — deletes the import holding each line that file showed as a copy (#441 review r2),
     * which removes it (a copy is held by the cut that kept it alone, as in the service).
     */
    private static Books importFiles(List<FileSpec> specs, boolean deleteShownCopies) {
        Map<LocalDate, List<BankStatementImportService.StoredLine>> stored = new TreeMap<>();
        Map<LocalDate, BankStatementImportService.DayState> states = new HashMap<>();
        Set<Integer> arrived = new HashSet<>();
        int moved = 0;
        // S25-01: which bank line (index) each stored hash is, to check what a moved balance took.
        Map<String, Integer> identity = new HashMap<>();
        List<String> wrongTakes = new ArrayList<>();
        Map<String, Integer> swallowed = new HashMap<>();
        List<String> genuineRemoved = new ArrayList<>();
        Map<String, Integer> marked = new HashMap<>();
        Set<LocalDate> everUnproven = new HashSet<>();
        int fileNo = 0;
        boolean[] backValueSeen = {false};
        // #441 r4: which file stored each line (bank_statement_lines.import_id).
        Map<String, Integer> owner = new HashMap<>();
        for (FileSpec spec : specs) {
            List<Bank> f = spec.lines();
            if (f.isEmpty() || !acceptedRows(f)) continue;
            int no = fileNo++;
            f.forEach(l -> arrived.add(l.index()));
            List<StatementMapper.Row> rows = f.stream().map(Bank::row).toList();
            BankStatementImportService.Matched m = BankStatementImportService.match(BANK, rows, stored,
                    spec.openingProves(), spec.closingProves());
            Map<LocalDate, List<BankStatementImportService.StoredLine>> before = new HashMap<>(stored);
            // S25-01: lines taken at a moved balance carry the file's balance from now on.
            moved += m.moved().size();
            // #441 ruling A: the account has shown a back-value.
            if (!m.moved().isEmpty() && OPTION_A) backValueSeen[0] = true;
            for (int i = 0; i < rows.size(); i++) if (!m.duplicates().contains(m.hashes().get(i))) owner.put(m.hashes().get(i), no);
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
                    ? new BankStatementImportService.StoredLine(l.hash(), l.amount(), l.description(), m.moved().get(l.hash()),
                            null, null, l.lookalike())
                    : l).toList());
            // #441 review r2: kept look-alikes this file proves copies are removed (none is booked here).
            Set<String> copies = BankStatementImportService.provenCopies(rows, m.hashes(), before, spec.openingProves(),
                    spec.closingProves());
            // #441 review r3: marked only (copy_proven_by); the service removes nothing at import.
            for (String h : copies) marked.put(h, no);
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
                        byHash.put(h, new BankStatementImportService.StoredLine(h, l.amount(), l.description(), l.balance(),
                                null, null, m.twinned().contains(h)));
                    }
                }
                BankStatementImportService.Pos pos = BankStatementImportService.Pos.of(day.getKey(), fileDays.firstKey(),
                        fileDays.lastKey(), spec.openingProves(), spec.closingProves());
                BankStatementImportService.DayState was = states.getOrDefault(day.getKey(), BankStatementImportService.DayState.UNKNOWN);
                BankStatementImportService.Settled s = BankStatementImportService.settle(
                        have.stream().map(BankStatementImportService.StoredLine::hash).toList(), fileHashes, pos, was,
                        fileHashes.stream().anyMatch(m.twinned()::contains),
                        BankStatementImportService.distrustWhole(backValueSeen[0], was, fileHashes.stream().anyMatch(m.moved()::containsKey))
                                || (OPTION_A && BankStatementImportService.orderContradicts(
                                        have.stream().map(BankStatementImportService.StoredLine::hash).toList(), fileHashes, owner)));
                if (s == null) {
                    // S20-03 left the day as it was: a row taken there for a stored line that is another
                    // bank line (by identity) is a file byte-identical to a re-export of the stored lines.
                    for (int i : day.getValue()) {
                        Integer prior = identity.get(m.hashes().get(i));
                        if (m.duplicates().contains(m.hashes().get(i)) && prior != null && prior != f.get(i).index()) {
                            Bank l = f.get(i);
                            swallowed.merge(l.date() + "|" + l.amount() + "|" + l.description(), 1, Integer::sum);
                        }
                    }
                    continue;
                }
                List<BankStatementImportService.StoredLine> order = s.order().stream().map(byHash::get).toList();
                if (s.state().confirmed() || (s.state().startKnown() && s.state().endKnown())) {
                    // A statement holding the day whole proved every kept look-alike a line of its own.
                    order = order.stream().map(l -> l.lookalike() ? new BankStatementImportService.StoredLine(l.hash(),
                            l.amount(), l.description(), l.balance()) : l).toList();
                }
                stored.put(day.getKey(), order);
                states.put(day.getKey(), s.state());
                if (s.state().unproven()) everUnproven.add(day.getKey());
            }
        }
        if (deleteShownCopies) {
            int lastNo = fileNo - 1;
            Set<String> gone = new HashSet<>();
            marked.forEach((h, by) -> { if (by == lastNo) gone.add(h); });
            stored.forEach((d, ls) -> ls.stream().filter(l -> gone.contains(l.hash()))
                    .forEach(l -> genuineRemoved.add(d + "|" + l.amount() + "|" + l.description())));
            stored.replaceAll((d, ls) -> ls.stream().filter(l -> !gone.contains(l.hash())).toList());
        }
        List<BankStatementImportService.StoredLine> lines = new ArrayList<>();
        List<LocalDate> dates = new ArrayList<>();
        stored.forEach((d, ls) -> ls.forEach(l -> { lines.add(l); dates.add(d); }));
        Set<LocalDate> unconfirmed = new HashSet<>(), unproven = new HashSet<>(), copies = new HashSet<>();
        states.forEach((d, st) -> {
            if (!st.confirmed()) unconfirmed.add(d);
            if (!st.confirmed() && st.unproven()) unproven.add(d);
        });
        stored.forEach((d, ls) -> {
            if (unproven.contains(d) && ls.stream().anyMatch(BankStatementImportService.StoredLine::lookalike)) copies.add(d);
        });
        return new Books(lines, dates, unconfirmed, unproven, arrived, moved, wrongTakes, copies, swallowed, genuineRemoved,
                marked.size(), backValueSeen[0], everUnproven);
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
                boolean allAgree = books.totalsSettle(bank, sl);
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
        // S25-P1 (#441 review): a part re-import that runs from a day's start no longer leaves the
        // day settled (it may end at a line the bank added in front of an identical one, its first
        // line too), so fewer trials finalize on the lines alone: 718 before S25-P1, 133 after.
        // A friction measure, not a safety bar.
        assertThat(derivedOk).as("statements finalized on the lines alone").isGreaterThan(100);
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
            if (!books.totalsSettle(bank, sl)) continue;
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
    enum Effort { FREE, TYPED_CLOSING, TYPED_DAY_TOTALS, WHOLE_DAY_STATEMENT, BLOCKED }

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
            else if (unproven.stream().anyMatch(books.copies()::contains)) e = Effort.WHOLE_DAY_STATEMENT;
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
        StringBuilder table = new StringBuilder("| export habit | free | typed closing | typed day totals + closing | a whole-day statement | blocked |\n|---|---:|---:|---:|---:|---:|\n");
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
                    .append(pct.apply(Effort.WHOLE_DAY_STATEMENT)).append(" | ").append(pct.apply(Effort.BLOCKED)).append(" |\n");
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

    /** S20-03 narrowed by S25-P1: {@link BankStatementImportService#sameLinesAgain} on named examples. */
    @Test
    void sameLinesAgainExamples() {
        var whole = new BankStatementImportService.DayState(true, false, true, true);
        var fromStart = new BankStatementImportService.DayState(true, false, true, false);
        var toEnd = new BankStatementImportService.DayState(true, false, false, true);
        List<String> day = List.of("A", "B", "C");
        // The file runs to the day's end over the stored last lines: the same lines.
        assertThat(BankStatementImportService.sameLinesAgain(day, List.of("B", "C"), BankStatementImportService.Pos.FIRST, toEnd)).isTrue();
        // #441 review: from the day's start, even the stored first line alone may be a line added
        // ahead of an identical first line.
        assertThat(BankStatementImportService.sameLinesAgain(day, List.of("A"), BankStatementImportService.Pos.LAST, fromStart)).isFalse();
        // S25-P1: from the start over more than the first line, the last row may be a line added in
        // front of an identical one; and a run inside a day held whole may end at one too.
        assertThat(BankStatementImportService.sameLinesAgain(day, List.of("A", "B"), BankStatementImportService.Pos.LAST, fromStart)).isFalse();
        assertThat(BankStatementImportService.sameLinesAgain(day, List.of("A", "B", "C"), BankStatementImportService.Pos.LAST, whole)).isFalse();
        assertThat(BankStatementImportService.sameLinesAgain(day, List.of("A", "B"), BankStatementImportService.Pos.SINGLE, whole)).isFalse();
        assertThat(BankStatementImportService.sameLinesAgain(day, List.of("B"), BankStatementImportService.Pos.SINGLE, whole)).isFalse();
        // Unproven stays unproven; an end the stored lines do not know proves nothing.
        assertThat(BankStatementImportService.sameLinesAgain(day, List.of("B", "C"), BankStatementImportService.Pos.FIRST,
                new BankStatementImportService.DayState(false, true, false, true))).isFalse();
        assertThat(BankStatementImportService.sameLinesAgain(day, List.of("B", "C"), BankStatementImportService.Pos.FIRST, fromStart)).isFalse();
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
        return backValued(history, rnd, extra, false);
    }

    /**
     * As above; {@code dayStart}: a line inserted where one day meets the next is dated, half
     * the time, like the line after it — the first line of that day (#441 review: a line added
     * at the start of a day, ahead of an identical first line).
     */
    private static List<Bank> backValued(List<Bank> history, Random rnd, int extra, boolean dayStart) {
        List<Bank> out = new ArrayList<>(history);
        for (int k = 0; k < extra; k++) {
            int at = rnd.nextInt(out.size() + 1);
            LocalDate d = at == 0 ? DAYS[0] : out.get(at - 1).date();
            if (dayStart && at > 0 && at < out.size() && !out.get(at).date().equals(d) && rnd.nextBoolean()) d = out.get(at).date();
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

    /** Whether {@code file}'s lines appear, by value (date, amount, narration, balance) and in order, within {@code whole}. */
    private static boolean inOrderIn(List<Bank> file, List<Bank> whole) {
        int j = 0;
        for (Bank f : file) {
            while (j < whole.size() && !(whole.get(j).date().equals(f.date()) && whole.get(j).amount().compareTo(f.amount()) == 0
                    && whole.get(j).description().equals(f.description()) && whole.get(j).balance().compareTo(f.balance()) == 0)) j++;
            if (j == whole.size()) return false;
            j++;
        }
        return true;
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
    /** Trials for the S25-01 properties; {@code R25_TRIALS} raises it for a long run. */
    static int r25Trials() {
        return r25Trials(40_000);
    }

    static int r25Trials(int byDefault) {
        String n = System.getenv("R25_TRIALS");
        return n == null || n.isBlank() ? byDefault : Integer.parseInt(n.trim());
    }

    @Test
    void backValuedLinesAreNeverLostOrStoredTwice() {
        Random rnd = new Random(2501);
        int finalized = 0, refused = 0, moved = 0, whole = 0, trials = r25Trials();
        for (int trial = 0; trial < trials; trial++) {
            List<Bank> old = statement(rnd);
            if (old.size() < 3) continue;
            // One or two back-valued lines: every later balance moves by their amount.
            List<Bank> now = backValued(old, rnd, 1 + rnd.nextInt(2));
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
            // Later exports are date ranges (whole days, as a bank exports them; the service
            // cannot tell). Time-window cuts of the new history are aCutReExportAfterABackValueNeverLosesALine
            // (S25-P1); aMovedBalanceNeverTakesASeparateLine covers the moved-balance rule on any cuts.
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
                boolean allAgree = books.totalsSettle(bank, sl);
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
        assertThat(whole).as("whole re-exports of the new history").isGreaterThan(trials / 4);
        assertThat(moved).as("lines taken at a moved balance").isGreaterThan(trials / 40);
        assertThat(finalized).as("finalized").isGreaterThan(trials / 40);
        assertThat(refused).as("refused").isGreaterThan(trials / 40);
    }

    /**
     * S25-P1, as a property (#441 review, ruling 3): the shape
     * {@link #backValuedLinesAreNeverLostOrStoredTwice} leaves out — after one or two
     * back-valued lines, inserted anywhere, a day's first line included, later exports are
     * time-window cuts of the new history (any stretch, part days included), sometimes after a
     * whole export of the old history settled every day. Finalize as the service does (the
     * paper's opening and closing typed; the paper's day totals for unproven days, never for a
     * day holding a kept look-alike).
     *
     * <p>Asserted: whenever it finalizes, every line a file brought is in the books — except
     * the one case no service can tell from a genuine re-export, asserted on its own: the
     * missing lines are exactly rows S20-03 took, on a day it left as it was (the file ran to
     * the day's end over the stored day's last lines), for stored lines that are other bank
     * lines — the file byte for byte a re-export of the stored lines. Only two back-valued
     * lines can do that (asserted); the count goes to the report.</p>
     */
    @Test
    void aCutReExportAfterABackValueNeverLosesALine() {
        Random rnd = new Random(2503);
        // 400,000 by default: the first loss with one back-valued line is trial 175,752 before S25-P1,
        // and 373,159 with #441 v1's day-start rule (sameLinesAgainExamples pins that one too).
        int finalized = 0, refused = 0, unprovenDays = 0, sameBytes = 0, staleProofs = 0, genuineCount = 0, removals = 0, staleMarks = 0, staleLost = 0, secondStep = 0,
                staleSilentConfirmed = 0, staleSilentTyped = 0, staleSilentTotals = 0, staleSilentLinesAlone = 0, staleVisibleTotals = 0,
                staleVisibleBalance = 0, staleSameBytes = 0, staleSilentCount = 0, backValuing = 0, totalsWithA = 0, trueStates = 0,
                totalsWithoutA = 0,
                trials = r25Trials(400_000);
        List<String> genuine = new ArrayList<>(), staleSilent = new ArrayList<>(), deadEnds = new ArrayList<>();
        List<String> lost = new ArrayList<>(), stuck = new ArrayList<>();
        for (int trial = 0; trial < trials; trial++) {
            List<Bank> old = statement(rnd);
            if (old.size() < 3) continue;
            int extra = 1 + rnd.nextInt(2);
            List<Bank> now = backValued(old, rnd, extra, true);
            BigDecimal trueClosing = now.get(now.size() - 1).balance();
            List<FileSpec> files = new ArrayList<>();
            if (rnd.nextBoolean()) files.add(new FileSpec(old, DAYS[0].minusDays(1), DAYS[2].plusDays(1), null));
            for (int f = 0, n = rnd.nextInt(3); f < n; f++) {
                int a = rnd.nextInt(old.size());
                files.add(FileSpec.plain(old.subList(a, a + 1 + rnd.nextInt(old.size() - a))));
            }
            for (int f = 0, n = 1 + rnd.nextInt(3); f < n; f++) {
                int a = rnd.nextInt(now.size());
                files.add(FileSpec.plain(now.subList(a, a + 1 + rnd.nextInt(now.size() - a))));
            }
            // #441 review r3: half the time an older whole export (from before the back-value, other
            // bytes) comes after the cuts — a candidate proof that must never remove a genuine line.
            boolean stale = rnd.nextBoolean();
            if (stale) {
                files.add(new FileSpec(old, DAYS[0].minusDays(1), DAYS[2].plusDays(1), null));
                staleProofs++;
            }
            Books books = importFiles(files);
            if (books.lines().isEmpty()) continue;
            unprovenDays += books.unproven().size();
            {
                Map<String, Integer> storedVals = new HashMap<>(), trueVals = new HashMap<>();
                for (int i = 0; i < books.lines().size(); i++) {
                    BankStatementImportService.StoredLine l = books.lines().get(i);
                    storedVals.merge(books.dates().get(i) + "|" + l.amount() + "|" + l.description() + "|" + l.balance(), 1, Integer::sum);
                }
                for (Bank l : now) trueVals.merge(l.date() + "|" + l.amount() + "|" + l.description() + "|" + l.balance(), 1, Integer::sum);
                if (storedVals.equals(trueVals)) {
                    trueStates++;
                    // A day holding a kept look-alike: the check names a statement holding it whole, exported now.
                    Books follow = books;
                    if (!books.copies().isEmpty()) {
                        List<FileSpec> plus = new ArrayList<>(files);
                        plus.add(new FileSpec(now, DAYS[0].minusDays(1), DAYS[2].plusDays(1), null));
                        follow = importFiles(plus);
                    }
                    final Books fb = follow;
                    List<BankReconciliationService.SL> tsl = new ArrayList<>(), tbank = new ArrayList<>();
                    for (int i = 0; i < fb.lines().size(); i++) {
                        BankStatementImportService.StoredLine l = fb.lines().get(i);
                        tsl.add(new BankReconciliationService.SL(UUID.randomUUID(), fb.dates().get(i), i + 1, l.description(), null,
                                null, l.amount(), l.balance()));
                    }
                    for (Bank l : now) tbank.add(new BankReconciliationService.SL(UUID.randomUUID(), l.date(), l.index(), l.description(),
                            null, null, l.amount(), l.balance()));
                    BigDecimal mv = fb.lines().stream().map(BankStatementImportService.StoredLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
                    boolean ok = fb.totalsSettle(tbank, tsl) && BankReconciliationService.continuity(OPENING, trueClosing, mv, tsl,
                            DAYS[0], fb.unconfirmed(), fb.unconfirmed()).ok();
                    if (!ok && deadEnds.size() < 3) deadEnds.add(String.format("trial %d: copies %s, unproven %s, files %s", trial,
                            fb.copies(), fb.unproven(), files.stream().map(f -> f.lines().stream().map(Bank::index).toList()).toList()));
                }
            }
            if (books.backValueSeen()) {
                // #441 ruling A's friction, on accounts that back-value: periods needing typed day totals.
                backValuing++;
                if (!books.unproven().isEmpty()) totalsWithA++;
                OPTION_A = false;
                try {
                    if (!importFiles(files).unproven().isEmpty()) totalsWithoutA++;
                } finally {
                    OPTION_A = true;
                }
            }
            // #441 review r2: no remedy dead-ends — whatever was imported, the statement holding
            // every day whole (what the check names) settles every day, with the bank's lines.
            List<FileSpec> remedied = new ArrayList<>(files);
            remedied.add(new FileSpec(now, DAYS[0].minusDays(1), DAYS[2].plusDays(1), null));
            Books after = importFiles(remedied, true);
            removals += after.genuineRemoved().size();
            {
                // #441 review r3: a removal never takes a genuine line — on a day a copy was removed
                // from, the books still hold every line of that kind a file brought (by value: lines
                // of one kind at one balance are the same line to the books).
                Map<String, Integer> haveKind = new HashMap<>(), broughtKind = new HashMap<>();
                for (int i = 0; i < after.lines().size(); i++) {
                    BankStatementImportService.StoredLine l = after.lines().get(i);
                    haveKind.merge(key(after.dates().get(i), l.amount(), l.description()), 1, Integer::sum);
                }
                for (Bank l : now) if (after.arrived().contains(l.index())) broughtKind.merge(key(l.date(), l.amount(), l.description()), 1, Integer::sum);
                for (String k : new HashSet<>(after.genuineRemoved())) {
                    if (haveKind.getOrDefault(k, 0) < broughtKind.getOrDefault(k, 0)) {
                        genuineCount++;
                        if (genuine.size() < 3) genuine.add(String.format("trial %d: %s short after a removal; old %s, now %s, files %s",
                                trial, k, old, now, files.stream().map(f -> f.lines().stream().map(Bank::index).toList()).toList()));
                    }
                }
            }
            // The stale proofs: lines an older whole export showed as copies (marked, never removed by it).
            staleMarks += books.marked();
            List<BankReconciliationService.SL> afterSl = new ArrayList<>();
            for (int i = 0; i < after.lines().size(); i++) {
                BankStatementImportService.StoredLine l = after.lines().get(i);
                afterSl.add(new BankReconciliationService.SL(UUID.randomUUID(), after.dates().get(i), i + 1, l.description(), null,
                        null, l.amount(), l.balance()));
            }
            List<BankReconciliationService.SL> nowSl = now.stream().map(l -> new BankReconciliationService.SL(
                    UUID.randomUUID(), l.date(), l.index(), l.description(), null, null, l.amount(), l.balance())).toList();
            boolean settled = after.copies().isEmpty() && after.totalsSettle(nowSl, afterSl);
            if (!settled) {
                // The check's next step there (#441 review r3): make each such day the whole
                // statement's alone — delete every other import holding its lines.
                secondStep++;
                Books alone = importFiles(List.of(new FileSpec(now, DAYS[0].minusDays(1), DAYS[2].plusDays(1), null)));
                settled = alone.unconfirmed().isEmpty() && alone.copies().isEmpty();
            }
            if (!settled) {
                if (stuck.size() < 5) stuck.add(String.format("trial %d: unconfirmed %s, copies %s after the whole statement; "
                        + "now %s, files %s, books %s %s", trial, after.unconfirmed(), after.copies(), now,
                        files.stream().map(f -> f.lines().stream().map(Bank::index).toList()).toList(), after.lines(), after.dates()));
            }
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
            List<BankReconciliationService.SL> sl = new ArrayList<>();
            for (int i = 0; i < books.lines().size(); i++) {
                BankStatementImportService.StoredLine l = books.lines().get(i);
                sl.add(new BankReconciliationService.SL(UUID.randomUUID(), books.dates().get(i), i + 1, l.description(), null, null,
                        l.amount(), l.balance()));
            }
            // #441 coordinator: after an older whole export, each loss is silent (it finalizes) or visible.
            boolean staleShort = stale && brought.entrySet().stream().anyMatch(e -> have.getOrDefault(e.getKey(), 0) < e.getValue());
            if (!books.unproven().isEmpty()) {
                List<BankReconciliationService.SL> bank = now.stream().map(l -> new BankReconciliationService.SL(
                        UUID.randomUUID(), l.date(), l.index(), l.description(), null, null, l.amount(), l.balance())).toList();
                boolean allAgree = books.totalsSettle(bank, sl);
                if (!allAgree) {
                    refused++;
                    if (staleShort) staleVisibleTotals++;
                    continue;
                }
            }
            boolean confirmed = books.unconfirmed().isEmpty();
            BigDecimal movement = books.lines().stream().map(BankStatementImportService.StoredLine::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            Set<LocalDate> skipped = confirmed ? Set.of() : books.unconfirmed();
            if (!BankReconciliationService.continuity(OPENING, trueClosing, movement, sl, DAYS[0], skipped, books.unconfirmed()).ok()) {
                refused++;
                if (staleShort) staleVisibleBalance++;
                continue;
            }
            finalized++;
            List<String> missing = brought.entrySet().stream().filter(e -> have.getOrDefault(e.getKey(), 0) < e.getValue())
                    .map(Map.Entry::getKey).toList();
            if (missing.isEmpty()) continue;
            // The same bytes as a genuine re-export: every missing line is a row S20-03 took on a day
            // it left as it was, for a stored line that is another bank line.
            Map<String, Integer> short_ = new HashMap<>();
            missing.forEach(k -> short_.put(k, brought.get(k) - have.getOrDefault(k, 0)));
            boolean identical = short_.entrySet().stream().allMatch(e -> books.swallowed().getOrDefault(e.getKey(), 0) >= e.getValue());
            if (stale) {
                // #441 r4: after an older whole export the same bar holds — the only loss accepted is
                // the byte-identical residue (S20-03 left the day as it was: below), never one a whole
                // file confirmed (an unproven day is now settled only by its paper totals).
                staleLost++;
                // Or no file shows the back-value at all: every file is, by value and in order, part of the
                // older whole export imported last (so the files are just as well explained by that export
                // being the bank's latest statement, with nothing lost) — and the lost line's day was never
                // unproven (so no whole file confirmed a day a cut had left in doubt, the r4 mechanism).
                List<Bank> lastFile = files.get(files.size() - 1).lines();
                boolean noFileShowsIt = files.stream().allMatch(fs -> inOrderIn(fs.lines(), lastFile));
                boolean neverInDoubt = short_.keySet().stream().noneMatch(k -> books.everUnproven().contains(LocalDate.parse(k.substring(0, 10))));
                if ((identical && extra == 2) || (noFileShowsIt && neverInDoubt)) {
                    staleSameBytes++;
                } else {
                    staleSilentCount++;
                    if (staleSilent.size() < 3) staleSilent.add(String.format("trial %d: lost %s; old %s, now %s, files %s; books %s %s; "
                            + "unconfirmed %s unproven %s copies %s", trial,
                            short_, old, now, files.stream().map(f -> f.lines().stream().map(Bank::index).toList()).toList(),
                            books.lines(), books.dates(), books.unconfirmed(), books.unproven(), books.copies()));
                }
                boolean onProven = missing.stream().anyMatch(k -> books.unproven().contains(LocalDate.parse(k.substring(0, 10))));
                boolean onConfirmed = missing.stream().anyMatch(k -> !books.unconfirmed().contains(LocalDate.parse(k.substring(0, 10))));
                if (onConfirmed) staleSilentConfirmed++; else if (onProven) staleSilentTotals++; else staleSilentTyped++;
                // On the lines alone: every day confirmed and the lines' own opening and closing chain.
                if (confirmed) {
                    BankStatementImportService.StoredLine f0 = books.lines().get(0), fz = books.lines().get(books.lines().size() - 1);
                    if (BankReconciliationService.continuity(f0.balance().subtract(f0.amount()), fz.balance(), movement, sl, DAYS[0],
                            Set.of(), Set.of()).ok()) staleSilentLinesAlone++;
                }
            } else if (identical && extra == 2) {
                sameBytes++;
            } else if (lost.size() < 5) {
                lost.add(String.format("trial %d: lost %s (%d back-valued); old %s, now %s, files %s, books %s %s, unconfirmed %s, "
                                + "unproven %s, copies %s, swallowed %s", trial, short_, extra, old, now,
                        files.stream().map(f -> f.lines().stream().map(Bank::index).toList()).toList(),
                        books.lines(), books.dates(), books.unconfirmed(), books.unproven(), books.copies(), books.swallowed()));
            }
        }
        System.out.printf("S25-P1 CUTS: trials %d, finalized %d, refused %d, unproven days %d, lost the same bytes as a re-export %d, "
                + "stale whole exports %d, lines shown as copies (not removed) %d, copies removed by the named remedy %d, "
                + "genuine lines removed %d, lost after an older whole export %d, remedies needing the delete-the-others step %d%n",
                trials, finalized, refused, unprovenDays, sameBytes, staleProofs, staleMarks, removals, genuineCount, staleLost, secondStep);
        assertThat(genuine).as("a genuine line removed as a copy (%d; %d stale whole exports)", genuineCount, staleProofs).isEmpty();
        System.out.printf("S25-P1 STALE SPLIT: silent %d, byte-identical to a re-export %d; states with the true lines stored %d, "
                + "dead ends %d%n", staleSilentCount, staleSameBytes, trueStates, deadEnds.size());
        System.out.printf("S25-P1 RULING A FRICTION: back-valuing accounts %d; periods needing typed day totals %d with A, %d without%n",
                backValuing, totalsWithA, totalsWithoutA);
        System.out.printf("S25-P1 STALE: lost and finalized %d (on a confirmed day %d, of which on the lines alone %d; on an unconfirmed "
                + "day with the typed closing %d; on an unproven day its totals passed %d); lost but refused %d (day totals) + %d (balance)%n",
                staleLost, staleSilentConfirmed, staleSilentLinesAlone, staleSilentTyped, staleSilentTotals, staleVisibleTotals,
                staleVisibleBalance);
        assertThat(lost).as("finalized with a line a file brought lost").isEmpty();
        assertThat(stuck).as("a day the whole statement does not settle").isEmpty();
        // The residue, on its own: two back-valued lines, a file byte-identical to a re-export.
        assertThat(sameBytes).as("lost in a file byte-identical to a re-export").isLessThanOrEqualTo(trials / 2_000);
        // #441 ruling A: after an older whole export, only the byte-identical residue may still be lost.
        assertThat(staleSilent).as("lost silently after an older whole export (%d)", staleSilentCount).isEmpty();
        assertThat(staleSameBytes).as("lost after an older whole export, byte-identical to a re-export").isLessThanOrEqualTo(trials / 1_000);
        // #441 r4 (P2): from every state whose stored lines are the bank's true ones, following the check
        // (the paper's day totals for each unproven day, the typed closing) reaches FINALIZED.
        assertThat(deadEnds).as("states with the bank's true lines stored that do not finalize (%d checked)", trueStates).isEmpty();
        assertThat(finalized).as("finalized").isGreaterThan(trials / 40);
        assertThat(refused).as("refused").isGreaterThan(trials / 40);
    }

    /**
     * Sim r25 S25-01, the new rule's own bar on any exports — time-window cuts included, one
     * or two back-valued lines: a row taken for a stored line at a moved balance is that very
     * bank line, or the stored one's own line is in the same file (identical lines trading
     * places), or — as with the exact rule on a part day (S20-01) — its day is left unproven,
     * so the paper's day totals expose a separate line taken. Never silently. (Moved takes on
     * a file's first or last date lost lines silently here — at 1,000,000 trials on the last
     * date: a line inserted in front of an identical one, the export cut right after it — so
     * moved balances are taken only on days the file holds whole.)
     */
    @Test
    void aMovedBalanceNeverTakesASeparateLine() {
        Random rnd = new Random(2502);
        int moved = 0, guarded = 0, trials = r25Trials();
        List<String> silent = new ArrayList<>();
        for (int trial = 0; trial < trials; trial++) {
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
        assertThat(moved).as("lines taken at a moved balance").isGreaterThan(trials / 40);
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
        // The file's last day (part of it): a moved balance is never taken there — it may be a line
        // the bank inserted in front of an identical one; the row is kept and flagged (its day unproven).
        stored.put(d3, List.of(new BankStatementImportService.StoredLine("Y1", new BigDecimal("5.00"), "Y", new BigDecimal("984.00"))));
        m = BankStatementImportService.match(BANK, rows, stored);
        assertThat(m.duplicates()).doesNotContain("Y1");
        assertThat(m.twinned()).containsExactly(m.hashes().get(3));
        // #97 P2-2 kept: a part day with nothing shown moved — an identical line at another balance is new.
        Map<LocalDate, List<BankStatementImportService.StoredLine>> part = Map.of(d2, List.of(
                new BankStatementImportService.StoredLine("F2", new BigDecimal("-5.00"), "FEE", new BigDecimal("985.00"))));
        List<StatementMapper.Row> head = List.of(
                new StatementMapper.Row(1, d1, d1, "FEE", null, null, new BigDecimal("-5.00"), new BigDecimal("995.00")),
                new StatementMapper.Row(2, d2, d2, "FEE", null, null, new BigDecimal("-5.00"), new BigDecimal("990.00")));
        assertThat(BankStatementImportService.match(BANK, head, part).duplicates()).isEmpty();
    }

    /** #438 review: the moved-balance warning gives each line's own shift. */
    @Test
    void theMovedWarningGivesEachLinesShift() {
        LocalDate d2 = DAYS[1], d3 = DAYS[2];
        List<StatementMapper.Row> rows = List.of(
                new StatementMapper.Row(1, d2, d2, "FEE", null, null, new BigDecimal("-5.00"), new BigDecimal("975.00")),
                new StatementMapper.Row(2, d3, d3, "RENT", null, null, new BigDecimal("50.00"), new BigDecimal("990.00")));
        Map<String, BankStatementImportService.StoredLine> stored = Map.of(
                "A", new BankStatementImportService.StoredLine("A", new BigDecimal("-5.00"), "FEE", new BigDecimal("995.00")),
                "B", new BankStatementImportService.StoredLine("B", new BigDecimal("50.00"), "RENT", new BigDecimal("1045.00")));
        Map<String, BigDecimal> moved = new LinkedHashMap<>();
        moved.put("A", new BigDecimal("975.00"));
        moved.put("B", new BigDecimal("990.00"));
        String w = BankStatementImportService.movedWarning(rows, List.of("A", "B"), moved, stored).orElseThrow();
        assertThat(w).contains("10/09/2026 FEE by -20.00").contains("11/09/2026 RENT by -55.00");
    }
}
