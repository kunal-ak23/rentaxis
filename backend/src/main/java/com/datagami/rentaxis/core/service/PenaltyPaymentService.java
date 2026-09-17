package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LeaseEvent;
import com.datagami.rentaxis.domain.entity.PaymentPenalty;
import com.datagami.rentaxis.domain.entity.PenaltyPayment;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.LeaseEventRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PaymentPenaltyRepository;
import com.datagami.rentaxis.domain.repository.PenaltyPaymentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Records penalty payment receipts (bank transfer / cheque / cash) against an
 * open {@link PaymentPenalty}. Posts the matching penalty-income journal entry,
 * keeps a running outstanding total that respects the daily accrual ({@code
 * penaltyAmount + daysOverdue * finePerDayRate}), and clears the penalty +
 * fires {@code PENALTY_CLEARED} the moment the receipts cover the total.
 *
 * <p>Validation rules:
 * <ul>
 *   <li>amount must be positive (zero / negative rejected)</li>
 *   <li>amount must not exceed the current outstanding</li>
 *   <li>penalty must not already be cleared or waived</li>
 * </ul>
 *
 * <p>Notification + LeaseEvent writes are best-effort relative to the receipt
 * itself: the receipt row is saved first, then the notification, then the audit
 * row.
 */
@Slf4j
@Service
public class PenaltyPaymentService {

