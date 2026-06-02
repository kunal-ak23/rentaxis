package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.UpdatePaymentStatusDTO;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.AccountMapping;
import com.datagami.rentaxis.domain.entity.FinancialTransaction;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.entity.enums.TransactionNature;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.LeaseEventRepository;
import com.datagami.rentaxis.domain.repository.PaymentPenaltyRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for VAT field stamping on financial transactions when a rent
 * cheque clears in {@link PaymentScheduleService#clearPayment}.
 *
 * Scope: only the credit (rental-income) transaction. The debit (bank/cash)
 * leg does not carry VAT — VAT is tracked on income lines.
 */
class PaymentScheduleServiceVatTest {

    private PaymentScheduleRepository paymentScheduleRepository;
    private AccountRepository accountRepository;
    private FinancialTransactionService financialTransactionService;
    private AccountMappingService accountMappingService;
    private RentCollectionSettingsRepository rentCollectionSettingsRepository;
    private NotificationService notificationService;
    private FineConfigResolver fineConfigResolver;
    private PaymentPenaltyRepository paymentPenaltyRepository;
    private LeaseEventRepository leaseEventRepository;
    private PaymentScheduleService service;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        paymentScheduleRepository = mock(PaymentScheduleRepository.class);
        accountRepository = mock(AccountRepository.class);
        financialTransactionService = mock(FinancialTransactionService.class);
        accountMappingService = mock(AccountMappingService.class);
        rentCollectionSettingsRepository = mock(RentCollectionSettingsRepository.class);
        notificationService = mock(NotificationService.class);
        fineConfigResolver = mock(FineConfigResolver.class);
        paymentPenaltyRepository = mock(PaymentPenaltyRepository.class);
        leaseEventRepository = mock(LeaseEventRepository.class);

        service = new PaymentScheduleService(
                paymentScheduleRepository,
                mock(com.datagami.rentaxis.domain.repository.LeaseChargeRepository.class),
                mock(com.datagami.rentaxis.domain.repository.LeaseRepository.class),
                accountRepository,
                financialTransactionService,
                accountMappingService,
                rentCollectionSettingsRepository,
                notificationService,
                fineConfigResolver,
                paymentPenaltyRepository,
                leaseEventRepository,
                mock(ApplicationEventPublisher.class),
                new com.fasterxml.jackson.databind.ObjectMapper());

        tenantId = UUID.randomUUID();
        TenantContextHolder.setTenantId(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void clearPayment_withRentVatApplicable_stampsVatFieldsOnCreditTxn_57750() {
        PaymentSchedule payment = buildDepositedPayment(new BigDecimal("57750.00"), true);
        stubAccountMapping();
        when(paymentScheduleRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(paymentScheduleRepository.save(any(PaymentSchedule.class))).thenAnswer(inv -> inv.getArgument(0));

        service.clearPayment(payment.getId(), new UpdatePaymentStatusDTO());

        FinancialTransaction credit = captureCreditTransaction();
        assertThat(credit.isVatApplicable()).isTrue();
        assertThat(credit.getVatRate()).isEqualByComparingTo(new BigDecimal("5.00"));
        assertThat(credit.getVatAmount()).isEqualByComparingTo(new BigDecimal("2750.00"));
        assertThat(credit.getGrossAmount()).isEqualByComparingTo(new BigDecimal("57750.00"));
        assertThat(credit.getNetAmount()).isEqualByComparingTo(new BigDecimal("55000.00"));
    }

    @Test
    void clearPayment_withRentVatApplicable_roundsHalfUp_13750() {
        // 13750 * 5 / 105 = 654.7619... → HALF_UP → 654.76
        PaymentSchedule payment = buildDepositedPayment(new BigDecimal("13750.00"), true);
        stubAccountMapping();
        when(paymentScheduleRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(paymentScheduleRepository.save(any(PaymentSchedule.class))).thenAnswer(inv -> inv.getArgument(0));

        service.clearPayment(payment.getId(), new UpdatePaymentStatusDTO());

        FinancialTransaction credit = captureCreditTransaction();
        assertThat(credit.isVatApplicable()).isTrue();
        assertThat(credit.getVatRate()).isEqualByComparingTo(new BigDecimal("5.00"));
        assertThat(credit.getVatAmount()).isEqualByComparingTo(new BigDecimal("654.76"));
        assertThat(credit.getGrossAmount()).isEqualByComparingTo(new BigDecimal("13750.00"));
        assertThat(credit.getNetAmount()).isEqualByComparingTo(new BigDecimal("13095.24"));
    }

    @Test
    void clearPayment_withRentVatNotApplicable_leavesVatDefaults() {
        PaymentSchedule payment = buildDepositedPayment(new BigDecimal("55000.00"), false);
        stubAccountMapping();
        when(paymentScheduleRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(paymentScheduleRepository.save(any(PaymentSchedule.class))).thenAnswer(inv -> inv.getArgument(0));

        service.clearPayment(payment.getId(), new UpdatePaymentStatusDTO());

        FinancialTransaction credit = captureCreditTransaction();
        assertThat(credit.isVatApplicable()).isFalse();
        assertThat(credit.getVatRate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(credit.getVatAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        // Pre-M9 behavior: grossAmount/netAmount left at default ZERO when VAT not applicable.
        assertThat(credit.getGrossAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(credit.getNetAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        // Existing fields unaffected.
        assertThat(credit.getCredit()).isEqualByComparingTo(new BigDecimal("55000.00"));
        assertThat(credit.getDebit()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // --- Helpers ---------------------------------------------------------

    private PaymentSchedule buildDepositedPayment(BigDecimal grossAmount, boolean rentVatApplicable) {
        Lease lease = new Lease();
        lease.setRentVatApplicable(rentVatApplicable);
        Renter renter = new Renter();
        // Leave userId null so the notification side effect is skipped — keeps the
        // test focused on the FinancialTransaction stamping logic.
        lease.setRenter(renter);

        Property property = new Property();
        Unit unit = new Unit();

        PaymentSchedule payment = new PaymentSchedule();
        payment.setId(UUID.randomUUID());
        payment.setLease(lease);
        payment.setProperty(property);
        payment.setUnit(unit);
        payment.setAmount(grossAmount);
        payment.setInstallmentNumber(1);
        payment.setStatus(PaymentStatus.DEPOSITED);
        return payment;
    }

    private void stubAccountMapping() {
        AccountMapping mapping = new AccountMapping();
        Account bank = new Account();
        Account income = new Account();
        mapping.setDebitAccount(bank);
        mapping.setCreditAccount(income);
        when(accountMappingService.resolveMapping(TransactionNature.RENT_PAYMENT_CLEARED))
                .thenReturn(mapping);
    }

    private FinancialTransaction captureCreditTransaction() {
        ArgumentCaptor<FinancialTransaction> captor = ArgumentCaptor.forClass(FinancialTransaction.class);
        verify(financialTransactionService, times(2)).createTransaction(captor.capture());
        List<FinancialTransaction> txns = captor.getAllValues();
        // First call = debit (bank), second call = credit (rental income).
        return txns.get(1);
    }
}
