package com.datagami.rentaxis.api.dto.payables;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** {@code POST /voucher-allocations}: apply a payment (typically an advance) to one invoice or opening item. */
public record AllocateRequestDTO(@NotNull UUID paymentId, UUID invoiceId, UUID openingItemId,
                                 @NotNull @Positive BigDecimal amount, LocalDate allocatedOn) { }
