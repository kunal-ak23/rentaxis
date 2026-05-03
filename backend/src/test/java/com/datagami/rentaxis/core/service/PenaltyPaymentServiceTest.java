package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.domain.entity.LeaseEvent;
import com.datagami.rentaxis.domain.entity.PaymentPenalty;
import com.datagami.rentaxis.domain.entity.PenaltyPayment;
import com.datagami.rentaxis.domain.repository.LeaseEventRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PaymentPenaltyRepository;
import com.datagami.rentaxis.domain.repository.PenaltyPaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PenaltyPaymentService#recordReceipt(UUID, PenaltyPaymentService.RecordReceiptInput, UUID)}
 * — the penalty clearance / partial-payment flow introduced in M7.
 */
@ExtendWith(MockitoExtension.class)
class PenaltyPaymentServiceTest {

    @Mock PaymentPenaltyRepository paymentPenaltyRepository;
    @Mock PenaltyPaymentRepository penaltyPaymentRepository;
    @Mock FinancialTransactionService financialTransactionService;
    @Mock NotificationService notificationService;
    @Mock LeaseEventRepository leaseEventRepository;
    @Mock LeaseRepository leaseRepository;

    private static final LocalDate TODAY = LocalDate.of(2026, 5, 3);
    private final Clock fixedClock = Clock.fixed(
            TODAY.atStartOfDay(ZoneId.of("UTC")).toInstant(), ZoneId.of("UTC"));

    private PenaltyPaymentService service;
    private UUID penaltyId;
    private UUID receivedBy;

    @BeforeEach
    void setUp() {
        service = new PenaltyPaymentService(
                paymentPenaltyRepository,
                penaltyPaymentRepository,
                financialTransactionService,
                notificationService,
                leaseEventRepository,
                leaseRepository,
                fixedClock);
        penaltyId = UUID.randomUUID();
        receivedBy = UUID.randomUUID();
    }

    // ---------- partial pay ----------

    @Test
    void recordReceipt_partialPay_keepsPenaltyOpen_outstandingDecreases() {
        PaymentPenalty p = openPenalty(new BigDecimal("500"), 0, BigDecimal.ZERO);
        when(paymentPenaltyRepository.findById(penaltyId)).thenReturn(Optional.of(p));
        when(penaltyPaymentRepository.findByPaymentPenaltyIdOrderByReceivedAtAscCreatedAtAsc(penaltyId))
                .thenReturn(List.of());
        UUID ftId = UUID.randomUUID();
        when(financialTransactionService.recordPenaltyIncome(any(PaymentPenalty.class), any(PenaltyPayment.class)))
                .thenReturn(ftId);
        when(penaltyPaymentRepository.save(any(PenaltyPayment.class))).thenAnswer(inv -> {
            PenaltyPayment row = inv.getArgument(0);
            if (row.getId() == null) row.setId(UUID.randomUUID());
            return row;
        });

        PenaltyPaymentService.RecordReceiptInput input = new PenaltyPaymentService.RecordReceiptInput(
                new BigDecimal("200"), "BANK_TRANSFER", "UTR-1", TODAY, null);

        PenaltyPayment saved = service.recordReceipt(penaltyId, input, receivedBy);

        assertThat(saved.getAmount()).isEqualByComparingTo("200");
        assertThat(saved.getPaymentMethod()).isEqualTo("BANK_TRANSFER");
        assertThat(saved.getPaymentReference()).isEqualTo("UTR-1");
        assertThat(saved.getFinancialTransactionId()).isEqualTo(ftId);

        // Penalty NOT cleared.
        assertThat(p.getClearedAt()).isNull();
        verify(paymentPenaltyRepository, never()).save(any(PaymentPenalty.class));
        // No PENALTY_CLEARED notification.
        verify(notificationService, never()).sendPenaltyCleared(any(), any());
        // FT was posted exactly once.
        verify(financialTransactionService, times(1)).recordPenaltyIncome(any(PaymentPenalty.class), any(PenaltyPayment.class));
    }

    // ---------- full pay ----------

