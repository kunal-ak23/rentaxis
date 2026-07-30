package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GateWalkInServiceTest {

    @Test
    void normalizesFormattingAndInternationalDialPrefix() {
        assertThat(GateWalkInService.normalizePhone("00 971 50-123-4567"))
                .isEqualTo("+971501234567");
        assertThat(GateWalkInService.normalizePhone("+91 (98765) 43210"))
                .isEqualTo("+919876543210");
    }

    @Test
    void rejectsNumbersWithoutCountryCode() {
        assertThatThrownBy(() -> GateWalkInService.normalizePhone("0501234567"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("country code");
    }

    @Test
    void rejectsBlankAndMalformedNumbers() {
        assertThatThrownBy(() -> GateWalkInService.normalizePhone("  "))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> GateWalkInService.normalizePhone("+12"))
                .isInstanceOf(BusinessRuleViolationException.class);
    }
}
