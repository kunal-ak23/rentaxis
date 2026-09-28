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
 * Break-it round 2 (money2) F1: a journal's date must lie in the fixed century
 * 2000-01-01 .. 2099-12-31, so no two dates ever allowed share a two-digit year in
 * their number, and a century typo (2126 for 2026) never reaches it.
 */
class PostingDateRangeTest {

    private static boolean allowed(LocalDate d) {
        try {
            PostingService.requireNumberableDate(d);
            return true;
        } catch (BusinessRuleViolationException e) {
            return false;
        }
    }

    @Test
    void bothBoundariesAreInsideAndTheDaysBeyondThemAreNot() {
        assertThat(allowed(LocalDate.of(1999, 12, 31))).isFalse();
        assertThat(allowed(LocalDate.of(2000, 1, 1))).isTrue();
        assertThat(allowed(LocalDate.of(2099, 12, 31))).isTrue();
        assertThat(allowed(LocalDate.of(2100, 1, 1))).isFalse();
        assertThatCode(() -> PostingService.requireNumberableDate(null)).doesNotThrowAnyException();
    }

    /** The collision the range exists to prevent: 1976 and 2076 would both number as "-76/". */
    @Test
    void noTwoAllowedYearsShareATwoDigitYear() {
        Map<Integer, Integer> byTwoDigits = new HashMap<>();
        int allowedYears = 0;
        for (int y = 1800; y <= 2300; y++) {
            if (!allowed(LocalDate.of(y, 6, 1))) continue;
            allowedYears++;
            Integer clash = byTwoDigits.put(y % 100, y);
            assertThat(clash).as("%d and %d share a journal-number year", y, clash).isNull();
        }
        assertThat(allowedYears).isEqualTo(100);
    }

    @Test
    void aCenturyTypoIsRefusedInWords() {
        assertThatThrownBy(() -> PostingService.requireNumberableDate(LocalDate.of(2126, 1, 15)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Posting date is out of range")
                .hasMessageContaining("between 2000 and 2099")
                .hasMessageNotContaining("uq_journal_entries_number")
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode())
                        .isEqualTo("posting.dateOutOfRange"));
    }
}
