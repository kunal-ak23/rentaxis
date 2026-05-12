package com.datagami.rentaxis.api.dto;
import com.datagami.rentaxis.domain.entity.enums.InteractionOutcome;
import jakarta.validation.constraints.FutureOrPresent;
import java.time.LocalDate;
public record UpdateInteractionRequest(
        String summary,
        InteractionOutcome outcome,
        @FutureOrPresent LocalDate followUpDate
) {}
