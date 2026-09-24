package com.datagami.rentaxis.core.service.bank;

import org.apache.poi.ss.usermodel.DateUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The value parsers behind statement import (finance-ops spec §3 step 3): amounts, dates, cheque numbers, the line hash. */
public final class StatementValues {

    private StatementValues() { }

    /** A parsed amount: its value, and a trailing DR/CR flag when one was written. */
    public record Amount(BigDecimal value, String flag) { }

    private static final Pattern NUMBER = Pattern.compile("[0-9]+(\\.[0-9]+)?|\\.[0-9]+");

    /** As {@link #amount(Object, char)} with a decimal point. */
    public static Amount amount(Object cell) {
        return amount(cell, '.');
    }

    /**
     * {@code null} for a blank cell. Strips {@code AED} and spaces;
     * {@code (1,234.50)}, a leading or a trailing {@code -} mean negative; a
     * trailing {@code DR}/{@code CR} is returned as the flag, not applied.
     *
     * <p>{@code decimal} is the profile's decimal separator; the other of
     * {@code .}/{@code ,} may only group thousands (PR #353 review). A value that
     * does not read unambiguously that way — {@code 1.234,50} under a decimal
     * point, {@code 1,23} as a thousands group, three decimals — is refused rather
     * than silently misread.</p>
     *
     * @throws NumberFormatException when the text is not an amount
     */
    public static Amount amount(Object cell, char decimal) {
        if (cell == null) return null;
        if (cell instanceof BigDecimal d) return new Amount(d.setScale(2, RoundingMode.HALF_UP), null);
        if (cell instanceof LocalDate) throw new NumberFormatException("a date, not an amount");
        String raw = cell.toString().trim();
        String s = raw.toUpperCase(Locale.ROOT).replace('\u00A0', ' ').replace('\u2212', '-');
        if (s.isEmpty()) return null;
        String flag = null;
        if (s.endsWith("DR") || s.endsWith("CR")) {
            flag = s.substring(s.length() - 2);
            s = s.substring(0, s.length() - 2).trim();
        }
        s = s.replace("AED", "").replace(" ", "");
        if (s.isEmpty() && flag != null) throw new NumberFormatException("no amount before " + flag);
        if (s.isEmpty()) return null;
        boolean negative = false;
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = true;
            s = s.substring(1, s.length() - 1);
        }
        if (s.endsWith("-")) {
            negative = !negative;
            s = s.substring(0, s.length() - 1);
        }
        if (s.startsWith("-")) {
            negative = !negative;
            s = s.substring(1);
        } else if (s.startsWith("+")) {
            s = s.substring(1);
        }
        char group = decimal == ',' ? '.' : ',';
        String plain = normaliseNumber(s, decimal, group);
        if (plain == null) {
            throw new NumberFormatException("\"" + raw + "\" is not an amount with '" + decimal + "' as the decimal separator");
        }
        BigDecimal v = new BigDecimal(plain).setScale(2, RoundingMode.HALF_UP);
        return new Amount(negative ? v.negate() : v, flag);
    }

    /** "1,234.50" → "1234.50" for decimal '.', group ','; null when it does not read that way unambiguously. */
    static String normaliseNumber(String s, char decimal, char group) {
        int d = s.indexOf(decimal);
        if (d != s.lastIndexOf(decimal)) return null;
        String intPart = d < 0 ? s : s.substring(0, d);
        String frac = d < 0 ? "" : s.substring(d + 1);
        if (frac.indexOf(group) >= 0 || frac.length() > 2 || (d >= 0 && frac.isEmpty())) return null;
        if (!frac.chars().allMatch(Character::isDigit)) return null;
        String[] groups = intPart.split(java.util.regex.Pattern.quote(String.valueOf(group)), -1);
        if (groups[0].isEmpty() && groups.length > 1) return null;
        for (int i = 0; i < groups.length; i++) {
            String g = groups[i];
            if (!g.chars().allMatch(Character::isDigit)) return null;
            if (i > 0 && g.length() != 3) return null;
            if (groups.length > 1 && i == 0 && (g.isEmpty() || g.length() > 3)) return null;
        }
        String digits = String.join("", groups);
        if (digits.isEmpty() && frac.isEmpty()) return null;
        return (digits.isEmpty() ? "0" : digits) + (frac.isEmpty() ? "" : "." + frac);
    }

    /**
     * A date cell: an Excel date cell as is, a number as an Excel serial date, and
     * text by the profile's formats in order (day first, always), then ISO
     * {@code yyyy-MM-dd}. A time after the date is ignored. {@code null} for blank.
     *
     * @throws DateTimeParseException when no format fits
     */
    public static LocalDate date(Object cell, List<String> formats) {
        if (cell == null) return null;
        if (cell instanceof LocalDate d) return d;
        if (cell instanceof BigDecimal n) {
            double serial = n.doubleValue();
            if (serial < 1 || serial > 2958465) throw new DateTimeParseException("not an Excel date", n.toPlainString(), 0);
            return DateUtil.getLocalDateTime(serial).toLocalDate();
        }
        String s = cell.toString().trim();
        if (s.isEmpty()) return null;
        String datePart = s.contains(" ") && s.indexOf(' ') >= 6 ? s.substring(0, s.indexOf(' ')) : s;
        for (String candidate : datePart.equals(s) ? List.of(s) : List.of(s, datePart)) {
            for (String f : formats) {
                try {
                    return LocalDate.parse(candidate, formatter(f));
                } catch (DateTimeParseException | IllegalArgumentException ignored) {
                    // next format
                }
            }
            try {
                return LocalDate.parse(candidate, DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (DateTimeParseException ignored) {
                // next candidate
            }
        }
        throw new DateTimeParseException("\"" + s + "\" matches none of " + String.join(", ", formats), s, 0);
    }

    static DateTimeFormatter formatter(String pattern) {
        // STRICT rejects 31/02; it needs the proleptic year letter.
        return new DateTimeFormatterBuilder().parseCaseInsensitive()
                .appendPattern(pattern.replace('y', 'u'))
                .toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);
    }

    /** The profile's pattern (default a six-digit run) against the reference, then the description. */
    public static String chequeNo(Pattern pattern, String reference, String description) {
        for (String s : new String[]{reference, description}) {
            if (s == null) continue;
            Matcher m = pattern.matcher(s);
            if (m.find()) return m.group();
        }
        return null;
    }

    /** Upper case, runs of whitespace as one space. */
    public static String normalise(String description) {
        return description == null ? "" : description.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    /**
     * {@code sha256(bank account | txn date | amount | description | occurrence)}.
     *
     * <p>Only what every mapping of every export yields (PR #353 review P2-2): the
     * value date, reference and running balance are data, not identity, so mapping
     * one of them later does not turn yesterday's lines into new ones. The amount
     * is signed, so it carries the direction. {@code occurrence} tells apart
     * identical lines on the same day in one file.</p>
     */
    public static String lineHash(UUID bankAccountId, LocalDate txnDate, BigDecimal amount, String description,
                                  int occurrence) {
        String key = bankAccountId + "|" + txnDate + "|" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString()
                + "|" + normalise(description) + "|" + occurrence;
        return sha256(key.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 1,234.50 */
    public static String money(BigDecimal v) {
        return String.format(Locale.ROOT, "%,.2f", v);
    }
}
