package com.datagami.rentaxis.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PenaltyPaymentDTO(
        UUID id,
        BigDecimal amount,
        String paymentMethod,
        String paymentReference,
        LocalDate receivedAt,
        UUID receivedBy,
        String notes,
        Instant createdAt
) {}
