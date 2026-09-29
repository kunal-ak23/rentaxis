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

    /**
     * Break-it R3 money3: one entry point for every posting path, by its class — EVENT
     * refuses tomorrow, PLANNED a year and a day, SCHEDULE only the 2000–2099 range —
     * and {@code allows} answers the same question without the refusal.
     */
    @Test
    void requireByPathAppliesThePathsClass() {
        // 27/09 21:30 UTC is already 28/09 in Dubai.
        ManualPostingDates dates = new ManualPostingDates(
                Clock.fixed(Instant.parse("2026-09-27T21:30:00Z"), ManualPostingDates.BUSINESS_ZONE));
        LocalDate today = LocalDate.of(2026, 9, 28);

        assertThatCode(() -> dates.require(PostingDatePath.CHEQUE_RECEIVE, today)).doesNotThrowAnyException();
        assertThatThrownBy(() -> dates.require(PostingDatePath.CHEQUE_RECEIVE, today.plusDays(1)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("receipt date 29/09/2026 is in the future")
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode()).isEqualTo("date.inFuture"));
        assertThatThrownBy(() -> dates.require(PostingDatePath.SETTLEMENT, LocalDate.of(2099, 12, 31)))
                .hasMessageContaining("settlement date 31/12/2099 is in the future");

        assertThatCode(() -> dates.require(PostingDatePath.LEASE_TERMINATION, today.plusYears(1))).doesNotThrowAnyException();
        assertThatThrownBy(() -> dates.require(PostingDatePath.LEASE_TERMINATION, today.plusYears(1).plusDays(1)))
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode()).isEqualTo("posting.dateTooFarAhead"))
                .hasMessageContaining("A termination cannot be dated more than a year ahead");

        assertThatCode(() -> dates.require(PostingDatePath.RECOGNITION, LocalDate.of(2099, 12, 31))).doesNotThrowAnyException();
        assertThatThrownBy(() -> dates.require(PostingDatePath.RECOGNITION, LocalDate.of(2100, 1, 1)))
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode()).isEqualTo("posting.dateOutOfRange"));

        assertThatCode(() -> dates.require(PostingDatePath.CHEQUE_CANCEL, null)).doesNotThrowAnyException();
        assertThat(dates.allows(PostingDatePath.BANK_STATEMENT_LINE, today)).isTrue();
        assertThat(dates.allows(PostingDatePath.BANK_STATEMENT_LINE, today.plusDays(1))).isFalse();
        assertThat(dates.allows(PostingDatePath.BANK_MATCH_UNDO, today.plusYears(1).plusDays(1))).isFalse();
        // Break-it R4 money4 F2: the contract date is PLANNED now (at most a year ahead).
        assertThat(dates.allows(PostingDatePath.LEASE_POST, LocalDate.of(2099, 12, 31))).isFalse();
        assertThat(dates.allows(PostingDatePath.LEASE_POST, today.plusYears(1))).isTrue();
    }

    /** Break-it R3 money3 N1: the books may start at most three months ahead (month-end clamped, like Java). */
    @Test
    void theBooksStartWindowIsThreeMonths() {
        ManualPostingDates dates = new ManualPostingDates(
                Clock.fixed(Instant.parse("2026-11-30T08:00:00Z"), ManualPostingDates.BUSINESS_ZONE));
        assertThat(dates.latestBooksStart()).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThatCode(() -> dates.requireBooksStart(LocalDate.of(2027, 2, 28))).doesNotThrowAnyException();
        assertThatThrownBy(() -> dates.requireBooksStart(LocalDate.of(2027, 3, 1)))
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode()).isEqualTo("fiscal.booksStartTooFar"));
    }
}
