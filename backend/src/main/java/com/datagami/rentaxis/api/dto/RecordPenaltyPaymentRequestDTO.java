package com.datagami.rentaxis.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RecordPenaltyPaymentRequestDTO(
        @NotNull @Positive BigDecimal amount,
        @NotNull String paymentMethod,
        String paymentReference,
        LocalDate receivedAt,
        String notes
) {}
