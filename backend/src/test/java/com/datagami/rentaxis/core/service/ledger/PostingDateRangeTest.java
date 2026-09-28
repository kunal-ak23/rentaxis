package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Break-it round 2 (money2) F1: a journal's year must lie in [today − 49, today + 50]
 * — exactly 100 distinct years — so no two dates allowed at once share a two-digit
 * year in their number, and a century typo (2126 for 2026) never reaches it.
 */
class PostingDateRangeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 28);

    private static boolean allowed(LocalDate d) {
        try {
            PostingService.requireNumberableDate(d, TODAY);
            return true;
        } catch (BusinessRuleViolationException e) {
            return false;
        }
    }

    @Test
    void bothBoundariesAreInsideAndTheYearsBeyondThemAreNot() {
        assertThat(allowed(LocalDate.of(1977, 1, 1))).isTrue();
        assertThat(allowed(LocalDate.of(1976, 12, 31))).isFalse();
        assertThat(allowed(LocalDate.of(2076, 12, 31))).isTrue();
        assertThat(allowed(LocalDate.of(2077, 1, 1))).isFalse();
        // A 50-year lease starting today ends inside the window.
        assertThat(allowed(TODAY.plusYears(50))).isTrue();
        assertThatCode(() -> PostingService.requireNumberableDate(null, TODAY)).doesNotThrowAnyException();
    }

    /** The collision the window exists to prevent: 1976 and 2076 would both number as "-76/". */
    @Test
    void noTwoAllowedYearsShareATwoDigitYear() {
        Map<Integer, Integer> byTwoDigits = new HashMap<>();
        int allowedYears = 0;
        for (int y = 1900; y <= 2200; y++) {
            if (!allowed(LocalDate.of(y, 6, 1))) continue;
            allowedYears++;
            Integer clash = byTwoDigits.put(y % 100, y);
            assertThat(clash).as("%d and %d share a journal-number year", y, clash).isNull();
        }
        assertThat(allowedYears).isEqualTo(100);
        assertThat(allowed(LocalDate.of(1976, 6, 1)) && allowed(LocalDate.of(2076, 6, 1))).isFalse();
    }

    @Test
    void aCenturyTypoIsRefusedInWords() {
        assertThatThrownBy(() -> PostingService.requireNumberableDate(LocalDate.of(2126, 1, 15), TODAY))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Posting date is out of range")
                .hasMessageContaining("between 1977 and 2076")
                .hasMessageNotContaining("uq_journal_entries_number")
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode())
                        .isEqualTo("posting.dateOutOfRange"));
    }
}
