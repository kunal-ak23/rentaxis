package com.datagami.rentaxis.core.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Break-it R3 data3 F3: the cut-over and portfolio importers read dates through
 * {@link SheetCells#parseDateOrNull}. The day-first patterns resolved SMART, so a
 * typo like 31/11/2026 became 2026-11-30 and a lease was posted on a date nobody
 * typed. Every form must now resolve STRICT, like ISO already did.
 */
class SheetCellsDateTest {

    @ParameterizedTest
    @ValueSource(strings = {"31/11/2026", "31-11-2026", "31/02/2026", "30/02/2026", "29/02/2026", "29-02-2027",
            "2026-02-30", "2027-02-29", "2026-13-01", "00/01/2026", "32/01/2026"})
    void impossibleCalendarDatesAreNotDatesInAnyForm(String raw) {
        assertThat(SheetCells.parseDateOrNull(raw)).as(raw).isNull();
    }

    @Test
    void realDatesStillParseInEveryAcceptedForm() {
        assertThat(SheetCells.parseDateOrNull("30/11/2026")).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(SheetCells.parseDateOrNull("28-02-2026")).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(SheetCells.parseDateOrNull(" 2026-09-01 ")).isEqualTo(LocalDate.of(2026, 9, 1));
        // 2028 is a leap year: 29 February exists.
        assertThat(SheetCells.parseDateOrNull("29/02/2028")).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(SheetCells.parseDateOrNull("29-02-2028")).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(SheetCells.parseDateOrNull("2028-02-29")).isEqualTo(LocalDate.of(2028, 2, 29));
    }

    @Test
    void blankAndGarbageAreNull() {
        assertThat(SheetCells.parseDateOrNull(null)).isNull();
        assertThat(SheetCells.parseDateOrNull("  ")).isNull();
        assertThat(SheetCells.parseDateOrNull("11/31/2026")).isNull();
        assertThat(SheetCells.parseDateOrNull("46296")).isNull();
    }
}
