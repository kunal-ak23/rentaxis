package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Batch 4 review #5: the window is read from the injected clock, in Asia/Dubai. */
class ManualPostingDatesTest {

    @Test
    void theWindowFollowsTheInjectedClock() {
        // 27/09 21:30 UTC is already 28/09 in Dubai.
        ManualPostingDates dates = new ManualPostingDates(
                Clock.fixed(Instant.parse("2026-09-27T21:30:00Z"), ManualPostingDates.BUSINESS_ZONE));
        assertThat(dates.latestAllowed()).isEqualTo(LocalDate.of(2027, 9, 28));
        assertThatCode(() -> dates.requireWithinAYear(LocalDate.of(2027, 9, 28), "A journal voucher"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> dates.requireWithinAYear(LocalDate.of(2027, 9, 29), "A journal voucher"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("more than a year ahead")
                .hasMessageContaining("28/09/2027");
        assertThatCode(() -> dates.requireWithinAYear(null, "A journal voucher")).doesNotThrowAnyException();
    }

    /** Break-it R2 money2 F1/F2/F3: the shared "not after today" rule, on the Dubai day. */
    @Test
    void notAfterTodayAllowsTodayAndRefusesTomorrow() {
        // 27/09 21:30 UTC is already 28/09 in Dubai.
        ManualPostingDates dates = new ManualPostingDates(
                Clock.fixed(Instant.parse("2026-09-27T21:30:00Z"), ManualPostingDates.BUSINESS_ZONE));
        assertThat(dates.today()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThatCode(() -> dates.requireNotAfterToday(LocalDate.of(2026, 9, 28), "clearing", "x"))
                .doesNotThrowAnyException();
        assertThatCode(() -> dates.requireNotAfterToday(LocalDate.of(2001, 1, 1), "clearing", "x"))
                .doesNotThrowAnyException();
        assertThatCode(() -> dates.requireNotAfterToday(null, "clearing", "x")).doesNotThrowAnyException();
        assertThatThrownBy(() -> dates.requireNotAfterToday(LocalDate.of(2026, 9, 29), "clearing",
                "Funds cannot have cleared yet, and nothing was cleared."))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("The clearing date 29/09/2026 is in the future (today is 28/09/2026)")
                .hasMessageContaining("nothing was cleared")
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode()).isEqualTo("date.inFuture"));
    }

    @Test
    void twentyNinthOfFebruaryMovesToTheTwentyEighth() {
        ManualPostingDates dates = new ManualPostingDates(
                Clock.fixed(Instant.parse("2028-02-29T08:00:00Z"), ManualPostingDates.BUSINESS_ZONE));
        assertThat(dates.latestAllowed()).isEqualTo(LocalDate.of(2029, 2, 28));
    }
}
