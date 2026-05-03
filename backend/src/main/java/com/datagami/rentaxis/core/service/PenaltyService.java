package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PaymentPenalty;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PaymentPenaltyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class PenaltyService {

    private static final Logger log = LoggerFactory.getLogger(PenaltyService.class);

    private final PaymentPenaltyRepository paymentPenaltyRepository;
    private final LeaseRepository leaseRepository;
    private final PenaltyProcessingService penaltyProcessingService;
    private final Clock clock;

    public PenaltyService(PaymentPenaltyRepository paymentPenaltyRepository,
                          LeaseRepository leaseRepository,
                          PenaltyProcessingService penaltyProcessingService,
                          Clock clock) {
        this.paymentPenaltyRepository = paymentPenaltyRepository;
        this.leaseRepository = leaseRepository;
        this.penaltyProcessingService = penaltyProcessingService;
        this.clock = clock;
    }

    /**
     * Daily cron job at 2 AM to calculate penalties for overdue payments.
     * Runs across all tenants.
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void calculateDailyPenalties() {
        log.info("Starting daily penalty calculation");
        LocalDate today = LocalDate.now(clock);

        // Clear tenant context to query across all tenants
        TenantContextHolder.clear();

        try {
            List<Lease> activeLeases = leaseRepository.findByStatus(LeaseStatus.ACTIVE);
            log.info("Found {} active leases to process", activeLeases.size());

            for (Lease lease : activeLeases) {
                try {
                    // Set tenant context for this lease
                    TenantContextHolder.setTenantId(lease.getTenantId());
                    penaltyProcessingService.processLeaseOverduePayments(lease, today);
                } catch (Exception e) {
                    log.error("Error processing penalties for lease {}: {}", lease.getId(), e.getMessage(), e);
                } finally {
                    TenantContextHolder.clear();
                }
            }

            // Sibling pass: per-day accrual on unpaid CHEQUE_FAILURE penalties.
            try {
                processChequeFailureAccruals();
            } catch (Exception e) {
                log.error("Error processing cheque-failure penalty accruals: {}", e.getMessage(), e);
            }
        } finally {
            TenantContextHolder.clear();
        }

        log.info("Daily penalty calculation completed");
    }

    /**
     * Sibling pass to {@link PenaltyProcessingService#processLeaseOverduePayments}: walks
     * open {@code CHEQUE_FAILURE} {@link PaymentPenalty} rows (cleared_at IS NULL) and
     * updates {@code daysOverdue} based on how many days past
     * {@code (createdAt + fineGraceDays)} we are today. Runs across all tenants — caller
     * is expected to have cleared {@link TenantContextHolder}.
     */
    @Transactional
    public void processChequeFailureAccruals() {
        LocalDate today = LocalDate.now(clock);
        List<PaymentPenalty> open = paymentPenaltyRepository
                .findByPenaltyTypeAndClearedAtIsNull("CHEQUE_FAILURE");
        log.info("Found {} open CHEQUE_FAILURE penalties to accrue", open.size());

        LocalDateTime now = LocalDateTime.now(clock);
        for (PaymentPenalty p : open) {
            if (p.getFineGraceDays() == null || p.getCreatedAt() == null) {
                // Safety: skip rows missing the fields populated by markFailed (M5).
                continue;
            }
            LocalDate graceUntil = p.getCreatedAt().toLocalDate().plusDays(p.getFineGraceDays());
            long days = today.isAfter(graceUntil)
                    ? ChronoUnit.DAYS.between(graceUntil, today)
                    : 0;
            p.setDaysOverdue((int) days);
            p.setLastCalculatedAt(now);
        }
        paymentPenaltyRepository.saveAll(open);
    }

    @Transactional(readOnly = true)
    public List<PaymentPenalty> getPenaltiesByLeaseId(UUID leaseId) {
        return paymentPenaltyRepository.findByLeaseIdOrderByCreatedAtAsc(leaseId);
    }

    @Transactional(readOnly = true)
    public BigDecimal getTotalUnwaivedPenalties(UUID leaseId) {
        List<PaymentPenalty> penalties = paymentPenaltyRepository.findByLeaseIdAndWaivedFalse(leaseId);
        return penalties.stream()
                .map(PaymentPenalty::getPenaltyAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transactional
    public PaymentPenalty waivePenalty(UUID penaltyId, String reason, UUID waivedBy) {
        PaymentPenalty penalty = paymentPenaltyRepository.findById(penaltyId)
                .orElseThrow(() -> new RuntimeException("Penalty not found: " + penaltyId));

        UUID currentTenantId = TenantContextHolder.getTenantId();
        if (currentTenantId != null && !currentTenantId.equals(penalty.getTenantId())) {
            throw new RuntimeException("Access denied");
        }

        penalty.setWaived(true);
        penalty.setWaivedReason(reason);
        penalty.setWaivedBy(waivedBy);
        penalty.setWaivedAt(LocalDateTime.now(clock));
        return paymentPenaltyRepository.save(penalty);
    }

    @Transactional
    public List<PaymentPenalty> recalculateForLease(UUID leaseId) {
        Lease lease = leaseRepository.findById(leaseId)
                .orElseThrow(() -> new RuntimeException("Lease not found: " + leaseId));

        UUID currentTenantId = TenantContextHolder.getTenantId();
        if (currentTenantId != null && !currentTenantId.equals(lease.getTenantId())) {
            throw new RuntimeException("Access denied");
        }

        penaltyProcessingService.processLeaseOverduePayments(lease, LocalDate.now(clock));
        return paymentPenaltyRepository.findByLeaseIdOrderByCreatedAtAsc(leaseId);
    }
}
