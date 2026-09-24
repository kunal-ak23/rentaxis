package com.datagami.rentaxis.core.service.bank;

import com.datagami.rentaxis.domain.entity.BankStatementProfile;
import com.datagami.rentaxis.domain.entity.BankStatementProfile.AmountMode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Maps a {@link StatementGrid} to statement rows through a profile (finance-ops
 * spec §3 import steps 3–5): parses each row, skips the rows that are not
 * transactions, decides the order, and checks the running balance.
 */
public final class StatementMapper {

    private StatementMapper() { }

    /** A transaction row in book order (oldest first). {@code fileRow} is the spreadsheet row number. */
    /**
     * A transaction row in book order (oldest first). {@code fileRow} is the
     * spreadsheet row number. {@code chequeNo} is set only from a mapped cheque
     * column; otherwise {@code chequeCandidates} holds the pattern's hits, and the
     * import keeps one only if it is a cheque the books know (PR #353 review).
     */
    public record Row(int fileRow, LocalDate txnDate, LocalDate valueDate, String description, String reference,
                      String chequeNo, BigDecimal amount, BigDecimal balance, List<String> chequeCandidates) {
        public Row(int fileRow, LocalDate txnDate, LocalDate valueDate, String description, String reference,
                   String chequeNo, BigDecimal amount, BigDecimal balance) {
            this(fileRow, txnDate, valueDate, description, reference, chequeNo, amount, balance, List.of());
        }

        public Row withChequeNo(String no) {
            return new Row(fileRow, txnDate, valueDate, description, reference, no, amount, balance, chequeCandidates);
        }
    }

    public enum Order { FILE, REVERSED, DATE }

    /**
     * {@code missingColumns}: profile columns named by a header the file no longer
     * has, so the profile must be re-mapped. {@code errors}: row problems, each
     * naming its row; the import is refused while there are any.
     */
    public record Result(List<Row> rows, List<String> errors, List<String> warnings, List<String> missingColumns,
                         BigDecimal openingBalance, BigDecimal closingBalance, boolean hasBalance, Order order) {
        public boolean ok() {
            return errors.isEmpty() && missingColumns.isEmpty();
        }
    }

    public static final List<String> FIELDS = List.of("txnDate", "valueDate", "description", "reference", "debit",
            "credit", "amount", "amountSign", "balance", "chequeNo");
    private static final int MAX_ERRORS = 50;
    private static final Pattern OPENING = Pattern.compile("(?i)\\b(opening balance|balance b/?f|brought forward|previous balance)\\b");
    private static final Pattern CLOSING = Pattern.compile("(?i)\\b(closing balance|balance c/?f|carried forward|available balance|ledger balance)\\b");
    private static final Pattern TOTAL = Pattern.compile("(?i)\\b(total|totals|grand total|sub-?total)\\b");

    public static Result map(StatementGrid grid, BankStatementProfile p) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        int headerIdx = Math.max(p.getHeaderRow(), 1) - 1;
        List<Object> header = headerIdx < grid.rows().size() ? grid.rows().get(headerIdx) : List.of();
        Map<String, Integer> col = new HashMap<>();
        Map<String, String> columns = p.getColumns() == null ? Map.of() : p.getColumns();
        for (String field : FIELDS) {
            String spec = columns.get(field);
            if (spec == null || spec.isBlank()) continue;
            int c = resolve(spec, header);
            if (c < 0) missing.add(field + " (\"" + spec + "\")");
            else col.put(field, c);
        }
        for (String need : required(p.getAmountMode())) {
            if (!columns.containsKey(need) || columns.get(need) == null || columns.get(need).isBlank()) {
                missing.add(need + " (not mapped)");
            }
        }
        if (!missing.isEmpty()) {
            return new Result(List.of(), errors, warnings, missing, null, null, false, Order.FILE);
        }
        List<String> formats = p.getDateFormats() == null || p.getDateFormats().length == 0
                ? List.of("dd/MM/yyyy", "dd-MMM-yyyy", "dd/MM/yy") : List.of(p.getDateFormats());
        Pattern chequePattern = Pattern.compile(p.getChequeNoPattern() == null ? "\\b\\d{6}\\b" : p.getChequeNoPattern());
        char decimal = ",".equals(p.getDecimalSeparator()) ? ',' : '.';
        List<int[]> textDates = new ArrayList<>();

