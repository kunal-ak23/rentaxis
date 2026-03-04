package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.RentCollectionSettings;
import com.datagami.rentaxis.domain.entity.enums.PenaltyType;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Service
public class PenaltyCalculationService {

    public BigDecimal calculatePenalty(PaymentSchedule schedule, RentCollectionSettings settings, LocalDate asOfDate) {
        if (settings == null || settings.getPenaltyType() == PenaltyType.NONE) {
            return BigDecimal.ZERO;
        }
        int gracePeriodDays = settings.getGracePeriodDays() != null ? settings.getGracePeriodDays() : 0;
        LocalDate effectiveDueDate = schedule.getDueDate().plusDays(gracePeriodDays);
        if (!asOfDate.isAfter(effectiveDueDate)) {
            return BigDecimal.ZERO;
        }
        long daysLate = ChronoUnit.DAYS.between(effectiveDueDate, asOfDate);
        return switch (settings.getPenaltyType()) {
            case FIXED_PER_DAY -> settings.getPenaltyAmount().multiply(BigDecimal.valueOf(daysLate));
            case PERCENTAGE -> schedule.getAmount()
                    .multiply(settings.getPenaltyAmount())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(daysLate));
            default -> BigDecimal.ZERO;
        };
    }

    public int calculateDaysOverdue(PaymentSchedule schedule, int gracePeriodDays, LocalDate asOfDate) {
        LocalDate effectiveDueDate = schedule.getDueDate().plusDays(gracePeriodDays);
        if (!asOfDate.isAfter(effectiveDueDate)) {
            return 0;
        }
        return (int) ChronoUnit.DAYS.between(effectiveDueDate, asOfDate);
    }
}
