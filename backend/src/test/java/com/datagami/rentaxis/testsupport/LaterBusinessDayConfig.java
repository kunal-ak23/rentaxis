package com.datagami.rentaxis.testsupport;

import com.datagami.rentaxis.core.service.ledger.ManualPostingDates;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Break-it round 2 (money2) F1/F2: deposits, clears, bounces, write-offs and the
 * period lock may not be dated after today. Suites written on a fixed 2026–27
 * timeline (a lease that starts next month, its cheques deposited in October) are
 * refused as "in the future" while that timeline is still ahead of the calendar.
 *
 * <p>This replaces the {@link ManualPostingDates} bean with one whose "today" is
 * {@link #TODAY}: five years after the real Dubai date, so it stays after both the
 * suites' fixed dates and the JVM-clock defaults ({@code dateOrToday()}) however
 * long the suites live. That moves <b>every</b> rule the bean owns — "not after
 * today" and the one-year window on manual dates (journal vouchers, vouchers,
 * payment runs, cash receipts, penalty and bad-debt dates) — to that day. No other
 * clock in the context moves ({@code PostingService}'s century window, the
 * statement's as-of, recognition stay on the real clock).</p>
 *
 * <p>{@code @Import} it on such a suite; suites sharing it share one Spring context.
 * The rules themselves against the real today are held by suites that do not import
 * it ({@code ChequeBankEventDatesIT}, {@code BadDebtIT}, {@code ManualPostingDatesTest}).
 * The lasting fix is to rebase those suites' timelines on the real today.</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class LaterBusinessDayConfig {

    public static final LocalDate TODAY = LocalDate.now(ManualPostingDates.BUSINESS_ZONE).plusYears(5);

    @Bean
    @Primary
    ManualPostingDates laterManualPostingDates() {
        return new ManualPostingDates(Clock.fixed(TODAY.atTime(10, 0).atZone(ManualPostingDates.BUSINESS_ZONE).toInstant(),
                ManualPostingDates.BUSINESS_ZONE));
    }
}