    @Test
    void recordReceipt_fullPay_setsClearedAtAndFiresPenaltyClearedNotification() {
        PaymentPenalty p = openPenalty(new BigDecimal("500"), 0, BigDecimal.ZERO);
        when(paymentPenaltyRepository.findById(penaltyId)).thenReturn(Optional.of(p));
        when(penaltyPaymentRepository.findByPaymentPenaltyIdOrderByReceivedAtAscCreatedAtAsc(penaltyId))
                .thenReturn(List.of());
        UUID ftId = UUID.randomUUID();
        when(financialTransactionService.recordPenaltyIncome(any(PaymentPenalty.class), any(PenaltyPayment.class)))
                .thenReturn(ftId);
        when(penaltyPaymentRepository.save(any(PenaltyPayment.class))).thenAnswer(inv -> {
            PenaltyPayment row = inv.getArgument(0);
            if (row.getId() == null) row.setId(UUID.randomUUID());
            return row;
        });

        PenaltyPaymentService.RecordReceiptInput input = new PenaltyPaymentService.RecordReceiptInput(
                new BigDecimal("500"), "BANK_TRANSFER", "UTR-1", TODAY, null);

        PenaltyPayment saved = service.recordReceipt(penaltyId, input, receivedBy);

        assertThat(saved.getAmount()).isEqualByComparingTo("500");
        assertThat(p.getClearedAt()).isEqualTo(LocalDateTime.now(fixedClock));
        verify(paymentPenaltyRepository, times(1)).save(p);
        verify(notificationService, times(1)).sendPenaltyCleared(eq(p), any(PenaltyPayment.class));
        verify(financialTransactionService, times(1)).recordPenaltyIncome(eq(p), any(PenaltyPayment.class));
    }

    // ---------- partial then final ----------

    @Test
    void recordReceipt_partialThenFinalPay_clearsOnSecondReceipt() {
        PaymentPenalty p = openPenalty(new BigDecimal("500"), 0, BigDecimal.ZERO);
        when(paymentPenaltyRepository.findById(penaltyId)).thenReturn(Optional.of(p));

        // First call: no prior payments.
        when(penaltyPaymentRepository.findByPaymentPenaltyIdOrderByReceivedAtAscCreatedAtAsc(penaltyId))
                .thenReturn(List.of());
        when(financialTransactionService.recordPenaltyIncome(any(PaymentPenalty.class), any(PenaltyPayment.class)))
                .thenReturn(UUID.randomUUID());
        when(penaltyPaymentRepository.save(any(PenaltyPayment.class))).thenAnswer(inv -> {
            PenaltyPayment row = inv.getArgument(0);
            if (row.getId() == null) row.setId(UUID.randomUUID());
            return row;
        });

        PenaltyPaymentService.RecordReceiptInput first = new PenaltyPaymentService.RecordReceiptInput(
                new BigDecimal("200"), "BANK_TRANSFER", "UTR-1", TODAY, null);
        PenaltyPayment firstSaved = service.recordReceipt(penaltyId, first, receivedBy);
        assertThat(p.getClearedAt()).isNull();
        verify(notificationService, never()).sendPenaltyCleared(any(), any());

        // Second call: prior payment of 200 exists.
        PenaltyPayment prior = new PenaltyPayment();
        prior.setAmount(new BigDecimal("200"));
        when(penaltyPaymentRepository.findByPaymentPenaltyIdOrderByReceivedAtAscCreatedAtAsc(penaltyId))
                .thenReturn(List.of(prior));

        PenaltyPaymentService.RecordReceiptInput second = new PenaltyPaymentService.RecordReceiptInput(
                new BigDecimal("300"), "CASH", null, TODAY, null);
        service.recordReceipt(penaltyId, second, receivedBy);

        assertThat(p.getClearedAt()).isEqualTo(LocalDateTime.now(fixedClock));
        verify(notificationService, times(1)).sendPenaltyCleared(eq(p), any(PenaltyPayment.class));
    }

    // ---------- accrual-aware outstanding ----------

    @Test
    void recordReceipt_amountWithAccrual_clearsWhenSumMeetsCurrentTotal() {
        // base 500 + 10 days * 25 perDay = 750 total.
        PaymentPenalty p = openPenalty(new BigDecimal("500"), 10, new BigDecimal("25"));
        when(paymentPenaltyRepository.findById(penaltyId)).thenReturn(Optional.of(p));
        when(penaltyPaymentRepository.findByPaymentPenaltyIdOrderByReceivedAtAscCreatedAtAsc(penaltyId))
                .thenReturn(List.of());
        when(financialTransactionService.recordPenaltyIncome(any(PaymentPenalty.class), any(PenaltyPayment.class)))
                .thenReturn(UUID.randomUUID());
        when(penaltyPaymentRepository.save(any(PenaltyPayment.class))).thenAnswer(inv -> inv.getArgument(0));

        PenaltyPaymentService.RecordReceiptInput input = new PenaltyPaymentService.RecordReceiptInput(
                new BigDecimal("750"), "BANK_TRANSFER", "UTR-1", TODAY, null);

        service.recordReceipt(penaltyId, input, receivedBy);

        assertThat(p.getClearedAt()).isEqualTo(LocalDateTime.now(fixedClock));
        verify(notificationService, times(1)).sendPenaltyCleared(eq(p), any(PenaltyPayment.class));
    }

