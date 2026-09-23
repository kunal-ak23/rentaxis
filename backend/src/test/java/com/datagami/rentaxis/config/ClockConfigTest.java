package com.datagami.rentaxis.config;

import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Today" is the UAE's today, not the server's. Production runs in UTC, so from
 * 00:00 to 04:00 in Dubai a server-zone {@code LocalDate.now()} is yesterday.
 */
class ClockConfigTest {

    private TimeZone saved;

    @BeforeEach
    void save() {
        saved = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @AfterEach
    void restore() {
        TimeZone.setDefault(saved);
    }

    @Test
    void theDefaultZoneIsDubai() {
        assertThat(AppTimeZone.DEFAULT).isEqualTo(ZoneId.of("Asia/Dubai"));
        assertThat(AppTimeZone.resolve(null)).isEqualTo(ZoneId.of("Asia/Dubai"));
        assertThat(AppTimeZone.resolve("  ")).isEqualTo(ZoneId.of("Asia/Dubai"));
    }

    @Test
    void theClockAndTheJvmDefaultFollowTheConfiguredZone() {
        Clock clock = new ClockConfig("Asia/Dubai").systemClock();
        assertThat(clock.getZone()).isEqualTo(ZoneId.of("Asia/Dubai"));
        assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Dubai");

        Clock other = new ClockConfig("Europe/London").systemClock();
        assertThat(other.getZone()).isEqualTo(ZoneId.of("Europe/London"));
        assertThat(TimeZone.getDefault().getID()).isEqualTo("Europe/London");
    }

    /**
     * 01:30 on 24 September in Dubai is 21:30 on the 23rd in UTC. With the zone
     * applied, the date a bare {@code LocalDate.now()} derives from that instant —
     * {@code ZoneId.systemDefault()} is what it reads — is the 24th, and a cheque
     * action posted with no date lands on Dubai's today.
     */
    @Test
    void justAfterMidnightInDubaiTodayIsDubaisDate() {
        Instant earlyMorningInDubai = Instant.parse("2026-09-23T21:30:00Z");
        assertThat(LocalDate.ofInstant(earlyMorningInDubai, ZoneId.systemDefault()))
                .as("before the zone is applied: the server's UTC date")
                .isEqualTo(LocalDate.of(2026, 9, 23));

        new ClockConfig(null).systemClock();

        assertThat(LocalDate.ofInstant(earlyMorningInDubai, ZoneId.systemDefault()))
                .isEqualTo(LocalDate.of(2026, 9, 24));
        assertThat(ChequeActionRequest.empty().dateOrToday())
                .isEqualTo(LocalDate.now(ZoneId.of("Asia/Dubai")));
    }
}
