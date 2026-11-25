package com.datagami.rentaxis.core.service.rentalindex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class DldResultParserTest {

    private static void assertCommon(DldResult r) {
        assertThat(r.dldPercent()).isEqualTo(5);
        assertThat(r.increase()).isEqualByComparingTo("6000");
        assertThat(r.rentAfter()).isEqualByComparingTo("126000");
        assertThat(r.premiseNo()).isEqualTo("123456789");
        assertThat(r.contractEnd()).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(r.currentRent()).isEqualByComparingTo("120000");
        assertThat(r.indexMin()).isEqualByComparingTo(new BigDecimal("138000"));
        assertThat(r.indexMax()).isEqualByComparingTo(new BigDecimal("162000"));
        assertThat(r.category()).isEqualTo("2 bed rooms+hall");
        assertThat(r.building()).isEqualTo("Test Tower");
    }

    private static void assertCode(byte[] pdf, String code) {
        assertThatThrownBy(() -> DldResultParser.parse(pdf))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getCode()).isEqualTo(code));
    }

    @Test
    void readsOctoberLayout() {
        DldResult r = DldResultParser.parse(DldPdfFixtures.layoutOctober(DldPdfFixtures.Values.valid()));
        assertCommon(r);
        assertThat(r.area()).isNull();
        assertThat(r.dldPropertyType()).isNull();
        assertThat(r.issuedAt()).isEqualTo("25 November 2026, 9:05 AM");
    }

    @Test
    void readsNovemberLayout() {
        DldResult r = DldResultParser.parse(DldPdfFixtures.layoutNovember(DldPdfFixtures.Values.valid()));
        assertCommon(r);
        assertThat(r.area()).isEqualTo("Test Area");
        assertThat(r.dldPropertyType()).isEqualTo("Apartment");
    }

    @Test
    void refusesNonDldPdf() {
        assertCode(DldPdfFixtures.pdf(List.of("Invoice", "Total 100 AED")), "rentalIndex.notDldResult");
    }

    @Test
    void refusesGarbage() {
        assertCode(new byte[] {1, 2, 3}, "rentalIndex.notDldResult");
    }

    @Test
    void refusesFiguresThatDontAddUp() {
        var v = DldPdfFixtures.Values.valid().withIncrease(6000, 126500);
        assertCode(DldPdfFixtures.layoutOctober(v), "rentalIndex.figuresDontAddUp");
    }

    @Test
    void refusesWrongIncrease() {
        var v = DldPdfFixtures.Values.valid().withIncrease(7000, 127000);
        assertCode(DldPdfFixtures.layoutNovember(v), "rentalIndex.figuresDontAddUp");
    }
}
