package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.VatReturnDTO;
import com.datagami.rentaxis.domain.entity.FinancialTransaction;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.repository.AccountMappingRepository;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.FinancialTransactionRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.StaffRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.VendorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The VAT return must report a VAT-exclusive taxable base.
 *
 * <p>Rent VAT here is inclusive: {@code clearPayment} posts the credit leg at the
 * gross face value and records VAT separately, with
 * {@code netAmount = gross - vat}. Reporting credit-minus-debit as the taxable
 * amount therefore reported the gross, so output VAT did not reconcile to the
 * base printed beside it — a 5,250 installment appeared as 5,250 taxable with
 * 250 VAT rather than 5,000 with 250.
 *
 * <p>Manual split transactions use the additive convention, so credit-minus-debit
 * already is the net there. A single return mixed both bases, which is what made
 * the discrepancy hard to see.
 */
class VatReturnTaxableBaseTest {

    private FinancialTransactionRepository repository;
    private FinancialTransactionService service;

    @BeforeEach
    void setUp() {
        repository = mock(FinancialTransactionRepository.class);
        service = new FinancialTransactionService(
                repository,
                mock(AccountRepository.class),
                mock(UnitRepository.class),
                mock(PropertyRepository.class),
                mock(VendorRepository.class),
                mock(StaffRepository.class),
                mock(AccountMappingRepository.class),
                mock(PaymentScheduleRepository.class));
    }

    private FinancialTransaction income(String credit, String vat, String net) {
        FinancialTransaction t = new FinancialTransaction();
        t.setDescription("Rental income - Lease installment #1");
        t.setAccountType(AccountType.INCOME);
        t.setDebit(BigDecimal.ZERO);
        t.setCredit(new BigDecimal(credit));
        t.setVatApplicable(true);
        t.setVatAmount(new BigDecimal(vat));
        if (net != null) {
            t.setNetAmount(new BigDecimal(net));
        }
        return t;
    }

    private FinancialTransaction expense(String debit, String vat, String net) {
        FinancialTransaction t = new FinancialTransaction();
        t.setDescription("Maintenance");
        t.setAccountType(AccountType.EXPENSE);
        t.setDebit(new BigDecimal(debit));
        t.setCredit(BigDecimal.ZERO);
        t.setVatApplicable(true);
        t.setVatAmount(new BigDecimal(vat));
        if (net != null) {
            t.setNetAmount(new BigDecimal(net));
        }
        return t;
    }

    private VatReturnDTO run(List<FinancialTransaction> rows) {
        when(repository.findBySplitParentFalseOrderByDateDesc()).thenReturn(rows);
        return service.getVatReturn(null, null);
    }

    @Test
    void inclusiveRentReportsTheNetBaseNotTheGross() {
        VatReturnDTO dto = run(List.of(income("5250", "250", "5000")));

        // Before the fix this was 5250 — the gross — beside 250 of VAT.
        assertThat(dto.getTotalTaxableSales()).isEqualByComparingTo("5000");
        assertThat(dto.getTotalOutputVat()).isEqualByComparingTo("250");
    }

    @Test
    void outputVatReconcilesToFivePercentOfTheReportedBase() {
        VatReturnDTO dto = run(List.of(
                income("5250", "250", "5000"),
                income("10500", "500", "10000")));

        BigDecimal expected = dto.getTotalTaxableSales()
                .multiply(new BigDecimal("0.05"))
                .setScale(2, java.math.RoundingMode.HALF_UP);
        // The whole point of the fix: the two figures on the return agree.
        assertThat(dto.getTotalOutputVat()).isEqualByComparingTo(expected);
        assertThat(dto.getTotalTaxableSales()).isEqualByComparingTo("15000");
    }

    @Test
    void legacyRowWithNoNetAmountDerivesTheBaseFromTheRecordedVat() {
        // Posted before VAT support stored netAmount; the row is gross.
        VatReturnDTO dto = run(List.of(income("5250", "250", null)));

        assertThat(dto.getTotalTaxableSales()).isEqualByComparingTo("5000");
    }

    @Test
    void aRowWithNoVatIsLeftAlone() {
        FinancialTransaction t = income("5000", "0", null);
        VatReturnDTO dto = run(List.of(t));

        assertThat(dto.getTotalTaxableSales()).isEqualByComparingTo("5000");
        assertThat(dto.getTotalOutputVat()).isEqualByComparingTo("0");
    }

    @Test
    void expensesUseTheSameExclusiveBase() {
        VatReturnDTO dto = run(List.of(expense("2100", "100", "2000")));

        assertThat(dto.getTotalTaxablePurchases()).isEqualByComparingTo("2000");
        assertThat(dto.getTotalInputVat()).isEqualByComparingTo("100");
    }

    @Test
    void netVatPayableIsStillOutputMinusInput() {
        VatReturnDTO dto = run(List.of(
                income("5250", "250", "5000"),
                expense("2100", "100", "2000")));

        assertThat(dto.getNetVatPayable()).isEqualByComparingTo("150");
    }

    @Test
    void anAdditiveRowThatAlreadyStoredNetIsNotDoubleReduced() {
        // Split transactions are additive: credit is already the net. Guard that
        // the legacy derivation does not subtract VAT a second time.
        FinancialTransaction t = income("1000", "1200", null);
        VatReturnDTO dto = run(List.of(t));

        // Subtracting would go negative, so the signed amount stands.
        assertThat(dto.getTotalTaxableSales()).isEqualByComparingTo("1000");
    }
}
