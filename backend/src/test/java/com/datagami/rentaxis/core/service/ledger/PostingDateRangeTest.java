package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Break-it round 2 (money2) F1: a journal's date must be within 60 years of today's,
 * so a century typo (2126 for 2026) never reaches the two-digit-year numbering.
 */
class PostingDateRangeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 28);

    @Test
    void theWindowIsSixtyYearsEitherSideOfThisYear() {
        assertThatCode(() -> PostingService.requireNumberableDate(LocalDate.of(1966, 1, 1), TODAY)).doesNotThrowAnyException();
        assertThatCode(() -> PostingService.requireNumberableDate(LocalDate.of(2086, 12, 31), TODAY)).doesNotThrowAnyException();
        assertThatCode(() -> PostingService.requireNumberableDate(TODAY, TODAY)).doesNotThrowAnyException();
        // A 50-year lease starting today ends inside the window.
        assertThatCode(() -> PostingService.requireNumberableDate(TODAY.plusYears(50), TODAY)).doesNotThrowAnyException();
        assertThatCode(() -> PostingService.requireNumberableDate(null, TODAY)).doesNotThrowAnyException();
    }

    @Test
    void aCenturyTypoAndTheYearsJustOutsideAreRefusedInWords() {
        for (LocalDate bad : new LocalDate[]{LocalDate.of(2126, 1, 15), LocalDate.of(2087, 1, 1), LocalDate.of(1965, 12, 31)}) {
            assertThatThrownBy(() -> PostingService.requireNumberableDate(bad, TODAY))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("Posting date is out of range")
                    .hasMessageNotContaining("uq_journal_entries_number")
                    .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode())
                            .isEqualTo("posting.dateOutOfRange"));
        }
    }
}
