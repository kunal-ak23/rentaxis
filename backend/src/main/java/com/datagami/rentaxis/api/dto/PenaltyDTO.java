package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.fasterxml.jackson.annotation.JsonProperty;

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
    /**
     * Derived status. The {@link JsonProperty} annotation is required because
     * Jackson's record support only auto-serialises record components by default;
     * non-component methods need explicit annotation to appear in the JSON output.
     * Without it the frontend's <code>status === "OPEN"</code> checks silently fail.
     */
    @JsonProperty("status")
    public String status() {
        if (clearedAt != null && waived) return "WAIVED";
        if (clearedAt != null) return "CLEARED";
        return "OPEN";
    }
}