        List<Row> rows = new ArrayList<>();
        BigDecimal opening = null;
        BigDecimal closing = null;
        int balanceMissing = 0;
        for (int r = Math.max(p.getFirstDataRow(), 1) - 1; r < grid.rows().size() && errors.size() < MAX_ERRORS; r++) {
            int fileRow = r + 1;
            List<Object> cells = grid.rows().get(r);
            if (cells.stream().allMatch(c -> c == null || StatementGrid.text(c).isEmpty())) continue;
            String description = text(grid, r, col.get("description"));
            Object dateCell = at(grid, r, col.get("txnDate"));
            BigDecimal amount;
            BigDecimal balance;
            try {
                amount = amount(grid, r, col, p.getAmountMode(), fileRow, decimal);
                StatementValues.Amount b = StatementValues.amount(at(grid, r, col.get("balance")), decimal);
                balance = b == null ? null : "DR".equals(b.flag()) ? b.value().abs().negate() : b.value();
            } catch (RowError e) {
                errors.add(e.getMessage());
                continue;
            } catch (NumberFormatException e) {
                errors.add("Row " + fileRow + ": " + e.getMessage());
                continue;
            }
            boolean noDate = dateCell == null || StatementGrid.text(dateCell).isEmpty();
            if (dateCell instanceof String ds) {
                java.util.regex.Matcher dm = NUMERIC_DATE.matcher(ds.trim());
                if (dm.find()) {
                    int y = Integer.parseInt(dm.group(3));
                    textDates.add(new int[]{Integer.parseInt(dm.group(1)), Integer.parseInt(dm.group(2)), y < 100 ? 2000 + y : y});
                }
            }
            if (amount == null) {
                if (OPENING.matcher(description).find()) {
                    opening = balance;
                } else if (CLOSING.matcher(description).find()) {
                    closing = balance;
                } else if (!(noDate || TOTAL.matcher(description).find())) {
                    errors.add("Row " + fileRow + ": no amount");
                }
                continue;
            }
            if (noDate && (TOTAL.matcher(description).find() || description.isEmpty())) continue;
            if (noDate) {
                errors.add("Row " + fileRow + ": no date");
                continue;
            }
            LocalDate txn;
            LocalDate value;
            try {
                txn = StatementValues.date(dateCell, formats);
                value = StatementValues.date(at(grid, r, col.get("valueDate")), formats);
            } catch (DateTimeParseException e) {
                errors.add("Row " + fileRow + ": date " + e.getMessage());
                continue;
            }
            if (amount.signum() == 0) {
                errors.add("Row " + fileRow + ": the amount is zero");
                continue;
            }
            if (col.containsKey("balance") && balance == null) balanceMissing++;

            String reference = blankToNull(text(grid, r, col.get("reference")));
            String chequeNo = col.containsKey("chequeNo") ? blankToNull(text(grid, r, col.get("chequeNo"))) : null;
            List<String> candidates = col.containsKey("chequeNo") ? List.of() : allMatches(chequePattern, reference, description);
            if (description.isEmpty()) description = reference == null ? "(no description)" : reference;
            rows.add(new Row(fileRow, txn, value, cut(description, 2000), cut(reference, 200), cut(chequeNo, 50),
                    amount, balance, candidates));
        }
        if (errors.size() >= MAX_ERRORS) errors.add("… and more; fix these first");
        // Said first: a month-first file otherwise shows up as a page of date errors.
        ambiguousDates(textDates, formats).ifPresent(m -> errors.add(0, m));
        boolean hasBalance = col.containsKey("balance") && balanceMissing == 0 && !rows.isEmpty();
        if (col.containsKey("balance") && balanceMissing > 0) {
            warnings.add(balanceMissing + " line(s) have no balance; the running-balance check was skipped");
        }

