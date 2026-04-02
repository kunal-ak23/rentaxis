package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PaymentPenalty;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.RentCollectionSettings;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.entity.enums.PenaltyType;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PaymentPenaltyRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PenaltyService {

    private static final Logger log = LoggerFactory.getLogger(PenaltyService.class);

    private final PaymentPenaltyRepository paymentPenaltyRepository;
    private final LeaseRepository leaseRepository;
    private final PaymentScheduleRepository paymentScheduleRepository;
    private final RentCollectionSettingsRepository rentCollectionSettingsRepository;
    private final PenaltyCalculationService penaltyCalculationService;

    public PenaltyService(PaymentPenaltyRepository paymentPenaltyRepository,
                          LeaseRepository leaseRepository,
                          PaymentScheduleRepository paymentScheduleRepository,
                          RentCollectionSettingsRepository rentCollectionSettingsRepository,
                          PenaltyCalculationService penaltyCalculationService) {
        this.paymentPenaltyRepository = paymentPenaltyRepository;
        this.leaseRepository = leaseRepository;
        this.paymentScheduleRepository = paymentScheduleRepository;
        this.rentCollectionSettingsRepository = rentCollectionSettingsRepository;
        this.penaltyCalculationService = penaltyCalculationService;
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
                    processLeaseOverduePayments(lease, today);
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

    @Transactional
    protected void processLeaseOverduePayments(Lease lease, LocalDate today) {
        List<PaymentSchedule> pendingSchedules = paymentScheduleRepository
                .findByLeaseIdAndStatus(lease.getId(), PaymentStatus.PENDING);

        // Also process already-overdue payments
        List<PaymentSchedule> overdueSchedules = paymentScheduleRepository
                .findByLeaseIdAndStatus(lease.getId(), PaymentStatus.OVERDUE);

        List<PaymentSchedule> allSchedules = new java.util.ArrayList<>(pendingSchedules);
        allSchedules.addAll(overdueSchedules);

        for (PaymentSchedule schedule : allSchedules) {
            if (schedule.getDueDate().isBefore(today)) {
                // Mark PENDING as OVERDUE
                if (schedule.getStatus() == PaymentStatus.PENDING) {
                    schedule.setStatus(PaymentStatus.OVERDUE);
                    paymentScheduleRepository.save(schedule);
                }

                // Get rent collection settings for the property
                UUID propertyId = schedule.getProperty().getId();
                Optional<RentCollectionSettings> settingsOpt =
                        rentCollectionSettingsRepository.findByPropertyId(propertyId);

                if (settingsOpt.isEmpty()) {
                    continue;
                }

                RentCollectionSettings settings = settingsOpt.get();
                if (settings.getPenaltyType() == null || settings.getPenaltyType() == PenaltyType.NONE) {
                    continue;
                }

                // Calculate penalty using existing service
                BigDecimal penaltyAmount = penaltyCalculationService.calculatePenalty(schedule, settings, today);
                int gracePeriodDays = settings.getGracePeriodDays() != null ? settings.getGracePeriodDays() : 0;
                int daysOverdue = penaltyCalculationService.calculateDaysOverdue(schedule, gracePeriodDays, today);

                if (penaltyAmount.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }

                // Create or update penalty record
                Optional<PaymentPenalty> existingPenalty =
                        paymentPenaltyRepository.findByPaymentScheduleId(schedule.getId());

                PaymentPenalty penalty;
                if (existingPenalty.isPresent()) {
                    penalty = existingPenalty.get();
                } else {
                    penalty = new PaymentPenalty();
                    penalty.setPaymentScheduleId(schedule.getId());
                    penalty.setLeaseId(lease.getId());
                }

                penalty.setPenaltyAmount(penaltyAmount);
                penalty.setDaysOverdue(daysOverdue);
                penalty.setPenaltyType(settings.getPenaltyType().name());
                penalty.setPenaltyRate(settings.getPenaltyAmount());
                penalty.setGracePeriodDays(gracePeriodDays);
                penalty.setLastCalculatedAt(LocalDateTime.now());

                paymentPenaltyRepository.save(penalty);
            }
        }
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
        processLeaseOverduePayments(lease, LocalDate.now());
        return paymentPenaltyRepository.findByLeaseIdOrderByCreatedAtAsc(leaseId);
    }
}
