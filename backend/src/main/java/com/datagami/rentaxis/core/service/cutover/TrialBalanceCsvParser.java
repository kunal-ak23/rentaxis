package com.datagami.rentaxis.core.service.cutover;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads PACT's trial-balance export: {@code code, name, debit, credit} (spec §10.3).
 *
 * <p>Deliberately hand-rolled rather than a CSV library: the only quoting PACT emits
 * is double quotes around fields containing its own thousands separators, and the
 * shapes that actually break a naive split are cheaper to handle here than to
 * configure around. Every rejected line is reported with its number so the accountant
 * can fix the file rather than wonder why a balance is missing.
 *
 * <p><b>The shapes below come from the client's real exports</b>, not from
 * imagination. PACT writes its reports as an HTML table saved with an {@code .xls}
 * extension; saved out as CSV they carry:
 * <ul>
 *   <li>a two-line banner — the organisation's name, then the report title — above
 *       the header row, each alone on its line;</li>
 *   <li>thousands separators, and {@code Dr} / {@code Cr} <em>suffixes</em> on the
 *       amounts ("2,750.00Cr", "19,000.00Dr");</li>
 *   <li>{@code Sub Total} and {@code Grand Total} rows inside the body as well as at
 *       the foot, sometimes carrying the section they close in brackets;</li>
 *   <li>a UTF-8 BOM and CRLF endings once Excel has been near it.</li>
 * </ul>
 * None of those is an error. A parser that reported them as one would put a dozen
 * red lines on a perfectly good file and teach the accountant to ignore the list —
 * which is the list that also carries the amount it could not read.
 *
 * <p><b>Sign convention.</b> A bracket or a minus inside a column means the figure
 * was printed as a negative <em>of that column</em>; the column already says which
 * side it is, so the magnitude is taken and the column wins. PACT never exports a
 * genuinely negative debit. A {@code Dr}/{@code Cr} suffix is the opposite case: it
 * is an explicit statement of side, so it overrides the column it sits in. A row
 * with both sides filled is netted down to one, because a journal line has only one.
 */
public final class TrialBalanceCsvParser {

    private TrialBalanceCsvParser() {}

    /** PACT's account codes are 5-6 digits; the column that stores them is varchar(40). */
    public static final int MAX_CODE_LENGTH = 40;

    /** {@code opening_balance_snapshots.account_name} is varchar(255). */
    public static final int MAX_NAME_LENGTH = 255;

    /**
     * One account's figure, already netted to a single side and rounded to the scale
     * the ledger stores. {@code lineNo} is the 1-based line of the file it came from,
     * so a caller rejecting a row later (a duplicate code, a code that matches no
     * account) can name the line the accountant has to look at.
     */
    public record CsvRow(String code, String name, BigDecimal debit, BigDecimal credit, int lineNo) {}

    public record CsvParseResult(List<CsvRow> rows, List<String> problems) {}

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    /** PACT's own vocabulary for a row that closes a section rather than naming an account. */
    private static final Pattern TOTAL_ROW = Pattern.compile("(?i)^(sub\\s*total|grand\\s*total|net\\s*total|total)\\b.*");

    /** A trailing {@code Dr} / {@code Cr} (with or without a space or a full stop) states the side outright. */
    private static final Pattern SIDE_SUFFIX = Pattern.compile("(?i)^(.*?)\\s*(dr|cr)\\.?$");

    private enum Side { DR, CR }

    /** A magnitude plus, when the file said so outright, the side it belongs on. */
    private record Amount(BigDecimal magnitude, Side explicitSide) {}

