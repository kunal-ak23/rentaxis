package com.datagami.rentaxis.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Fixes the app {@link Clock} on {@link #TODAY}, 2026-09-30, for suites written on a
 * fixed 2026–27 timeline whose contracts start on 2026-10-02.
 *
 * <p>An amendment is dated "today" on the app clock and rebuilds recognition from
 * that day ({@code LeasePostingService.amendLines}: posted months kept, one catch-up
 * dated today, the rest planned from today), and the AP aging reads a release as of
 * the day it was stamped. On the real clock those suites changed their answers the
 * day the calendar passed their contracts' start (2026-10-01/02): segments split at
 * today, advance rent partly recognised, a release after the report's as-of date.
 * Pinned here they keep the day they were written for. It moves only the {@code Clock}
 * bean; {@link LaterBusinessDayConfig}'s "not after today" policy is a separate bean
 * and may be imported alongside.</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class TimelineClockConfig {

    public static final ZoneId DUBAI = ZoneId.of("Asia/Dubai");
    public static final LocalDate TODAY = LocalDate.of(2026, 9, 30);

    @Bean
    @Primary
    Clock timelineClock() {
        return Clock.fixed(TODAY.atTime(10, 0).atZone(DUBAI).toInstant(), DUBAI);
    }
}
