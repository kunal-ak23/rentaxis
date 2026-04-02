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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class PenaltyService {

    private static final Logger log = LoggerFactory.getLogger(PenaltyService.class);

    private final PaymentPenaltyRepository paymentPenaltyRepository;
    private final LeaseRepository leaseRepository;
    private final PenaltyProcessingService penaltyProcessingService;

    public PenaltyService(PaymentPenaltyRepository paymentPenaltyRepository,
                          LeaseRepository leaseRepository,
                          PenaltyProcessingService penaltyProcessingService) {
        this.paymentPenaltyRepository = paymentPenaltyRepository;
        this.leaseRepository = leaseRepository;
        this.penaltyProcessingService = penaltyProcessingService;
    }

    /**
     * Daily cron job at 2 AM to calculate penalties for overdue payments.
     * Runs across all tenants.
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void calculateDailyPenalties() {
        log.info("Starting daily penalty calculation");
        LocalDate today = LocalDate.now();

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
        } finally {
            TenantContextHolder.clear();
        }

        log.info("Daily penalty calculation completed");
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
        penalty.setWaivedAt(LocalDateTime.now());
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

        penaltyProcessingService.processLeaseOverduePayments(lease, LocalDate.now());
        return paymentPenaltyRepository.findByLeaseIdOrderByCreatedAtAsc(leaseId);
    }
}
