package com.datagami.rentaxis.core.service.recognition;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Accounting v2 plan 3 (spec §8): rent is recognised per day. A lease's RENT
 * line runs from a {@code from} date to a {@code to} date inclusive; its day
 * rate is {@code amount / daysInclusive(from, to)}, and that single rate is
 * the source of every number this module produces — the calendar-month
 * slices booked as recognition entries, the truncated slices left after an
 * early termination, and the earned-to-date figure a settlement statement
 * reads off.
 *
 * <p>Pure and stateless: no Spring, no persistence, safe to unit test and to
 * call from anywhere that has the segment's stored amount/dates/day rate on
 * hand. All day rates are scale 6, all money amounts are scale 2, and both
 * use {@link RoundingMode#HALF_UP}.
 */
public final class ProrationEngine {

    private static final int RATE_SCALE = 6;
    private static final int AMOUNT_SCALE = 2;

    private ProrationEngine() {
    }

    /** One calendar-month (or partial) slice of a segment's amount. */
    public record Slice(LocalDate periodStart, LocalDate periodEnd, int days, BigDecimal amount) {
    }

    /** {@code (to - from) + 1}; throws if {@code to} is before {@code from}. */
    public static int daysInclusive(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("to (" + to + ") is before from (" + from + ")");
        }
        return (int) (to.toEpochDay() - from.toEpochDay()) + 1;
    }

    /** {@code amount / daysInclusive(from, to)}, scale 6 HALF_UP. */
    public static BigDecimal dayRate(BigDecimal amount, LocalDate from, LocalDate to) {
        requireNonNegative(amount);
        int days = daysInclusive(from, to);
        return amount.divide(BigDecimal.valueOf(days), RATE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Slices {@code amount} into calendar-month periods from {@code from} to
     * {@code to} inclusive. Every slice but the last is {@code round(dayRate
     * * days, 2)}; the last absorbs whatever remainder keeps the sum exactly
     * equal to {@code amount}.
     */
    public static List<Slice> slice(BigDecimal amount, LocalDate from, LocalDate to) {
        requireNonNegative(amount);
        daysInclusive(from, to); // validates to >= from

        BigDecimal rate = dayRate(amount, from, to);
        List<Slice> slices = new ArrayList<>();
        BigDecimal runningTotal = BigDecimal.ZERO;
        LocalDate cursor = from;

        while (!cursor.isAfter(to)) {
            LocalDate calendarMonthEnd = cursor.withDayOfMonth(cursor.lengthOfMonth());
            LocalDate periodEnd = calendarMonthEnd.isBefore(to) ? calendarMonthEnd : to;
            boolean isLastSlice = periodEnd.isEqual(to);
            int days = daysInclusive(cursor, periodEnd);

            BigDecimal sliceAmount = isLastSlice
                    ? amount.subtract(runningTotal).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
                    : rate.multiply(BigDecimal.valueOf(days)).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);

            slices.add(new Slice(cursor, periodEnd, days, sliceAmount));
            runningTotal = runningTotal.add(sliceAmount);
            cursor = periodEnd.plusDays(1);
        }
        return slices;
    }

    /**
     * Drops slices after {@code lastDay} and truncates the slice that
     * contains it (inclusive of that slice's own {@code periodEnd} — a
     * termination effective on a calendar month-end is an ordinary input,
     * not an edge case). {@code dayRate} is the segment's own stored 6-dp
     * rate (not recomputed here, so the slices strictly before the cut are
     * returned unchanged — same object, same amount). The slice containing
     * {@code lastDay} — including when {@code lastDay} lands exactly on
     * that slice's original {@code periodEnd} — always has its amount
     * recomputed as {@code earnedThrough(...) - sum(previous slices)}; this
     * usually equals the original {@code round(dayRate * days, 2)} for that
     * slice, but is not guaranteed to (accumulated rounding across the
     * earlier slices can drift it by a cent), so the returned list's sum is
     * always exactly {@code earnedThrough(amount, from, to, lastDay)}, never
     * a value computed for that one slice in isolation.
     *
     * <p>{@code lastDay} before the segment's own start returns an empty
     * list: a termination before the term even began has earned nothing.
     * {@code lastDay} at or after the segment's own end returns the
     * original slices unchanged (same list contents): there is nothing to
     * truncate.
     */
    public static List<Slice> truncate(List<Slice> slices, BigDecimal dayRate, LocalDate lastDay) {
        if (slices.isEmpty()) {
            return List.of();
        }
        LocalDate from = slices.get(0).periodStart();
        LocalDate segmentEnd = slices.get(slices.size() - 1).periodEnd();
        if (!lastDay.isBefore(segmentEnd)) {
            // lastDay at/after the segment's own end: nothing to truncate.
            return new ArrayList<>(slices);
        }
        if (lastDay.isBefore(from)) {
            // Terminated before the term began: nothing earned.
            return List.of();
        }

        List<Slice> result = new ArrayList<>();
        BigDecimal runningTotal = BigDecimal.ZERO;
        for (Slice s : slices) {
            // Inclusive of s.periodEnd(): lastDay landing exactly on a
            // slice's own last day still means that slice is "the one
            // containing lastDay", not the next one.
            if (!lastDay.isAfter(s.periodEnd())) {
                int days = daysInclusive(s.periodStart(), lastDay);
                BigDecimal earnedTotal = dayRate.multiply(BigDecimal.valueOf(daysInclusive(from, lastDay)))
                        .setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
                BigDecimal amount = earnedTotal.subtract(runningTotal);
                result.add(new Slice(s.periodStart(), lastDay, days, amount));
                break;
            }
            result.add(s);
            runningTotal = runningTotal.add(s.amount());
        }
        return result;
    }

    /**
     * Amount earned as of {@code asOf}: 0 before {@code from}, the full
     * amount at or after {@code to}, otherwise {@code round(dayRate *
     * daysInclusive(from, asOf), 2)}.
     */
    public static BigDecimal earnedThrough(BigDecimal amount, LocalDate from, LocalDate to, LocalDate asOf) {
        requireNonNegative(amount);
        daysInclusive(from, to); // validates to >= from

        if (asOf.isBefore(from)) {
            return BigDecimal.ZERO.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        }
        if (!asOf.isBefore(to)) {
            return amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal rate = dayRate(amount, from, to);
        int days = daysInclusive(from, asOf);
        return rate.multiply(BigDecimal.valueOf(days)).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    private static void requireNonNegative(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("amount must not be negative: " + amount);
        }
    }
}
