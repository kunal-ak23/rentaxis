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

    @Test
    void twentyNinthOfFebruaryMovesToTheTwentyEighth() {
        ManualPostingDates dates = new ManualPostingDates(
                Clock.fixed(Instant.parse("2028-02-29T08:00:00Z"), ManualPostingDates.BUSINESS_ZONE));
        assertThat(dates.latestAllowed()).isEqualTo(LocalDate.of(2029, 2, 28));
    }
}