        Order order = Order.FILE;
        if (errors.isEmpty() && rows.size() > 1) {
            if (hasBalance) {
                String topDown = firstBreak(rows, false);
                if (topDown == null) {
                    order = Order.FILE;
                } else if (firstBreak(rows, true) == null) {
                    order = Order.REVERSED;
                } else {
                    boolean newestFirst = rows.get(0).txnDate().isAfter(rows.get(rows.size() - 1).txnDate());
                    errors.add(newestFirst ? firstBreak(rows, true) : topDown);
                }
            } else if (nonDecreasing(rows)) {
                order = Order.FILE;
            } else if (nonDecreasing(reversed(rows))) {
                order = Order.REVERSED;
            } else {
                order = Order.DATE;
                warnings.add("The lines are in neither date order; they were sorted by date");
            }
        }
        List<Row> book = switch (order) {
            case FILE -> rows;
            case REVERSED -> reversed(rows);
            case DATE -> rows.stream().sorted(Comparator.comparing(Row::txnDate)).toList();
        };
        if (hasBalance && !book.isEmpty()) {
            if (opening == null) opening = book.get(0).balance().subtract(book.get(0).amount());
            if (closing == null) closing = book.get(book.size() - 1).balance();
        }
        return new Result(book, errors, warnings, missing, opening, closing, hasBalance, order);
    }

    private static final Pattern NUMERIC_DATE = Pattern.compile("^(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4}|\\d{2})\\b");

    /**
     * PR #353 review: a file whose numeric dates read the other way round. When no
     * date has a field above 12 either way, the text alone cannot say which is the
     * day, so the profile's format decides — unless its reading is the one that is
     * implausible: a second field above 12 under a day-first format (a US export),
     * or a first field above 12 under a month-first one.
     */
    static java.util.Optional<String> ambiguousDates(List<int[]> dates, List<String> formats) {
        if (dates.isEmpty() || formats.isEmpty()) return java.util.Optional.empty();
        boolean dayFirst = !formats.get(0).trim().startsWith("M");
        boolean firstOver12 = dates.stream().anyMatch(d -> d[0] > 12);
        boolean secondOver12 = dates.stream().anyMatch(d -> d[1] > 12);
        if (dayFirst && secondOver12 && !firstOver12) {
            return java.util.Optional.of("The dates look month-first (e.g. " + example(dates, 1)
                    + "), but the mapping reads them day-first; set the date format to MM/dd/yyyy");
        }
        if (!dayFirst && firstOver12 && !secondOver12) {
            return java.util.Optional.of("The dates look day-first (e.g. " + example(dates, 0)
                    + "), but the mapping reads them month-first; set the date format to dd/MM/yyyy");
        }
        // Every field is 12 or under, so either reading parses. A statement covers a
        // month or so: when the mapping's reading spreads the lines over far more
        // time than the other reading would, the mapping is the likelier mistake.
        if (!firstOver12 && !secondOver12 && dates.size() > 1) {
            long as = span(dates, dayFirst);
            long other = span(dates, !dayFirst);
            if (as > 62 && other <= 31) {
                return java.util.Optional.of("Read " + (dayFirst ? "day-first" : "month-first") + ", the dates spread over "
                        + as + " days; read the other way they fit in " + other + ". Check the date format");
            }
        }
        return java.util.Optional.empty();
    }

    private static long span(List<int[]> dates, boolean dayFirst) {
        LocalDate min = null, max = null;
        for (int[] d : dates) {
            LocalDate x;
            try {
                x = dayFirst ? LocalDate.of(d[2], d[1], d[0]) : LocalDate.of(d[2], d[0], d[1]);
            } catch (java.time.DateTimeException e) {
                return Long.MAX_VALUE;
            }
            if (min == null || x.isBefore(min)) min = x;
            if (max == null || x.isAfter(max)) max = x;
        }
        return java.time.temporal.ChronoUnit.DAYS.between(min, max);
    }

    private static String example(List<int[]> dates, int over) {
        return dates.stream().filter(d -> d[over] > 12).findFirst().map(d -> d[0] + "/" + d[1]).orElse("");
    }

    private static List<String> allMatches(Pattern p, String... texts) {
        List<String> out = new ArrayList<>();
        for (String s : texts) {
            if (s == null) continue;
            java.util.regex.Matcher m = p.matcher(s);
            while (m.find()) if (!out.contains(m.group())) out.add(m.group());
        }
        return out;
    }

    /** The fields each amount mode needs besides the date and description. */
    static List<String> required(AmountMode mode) {
        List<String> need = new ArrayList<>(List.of("txnDate", "description"));
        switch (mode == null ? AmountMode.SPLIT : mode) {
            case SPLIT -> { need.add("debit"); need.add("credit"); }
            case SIGNED, DRCR_FLAG -> need.add("amount");
        }
        return need;
    }

    /**
     * The first row whose balance does not follow the one before it, reading
     * top-down ({@code bottomUp} false) or bottom-up. Null when it all follows.
     */
    static String firstBreak(List<Row> rows, boolean bottomUp) {
        List<Row> seq = bottomUp ? reversed(rows) : rows;
        for (int i = 1; i < seq.size(); i++) {
            Row prev = seq.get(i - 1);
            Row cur = seq.get(i);
            if (prev.balance().add(cur.amount()).compareTo(cur.balance()) != 0) {
                return "Row " + cur.fileRow() + ": balance " + StatementValues.money(cur.balance()) + " does not follow "
                        + StatementValues.money(prev.balance()) + " + " + StatementValues.money(cur.amount());
            }
        }
        return null;
    }

    private static boolean nonDecreasing(List<Row> rows) {
        for (int i = 1; i < rows.size(); i++) {
            if (rows.get(i).txnDate().isBefore(rows.get(i - 1).txnDate())) return false;
        }
        return true;
    }

    private static List<Row> reversed(List<Row> rows) {
        List<Row> r = new ArrayList<>(rows);
        Collections.reverse(r);
        return r;
    }

    private static final class RowError extends RuntimeException {
        RowError(String m) { super(m); }
    }

    private static BigDecimal amount(StatementGrid g, int r, Map<String, Integer> col, AmountMode mode, int fileRow,
                                     char decimal) {
        switch (mode == null ? AmountMode.SPLIT : mode) {
            case SPLIT -> {
                StatementValues.Amount dr = StatementValues.amount(at(g, r, col.get("debit")), decimal);
                StatementValues.Amount cr = StatementValues.amount(at(g, r, col.get("credit")), decimal);
                BigDecimal d = dr == null ? BigDecimal.ZERO : dr.value().abs();
                BigDecimal c = cr == null ? BigDecimal.ZERO : cr.value().abs();
                if (dr == null && cr == null) return null;
                if ((d.signum() == 0) == (c.signum() == 0)) {
                    throw new RowError("Row " + fileRow + ": exactly one of debit and credit must be non-zero");
                }
                return c.subtract(d);
            }
            case SIGNED -> {
                StatementValues.Amount a = StatementValues.amount(at(g, r, col.get("amount")), decimal);
                if (a == null) return null;
                return "DR".equals(a.flag()) ? a.value().abs().negate() : "CR".equals(a.flag()) ? a.value().abs() : a.value();
            }
            case DRCR_FLAG -> {
                StatementValues.Amount a = StatementValues.amount(at(g, r, col.get("amount")), decimal);
                if (a == null) return null;
                String flag = a.flag();
                if (col.containsKey("amountSign")) {
                    String s = StatementGrid.text(at(g, r, col.get("amountSign"))).toUpperCase(Locale.ROOT);
                    if (s.startsWith("D")) flag = "DR";
                    else if (s.startsWith("C")) flag = "CR";
                }
                if (flag == null) throw new RowError("Row " + fileRow + ": no DR/CR flag on the amount");
                return "DR".equals(flag) ? a.value().abs().negate() : a.value().abs();
            }
        }
        return null;
    }

    /** A header name (case-insensitive) first, then a column letter. -1 when neither fits. */
    static int resolve(String spec, List<Object> header) {
        String s = spec.trim();
        for (int i = 0; i < header.size(); i++) {
            if (StatementGrid.text(header.get(i)).equalsIgnoreCase(s)) return i;
        }
        if (s.matches("[A-Za-z]{1,2}")) {
            String u = s.toUpperCase(Locale.ROOT);
            int n = 0;
            for (char ch : u.toCharArray()) n = n * 26 + (ch - 'A' + 1);
            return n - 1;
        }
        return -1;
    }

    /** "A", "B", … "AA" for column index 0, 1, … 26. */
    public static String letter(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index + 1;
        while (n > 0) {
            int rem = (n - 1) % 26;
            sb.insert(0, (char) ('A' + rem));
            n = (n - 1) / 26;
        }
        return sb.toString();
    }

    private static Object at(StatementGrid g, int r, Integer c) {
        return c == null ? null : g.cell(r, c);
    }

    private static String text(StatementGrid g, int r, Integer c) {
        return c == null ? "" : StatementGrid.text(g.cell(r, c));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String cut(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }
}
