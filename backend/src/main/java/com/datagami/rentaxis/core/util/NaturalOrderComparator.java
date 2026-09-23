package com.datagami.rentaxis.core.util;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orders strings the way a person reads a unit number, not the way a database
 * collates one: split into runs of digits and non-digits, compare digit runs
 * numerically and everything else case-insensitively.
 *
 * <p>A plain lexicographic sort put "A-1001" before "A-201" — the run "1"
 * sorts before "2" as a character, whatever follows it. Reading unit numbers
 * as runs fixes that: {@code "A-101" < "A-201" < "A-1001" < "B-101"}.</p>
 *
 * <p>A tie between digit runs that carry different leading zeros (
 * {@code "G01"} vs {@code "G1"}, both value 1) falls back to run length so
 * the comparator stays a total order rather than reporting them equal — two
 * unequal strings never compare as 0 here, which is what a caller that sorts
 * or de-duplicates by this comparator needs. A {@code null} sorts after every
 * non-null value.</p>
 */
public final class NaturalOrderComparator implements Comparator<String> {

    public static final NaturalOrderComparator INSTANCE = new NaturalOrderComparator();

    private static final Pattern RUN = Pattern.compile("\\d+|\\D+");

    @Override
    public int compare(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;

        List<String> ra = runsOf(a);
        List<String> rb = runsOf(b);
        int n = Math.min(ra.size(), rb.size());
        for (int i = 0; i < n; i++) {
            String x = ra.get(i);
            String y = rb.get(i);
            int c = Character.isDigit(x.charAt(0)) && Character.isDigit(y.charAt(0))
                    ? compareDigitRuns(x, y)
                    : x.compareToIgnoreCase(y);
            if (c != 0) return c;
        }
        return Integer.compare(ra.size(), rb.size());
    }

    private static int compareDigitRuns(String x, String y) {
        int c = new BigInteger(x).compareTo(new BigInteger(y));
        // Same numeric value, different leading zeros ("01" vs "1"): fall back to
        // length rather than calling them equal, so the order stays deterministic.
        return c != 0 ? c : Integer.compare(x.length(), y.length());
    }

    private static List<String> runsOf(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = RUN.matcher(s);
        while (m.find()) out.add(m.group());
        return out;
    }
}
