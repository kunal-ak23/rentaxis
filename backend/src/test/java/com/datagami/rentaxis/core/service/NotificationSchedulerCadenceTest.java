package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How often a renter hears that a cheque is still unpaid.
 *
 * <p>The job runs daily over every due row, so the cadence is the whole difference
 * between a reminder and a mailbox nobody opens. Asserted as a table because the
 * rule is a table: a nudge on days 1, 3, 7 and 14, monthly after that, and silence
 * once the debt is old enough to be somebody's job rather than a cron's.</p>
 */
class NotificationSchedulerCadenceTest {

    @ParameterizedTest(name = "day {0} → send={1}")
    @CsvSource({
            // Day zero is the day grace ran out; the "due today" reminder covered it.
            "0,  false",
            "1,  true",
            "2,  false",
            "3,  true",
            "4,  false",
            "6,  false",
            "7,  true",
            "8,  false",
            "13, false",
            "14, true",
            "15, false",
            "29, false",
            "30, true",
            "31, false",
            "59, false",
            "60, true",
            "90, true",
            "120, true",
            "150, true",
            // The last monthly nudge, and then nothing.
            "180, true",
            "181, false",
            // A multiple of thirty, but past the cap.
            "210, false",
    })
    void cadenceIsDays1_3_7_14ThenMonthlyUntilTheCap(int daysOverdue, boolean send) {
        assertThat(NotificationScheduler.shouldRemind(daysOverdue)).isEqualTo(send);
    }

    /**
     * Past the cap the job says nothing at all — including on the multiples of
     * thirty that would otherwise qualify. A year-old debt is a collections matter
     * somebody is handling by hand.
     */
    @Test
    void nothingIsSentBeyondTheCapEvenOnAMonthlyBoundary() {
        assertThat(NotificationScheduler.MAX_OVERDUE_REMINDER_DAYS).isEqualTo(180);
        assertThat(NotificationScheduler.shouldRemind(210)).isFalse();
        assertThat(NotificationScheduler.shouldRemind(360)).isFalse();
        assertThat(NotificationScheduler.shouldRemind(365)).isFalse();
    }

    /** A negative count cannot happen — {@code daysOverdue} floors at zero — but it is not a send. */
    /**
     * A row in an open checkout is owed but not yet chased.
     *
     * <p>{@code ONLINE_PENDING} counts as due — nothing has posted, so the instalment
     * is exactly as unpaid as it was before the renter clicked Pay — but telling
     * someone their rent is overdue while they are on the gateway's own page is the
     * kind of reminder that teaches people to ignore reminders. Past the window the
     * session is an abandonment (the gateway reports nothing for an order nobody
     * finished) and the row is chased like any other.
     */
    @ParameterizedTest(name = "{0}, checkout {1}m ago → chase={2}")
    @CsvSource({
            // Not in a session at all: the window never applies.
            "REGISTERED,     1,   true",
            "DEPOSITED,      1,   true",
            "BOUNCED,        1,   true",
            // In a session: silence inside the window, chased outside it.
            "ONLINE_PENDING, 0,   false",
            "ONLINE_PENDING, 29,  false",
            "ONLINE_PENDING, 30,  true",
            "ONLINE_PENDING, 31,  true",
            "ONLINE_PENDING, 600, true",
    })
    void anOpenCheckoutSuppressesTheChaseUntilItIsOldEnough(ChequeStatus status, long minutesAgo, boolean chase) {
        Instant now = Instant.parse("2026-09-21T09:00:00Z");
        assertThat(NotificationScheduler.chaseable(status, now.minus(Duration.ofMinutes(minutesAgo)), now))
                .isEqualTo(chase);
    }

    /**
     * No open order at all — the renter finished or the session was superseded — so
     * there is nothing to wait for and the row is chased immediately.
     */
    @Test
    void anOnlinePendingRowWithNoOpenCheckoutIsChasedAtOnce() {
        Instant now = Instant.parse("2026-09-21T09:00:00Z");
        assertThat(NotificationScheduler.chaseable(ChequeStatus.ONLINE_PENDING, null, now)).isTrue();
        assertThat(NotificationScheduler.ABANDONED_CHECKOUT_AFTER).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void aNonPositiveCountNeverSends() {
        assertThat(NotificationScheduler.shouldRemind(-1)).isFalse();
        assertThat(NotificationScheduler.shouldRemind(0)).isFalse();
    }
}