    public static CsvParseResult parse(InputStream in) {
        List<CsvRow> rows = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        int lineNo = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (lineNo == 1) line = stripBom(line);
                if (line.isBlank()) continue;
                String[] parts = splitCsv(line);

                String code = unquote(parts[0]);
                // Checked before the column count: PACT's totals rows are not always
                // four columns wide, and a totals row is furniture either way.
                if (TOTAL_ROW.matcher(code).matches()) continue;

                if (parts.length < 4) {
                    // The banner above the table ("Al Ashram Real Estate", then the
                    // report title) is a short row with nothing numeric on it. A short
                    // row that DOES carry a figure is real data in the wrong shape and
                    // has to be reported, or a column the accountant dropped while
                    // editing the file would silently take a balance with it.
                    if (!carriesAFigure(parts)) continue;
                    problems.add("line " + lineNo + ": expected 4 columns (code, name, debit, credit), got " + parts.length);
                    continue;
                }
                if (code.isEmpty()) continue;                              // nameless totals row
                if (isHeader(code, parts[2], parts[3])) continue;          // header row, present or not
                if (code.length() > MAX_CODE_LENGTH) {
                    problems.add("line " + lineNo + ": account code is longer than " + MAX_CODE_LENGTH + " characters");
                    continue;
                }

                Amount debit, credit;
                try {
                    debit = amount(parts[2]);
                    credit = amount(parts[3]);
                } catch (NumberFormatException e) {
                    problems.add("line " + lineNo + ": '" + e.getMessage() + "' is not an amount");
                    continue;
                }

                // Debit-positive. A column's natural side is used unless the figure
                // named its own; both columns are then summed, which is also what nets
                // a two-sided row down to the one side a journal line can carry.
                BigDecimal net = signed(debit, Side.DR).add(signed(credit, Side.CR));
                rows.add(new CsvRow(code, truncate(unquote(parts[1])),
                        net.signum() > 0 ? net : ZERO,
                        net.signum() < 0 ? net.negate() : ZERO,
                        lineNo));
            }
        } catch (IOException e) {
            problems.add("could not read the file: " + e.getMessage());
        }
        return new CsvParseResult(rows, problems);
    }

    /** The column's side unless the figure stated one of its own. */
    private static BigDecimal signed(Amount a, Side column) {
        Side side = a.explicitSide() == null ? column : a.explicitSide();
        return side == Side.DR ? a.magnitude() : a.magnitude().negate();
    }

    private static boolean isHeader(String code, String debit, String credit) {
        String d = unquote(debit).toLowerCase();
        String c = unquote(credit).toLowerCase();
        return d.contains("debit") || c.contains("credit") || code.toLowerCase().contains("code");
    }

    /** True when any cell of a short row reads as a number — i.e. it is data, not a banner. */
    private static boolean carriesAFigure(String[] parts) {
        for (String p : parts) {
            try {
                if (amount(p).magnitude().signum() != 0) return true;
            } catch (NumberFormatException ignored) {
                // Not a number, so it says nothing about whether this row is data.
            }
        }
        return false;
    }

    /**
     * Strips quotes, spaces, thousands separators and currency suffixes; reads a
     * trailing Dr/Cr as the side; treats brackets and a leading minus as "printed
     * negative", which is a presentation detail rather than a change of side.
     */
    private static Amount amount(String raw) {
        String s = unquote(raw).replace(",", "").replace(' ', ' ').trim();
        s = s.replaceAll("(?i)\\bAED\\b", "").trim();

        Side explicit = null;
        Matcher m = SIDE_SUFFIX.matcher(s);
        if (m.matches() && !m.group(1).isBlank()) {
            explicit = m.group(2).equalsIgnoreCase("dr") ? Side.DR : Side.CR;
            s = m.group(1).trim();
        }
        if (s.isEmpty() || s.equals("-") || s.equals("–") || s.equals("—")) {
            return new Amount(ZERO, explicit);
        }
        if (s.startsWith("(") && s.endsWith(")")) s = s.substring(1, s.length() - 1).trim();
        if (s.startsWith("+")) s = s.substring(1).trim();
        try {
            return new Amount(new BigDecimal(s).setScale(2, RoundingMode.HALF_UP).abs(), explicit);
        } catch (NumberFormatException e) {
            throw new NumberFormatException(raw.trim());
        }
    }

    private static String truncate(String s) {
        return s.length() <= MAX_NAME_LENGTH ? s : s.substring(0, MAX_NAME_LENGTH);
    }

    /** Excel prefixes a UTF-8 CSV with a BOM, which would otherwise ride on the first account code. */
    private static String stripBom(String line) {
        return line.startsWith("﻿") ? line.substring(1) : line;
    }

    private static String unquote(String s) {
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length() - 1);
        return t.trim();
    }

    /** Splits on commas that are not inside double quotes. */
    private static String[] splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (char ch : line.toCharArray()) {
            if (ch == '"') { inQuotes = !inQuotes; cur.append(ch); }
            else if (ch == ',' && !inQuotes) { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(ch);
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}
