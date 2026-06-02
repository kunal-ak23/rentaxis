package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.PaymentMethod;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.LeaseEventRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PaymentPenaltyRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration-style test for {@link PaymentScheduleService#generateScheduleForLease(Lease)}
 * focusing on the cheque-amount distribution produced by the wired-in
 * {@link ChequeRoundingCalculator}.
 */
class PaymentScheduleServiceGenerateTest {

    private PaymentScheduleRepository paymentScheduleRepository;
    private RentCollectionSettingsRepository rentCollectionSettingsRepository;
    private com.datagami.rentaxis.domain.repository.LeaseChargeRepository leaseChargeRepository;
    private PaymentScheduleService service;

    @BeforeEach
    void setUp() {
        paymentScheduleRepository = mock(PaymentScheduleRepository.class);
        rentCollectionSettingsRepository = mock(RentCollectionSettingsRepository.class);

        when(paymentScheduleRepository.findByLeaseId(any())).thenReturn(List.of());
        when(paymentScheduleRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(rentCollectionSettingsRepository.findByPropertyId(any())).thenReturn(Optional.empty());

        leaseChargeRepository =
                mock(com.datagami.rentaxis.domain.repository.LeaseChargeRepository.class);
        when(leaseChargeRepository.findByLeaseId(any())).thenReturn(List.of());

        service = new PaymentScheduleService(
                paymentScheduleRepository,
                leaseChargeRepository,
                mock(LeaseRepository.class),
                mock(AccountRepository.class),
                mock(FinancialTransactionService.class),
                mock(AccountMappingService.class),
                rentCollectionSettingsRepository,
                mock(NotificationService.class),
                mock(FineConfigResolver.class),
                mock(PaymentPenaltyRepository.class),
                mock(LeaseEventRepository.class),
                mock(ApplicationEventPublisher.class),
                new com.fasterxml.jackson.databind.ObjectMapper());

        TenantContextHolder.setTenantId(UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void produces5x5000Plus6000WhenRentIs31000Over12MonthsAcross6Cheques() {
        // The canonical example: total rent 31000 / 6 cheques / deposit 10000.
        // The five non-last cheques should be exactly 5000 (floored to 1000)
        // and the last should be 6000 (residual). Deposit covers the residual.
        Lease lease = buildLease(
                new BigDecimal("31000"), // total rent
                new BigDecimal("10000"), // deposit
                6,                         // cheque count
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2027, 1, 1));

        List<PaymentSchedule> result = service.generateScheduleForLease(lease);

        assertThat(result).hasSize(6);
        assertThat(result).extracting(PaymentSchedule::getAmount).containsExactly(
                new BigDecimal("5000"),
                new BigDecimal("5000"),
                new BigDecimal("5000"),
                new BigDecimal("5000"),
                new BigDecimal("5000"),
                new BigDecimal("6000"));
        assertThat(result).extracting(PaymentSchedule::getInstallmentNumber)
                .containsExactly(1, 2, 3, 4, 5, 6);
    }

    @Test
    void rejectsDraftWhenLastChequeWouldExceedDeposit() {
        // Rent 60000 over 12 months, 6 cheques, deposit 5000. Natural per-cheque
        // is 10000, already exceeding the deposit — no rounding strategy can
        // satisfy the cap, so generation must fail loudly at draft time.
        Lease lease = buildLease(
                new BigDecimal("60000"),
                new BigDecimal("5000"),
                6,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2027, 1, 1));

        assertThatThrownBy(() -> service.generateScheduleForLease(lease))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("deposit");
    }

    @Test
    void zeroRentLeaseGeneratesNoSchedule() {
        // Edge case: a 0-rent lease (rare fixture / employee housing). Used to
        // produce N rows of amount=0; new behavior is to skip generation
        // without throwing, preserving the no-throw contract for callers.
        Lease lease = buildLease(
                BigDecimal.ZERO,
                new BigDecimal("5000"),
                6,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2027, 1, 1));

        List<PaymentSchedule> result = service.generateScheduleForLease(lease);

        assertThat(result).isEmpty();
    }

    @Test
    void noRentLeaseWithPerInstallmentCharge_generatesChargeOnlyInstallments() {
        // Blocker 2: a no-rent lease that carries a PER_INSTALLMENT charge must
        // still produce installment rows so the charge money is collected, not
        // silently dropped. Expect n=4 rows, each = the folded per-installment
        // charge (incl additive VAT), labelled by the charge name (no "RENT -"
        // prefix because there is no rent).
        com.datagami.rentaxis.domain.entity.LeaseCharge maintenance =
                new com.datagami.rentaxis.domain.entity.LeaseCharge();
        maintenance.setName("Maintenance");
        maintenance.setAmount(new BigDecimal("200"));
        maintenance.setVatApplicable(true);
        maintenance.setFrequency(com.datagami.rentaxis.domain.entity.enums.ChargeFrequency.PER_INSTALLMENT);
        when(leaseChargeRepository.findByLeaseId(any())).thenReturn(List.of(maintenance));

        Lease lease = buildLease(
                BigDecimal.ZERO,           // no rent
                new BigDecimal("5000"),
                4,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2027, 1, 1));

        List<PaymentSchedule> result = service.generateScheduleForLease(lease);

        assertThat(result).hasSize(4);
        assertThat(result).extracting(PaymentSchedule::getAmount).allSatisfy(amt ->
                // 200 * 1.05 = 210.00 (no rent share)
                assertThat(amt).isEqualByComparingTo("210.00"));
        assertThat(result).extracting(PaymentSchedule::getPurposeLabel).allSatisfy(label ->
                assertThat(label).isEqualTo("Maintenance"));
        assertThat(result).extracting(PaymentSchedule::getInstallmentNumber)
                .containsExactly(1, 2, 3, 4);
    }

    @Test
    void respectsPaymentTermsCountForCleanlyDivisibleRent() {
        // 24000 over 12 months / 4 cheques → 6000 each, all uniform.
        Lease lease = buildLease(
                new BigDecimal("24000"),
                new BigDecimal("10000"),
                4,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2027, 1, 1));

        List<PaymentSchedule> result = service.generateScheduleForLease(lease);

        assertThat(result).hasSize(4);
        assertThat(result).extracting(PaymentSchedule::getAmount).allSatisfy(amt ->
                assertThat(amt).isEqualByComparingTo(new BigDecimal("6000")));
    }

    private Lease buildLease(BigDecimal totalRent, BigDecimal deposit, int paymentTerms,
                             LocalDate start, LocalDate end) {
        Property property = new Property();
        property.setId(UUID.randomUUID());
        Unit unit = new Unit();
        unit.setProperty(property);

        Lease lease = new Lease();
        lease.setId(UUID.randomUUID());
        lease.setUnit(unit);
        lease.setStartDate(start);
        lease.setEndDate(end);
        lease.setRentAmount(totalRent);
        // monthlyRent left null so the service derives from rentAmount/months.
        lease.setDepositAmount(deposit);
        lease.setPaymentTerms(paymentTerms);
        lease.setPaymentMethod(PaymentMethod.CHEQUE);
        return lease;
    }
}
