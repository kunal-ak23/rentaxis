package com.datagami.rentaxis.api.dto;
import com.datagami.rentaxis.domain.entity.enums.*;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
public record CreateInteractionRequest(
        @NotNull InteractionType type,
        @NotNull InteractionDirection direction,
        @NotNull @PastOrPresent Instant occurredAt,
        @NotBlank String summary,
        InteractionOutcome outcome,
        @FutureOrPresent LocalDate followUpDate
) {}
