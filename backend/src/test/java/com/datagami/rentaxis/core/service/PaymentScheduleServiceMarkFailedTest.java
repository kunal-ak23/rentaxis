package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.dto.PaymentScheduleDTO;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseEvent;
import com.datagami.rentaxis.domain.entity.PaymentPenalty;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.repository.LeaseEventRepository;
import com.datagami.rentaxis.domain.repository.PaymentPenaltyRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the new {@link PaymentScheduleService#markFailed(UUID, ChequeFailureReason, String)}
 * flow introduced for cheque-failure penalties (M5).
 */
class PaymentScheduleServiceMarkFailedTest {

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
    private UUID propertyId;
    private UUID leaseId;
    private UUID renterUserId;

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
                accountRepository,
                financialTransactionService,
                accountMappingService,
                rentCollectionSettingsRepository,
                notificationService,
                fineConfigResolver,
                paymentPenaltyRepository,
                leaseEventRepository);

        tenantId = UUID.randomUUID();
        propertyId = UUID.randomUUID();
        leaseId = UUID.randomUUID();
        renterUserId = UUID.randomUUID();
        TenantContextHolder.setTenantId(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    // ---------- Happy path: status + reason transitions ----------

    @Test
    void markFailed_bounce_setsStatusBouncedAndFailureReason() {
        PaymentSchedule payment = depositedPayment(new BigDecimal("5000"));
        stubFindAndSave(payment);
        stubFineConfig(bounceCfg());

        MarkFailedResult result = service.markFailed(payment.getId(), ChequeFailureReason.BOUNCE, "n");

        ArgumentCaptor<PaymentSchedule> captor = ArgumentCaptor.forClass(PaymentSchedule.class);
        verify(paymentScheduleRepository).save(captor.capture());
        PaymentSchedule saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.BOUNCED);
        assertThat(saved.getFailureReason()).isEqualTo(ChequeFailureReason.BOUNCE);
        assertThat(saved.getStatusChangedAt()).isNotNull();
        assertThat(saved.getNotes()).isEqualTo("n");
        // Result schedule mirrors the saved entity.
        PaymentScheduleDTO dto = result.schedule();
        assertThat(dto).isNotNull();
        assertThat(dto.getStatus()).isEqualTo(PaymentStatus.BOUNCED);
    }

    // ---------- Penalty creation: amount per reason + snapshot fields ----------

    @Test
    void markFailed_bounce_createsPenaltyWithBounceAmount() {
        PaymentSchedule payment = depositedPayment(new BigDecimal("5000"));
        stubFindAndSave(payment);
        stubFineConfig(new FineConfig(
                new BigDecimal("500"),
                new BigDecimal("750"),
                new BigDecimal("1000"),
                7,
                new BigDecimal("25"),
                FineConfig.Source.ORG));

        MarkFailedResult result = service.markFailed(payment.getId(), ChequeFailureReason.BOUNCE, null);

        PaymentPenalty saved = capturePenalty();
        assertThat(saved.getPenaltyType()).isEqualTo("CHEQUE_FAILURE");
        assertThat(saved.getPenaltyAmount()).isEqualByComparingTo("500");
        assertThat(saved.getDaysOverdue()).isEqualTo(0);
        assertThat(saved.getFineGraceDays()).isEqualTo(7);
        assertThat(saved.getFinePerDayRate()).isEqualByComparingTo("25");
        assertThat(saved.getPaymentScheduleId()).isEqualTo(payment.getId());
        assertThat(saved.getLeaseId()).isEqualTo(leaseId);
        assertThat(saved.getLastCalculatedAt()).isNotNull();
        // Result.penalty() returns the same persisted entity (no extra DB roundtrip).
        assertThat(result.penalty()).isSameAs(saved);
    }

    @Test
    void markFailed_signatureMismatch_usesSignatureMismatchAmount() {
        PaymentSchedule payment = depositedPayment(new BigDecimal("5000"));
        stubFindAndSave(payment);
        stubFineConfig(new FineConfig(
                new BigDecimal("500"),
                new BigDecimal("750"),
                new BigDecimal("1000"),
                7,
                new BigDecimal("25"),
                FineConfig.Source.ORG));

        service.markFailed(payment.getId(), ChequeFailureReason.SIGNATURE_MISMATCH, null);

        PaymentPenalty saved = capturePenalty();
        assertThat(saved.getPenaltyAmount()).isEqualByComparingTo("750");
    }

    @Test
    void markFailed_accountClosed_usesAccountClosedAmount() {
        PaymentSchedule payment = depositedPayment(new BigDecimal("5000"));
        stubFindAndSave(payment);
        stubFineConfig(new FineConfig(
                new BigDecimal("500"),
                new BigDecimal("750"),
                new BigDecimal("1000"),
                7,
                new BigDecimal("25"),
                FineConfig.Source.ORG));

        service.markFailed(payment.getId(), ChequeFailureReason.ACCOUNT_CLOSED, null);

        PaymentPenalty saved = capturePenalty();
        assertThat(saved.getPenaltyAmount()).isEqualByComparingTo("1000");
    }

    @Test
    void markFailed_propertyOverride_usesOverriddenAmount() {
        PaymentSchedule payment = depositedPayment(new BigDecimal("5000"));
        stubFindAndSave(payment);
        // Property override path — bounce raised to 750 at property level.
        stubFineConfig(new FineConfig(
                new BigDecimal("750"),
                new BigDecimal("750"),
                new BigDecimal("1000"),
                7,
                new BigDecimal("25"),
                FineConfig.Source.PROPERTY));

        service.markFailed(payment.getId(), ChequeFailureReason.BOUNCE, null);

        PaymentPenalty saved = capturePenalty();
        assertThat(saved.getPenaltyAmount()).isEqualByComparingTo("750");
    }

    // ---------- Status guards ----------

    @Test
    void markFailed_pendingSchedule_throws() {
        PaymentSchedule payment = depositedPayment(new BigDecimal("5000"));
        payment.setStatus(PaymentStatus.PENDING);
        when(paymentScheduleRepository.findById(payment.getId())).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.markFailed(payment.getId(), ChequeFailureReason.BOUNCE, null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Can only mark payments in DEPOSITED status as failed");

        verify(paymentPenaltyRepository, never()).save(any());
        verify(financialTransactionService, never()).recordChequeBounce(any());
    }

    @Test
    void markFailed_alreadyBounced_throws() {
        PaymentSchedule payment = depositedPayment(new BigDecimal("5000"));
        payment.setStatus(PaymentStatus.BOUNCED);
        when(paymentScheduleRepository.findById(payment.getId())).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.markFailed(payment.getId(), ChequeFailureReason.BOUNCE, null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Can only mark payments in DEPOSITED status as failed");
    }

    @Test
    void markFailed_clearedSchedule_throws() {
        PaymentSchedule payment = depositedPayment(new BigDecimal("5000"));
        payment.setStatus(PaymentStatus.CLEARED);
        when(paymentScheduleRepository.findById(payment.getId())).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.markFailed(payment.getId(), ChequeFailureReason.BOUNCE, null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Can only mark payments in DEPOSITED status as failed");
    }

    // ---------- Side effects ----------

    @Test
    void markFailed_firesChequeBouncedNotification() {
        PaymentSchedule payment = depositedPayment(new BigDecimal("5000"));
        stubFindAndSave(payment);
        stubFineConfig(bounceCfg());

        service.markFailed(payment.getId(), ChequeFailureReason.BOUNCE, null);

        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        // Only PAYMENT_BOUNCED still goes via the inline notify(...) — the
        // PENALTY_INCURRED notification was extracted to sendPenaltyIncurred()
        // in M7.
        verify(notificationService, times(1)).notify(
                eq(tenantId), eq(renterUserId),
                typeCaptor.capture(),
                any(),
                bodyCaptor.capture(),
                any(),
                any());

        // PAYMENT_BOUNCED with reason AND fine amount in body — the fine
        // matters here because the body explicitly tells the renter what the
        // financial impact of the bounce is.
        assertThat(typeCaptor.getValue()).isEqualTo("PAYMENT_BOUNCED");
        String bouncedBody = bodyCaptor.getValue();
        assertThat(bouncedBody).contains("BOUNCE");
        assertThat(bouncedBody).contains("500");
    }

    @Test
    void markFailed_firesPenaltyIncurredNotification() {
        PaymentSchedule payment = depositedPayment(new BigDecimal("5000"));
        stubFindAndSave(payment);
        stubFineConfig(new FineConfig(
                new BigDecimal("500"),
                new BigDecimal("750"),
                new BigDecimal("1000"),
                7,
                new BigDecimal("25"),
                FineConfig.Source.ORG));

        service.markFailed(payment.getId(), ChequeFailureReason.SIGNATURE_MISMATCH, null);

        // PENALTY_INCURRED is now sent via the dedicated NotificationService
        // helper — verify the helper was invoked with the right schedule, reason,
        // and fine amount.
        ArgumentCaptor<BigDecimal> fineCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(notificationService, times(1)).sendPenaltyIncurred(
                eq(payment), eq(ChequeFailureReason.SIGNATURE_MISMATCH),
                fineCaptor.capture(), any(UUID.class));
        assertThat(fineCaptor.getValue()).isEqualByComparingTo("750");
    }

    @Test
    void markFailed_writesLeaseEvent() {
        PaymentSchedule payment = depositedPayment(new BigDecimal("5000"));
        stubFindAndSave(payment);
        stubFineConfig(bounceCfg());

        service.markFailed(payment.getId(), ChequeFailureReason.BOUNCE, null);

        ArgumentCaptor<LeaseEvent> captor = ArgumentCaptor.forClass(LeaseEvent.class);
        verify(leaseEventRepository, times(1)).save(captor.capture());
        LeaseEvent ev = captor.getValue();
        // Existing LeaseEvent entity has no `eventType` column — the audit
        // marker is recorded in `notes` so search-by-event-type still works.
        String marker = "PAYMENT_FAILED_BOUNCE";
        assertThat(ev.getNotes()).contains(marker);
    }

    @Test
    void markFailed_postsChequeBouncedFinancialTransaction() {
        PaymentSchedule payment = depositedPayment(new BigDecimal("5000"));
        stubFindAndSave(payment);
        stubFineConfig(bounceCfg());

        service.markFailed(payment.getId(), ChequeFailureReason.BOUNCE, null);

        verify(financialTransactionService, times(1)).recordChequeBounce(any(PaymentSchedule.class));
    }

    // ---------- Robustness: null lease.status + FT-failure propagation ----------

    @Test
    void markFailed_leaseStatusNull_doesNotThrow() {
        PaymentSchedule payment = depositedPayment(new BigDecimal("5000"));
        // A draft / freshly-created lease may not have a status set yet —
        // LeaseEvent.newState is NOT NULL so this would otherwise blow up at
        // flush time. The service should fall back to ACTIVE (the most common
        // non-null default) so the audit row still saves.
        payment.getLease().setStatus(null);
        stubFindAndSave(payment);
        stubFineConfig(bounceCfg());

        service.markFailed(payment.getId(), ChequeFailureReason.BOUNCE, null);

        ArgumentCaptor<LeaseEvent> captor = ArgumentCaptor.forClass(LeaseEvent.class);
        verify(leaseEventRepository).save(captor.capture());
        LeaseEvent ev = captor.getValue();
        assertThat(ev.getNewState()).isEqualTo(LeaseStatus.ACTIVE);
        assertThat(ev.getPreviousState()).isEqualTo(LeaseStatus.ACTIVE);
    }

    @Test
    void markFailed_financialTransactionFails_rollsBack() {
        PaymentSchedule payment = depositedPayment(new BigDecimal("5000"));
        stubFindAndSave(payment);
        stubFineConfig(bounceCfg());
        // recordChequeBounce shares the outer JPA transaction — when it
        // throws, the exception MUST propagate so @Transactional triggers a
        // full rollback of the schedule update + penalty + audit. Swallowing
        // it would leave the books inconsistent with the schedule state.
        org.mockito.Mockito.doThrow(new RuntimeException("ledger down"))
                .when(financialTransactionService).recordChequeBounce(any(PaymentSchedule.class));

        assertThatThrownBy(() -> service.markFailed(payment.getId(), ChequeFailureReason.BOUNCE, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("ledger down");

        // LeaseEvent should NOT have been written — the FT call happens before
        // the audit save, and even if it didn't, @Transactional would roll the
        // whole unit back. Verify the audit save was not invoked.
        verify(leaseEventRepository, never()).save(any());
    }

    // ---------- Helpers ----------

    private PaymentSchedule depositedPayment(BigDecimal amount) {
        Lease lease = new Lease();
        lease.setId(leaseId);
        lease.setStatus(LeaseStatus.ACTIVE);
        Renter renter = new Renter();
        renter.setUserId(renterUserId);
        renter.setNameEn("Test Renter");
        lease.setRenter(renter);

        Property property = new Property();
        property.setId(propertyId);
        property.setNameEn("Test Property");
        Unit unit = new Unit();
        unit.setUnitNumber("UNIT-1");

        PaymentSchedule payment = new PaymentSchedule();
        payment.setId(UUID.randomUUID());
        payment.setLease(lease);
        payment.setProperty(property);
        payment.setUnit(unit);
        payment.setAmount(amount);
        payment.setInstallmentNumber(1);
        payment.setStatus(PaymentStatus.DEPOSITED);
        return payment;
    }

    private void stubFindAndSave(PaymentSchedule payment) {
        when(paymentScheduleRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(paymentScheduleRepository.save(any(PaymentSchedule.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentPenaltyRepository.save(any(PaymentPenalty.class))).thenAnswer(inv -> {
            PaymentPenalty p = inv.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            return p;
        });
    }

    private FineConfig bounceCfg() {
        return new FineConfig(
                new BigDecimal("500"),
                new BigDecimal("500"),
                new BigDecimal("1000"),
                7,
                new BigDecimal("25"),
                FineConfig.Source.ORG);
    }

    private void stubFineConfig(FineConfig cfg) {
        when(fineConfigResolver.resolve(propertyId, tenantId)).thenReturn(cfg);
    }

    private PaymentPenalty capturePenalty() {
        ArgumentCaptor<PaymentPenalty> captor = ArgumentCaptor.forClass(PaymentPenalty.class);
        verify(paymentPenaltyRepository).save(captor.capture());
        return captor.getValue();
    }
}
