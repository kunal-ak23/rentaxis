package com.datagami.rentaxis.api.dto.voucher;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code shared}: the line is deliberately a shared / head-office cost with no
 * property (finance-ops spec §1, S12). Not persisted — a null property already
 * means shared — it only answers the server's "which property?" check.
 */
public record VoucherLineInputDTO(
        @NotNull UUID accountId,
        String description,
        @NotNull @Positive BigDecimal amount,
        BigDecimal vatRate,
        UUID propertyId,
        UUID unitId,
        Boolean shared) {}
