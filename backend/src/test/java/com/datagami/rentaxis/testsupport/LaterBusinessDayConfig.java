package com.datagami.rentaxis.testsupport;

import com.datagami.rentaxis.core.service.ledger.ManualPostingDates;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Break-it round 2 (money2) F1/F2: deposits, clears, bounces and write-offs may not
 * be dated after today. Suites written on a fixed 2026–2027 timeline (a lease that
 * starts next month, its cheques deposited in October) would stop working as the
 * calendar passes them — or, before it does, be refused as "in the future". This
 * moves only the date rules' "today" ({@link ManualPostingDates}) to {@link #TODAY};
 * every other clock in the context stays real. {@code @Import} it on such a suite;
 * suites sharing it share one Spring context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class LaterBusinessDayConfig {

    public static final LocalDate TODAY = LocalDate.of(2031, 1, 1);

    @Bean
    @Primary
    ManualPostingDates laterManualPostingDates() {
        return new ManualPostingDates(Clock.fixed(TODAY.atTime(10, 0).atZone(ManualPostingDates.BUSINESS_ZONE).toInstant(),
                ManualPostingDates.BUSINESS_ZONE));
    }
}
