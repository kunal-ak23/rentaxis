package com.datagami.rentaxis.core.service.rentalindex;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RentalIndexMathTest {

    @ParameterizedTest
    @CsvSource({"-5,0", "0,0", "10,0", "10.4,5", "11,5", "20,5", "20.72,10", "30,10",
            "30.5,15", "40,15", "40.01,20", "65,20"})
    void bandEdges(String gap, int expected) {
        assertEquals(expected, RentalIndexMath.allowedPercent(new BigDecimal(gap)));
    }

    @Test
    void dldWorkedExample() {
        var cap = RentalIndexMath.of(new BigDecimal("80000"), new BigDecimal("80000"), new BigDecimal("60000"));
        assertEquals(new BigDecimal("80000.00"), cap.average());
        assertEquals(new BigDecimal("25.0000"), cap.gap());
        assertEquals(10, cap.percent());
        assertEquals(new BigDecimal("66000"), cap.capRent());
    }

    @Test
    void realSampleFigures() {
        var cap = RentalIndexMath.of(new BigDecimal("117499"), new BigDecimal("143609"), new BigDecimal("103500"));
        assertEquals(new BigDecimal("130554.00"), cap.average());
        assertEquals(new BigDecimal("20.7225"), cap.gap());
        assertEquals(10, cap.percent());
        assertEquals(new BigDecimal("113850"), cap.capRent());
    }

    @Test
    void annualiseYearUnchanged() {
        assertEquals(new BigDecimal("60000"), RentalIndexMath.annualise(new BigDecimal("60000"),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
    }

    @Test
    void annualiseSixMonths() {
        assertEquals(new BigDecimal("100828.73"), RentalIndexMath.annualise(new BigDecimal("50000"),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30)));
    }

    @Test
    void rentAboveAverageIsZero() {
        var cap = RentalIndexMath.of(new BigDecimal("50000"), new BigDecimal("60000"), new BigDecimal("70000"));
        assertEquals(0, cap.percent());
        assertEquals(new BigDecimal("70000"), cap.capRent());
    }
}
