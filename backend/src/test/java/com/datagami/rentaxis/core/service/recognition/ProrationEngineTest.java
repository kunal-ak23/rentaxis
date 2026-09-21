package com.datagami.rentaxis.core.service.recognition;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.datagami.rentaxis.core.service.recognition.ProrationEngine.Slice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec §8.2's client fixture, verbatim where the original ProrationEngine
 * interface holds and re-derived where it does not: the brief's Step 1 gave
 * {@code truncate(List<Slice>, LocalDate)}, but its own Step 2 corrects the
 * binding signature to {@code truncate(List<Slice>, BigDecimal dayRate,
 * LocalDate lastDay)} — using the segment's stored 6-dp rate rather than
 * recomputing it from a truncated amount, which is the only way the earlier,
 * unaffected slices come back byte-identical to the untruncated ones. Every
 * expected number below was independently recomputed from the day rate
 * (139.726027) rather than copied from the brief.
 */
class ProrationEngineTest {

    static final LocalDate S = LocalDate.of(2026, 9, 24);
    static final LocalDate E = LocalDate.of(2027, 9, 23);

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    @Test
    void clientExampleFiftyOneThousand() {
        assertThat(ProrationEngine.daysInclusive(S, E)).isEqualTo(365);
        assertThat(ProrationEngine.dayRate(bd("51000"), S, E)).isEqualByComparingTo("139.726027");

        List<Slice> s = ProrationEngine.slice(bd("51000"), S, E);
        assertThat(s).hasSize(13);
        assertSlice(s.get(0), "2026-09-24", "2026-09-30", 7, "978.08");
        assertSlice(s.get(1), "2026-10-01", "2026-10-31", 31, "4331.51");
        assertSlice(s.get(2), "2026-11-01", "2026-11-30", 30, "4191.78");
        assertSlice(s.get(5), "2027-02-01", "2027-02-28", 28, "3912.33");
        assertSlice(s.get(12), "2027-09-01", "2027-09-23", 23, "3213.68");
        assertThat(s.stream().map(Slice::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("51000.00");
    }

    @Test
    void leapYearTermHas366Days() {
        LocalDate from = LocalDate.of(2027, 3, 1), to = LocalDate.of(2028, 2, 29);
        assertThat(ProrationEngine.daysInclusive(from, to)).isEqualTo(366);
        assertThat(ProrationEngine.dayRate(bd("36600"), from, to)).isEqualByComparingTo("100.000000");
        assertThat(ProrationEngine.slice(bd("36600"), from, to).get(11).amount()).isEqualByComparingTo("2900.00"); // Feb 2028 = 29 days
    }

    @Test
    void singleDayAndSingleMonthTerms() {
        assertThat(ProrationEngine.slice(bd("100"), S, S)).singleElement()
                .satisfies(x -> assertThat(x.amount()).isEqualByComparingTo("100.00"));
        assertThat(ProrationEngine.slice(bd("3000"), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))
                .singleElement().satisfies(x -> assertThat(x.days()).isEqualTo(31));
    }

    /**
     * Re-derivation (Task brief's numbers checked independently):
     * daysInclusive(S, 2027-01-15) = 7 (Sep) + 31 (Oct) + 30 (Nov) + 31 (Dec) + 15 (Jan) = 114.
     * earnedTotal = round(139.726027 * 114, 2) = round(15928.767078, 2) = 15928.77.
     * The first four full slices (Sep..Dec) sum to 978.08 + 4331.51 + 4191.78 + 4331.51 = 13832.88
     * (Oct and Dec are both 31-day months at the same rate, hence both 4331.51).
     * So the truncated Jan slice must be 15928.77 - 13832.88 = 2095.89 — not
     * round(139.726027 * 15, 2), which happens to also be 2095.89 here but is not
     * the general rule (see the brief's own note on this).
     */
    @Test
    void truncateReslicesTheMonthContainingTheCutoff() {
        BigDecimal dayRate = ProrationEngine.dayRate(bd("51000"), S, E);
        List<Slice> s = ProrationEngine.slice(bd("51000"), S, E);
        LocalDate cutoff = LocalDate.of(2027, 1, 15);

        List<Slice> t = ProrationEngine.truncate(s, dayRate, cutoff);
        assertThat(t).hasSize(5);
        assertSlice(t.get(4), "2027-01-01", "2027-01-15", 15, "2095.89");

        BigDecimal earned = t.stream().map(Slice::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(earned).isEqualByComparingTo(ProrationEngine.earnedThrough(bd("51000"), S, E, cutoff));
        assertThat(earned).isEqualByComparingTo("15928.77");
    }

    /**
     * Step 2's extra requirement: every slice before the cut month must come
     * back identical to its untruncated counterpart, not merely equal in
     * value but constructed the same way (same day rate, no re-slicing).
     */
    @Test
    void slicesBeforeTheCutMonthAreUnchanged() {
        BigDecimal dayRate = ProrationEngine.dayRate(bd("51000"), S, E);
        List<Slice> s = ProrationEngine.slice(bd("51000"), S, E);
        List<Slice> t = ProrationEngine.truncate(s, dayRate, LocalDate.of(2027, 1, 15));

        for (int i = 0; i < 4; i++) {
            assertThat(t.get(i)).isEqualTo(s.get(i));
        }
    }

    /**
     * At the brief's own cutoff (2027-01-15) {@code earnedThrough - Σprevious}
     * and {@code round(dayRate * daysInSlice, 2)} happen to coincide (both
     * 2095.89), which is exactly the coincidence the brief warns about — a
     * mutation to the wrong formula would slip past that fixture undetected.
     * 2027-06-10 is a cutoff where the two formulas diverge (1397.25 vs
     * 1397.26 — verified independently in Python with the same HALF_UP/scale
     * rules), so this test actually exercises the "not a general rule" claim.
     */
    @Test
    void truncateAtACutoffWhereTheTwoFormulasDiverge() {
        BigDecimal dayRate = ProrationEngine.dayRate(bd("51000"), S, E);
        List<Slice> s = ProrationEngine.slice(bd("51000"), S, E);
        LocalDate cutoff = LocalDate.of(2027, 6, 10);

        List<Slice> t = ProrationEngine.truncate(s, dayRate, cutoff);
        assertThat(t).hasSize(10);
        assertSlice(t.get(9), "2027-06-01", "2027-06-10", 10, "1397.25");

        BigDecimal earned = t.stream().map(Slice::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(earned).isEqualByComparingTo(ProrationEngine.earnedThrough(bd("51000"), S, E, cutoff));
        assertThat(earned).isEqualByComparingTo("36328.77");
    }

    /**
     * Review fix-round-1, Critical #1: a termination effective on a calendar
     * month-end lands {@code lastDay} exactly on a slice's own
     * {@code periodEnd}. Before the fix, the loop's containment test
     * ({@code lastDay.isBefore(s.periodEnd())}) was {@code false} for that
     * slice, so it fell through to the "keep unchanged" branch and the loop
     * moved on to the *next* slice, whose {@code periodStart} is after
     * {@code lastDay} — {@code daysInclusive} then threw
     * {@code IllegalArgumentException}. This is the first of the slices
     * (index 0, September), so the truncated result is that single 7-day
     * slice, unchanged in every field from the untruncated {@code slice()}
     * output — no rounding drift is possible with nothing preceding it.
     *
     * <p>This test throws against the pre-fix code (confirmed by reverting
     * the fix and re-running: {@code IllegalArgumentException: to
     * (2026-09-30) is before from (2026-10-01)}).
     */
    @Test
    void truncateAtFirstSlicesPeriodEnd() {
        BigDecimal dayRate = ProrationEngine.dayRate(bd("51000"), S, E);
        List<Slice> s = ProrationEngine.slice(bd("51000"), S, E);
        LocalDate cutoff = LocalDate.of(2026, 9, 30);

        List<Slice> t = ProrationEngine.truncate(s, dayRate, cutoff);
        assertThat(t).hasSize(1);
        assertSlice(t.get(0), "2026-09-24", "2026-09-30", 7, "978.08");
        assertThat(t.get(0)).isEqualTo(s.get(0));

        BigDecimal earned = t.stream().map(Slice::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(earned).isEqualByComparingTo(ProrationEngine.earnedThrough(bd("51000"), S, E, cutoff));
        assertThat(earned).isEqualByComparingTo("978.08");
    }

    /**
     * Same Critical #1 bug, a second (non-first, non-final) month — October.
     * Re-derived independently: daysInclusive(S, 2026-10-31) = 38,
     * earnedThrough = round(139.726027 * 38, 2) = round(5309.589026, 2) =
     * 5309.59; the one preceding slice (Sep, 978.08) is unchanged, so the
     * October slice is 5309.59 - 978.08 = 4331.51 — the same value
     * {@code slice()} itself gave that month (no drift here; see the
     * January case below for a cutoff where drift does occur).
     *
     * <p>Throws against the pre-fix code (same shape of exception as the
     * September case, one slice further in).
     */
    @Test
    void truncateAtANonFinalSlicesPeriodEndOctober() {
        BigDecimal dayRate = ProrationEngine.dayRate(bd("51000"), S, E);
        List<Slice> s = ProrationEngine.slice(bd("51000"), S, E);
        LocalDate cutoff = LocalDate.of(2026, 10, 31);

        List<Slice> t = ProrationEngine.truncate(s, dayRate, cutoff);
        assertThat(t).hasSize(2);
        assertThat(t.get(0)).isEqualTo(s.get(0));
        assertSlice(t.get(1), "2026-10-01", "2026-10-31", 31, "4331.51");

        BigDecimal earned = t.stream().map(Slice::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(earned).isEqualByComparingTo(ProrationEngine.earnedThrough(bd("51000"), S, E, cutoff));
        assertThat(earned).isEqualByComparingTo("5309.59");
    }

    /**
     * Same Critical #1 bug, a third month (December) — this is exactly the
     * reviewer's own reproduction case
     * ({@code truncate(lastDay=2026-12-31) THREW}). Re-derived: daysInclusive(S,
     * 2026-12-31) = 99, earnedThrough = round(139.726027 * 99, 2) =
     * round(13832.876673, 2) = 13832.88; the three preceding slices
     * (978.08 + 4331.51 + 4191.78 = 9501.37) are unchanged, so December is
     * 13832.88 - 9501.37 = 4331.51 (again matching the untruncated value —
     * the drift below in January is the interesting case).
     */
    @Test
    void truncateAtANonFinalSlicesPeriodEndDecember() {
        BigDecimal dayRate = ProrationEngine.dayRate(bd("51000"), S, E);
        List<Slice> s = ProrationEngine.slice(bd("51000"), S, E);
        LocalDate cutoff = LocalDate.of(2026, 12, 31);

        List<Slice> t = ProrationEngine.truncate(s, dayRate, cutoff);
        assertThat(t).hasSize(4);
        for (int i = 0; i < 3; i++) {
            assertThat(t.get(i)).isEqualTo(s.get(i));
        }
        assertSlice(t.get(3), "2026-12-01", "2026-12-31", 31, "4331.51");

        BigDecimal earned = t.stream().map(Slice::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(earned).isEqualByComparingTo(ProrationEngine.earnedThrough(bd("51000"), S, E, cutoff));
        assertThat(earned).isEqualByComparingTo("13832.88");
    }

    /**
     * The rounding-drift case the fixed contract calls for explicitly: at
     * cutoff 2027-01-31 (January's own {@code periodEnd}), daysInclusive(S,
     * cutoff) = 130, earnedThrough = round(139.726027 * 130, 2) =
     * round(18164.38351, 2) = <b>18164.38</b>. The four preceding slices sum
     * to 978.08 + 4331.51 + 4191.78 + 4331.51 = 13832.88, so January's
     * recomputed amount is 18164.38 - 13832.88 = <b>4331.50</b> — one cent
     * less than the 4331.51 {@code slice()} itself gave that same month.
     * This is the accumulated-rounding drift the fix's Javadoc warns about:
     * the rule is "earnedThrough - Σprevious", not "reuse the original
     * slice's amount", and this is the fixture where the two actually
     * differ. Verified independently via Python {@code Decimal} with the
     * same HALF_UP/scale rules before writing this assertion.
     */
    @Test
    void truncateAtANonFinalSlicesPeriodEndJanuaryHasRoundingDrift() {
        BigDecimal dayRate = ProrationEngine.dayRate(bd("51000"), S, E);
        List<Slice> s = ProrationEngine.slice(bd("51000"), S, E);
        LocalDate cutoff = LocalDate.of(2027, 1, 31);

        List<Slice> t = ProrationEngine.truncate(s, dayRate, cutoff);
        assertThat(t).hasSize(5);
        for (int i = 0; i < 4; i++) {
            assertThat(t.get(i)).isEqualTo(s.get(i));
        }
        // Note: 4331.50, NOT 4331.51 (s.get(4).amount()) — the drift is real.
        assertSlice(t.get(4), "2027-01-01", "2027-01-31", 31, "4331.50");
        assertThat(t.get(4).amount()).isNotEqualByComparingTo(s.get(4).amount());

        BigDecimal earned = t.stream().map(Slice::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(earned).isEqualByComparingTo(ProrationEngine.earnedThrough(bd("51000"), S, E, cutoff));
        assertThat(earned).isEqualByComparingTo("18164.38");
    }

    /**
     * {@code lastDay} equal to the segment's own start: a one-day
     * termination. daysInclusive(S, S) = 1, earnedThrough = round(139.726027
     * * 1, 2) = 139.73. Both the pre-fix and post-fix code give the correct
     * result here (S is strictly before the first slice's periodEnd, so the
     * old {@code isBefore} containment test already matched it) — this test
     * documents the boundary rather than pinning a regression.
     */
    @Test
    void truncateAtSegmentStartReturnsASingleDaySlice() {
        BigDecimal dayRate = ProrationEngine.dayRate(bd("51000"), S, E);
        List<Slice> s = ProrationEngine.slice(bd("51000"), S, E);

        List<Slice> t = ProrationEngine.truncate(s, dayRate, S);
        assertThat(t).hasSize(1);
        assertSlice(t.get(0), "2026-09-24", "2026-09-24", 1, "139.73");
        assertThat(t.get(0).amount()).isEqualByComparingTo(ProrationEngine.earnedThrough(bd("51000"), S, E, S));
    }

    /**
     * {@code lastDay} before the segment's own start: documented in the
     * Javadoc as "earned nothing", so the result is an empty list, not an
     * exception. Already correct pre-fix (the early-return guard is
     * untouched by this round's fix) — included for the boundary coverage
     * the review asked for, not because it was broken.
     */
    @Test
    void truncateBeforeSegmentStartReturnsAnEmptyList() {
        BigDecimal dayRate = ProrationEngine.dayRate(bd("51000"), S, E);
        List<Slice> s = ProrationEngine.slice(bd("51000"), S, E);

        assertThat(ProrationEngine.truncate(s, dayRate, S.minusDays(1))).isEmpty();
    }

    /**
     * {@code lastDay} at, or strictly after, the segment's own end: nothing
     * to truncate, the original slices come back unchanged (same list
     * contents; the early-return path returns a fresh list wrapping the same
     * {@code Slice} objects). Already correct pre-fix — included for the
     * boundary coverage the review asked for, not because it was broken.
     */
    @Test
    void truncateAtOrAfterSegmentEndReturnsTheOriginalSlicesUnchanged() {
        BigDecimal dayRate = ProrationEngine.dayRate(bd("51000"), S, E);
        List<Slice> s = ProrationEngine.slice(bd("51000"), S, E);

        assertThat(ProrationEngine.truncate(s, dayRate, E)).isEqualTo(s);
        assertThat(ProrationEngine.truncate(s, dayRate, E.plusYears(1))).isEqualTo(s);
    }

    @Test
    void earnedThroughBoundaries() {
        assertThat(ProrationEngine.earnedThrough(bd("51000"), S, E, S.minusDays(1))).isEqualByComparingTo("0");
        assertThat(ProrationEngine.earnedThrough(bd("51000"), S, E, E)).isEqualByComparingTo("51000");
        assertThat(ProrationEngine.earnedThrough(bd("51000"), S, E, E.plusYears(1))).isEqualByComparingTo("51000");
    }

    @Test
    void rejectsNegativeAmountAndInvertedDates() {
        assertThatThrownBy(() -> ProrationEngine.slice(bd("-100"), S, E))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProrationEngine.slice(bd("100"), E, S))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProrationEngine.dayRate(bd("-1"), S, E))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProrationEngine.daysInclusive(E, S))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertSlice(Slice s, String from, String to, int days, String amount) {
        assertThat(s.periodStart()).isEqualTo(LocalDate.parse(from));
        assertThat(s.periodEnd()).isEqualTo(LocalDate.parse(to));
        assertThat(s.days()).isEqualTo(days);
        assertThat(s.amount()).isEqualByComparingTo(amount);
    }
}
