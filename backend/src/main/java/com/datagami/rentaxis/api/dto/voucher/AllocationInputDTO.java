package com.datagami.rentaxis.api.dto.voucher;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/** One invoice a payment settles: exactly one of {@code invoiceId} (a PISR) and {@code openingItemId}. */
public record AllocationInputDTO(UUID invoiceId, UUID openingItemId, @NotNull @Positive BigDecimal amount) { }
