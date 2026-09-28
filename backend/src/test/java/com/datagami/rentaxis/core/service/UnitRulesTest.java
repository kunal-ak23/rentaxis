package com.datagami.rentaxis.core.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Break-it R3 ops3 F1/F2: the unit number key and the shared field rules. */
class UnitRulesTest {

    @Test
    void normaliseTrimsCollapsesFoldsCaseAndReadsArabicDigits() {
        assertThat(UnitRules.normalise("  OPS-B101 ")).isEqualTo("ops-b101");
        assertThat(UnitRules.normalise("Flat \t  7")).isEqualTo("flat 7");
        assertThat(UnitRules.normalise("١٠١")).isEqualTo("101");
        assertThat(UnitRules.normalise("۱۰۲")).isEqualTo("102");
        assertThat(UnitRules.normalise("A-١٠١")).isEqualTo(UnitRules.normalise("a-101"));
        assertThat(UnitRules.normalise("101")).isNotEqualTo(UnitRules.normalise("1 01"));
    }

    @Test
    void displayCollapsesWhitespaceButKeepsWhatWasTyped() {
        assertThat(UnitRules.display("  Flat   7 ")).isEqualTo("Flat 7");
        assertThat(UnitRules.display("١٠١")).isEqualTo("١٠١");
    }

    @Test
    void fieldProblems() {
        assertThat(UnitRules.fieldProblems("101", new BigDecimal("850"), new BigDecimal("55000.50"), null)).isEmpty();
        assertThat(UnitRules.fieldProblems(" ", null, null, null)).containsExactly(UnitRules.NUMBER_REQUIRED);
        assertThat(UnitRules.fieldProblems("x".repeat(51), null, null, null)).containsExactly(UnitRules.NUMBER_TOO_LONG);
        assertThat(UnitRules.fieldProblems("1", new BigDecimal("-1"), null, null)).containsExactly(UnitRules.SIZE_NOT_POSITIVE);
        assertThat(UnitRules.fieldProblems("1", new BigDecimal("10.555"), null, null)).containsExactly(UnitRules.SIZE_TOO_MANY_DECIMALS);
        assertThat(UnitRules.fieldProblems("1", null, new BigDecimal("1000.555"), null))
                .containsExactly("Expected rent: Amounts can have at most 2 decimal places");
        assertThat(UnitRules.fieldProblems("1", null, new BigDecimal("-5000"), new BigDecimal("-1")))
                .containsExactly("Expected rent: Amounts cannot be negative", "Actual rent: Amounts cannot be negative");
    }
}
