package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code POST /api/v1/payments/{id}/mark-failed}. The
 * reason drives which fine amount is applied (BOUNCE / SIGNATURE_MISMATCH /
 * ACCOUNT_CLOSED) — it isn't optional.
 */
public record MarkFailedRequestDTO(@NotNull ChequeFailureReason failureReason, String notes) {}
