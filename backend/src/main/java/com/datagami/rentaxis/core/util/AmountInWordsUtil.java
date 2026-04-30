package com.datagami.rentaxis.core.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class AmountInWordsUtil {

    private static final String[] BELOW_TWENTY = {
            "Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
            "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen",
            "Sixteen", "Seventeen", "Eighteen", "Nineteen"
    };

    private static final String[] TENS = {
            "", "", "Twenty", "Thirty", "Forty", "Fifty",
            "Sixty", "Seventy", "Eighty", "Ninety"
    };

    private AmountInWordsUtil() {}

    public static String toEnglishWords(BigDecimal amount, String currencyCode) {
        if (amount == null) {
            throw new IllegalArgumentException("amount must not be null");
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        long whole = amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
        String words = numberToWords(whole);
        return currencyCode + " " + words + " Only";
    }

    private static String numberToWords(long n) {
        if (n == 0) return "Zero";
        StringBuilder sb = new StringBuilder();
        appendChunk(sb, n / 1_000_000_000L, "Billion");
        n %= 1_000_000_000L;
        appendChunk(sb, n / 1_000_000L, "Million");
        n %= 1_000_000L;
        appendChunk(sb, n / 1_000L, "Thousand");
        n %= 1_000L;
        appendBelowThousand(sb, (int) n);
        return sb.toString().trim();
    }

    private static void appendChunk(StringBuilder sb, long count, String unit) {
        if (count == 0) return;
        appendBelowThousand(sb, (int) count);
        sb.append(unit).append(' ');
    }

    private static void appendBelowThousand(StringBuilder sb, int n) {
        if (n == 0) return;
        if (n >= 100) {
            sb.append(BELOW_TWENTY[n / 100]).append(" Hundred ");
            n %= 100;
        }
        if (n >= 20) {
            sb.append(TENS[n / 10]).append(' ');
            n %= 10;
        }
        if (n > 0 && n < 20) {
            sb.append(BELOW_TWENTY[n]).append(' ');
        }
    }
}