    // ---------- guards: amount > outstanding ----------

    @Test
    void recordReceipt_amountExceedsOutstanding_throws() {
        PaymentPenalty p = openPenalty(new BigDecimal("500"), 0, BigDecimal.ZERO);
        when(paymentPenaltyRepository.findById(penaltyId)).thenReturn(Optional.of(p));
        when(penaltyPaymentRepository.findByPaymentPenaltyIdOrderByReceivedAtAscCreatedAtAsc(penaltyId))
                .thenReturn(List.of());

        PenaltyPaymentService.RecordReceiptInput input = new PenaltyPaymentService.RecordReceiptInput(
                new BigDecimal("600"), "BANK_TRANSFER", "UTR-1", TODAY, null);

        assertThatThrownBy(() -> service.recordReceipt(penaltyId, input, receivedBy))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("exceeds outstanding");

        verify(penaltyPaymentRepository, never()).save(any(PenaltyPayment.class));
        verify(financialTransactionService, never()).recordPenaltyIncome(any(), any());
        verify(notificationService, never()).sendPenaltyCleared(any(), any());
    }

    // ---------- guards: already cleared / waived / non-positive amount ----------

    @Test
    void recordReceipt_alreadyCleared_throws() {
        PaymentPenalty p = openPenalty(new BigDecimal("500"), 0, BigDecimal.ZERO);
        p.setClearedAt(LocalDateTime.now(fixedClock));
        when(paymentPenaltyRepository.findById(penaltyId)).thenReturn(Optional.of(p));

        PenaltyPaymentService.RecordReceiptInput input = new PenaltyPaymentService.RecordReceiptInput(
                new BigDecimal("100"), "BANK_TRANSFER", "UTR-1", TODAY, null);

        assertThatThrownBy(() -> service.recordReceipt(penaltyId, input, receivedBy))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("already cleared");

        verify(penaltyPaymentRepository, never()).save(any(PenaltyPayment.class));
        verify(financialTransactionService, never()).recordPenaltyIncome(any(), any());
    }

    @Test
    void recordReceipt_alreadyWaived_throws() {
        PaymentPenalty p = openPenalty(new BigDecimal("500"), 0, BigDecimal.ZERO);
        p.setWaived(true);
        when(paymentPenaltyRepository.findById(penaltyId)).thenReturn(Optional.of(p));

        PenaltyPaymentService.RecordReceiptInput input = new PenaltyPaymentService.RecordReceiptInput(
                new BigDecimal("100"), "BANK_TRANSFER", "UTR-1", TODAY, null);

        assertThatThrownBy(() -> service.recordReceipt(penaltyId, input, receivedBy))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("already waived");
    }

    @Test
    void recordReceipt_zeroAmount_throws() {
        PenaltyPaymentService.RecordReceiptInput input = new PenaltyPaymentService.RecordReceiptInput(
                BigDecimal.ZERO, "BANK_TRANSFER", "UTR-1", TODAY, null);

        assertThatThrownBy(() -> service.recordReceipt(penaltyId, input, receivedBy))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("amount must be positive");
    }

    @Test
    void recordReceipt_negativeAmount_throws() {
        PenaltyPaymentService.RecordReceiptInput input = new PenaltyPaymentService.RecordReceiptInput(
                new BigDecimal("-1"), "BANK_TRANSFER", "UTR-1", TODAY, null);

        assertThatThrownBy(() -> service.recordReceipt(penaltyId, input, receivedBy))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("amount must be positive");
    }

    // ---------- FT helper signature ----------

    @Test
    void recordReceipt_postsFinancialTransactionWithPenaltyIncomeNature() {
        PaymentPenalty p = openPenalty(new BigDecimal("500"), 0, BigDecimal.ZERO);
        when(paymentPenaltyRepository.findById(penaltyId)).thenReturn(Optional.of(p));
        when(penaltyPaymentRepository.findByPaymentPenaltyIdOrderByReceivedAtAscCreatedAtAsc(penaltyId))
                .thenReturn(List.of());
        when(financialTransactionService.recordPenaltyIncome(any(PaymentPenalty.class), any(PenaltyPayment.class)))
                .thenReturn(UUID.randomUUID());
        when(penaltyPaymentRepository.save(any(PenaltyPayment.class))).thenAnswer(inv -> inv.getArgument(0));

        PenaltyPaymentService.RecordReceiptInput input = new PenaltyPaymentService.RecordReceiptInput(
                new BigDecimal("100"), "CASH", null, TODAY, null);

        service.recordReceipt(penaltyId, input, receivedBy);

        ArgumentCaptor<PaymentPenalty> penaltyCaptor = ArgumentCaptor.forClass(PaymentPenalty.class);
        ArgumentCaptor<PenaltyPayment> rowCaptor = ArgumentCaptor.forClass(PenaltyPayment.class);
        verify(financialTransactionService).recordPenaltyIncome(penaltyCaptor.capture(), rowCaptor.capture());
        assertThat(penaltyCaptor.getValue()).isSameAs(p);
        assertThat(rowCaptor.getValue().getAmount()).isEqualByComparingTo("100");
    }

