package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.PaymentPenalty;
import com.datagami.rentaxis.domain.entity.PenaltyPayment;
import com.datagami.rentaxis.domain.repository.LeaseEventRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PaymentPenaltyRepository;
import com.datagami.rentaxis.domain.repository.PenaltyPaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link PenaltyService#getTotalUnwaivedPenalties} is what
 * {@code SettlementService.getSettlementPreview} subtracts from the deposit to
 * produce the refund an operator accepts, so an overstatement here takes real
 * money off a departing renter.
 *
 * <p>The old implementation summed {@code penaltyAmount} over
 * {@code findByLeaseIdAndWaivedFalse}. A penalty settled in cash has
 * {@code clearedAt} set but {@code waived} false, so it stayed in that sum and
 * was deducted a second time; partial receipts were ignored; and the per-day
 * accrual was dropped. These tests drive a real {@link PenaltyPaymentService}
 * so {@code outstanding()} executes its actual logic rather than a stub that
 * would make the assertions tautological.
 */
class PenaltyServiceSettlementTotalTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-09T08:00:00Z"), ZoneOffset.UTC);

    private PaymentPenaltyRepository paymentPenaltyRepository;
    private PenaltyPaymentRepository penaltyPaymentRepository;
    private PenaltyService service;

    @BeforeEach
    void setUp() {
        paymentPenaltyRepository = mock(PaymentPenaltyRepository.class);
        penaltyPaymentRepository = mock(PenaltyPaymentRepository.class);

        PenaltyPaymentService penaltyPaymentService = new PenaltyPaymentService(
                paymentPenaltyRepository,
                penaltyPaymentRepository,
                mock(FinancialTransactionService.class),
                mock(NotificationService.class),
                mock(LeaseEventRepository.class),
                mock(LeaseRepository.class),
                CLOCK,
                new ObjectMapper());

        service = new PenaltyService(
                paymentPenaltyRepository,
                penaltyPaymentService,
                mock(LeaseRepository.class),
                mock(PenaltyProcessingService.class),
                mock(NotificationService.class),
                CLOCK);

        // Default: no receipts against any penalty.
        when(penaltyPaymentRepository.findByPaymentPenaltyIdOrderByReceivedAtAscCreatedAtAsc(any()))
                .thenReturn(List.of());
    }

    private PaymentPenalty penalty(String amount, boolean cleared) {
        PaymentPenalty p = new PaymentPenalty();
        p.setId(UUID.randomUUID());
        p.setPenaltyAmount(new BigDecimal(amount));
        p.setWaived(false);
        if (cleared) {
            p.setClearedAt(java.time.LocalDateTime.now(CLOCK));
        }
        return p;
    }

    @Test
    void alreadyPaidPenaltyIsNotDeductedFromTheDepositAgain() {
        UUID leaseId = UUID.randomUUID();
        when(paymentPenaltyRepository.findByLeaseIdAndWaivedFalse(leaseId)).thenReturn(List.of(
                penalty("500", true),    // renter already paid this in cash
                penalty("300", false))); // genuinely still owed

        // Before the fix this returned 800 — the settled 500 was deducted from
        // the refund a second time.
        assertThat(service.getTotalUnwaivedPenalties(leaseId)).isEqualByComparingTo("300");
    }

    @Test
    void partialReceiptsAreNettedOffRatherThanIgnored() {
        UUID leaseId = UUID.randomUUID();
        PaymentPenalty open = penalty("1000", false);
        when(paymentPenaltyRepository.findByLeaseIdAndWaivedFalse(leaseId)).thenReturn(List.of(open));

        PenaltyPayment receipt = new PenaltyPayment();
        receipt.setAmount(new BigDecimal("400"));
        when(penaltyPaymentRepository.findByPaymentPenaltyIdOrderByReceivedAtAscCreatedAtAsc(open.getId()))
                .thenReturn(List.of(receipt));

        // Before the fix this returned the 1000 face value despite 400 paid.
        assertThat(service.getTotalUnwaivedPenalties(leaseId)).isEqualByComparingTo("600");
    }

    @Test
    void perDayAccrualIsIncludedInTheAmountStillOwed() {
        UUID leaseId = UUID.randomUUID();
        PaymentPenalty open = penalty("200", false);
        open.setFinePerDayRate(new BigDecimal("25"));
        open.setDaysOverdue(4);
        when(paymentPenaltyRepository.findByLeaseIdAndWaivedFalse(leaseId)).thenReturn(List.of(open));

        // 200 base + (4 * 25) accrual. The old sum dropped the accrual entirely.
        assertThat(service.getTotalUnwaivedPenalties(leaseId)).isEqualByComparingTo("300");
    }

    @Test
    void aLeaseWithNothingOwedDeductsNothing() {
        UUID leaseId = UUID.randomUUID();
        when(paymentPenaltyRepository.findByLeaseIdAndWaivedFalse(leaseId))
                .thenReturn(List.of(penalty("750", true)));

        assertThat(service.getTotalUnwaivedPenalties(leaseId)).isEqualByComparingTo("0");
    }
}
