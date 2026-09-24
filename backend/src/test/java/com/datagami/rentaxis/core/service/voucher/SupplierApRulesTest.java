package com.datagami.rentaxis.core.service.voucher;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.VendorService;
import com.datagami.rentaxis.core.service.payables.PayablesService;
import com.datagami.rentaxis.core.service.payables.PayablesService.Bucket;
import com.datagami.rentaxis.domain.entity.Vendor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The pure rules of supplier AP (finance-ops spec §2): normalisation, due dates, buckets, arithmetic. */
class SupplierApRulesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "INV-7781|INV7781",
            "' inv 7781 '|INV7781",
            "Inv-77 81|INV7781",
            "a--b|AB",
            "'  '|",
            "---|",
            "AN/311|AN/311"})
    void invoiceNumbersNormalise(String raw, String expected) {
        assertThat(VoucherService.normaliseInvoiceNumber(raw)).isEqualTo(expected);
    }

    @Test
    void aNullInvoiceNumberNormalisesToNull() {
        assertThat(VoucherService.normaliseInvoiceNumber(null)).isNull();
    }

    @Test
    void theDueDateIsTheSupplierDatePlusTheVendorsTerms() {
        Vendor v = new Vendor();
        v.setPaymentTermsDays(30);
        assertThat(VoucherService.defaultDueDate(LocalDate.of(2026, 8, 1), v)).isEqualTo(LocalDate.of(2026, 8, 31));
        v.setPaymentTermsDays(0);
        assertThat(VoucherService.defaultDueDate(LocalDate.of(2026, 8, 1), v)).isEqualTo(LocalDate.of(2026, 8, 1));
        v.setPaymentTermsDays(null);
        assertThat(VoucherService.defaultDueDate(LocalDate.of(2026, 8, 20), v)).isEqualTo(LocalDate.of(2026, 9, 19));
        assertThat(VoucherService.defaultDueDate(null, v)).isNull();
    }

    @ParameterizedTest
    @CsvSource({"-5,CURRENT", "0,CURRENT", "1,D1_30", "30,D1_30", "31,D31_60", "60,D31_60", "61,D61_90",
            "90,D61_90", "91,D90_PLUS", "400,D90_PLUS"})
    void bucketBoundaries(long days, Bucket expected) {
        assertThat(PayablesService.bucketOf(days)).isEqualTo(expected);
    }

    @Test
    void statusFollowsTheAllocations() {
        assertThat(PayablesService.statusOf(new BigDecimal("2100"), BigDecimal.ZERO)).isEqualTo("OPEN");
        assertThat(PayablesService.statusOf(new BigDecimal("2100"), new BigDecimal("600"))).isEqualTo("PART_PAID");
        assertThat(PayablesService.statusOf(new BigDecimal("2100"), new BigDecimal("2100.00"))).isEqualTo("PAID");
    }

    @Test
    void aPropertysShareIsItsGrossShareRoundedHalfUp() {
        // 735 of a 1,470 invoice whose P1 lines are 1,050: 525.00.
        assertThat(PayablesService.share(new BigDecimal("735.00"), new BigDecimal("1050.00"), new BigDecimal("1470.00")))
                .isEqualByComparingTo("525.00");
        // 100 split a third: 33.333… → 33.33.
        assertThat(PayablesService.share(new BigDecimal("100"), new BigDecimal("1"), new BigDecimal("3")))
                .isEqualByComparingTo("33.33");
        // All of it: exact.
        assertThat(PayablesService.share(new BigDecimal("600"), new BigDecimal("2100"), new BigDecimal("2100")))
                .isEqualByComparingTo("600.00");
    }

    @Test
    void aTrnIsFifteenDigitsWithSpacesDropped() {
        assertThat(VendorService.normaliseTrn(" 100 1234 5670 0003 ")).isEqualTo("100123456700003");
        assertThat(VendorService.normaliseTrn("")).isNull();
        assertThat(VendorService.normaliseTrn("100-1234-5670-0003")).isEqualTo("100123456700003");
        assertThat(VendorService.normaliseTrn("١٠٠١٢٣٤٥٦٧٠٠٠٠٣")).isEqualTo("100123456700003");
        assertThat(VendorService.normaliseTrn(null)).isNull();
        assertThatThrownBy(() -> VendorService.normaliseTrn("10012345670000")).isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> VendorService.normaliseTrn("1001234567000030")).isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> VendorService.normaliseTrn("10012345670000X")).isInstanceOf(BusinessRuleViolationException.class);
    }
}
