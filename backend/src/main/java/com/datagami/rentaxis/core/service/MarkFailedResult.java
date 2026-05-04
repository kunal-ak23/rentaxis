package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PaymentScheduleDTO;
import com.datagami.rentaxis.domain.entity.PaymentPenalty;

/**
 * Service-level result for {@link PaymentScheduleService#markFailed} —
 * carries both the updated {@link PaymentScheduleDTO} and the freshly-created
 * {@link PaymentPenalty} so the controller can render the full effect (status
 * change + fine) without a follow-up DB round-trip.
 */
public record MarkFailedResult(PaymentScheduleDTO schedule, PaymentPenalty penalty) {
}
