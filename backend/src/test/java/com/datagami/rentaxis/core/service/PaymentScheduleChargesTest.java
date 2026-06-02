package com.datagami.rentaxis.core.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentScheduleChargesTest {

    @Test
    void withVat_addsFivePercentWhenApplicable() {
        assertThat(PaymentScheduleService.withVat(new BigDecimal("100"), true))
                .isEqualByComparingTo("105.00");
    }

    @Test
    void withVat_returnsAmountWhenNotApplicable() {
        assertThat(PaymentScheduleService.withVat(new BigDecimal("100"), false))
                .isEqualByComparingTo("100");
    }

    @Test
    void withVat_nullAmountTreatedAsZero() {
        assertThat(PaymentScheduleService.withVat(null, true))
                .isEqualByComparingTo("0");
    }
}
