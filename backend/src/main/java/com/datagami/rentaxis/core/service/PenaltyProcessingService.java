package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PaymentPenalty;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.RentCollectionSettings;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.entity.enums.PenaltyType;
import com.datagami.rentaxis.domain.repository.PaymentPenaltyRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PenaltyProcessingService {

    private static final Logger log = LoggerFactory.getLogger(PenaltyProcessingService.class);

    private final PaymentPenaltyRepository paymentPenaltyRepository;
    private final PaymentScheduleRepository paymentScheduleRepository;
    private final RentCollectionSettingsRepository rentCollectionSettingsRepository;
    private final PenaltyCalculationService penaltyCalculationService;

    public PenaltyProcessingService(PaymentPenaltyRepository paymentPenaltyRepository,
                                     PaymentScheduleRepository paymentScheduleRepository,
                                     RentCollectionSettingsRepository rentCollectionSettingsRepository,
                                     PenaltyCalculationService penaltyCalculationService) {
        this.paymentPenaltyRepository = paymentPenaltyRepository;
        this.paymentScheduleRepository = paymentScheduleRepository;
        this.rentCollectionSettingsRepository = rentCollectionSettingsRepository;
        this.penaltyCalculationService = penaltyCalculationService;
    }

    @Transactional
    public void processLeaseOverduePayments(Lease lease, LocalDate today) {
        List<PaymentSchedule> pendingSchedules = paymentScheduleRepository
                .findByLeaseIdAndStatus(lease.getId(), PaymentStatus.PENDING);

        List<PaymentSchedule> overdueSchedules = paymentScheduleRepository
                .findByLeaseIdAndStatus(lease.getId(), PaymentStatus.OVERDUE);

        List<PaymentSchedule> allSchedules = new ArrayList<>(pendingSchedules);
        allSchedules.addAll(overdueSchedules);

        for (PaymentSchedule schedule : allSchedules) {
            if (schedule.getDueDate().isBefore(today)) {
                // Mark PENDING as OVERDUE
                if (schedule.getStatus() == PaymentStatus.PENDING) {
                    schedule.setStatus(PaymentStatus.OVERDUE);
                    paymentScheduleRepository.save(schedule);
                }

                UUID propertyId = schedule.getProperty().getId();
                Optional<RentCollectionSettings> settingsOpt =
                        rentCollectionSettingsRepository.findByPropertyId(propertyId);

                if (settingsOpt.isEmpty()) continue;

                RentCollectionSettings settings = settingsOpt.get();
                if (settings.getPenaltyType() == null || settings.getPenaltyType() == PenaltyType.NONE) continue;

                BigDecimal penaltyAmount = penaltyCalculationService.calculatePenalty(schedule, settings, today);
                int gracePeriodDays = settings.getGracePeriodDays() != null ? settings.getGracePeriodDays() : 0;
                int daysOverdue = penaltyCalculationService.calculateDaysOverdue(schedule, gracePeriodDays, today);

                if (penaltyAmount.compareTo(BigDecimal.ZERO) <= 0) continue;

                Optional<PaymentPenalty> existingPenalty =
                        paymentPenaltyRepository.findByPaymentScheduleId(schedule.getId());

                // Skip waived penalties
                if (existingPenalty.isPresent() && existingPenalty.get().isWaived()) continue;

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
}
