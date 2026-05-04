package com.datagami.rentaxis.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response shape for {@code POST /api/v1/payments/{id}/mark-failed}: returns
 * the updated schedule plus a summary of the freshly-created penalty so the
 * caller can render the full effect (status + fine) without a follow-up
 * round-trip.
 */
public record MarkFailedResponseDTO(
        PaymentScheduleDTO schedule,
        PenaltySummaryDTO penalty
) {
    public record PenaltySummaryDTO(
            UUID id, String penaltyType, BigDecimal penaltyAmount,
            Integer fineGraceDays, BigDecimal finePerDayRate, LocalDateTime createdAt
    ) {}
}
