package com.datagami.rentaxis.api.dto.voucher;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

public record VoucherLineInputDTO(
        @NotNull UUID accountId,
        String description,
        @NotNull @Positive BigDecimal amount,
        BigDecimal vatRate,
        UUID propertyId,
        UUID unitId) {}
