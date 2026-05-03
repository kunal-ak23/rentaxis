package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record PenaltyDTO(
        UUID id,
        UUID paymentScheduleId,
        UUID leaseId,
        String penaltyType,
        ChequeFailureReason failureReason,
        BigDecimal penaltyAmount,
        Integer daysOverdue,
        Integer fineGraceDays,
        BigDecimal finePerDayRate,
        BigDecimal currentTotal,
        BigDecimal outstanding,
        boolean waived,
        String waivedReason,
        LocalDateTime createdAt,
        LocalDateTime clearedAt,
        List<PenaltyPaymentDTO> payments
) {
    public String status() {
        if (clearedAt != null && waived) return "WAIVED";
        if (clearedAt != null) return "CLEARED";
        return "OPEN";
    }
}
