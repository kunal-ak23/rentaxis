package com.datagami.rentaxis.core.notification;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The structured notification text (#81) carries raw values for the reader to
 * format in its own locale: no thousands separators, no "AED", ISO dates.
 */
class NotificationMessageTest {

    @Test
    void valuesAreRawSoTheReaderFormatsThem() {
        NotificationMessage m = NotificationMessage.of("PAYMENT_DUE",
                "seq", 2,
                "amount", new BigDecimal("31500.00"),
                "date", LocalDate.of(2026, 10, 1),
                "slot", Instant.parse("2026-09-10T06:00:00Z"),
                "reason", java.time.DayOfWeek.MONDAY);

        assertThat(m.key()).isEqualTo("PAYMENT_DUE");
        assertThat(m.params()).containsExactly(
                java.util.Map.entry("seq", "2"),
                java.util.Map.entry("amount", "31500.00"),
                java.util.Map.entry("date", "2026-10-01"),
                java.util.Map.entry("slot", "2026-09-10T06:00:00Z"),
                java.util.Map.entry("reason", "MONDAY"));
    }

    @Test
    void aNullValueIsLeftOutSoTheSentenceFallsBackToItsVariantWithoutIt() {
        NotificationMessage m = NotificationMessage.of("LISTING_AVAILABLE", "listingTitle", null);

        assertThat(m.params()).isEmpty();
    }

    @Test
    void aBigDecimalIsNeverWrittenInScientificNotation() {
        assertThat(NotificationMessage.of("K", "amount", new BigDecimal("1E+5")).params().get("amount"))
                .isEqualTo("100000");
    }

    @Test
    void oddKeyValuesAreRejected() {
        assertThatThrownBy(() -> NotificationMessage.of("K", "amount"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
