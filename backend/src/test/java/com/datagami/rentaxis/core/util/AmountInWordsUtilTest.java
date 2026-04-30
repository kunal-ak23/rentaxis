package com.datagami.rentaxis.core.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AmountInWordsUtilTest {

    @Test
    void zero() {
        assertThat(AmountInWordsUtil.toEnglishWords(BigDecimal.ZERO, "AED"))
                .isEqualTo("AED Zero Only");
    }

    @Test
    void one() {
        assertThat(AmountInWordsUtil.toEnglishWords(BigDecimal.ONE, "AED"))
                .isEqualTo("AED One Only");
    }

    @Test
    void nineteen() {
        assertThat(AmountInWordsUtil.toEnglishWords(new BigDecimal("19"), "AED"))
                .isEqualTo("AED Nineteen Only");
    }

    @Test
    void twenty() {
        assertThat(AmountInWordsUtil.toEnglishWords(new BigDecimal("20"), "AED"))
                .isEqualTo("AED Twenty Only");
    }

    @Test
    void ninetyNine() {
        assertThat(AmountInWordsUtil.toEnglishWords(new BigDecimal("99"), "AED"))
                .isEqualTo("AED Ninety Nine Only");
    }

    @Test
    void oneHundred() {
        assertThat(AmountInWordsUtil.toEnglishWords(new BigDecimal("100"), "AED"))
                .isEqualTo("AED One Hundred Only");
    }

    @Test
    void nineHundredNinetyNine() {
        assertThat(AmountInWordsUtil.toEnglishWords(new BigDecimal("999"), "AED"))
                .isEqualTo("AED Nine Hundred Ninety Nine Only");
    }

    @Test
    void oneThousand() {
        assertThat(AmountInWordsUtil.toEnglishWords(new BigDecimal("1000"), "AED"))
                .isEqualTo("AED One Thousand Only");
    }

    @Test
    void contractSampleNumber() {
        assertThat(AmountInWordsUtil.toEnglishWords(new BigDecimal("1751"), "AED"))
                .isEqualTo("AED One Thousand Seven Hundred Fifty One Only");
    }

    @Test
    void contractSampleTotal() {
        assertThat(AmountInWordsUtil.toEnglishWords(new BigDecimal("60300"), "AED"))
                .isEqualTo("AED Sixty Thousand Three Hundred Only");
    }

    @Test
    void oneMillion() {
        assertThat(AmountInWordsUtil.toEnglishWords(new BigDecimal("1000000"), "AED"))
                .isEqualTo("AED One Million Only");
    }

    @Test
    void largeNumber() {
        assertThat(AmountInWordsUtil.toEnglishWords(new BigDecimal("999999999"), "AED"))
                .isEqualTo("AED Nine Hundred Ninety Nine Million Nine Hundred Ninety Nine Thousand Nine Hundred Ninety Nine Only");
    }

    @Test
    void roundsFractionalPartToWholeUnit() {
        assertThat(AmountInWordsUtil.toEnglishWords(new BigDecimal("100.49"), "AED"))
                .isEqualTo("AED One Hundred Only");
        assertThat(AmountInWordsUtil.toEnglishWords(new BigDecimal("100.50"), "AED"))
                .isEqualTo("AED One Hundred One Only");
    }

    @Test
    void rejectsNegative() {
        assertThatThrownBy(() -> AmountInWordsUtil.toEnglishWords(new BigDecimal("-1"), "AED"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullAmount() {
        assertThatThrownBy(() -> AmountInWordsUtil.toEnglishWords(null, "AED"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
