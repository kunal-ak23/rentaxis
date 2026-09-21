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
