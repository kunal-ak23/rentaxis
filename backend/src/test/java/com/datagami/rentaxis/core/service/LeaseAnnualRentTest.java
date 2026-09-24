package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.Lease;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** F14-57: a unit's actual rent is the holder lease's rent annualised. */
class LeaseAnnualRentTest {

    private static Lease lease(String rent, LocalDate start, LocalDate end) {
        Lease l = new Lease();
        l.setRentAmount(new BigDecimal(rent));
        l.setStartDate(start);
        l.setEndDate(end);
        return l;
    }

    @Test
    void aFifteenMonthTermIsScaledToAYear() {
        assertThat(LeaseService.annualRent(lease("150000", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 3, 31))))
                .isEqualByComparingTo("120330");
    }

    @Test
    void aTwelveMonthTermKeepsItsRent() {
        assertThat(LeaseService.annualRent(lease("60000", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))))
                .isEqualByComparingTo("60000");
    }

    @Test
    void noDatesKeepsTheContractFigure() {
        assertThat(LeaseService.annualRent(lease("60000", null, null))).isEqualByComparingTo("60000");
    }
}
