package com.datagami.rentaxis.core.service.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spreads an amount over weighted keys by the largest-remainder method, in fils,
 * so the parts always add back to the amount exactly (finance-ops spec §1: the
 * "Allocate unassigned" row is report-only and must tie to the Unassigned column).
 */
public final class PnlAllocation {

    private PnlAllocation() { }

    public enum Basis { NONE, UNITS, RENT, EQUAL }

    /**
     * {@code weights} in display order; non-positive weights take nothing. When no
     * weight is positive the amount is spread equally — the caller reports that it
     * fell back.
     */
    public static <K> Map<K, BigDecimal> largestRemainder(BigDecimal amount, Map<K, BigDecimal> weights) {
        Map<K, BigDecimal> out = new LinkedHashMap<>();
        if (weights.isEmpty()) return out;
        Map<K, BigDecimal> w = new LinkedHashMap<>();
        weights.forEach((k, v) -> w.put(k, v != null && v.signum() > 0 ? v : BigDecimal.ZERO));
        BigDecimal total = w.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() == 0) {
            w.replaceAll((k, v) -> BigDecimal.ONE);
            total = BigDecimal.valueOf(w.size());
        }
        long fils = amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
        int sign = Long.signum(fils);
        long abs = Math.abs(fils);

        record Share<K>(K key, int order, long floor, BigDecimal remainder) { }
        List<Share<K>> shares = new ArrayList<>();
        long allotted = 0;
        int i = 0;
        for (Map.Entry<K, BigDecimal> e : w.entrySet()) {
            BigDecimal exact = BigDecimal.valueOf(abs).multiply(e.getValue()).divide(total, 12, RoundingMode.DOWN);
            long floor = exact.setScale(0, RoundingMode.DOWN).longValueExact();
            shares.add(new Share<>(e.getKey(), i++, floor, exact.subtract(BigDecimal.valueOf(floor))));
            allotted += floor;
        }
        long left = abs - allotted;
        List<Share<K>> byRemainder = new ArrayList<>(shares);
        byRemainder.sort(Comparator.comparing((Share<K> s) -> s.remainder()).reversed()
                .thenComparingInt(Share::order));
        Map<K, Long> extra = new LinkedHashMap<>();
        for (int j = 0; j < left; j++) {
            extra.merge(byRemainder.get(j % byRemainder.size()).key(), 1L, Long::sum);
        }
        for (Share<K> s : shares) {
            long f = (s.floor() + extra.getOrDefault(s.key(), 0L)) * sign;
            out.put(s.key(), BigDecimal.valueOf(f).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY));
        }
        return out;
    }
}
