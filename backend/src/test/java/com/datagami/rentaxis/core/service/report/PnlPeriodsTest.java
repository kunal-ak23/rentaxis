package com.datagami.rentaxis.core.service.report;

import com.datagami.rentaxis.core.service.report.PnlPeriods.Compare;
import com.datagami.rentaxis.core.service.report.PnlPeriods.Period;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PnlPeriodsTest {

    private static Period prev(String from, String to) {
        return PnlPeriods.prior(LocalDate.parse(from), LocalDate.parse(to), Compare.PREVIOUS);
    }

    @Test
    void aMonthComparesWithThePreviousMonth() {
        assertThat(prev("2026-09-01", "2026-09-30")).isEqualTo(new Period(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31")));
        // February has fewer days than March: still the whole month.
        assertThat(prev("2028-03-01", "2028-03-31")).isEqualTo(new Period(LocalDate.parse("2028-02-01"), LocalDate.parse("2028-02-29")));
        assertThat(prev("2026-01-01", "2026-01-31")).isEqualTo(new Period(LocalDate.parse("2025-12-01"), LocalDate.parse("2025-12-31")));
    }

    @Test
    void aQuarterAndAYearCompareWithTheSameShapeBefore() {
        assertThat(prev("2026-07-01", "2026-09-30")).isEqualTo(new Period(LocalDate.parse("2026-04-01"), LocalDate.parse("2026-06-30")));
        assertThat(prev("2026-01-01", "2026-12-31")).isEqualTo(new Period(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31")));
    }

    @Test
    void aCustomRangeComparesWithTheSameNumberOfDaysEndingTheDayBefore() {
        // 10 days, 11th to 20th: the prior is the 1st to the 10th.
        assertThat(prev("2026-09-11", "2026-09-20")).isEqualTo(new Period(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-10")));
        assertThat(prev("2026-03-01", "2026-03-15")).isEqualTo(new Period(LocalDate.parse("2026-02-14"), LocalDate.parse("2026-02-28")));
    }

    @Test
    void lastYearMaps29FebruaryTo28() {
        assertThat(PnlPeriods.prior(LocalDate.parse("2028-02-01"), LocalDate.parse("2028-02-29"), Compare.LAST_YEAR))
                .isEqualTo(new Period(LocalDate.parse("2027-02-01"), LocalDate.parse("2027-02-28")));
        assertThat(PnlPeriods.prior(LocalDate.parse("2028-02-29"), LocalDate.parse("2028-02-29"), Compare.LAST_YEAR))
                .isEqualTo(new Period(LocalDate.parse("2027-02-28"), LocalDate.parse("2027-02-28")));
    }

    @Test
    void lastYearOfAWholeMonthRangeKeeps29February() {
        assertThat(PnlPeriods.prior(LocalDate.parse("2025-02-01"), LocalDate.parse("2025-02-28"), Compare.LAST_YEAR))
                .isEqualTo(new Period(LocalDate.parse("2024-02-01"), LocalDate.parse("2024-02-29")));
        // A March–February fiscal year: the prior year ends on 29 February 2024, not the 28th.
        assertThat(PnlPeriods.prior(LocalDate.parse("2024-03-01"), LocalDate.parse("2025-02-28"), Compare.LAST_YEAR))
                .isEqualTo(new Period(LocalDate.parse("2023-03-01"), LocalDate.parse("2024-02-29")));
    }

    @Test
    void noneHasNoPrior() {
        assertThat(PnlPeriods.prior(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"), Compare.NONE)).isNull();
    }
}
