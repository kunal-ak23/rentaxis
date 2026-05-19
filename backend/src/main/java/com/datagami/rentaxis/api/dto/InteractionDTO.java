package com.datagami.rentaxis.api.dto;
import com.datagami.rentaxis.domain.entity.enums.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
public record InteractionDTO(
        UUID id, UUID leaseId, UUID opportunityId,
        InteractionType type, InteractionDirection direction,
        Instant occurredAt, String summary,
        InteractionOutcome outcome, LocalDate followUpDate,
        UUID createdBy, String createdByName, Instant createdAt
) {}
