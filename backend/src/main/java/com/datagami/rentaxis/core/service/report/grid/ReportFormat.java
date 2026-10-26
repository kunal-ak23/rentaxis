package com.datagami.rentaxis.core.service.report.grid;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Text forms shared by every report sink, in PACT's spelling. Digits are always Latin and
 * month names English, whatever the report language, so an Arabic export reads the same
 * figures as the English one.
 */
public final class ReportFormat {

    /** The date patterns a {@code DATE} column may use (PACT's three). */
    public static final List<String> DATE_PATTERNS = List.of("dd/MM/yyyy", "dd-MM-yyyy", "dd/MMM/yyyy");

    /** Excel's limit on the characters in one cell. */
    public static final int MAX_CELL_CHARS = 32_767;

    /** Excel's limit on a sheet name. */
    public static final int MAX_SHEET_NAME_CHARS = 31;

    private static final ZoneId DUBAI = ZoneId.of("Asia/Dubai");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ENGLISH);

    /**
     * C0 controls except tab, line feed and carriage return; DEL; and every Unicode Bidi_Control
     * character: the Arabic letter mark, the LRM/RLM marks, embeddings, overrides and isolates.
     */
    private static final Pattern CONTROL_AND_BIDI =
            Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F\\u061C\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]");
    private static final Pattern SHEET_NAME_FORBIDDEN = Pattern.compile("[\\[\\]:*?/\\\\]");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private ReportFormat() {
    }

    /** {@code #,##0.00}, half-up, Latin digits; {@code null} is blank. A negative keeps its minus sign. */
    public static String money(BigDecimal v) {
        if (v == null) {
            return "";
        }
        DecimalFormat f = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
        f.setRoundingMode(RoundingMode.HALF_UP);
        return f.format(v.setScale(2, RoundingMode.HALF_UP));
    }

    /** PACT's balance form: {@code 12,345.00Dr}, {@code 8,000.00Cr}, {@code 0.00Dr} (zero reads Dr); {@code null} is blank. */
    public static String drCr(BigDecimal v) {
        if (v == null) {
            return "";
        }
        BigDecimal scaled = v.setScale(2, RoundingMode.HALF_UP);
        return scaled.signum() >= 0 ? money(scaled) + "Dr" : money(scaled.abs()) + "Cr";
    }

    /** The date in {@code pattern} with English month names and Latin digits; {@code null} is blank. */
    public static String day(LocalDate d, String pattern) {
        if (d == null) {
            return "";
        }
        return DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).format(d);
    }

    /** The "generated at" stamp: {@code dd/MM/yyyy HH:mm} in Dubai time; {@code null} is blank. */
    public static String stamp(ZonedDateTime t) {
        if (t == null) {
            return "";
        }
        return STAMP.format(t.withZoneSameInstant(DUBAI));
    }

    /**
     * A term in PACT's spelling, both ends inclusive: {@code 1 Years }, {@code 1 Years 1 Months },
     * {@code 1 Years 8 Days}. Years and months keep their trailing space; a part that is zero is left out.
     * A missing end or an end before the start is blank.
     */
    public static String duration(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            return "";
        }
        Period p = Period.between(from, to.plusDays(1));
        StringBuilder sb = new StringBuilder();
        if (p.getYears() > 0) {
            sb.append(p.getYears()).append(" Years ");
        }
        if (p.getMonths() > 0) {
            sb.append(p.getMonths()).append(" Months ");
        }
        if (p.getDays() > 0) {
            sb.append(p.getDays()).append(" Days");
        }
        return sb.toString();
    }

    /**
     * User-typed text made safe for a cell: control characters (except tab and line breaks) and
     * bidi controls are removed, and the result is cut to Excel's 32,767-character limit without
     * splitting a surrogate pair. {@code null} is blank.
     */
    public static String cellText(String s) {
        if (s == null) {
            return "";
        }
        String t = CONTROL_AND_BIDI.matcher(s).replaceAll("");
        if (t.length() > MAX_CELL_CHARS) {
            int cut = Character.isHighSurrogate(t.charAt(MAX_CELL_CHARS - 1)) ? MAX_CELL_CHARS - 1 : MAX_CELL_CHARS;
            t = t.substring(0, cut);
        }
        return t;
    }

    /**
     * An Excel-safe sheet name: no {@code []:*?/\}, no control or bidi characters, single spaces,
     * no leading or trailing apostrophe, at most 31 characters; blank becomes {@code Report}.
     */
    public static String sheetName(String s) {
        String t = cellText(s);
        t = SHEET_NAME_FORBIDDEN.matcher(t).replaceAll("");
        t = WHITESPACE_RUN.matcher(t).replaceAll(" ");
        t = trimEdges(t);
        if (t.length() > MAX_SHEET_NAME_CHARS) {
            int cut = Character.isHighSurrogate(t.charAt(MAX_SHEET_NAME_CHARS - 1))
                    ? MAX_SHEET_NAME_CHARS - 1 : MAX_SHEET_NAME_CHARS;
            t = trimEdges(t.substring(0, cut));
        }
        return t.isEmpty() ? "Report" : t;
    }

    /** Trims spaces and apostrophes from both ends until neither is left. */
    private static String trimEdges(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && (s.charAt(start) == ' ' || s.charAt(start) == '\'')) {
            start++;
        }
        while (end > start && (s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '\'')) {
            end--;
        }
        return s.substring(start, end);
    }
}