    // ---------- LeaseEvent audit ----------

    @Test
    void recordReceipt_writesLeaseEventPenaltyPaymentRecorded() {
        PaymentPenalty p = openPenalty(new BigDecimal("500"), 0, BigDecimal.ZERO);
        when(paymentPenaltyRepository.findById(penaltyId)).thenReturn(Optional.of(p));
        when(penaltyPaymentRepository.findByPaymentPenaltyIdOrderByReceivedAtAscCreatedAtAsc(penaltyId))
                .thenReturn(List.of());
        when(financialTransactionService.recordPenaltyIncome(any(PaymentPenalty.class), any(PenaltyPayment.class)))
                .thenReturn(UUID.randomUUID());
        when(penaltyPaymentRepository.save(any(PenaltyPayment.class))).thenAnswer(inv -> inv.getArgument(0));

        PenaltyPaymentService.RecordReceiptInput input = new PenaltyPaymentService.RecordReceiptInput(
                new BigDecimal("100"), "CHEQUE", "CHQ-1", TODAY, null);

        service.recordReceipt(penaltyId, input, receivedBy);

        ArgumentCaptor<LeaseEvent> captor = ArgumentCaptor.forClass(LeaseEvent.class);
        verify(leaseEventRepository, times(1)).save(captor.capture());
        LeaseEvent ev = captor.getValue();
        assertThat(ev.getNotes()).contains("PENALTY_PAYMENT_RECORDED");
        assertThat(ev.getNotes()).contains(penaltyId.toString());
        assertThat(ev.getNotes()).contains("100");
        assertThat(ev.getNotes()).contains("CHEQUE");
    }

    // ---------- payment methods ----------

    @Test
    void recordReceipt_bankTransfer_succeeds() {
        runMethodCase("BANK_TRANSFER", "UTR-1");
    }

    @Test
    void recordReceipt_cheque_succeeds() {
        runMethodCase("CHEQUE", "CHQ-1");
    }

    @Test
    void recordReceipt_cash_succeedsWithNullReference() {
        runMethodCase("CASH", null);
    }

    private void runMethodCase(String method, String ref) {
        PaymentPenalty p = openPenalty(new BigDecimal("500"), 0, BigDecimal.ZERO);
        when(paymentPenaltyRepository.findById(penaltyId)).thenReturn(Optional.of(p));
        when(penaltyPaymentRepository.findByPaymentPenaltyIdOrderByReceivedAtAscCreatedAtAsc(penaltyId))
                .thenReturn(List.of());
        when(financialTransactionService.recordPenaltyIncome(any(PaymentPenalty.class), any(PenaltyPayment.class)))
                .thenReturn(UUID.randomUUID());
        when(penaltyPaymentRepository.save(any(PenaltyPayment.class))).thenAnswer(inv -> inv.getArgument(0));

        PenaltyPaymentService.RecordReceiptInput input = new PenaltyPaymentService.RecordReceiptInput(
                new BigDecimal("100"), method, ref, TODAY, null);

        PenaltyPayment saved = service.recordReceipt(penaltyId, input, receivedBy);
        assertThat(saved.getPaymentMethod()).isEqualTo(method);
        assertThat(saved.getPaymentReference()).isEqualTo(ref);
    }

    // ---------- helpers ----------

    private PaymentPenalty openPenalty(BigDecimal amount, int daysOverdue, BigDecimal perDayRate) {
        PaymentPenalty p = new PaymentPenalty();
        p.setId(penaltyId);
        p.setPaymentScheduleId(UUID.randomUUID());
        p.setLeaseId(UUID.randomUUID());
        p.setPenaltyType("CHEQUE_FAILURE");
        p.setPenaltyAmount(amount);
        p.setDaysOverdue(daysOverdue);
        p.setFinePerDayRate(perDayRate);
        p.setCreatedAt(LocalDateTime.now(fixedClock).minusDays(15));
        return p;
    }
}
