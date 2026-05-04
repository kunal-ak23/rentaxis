package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
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
    private final NotificationService notificationService;
    private final Clock clock;

    public PenaltyService(PaymentPenaltyRepository paymentPenaltyRepository,
                          LeaseRepository leaseRepository,
                          PenaltyProcessingService penaltyProcessingService,
                          NotificationService notificationService,
                          Clock clock) {
        this.paymentPenaltyRepository = paymentPenaltyRepository;
        this.leaseRepository = leaseRepository;
        this.penaltyProcessingService = penaltyProcessingService;
        this.notificationService = notificationService;
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
     * {@code (createdAt + fineGraceDays)} we are today.
     *
     * <p><b>Multi-tenant note:</b> The caller ({@link #calculateDailyPenalties}) clears
     * {@link TenantContextHolder} before invoking this method. With no tenant context set,
     * the Hibernate tenant filter ({@code @Filter("tenantFilter")}) on
     * {@code BaseTenantEntity} subclasses is <em>not</em> activated by
     * {@link com.datagami.rentaxis.core.tenant.TenantAspect} — because the aspect gates on
     * {@code TenantContextHolder.getTenantId() != null}. This is intentional: the cron
     * must process all tenants in a single pass. Each saved entity retains its original
     * {@code tenant_id}, so data is never written cross-tenant.</p>
     *
     * <p>TODO (MVP deferral): reminder notifications for unpaid penalties past
     * {@code graceUntil} are not yet wired here; see design §Notifications.</p>
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

    /**
     * Waive a penalty: set waived/waivedBy/waivedReason/waivedAt and also
     * {@code clearedAt} so the daily accrual pass stops accruing perDayRate
     * against this row. Rejects re-waivers and already-cleared penalties so
     * a goodwill waiver after a partial payment doesn't quietly clobber the
     * payment audit.
     */
    @Transactional
    public PaymentPenalty waivePenalty(UUID penaltyId, String reason, UUID waivedBy) {
        PaymentPenalty penalty = paymentPenaltyRepository.findById(penaltyId)
                .orElseThrow(() -> new NotFoundException("Penalty not found: " + penaltyId));

        UUID currentTenantId = TenantContextHolder.getTenantId();
        if (currentTenantId != null && !currentTenantId.equals(penalty.getTenantId())) {
            throw new BusinessRuleViolationException("Access denied");
        }

        if (penalty.getClearedAt() != null) {
            throw new BusinessRuleViolationException("Penalty already cleared");
        }
        if (penalty.isWaived()) {
            throw new BusinessRuleViolationException("Penalty already waived");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        penalty.setWaived(true);
        penalty.setWaivedReason(reason);
        penalty.setWaivedBy(waivedBy);
        penalty.setWaivedAt(now);
        // Also stamp clearedAt — accrual job ignores cleared rows. Otherwise
        // a waived-but-not-cleared penalty would keep accruing perDayRate.
        penalty.setClearedAt(now);
        PaymentPenalty saved = paymentPenaltyRepository.save(penalty);

        // Best-effort notification — never block the waive bookkeeping.
        try {
            notificationService.sendPenaltyWaived(saved, reason);
        } catch (Exception e) {
            log.warn("Failed to send PENALTY_WAIVED notification for penalty {}: {}", saved.getId(), e.getMessage());
        }

        return saved;
    }

    @Transactional
    public List<PaymentPenalty> recalculateForLease(UUID leaseId) {
        Lease lease = leaseRepository.findById(leaseId)
                .orElseThrow(() -> new NotFoundException("Lease not found: " + leaseId));

        UUID currentTenantId = TenantContextHolder.getTenantId();
        if (currentTenantId != null && !currentTenantId.equals(lease.getTenantId())) {
            throw new BusinessRuleViolationException("Access denied");
        }

        penaltyProcessingService.processLeaseOverduePayments(lease, LocalDate.now(clock));
        return paymentPenaltyRepository.findByLeaseIdOrderByCreatedAtAsc(leaseId);
    }
}