    private final PaymentPenaltyRepository paymentPenaltyRepository;
    private final PenaltyPaymentRepository penaltyPaymentRepository;
    private final NotificationService notificationService;
    private final LeaseEventRepository leaseEventRepository;
    private final LeaseRepository leaseRepository;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public PenaltyPaymentService(PaymentPenaltyRepository paymentPenaltyRepository,
                                 PenaltyPaymentRepository penaltyPaymentRepository,
                                 NotificationService notificationService,
                                 LeaseEventRepository leaseEventRepository,
                                 LeaseRepository leaseRepository,
                                 Clock clock,
                                 com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.paymentPenaltyRepository = paymentPenaltyRepository;
        this.penaltyPaymentRepository = penaltyPaymentRepository;
        this.notificationService = notificationService;
        this.leaseEventRepository = leaseEventRepository;
        this.leaseRepository = leaseRepository;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /**
     * Input bundle for {@link #recordReceipt(UUID, RecordReceiptInput, UUID)}.
     *
     * @param amount           positive receipt amount
     * @param paymentMethod    BANK_TRANSFER / CHEQUE / CASH
     * @param paymentReference UTR / cheque number / null for cash
     * @param receivedAt       date the money was received (defaults to today on null)
     * @param notes            optional free-text note attached to the receipt row
     */
    public record RecordReceiptInput(
            BigDecimal amount,
            String paymentMethod,
            String paymentReference,
            LocalDate receivedAt,
            String notes
    ) {}

    @Transactional
    public PenaltyPayment recordReceipt(UUID penaltyId, RecordReceiptInput input, UUID receivedBy) {
        if (input.amount() == null || input.amount().signum() <= 0) {
            throw new BusinessRuleViolationException("amount must be positive");
        }
        if (input.paymentMethod() == null) {
            throw new BusinessRuleViolationException("paymentMethod is required");
        }

        PaymentPenalty p = paymentPenaltyRepository.findById(penaltyId)
                .orElseThrow(() -> new NotFoundException("Penalty " + penaltyId + " not found"));

        // Defense-in-depth: the Hibernate tenantFilter already filters findById,
        // but mirror the explicit check used in PenaltyService.waivePenalty so a
        // misconfigured filter cannot silently let a cross-tenant receipt land.
        UUID currentTenantId = TenantContextHolder.getTenantId();
        if (currentTenantId != null && !currentTenantId.equals(p.getTenantId())) {
            throw new BusinessRuleViolationException("Access denied");
        }

        if (p.getClearedAt() != null) {
            throw new BusinessRuleViolationException("Penalty already cleared");
        }
        if (p.isWaived()) {
            throw new BusinessRuleViolationException("Penalty already waived");
        }

        BigDecimal currentTotal = currentTotal(p);
        BigDecimal alreadyPaid = penaltyPaymentRepository
                .findByPaymentPenaltyIdOrderByReceivedAtAscCreatedAtAsc(penaltyId)
                .stream()
                .map(PenaltyPayment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal outstanding = currentTotal.subtract(alreadyPaid);

        if (input.amount().compareTo(outstanding) > 0) {
            throw new BusinessRuleViolationException(
                    "amount " + input.amount() + " exceeds outstanding " + outstanding);
        }

        PenaltyPayment row = new PenaltyPayment();
        row.setPaymentPenaltyId(penaltyId);
        row.setAmount(input.amount());
        row.setPaymentMethod(input.paymentMethod());
        row.setPaymentReference(input.paymentReference());
        row.setReceivedAt(input.receivedAt() != null ? input.receivedAt() : LocalDate.now(clock));
        row.setReceivedBy(receivedBy);
        row.setNotes(input.notes());

        // Ledger posting moves to PostingService in accounting v2 plan 2/3 (see spec §7/§9).
        PenaltyPayment saved = penaltyPaymentRepository.save(row);

        BigDecimal nowPaid = alreadyPaid.add(input.amount());
        if (nowPaid.compareTo(currentTotal) >= 0) {
            p.setClearedAt(LocalDateTime.now(clock));
            paymentPenaltyRepository.save(p);
            try {
                notificationService.sendPenaltyCleared(p, saved);
            } catch (Exception e) {
                log.warn("Failed to send PENALTY_CLEARED notification for penalty {}: {}", p.getId(), e.getMessage());
            }
        }

        leaseEventRepository.save(buildLeaseEvent(p, saved));
        return saved;
    }

    /**
     * Total amount currently owed on a penalty: base {@code penaltyAmount} plus
     * the per-day accrual ({@code daysOverdue * finePerDayRate}). Public so
     * callers (DTO builders, controllers) can render the same total a partial
     * receipt would settle against.
     */
    public BigDecimal currentTotal(PaymentPenalty p) {
        BigDecimal accrual = p.getFinePerDayRate() == null || p.getDaysOverdue() == null
                ? BigDecimal.ZERO
                : p.getFinePerDayRate().multiply(BigDecimal.valueOf(Math.max(0, p.getDaysOverdue())));
        return p.getPenaltyAmount().add(accrual);
    }

    /**
     * Outstanding = {@code currentTotal - sum(receipts)} for an open penalty;
     * zero for cleared / waived rows. Public for dashboard / lease-payments
     * views.
     */
    public BigDecimal outstanding(PaymentPenalty p) {
        if (p.getClearedAt() != null || p.isWaived()) return BigDecimal.ZERO;
        BigDecimal paid = penaltyPaymentRepository
                .findByPaymentPenaltyIdOrderByReceivedAtAscCreatedAtAsc(p.getId())
                .stream().map(PenaltyPayment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return currentTotal(p).subtract(paid);
    }

    private LeaseEvent buildLeaseEvent(PaymentPenalty p, PenaltyPayment receipt) {
        LeaseEvent ev = new LeaseEvent();
        // LeaseEvent.lease_id is NOT NULL. Hydrate the Lease relation from the
        // penalty's leaseId so the audit row saves cleanly. If the lease has
        // been deleted (rare, but possible during settlement clean-up), skip
        // the relation — the JSON marker still carries enough context to
        // reconstruct the link offline.
        if (p.getLeaseId() != null) {
            leaseRepository.findById(p.getLeaseId()).ifPresent(ev::setLease);
        }
        // previousState/newState are NOT NULL — keep both ACTIVE to match the
        // M5 mark-failed pattern (the payment-level transition does not change
        // the lease's lifecycle state).
        ev.setPreviousState(LeaseStatus.ACTIVE);
        ev.setNewState(LeaseStatus.ACTIVE);
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", "PENALTY_PAYMENT_RECORDED");
            payload.put("penaltyId", p.getId().toString());
            payload.put("amount", receipt.getAmount().toPlainString());
            payload.put("paymentMethod", receipt.getPaymentMethod());
            ev.setNotes(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize PENALTY_PAYMENT_RECORDED notes JSON: {}", e.getMessage());
            ev.setNotes("PENALTY_PAYMENT_RECORDED penaltyId=" + p.getId() + " amount=" + receipt.getAmount());
        }
        return ev;
    }
}
