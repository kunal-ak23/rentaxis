package com.datagami.rentaxis.core.service.lease;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review of R4-B, M5: a 12-month term ends the day before the anniversary, and the
 * anniversary of 29 February is 1 March — 29/02/2028 ends 28/02/2029. The renewal's
 * same-length check agrees with the web's {@code leaseMath.sameTermEnd}.
 */
class LeaseRenewalTermLengthTest {

    private static LocalDate d(String s) { return LocalDate.parse(s); }

    @Test
    void twelveMonthsEndTheDayBeforeTheAnniversary() {
        assertThat(LeaseRenewalService.termEnd(d("2028-02-29"), 12)).isEqualTo(d("2029-02-28"));
        assertThat(LeaseRenewalService.termEnd(d("2028-02-28"), 12)).isEqualTo(d("2029-02-27"));
        assertThat(LeaseRenewalService.termEnd(d("2027-02-28"), 12)).isEqualTo(d("2028-02-27"));
        assertThat(LeaseRenewalService.termEnd(d("2027-01-31"), 12)).isEqualTo(d("2028-01-30"));
        assertThat(LeaseRenewalService.termEnd(d("2027-03-01"), 12)).isEqualTo(d("2028-02-29"));
        assertThat(LeaseRenewalService.termEnd(d("2028-03-01"), 12)).isEqualTo(d("2029-02-28"));
    }

    @Test
    void aTwelveMonthTermFrom29FebruaryRenewsAsTwelveMonths() {
        assertThat(LeaseRenewalService.sameTermLength(d("2028-02-29"), d("2029-02-28"), d("2029-03-01"), d("2030-02-28")))
                .isTrue();
        assertThat(LeaseRenewalService.sameTermLength(d("2027-03-01"), d("2028-02-29"), d("2028-03-01"), d("2029-02-28")))
                .isTrue();
        // Unchanged: ordinary 12-month terms, and a different length is still different.
        assertThat(LeaseRenewalService.sameTermLength(d("2025-09-24"), d("2026-09-23"), d("2026-10-01"), d("2027-09-30")))
                .isTrue();
        assertThat(LeaseRenewalService.sameTermLength(d("2025-09-24"), d("2026-09-23"), d("2026-10-01"), d("2027-03-31")))
                .isFalse();
        assertThat(LeaseRenewalService.sameTermLength(d("2028-02-29"), d("2029-02-28"), d("2029-03-01"), d("2030-02-27")))
                .isFalse();
    }
}
