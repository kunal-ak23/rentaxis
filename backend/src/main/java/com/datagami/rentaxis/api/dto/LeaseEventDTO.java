package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
public class LeaseEventDTO {
    private UUID id;
    private UUID leaseId;
    private LeaseStatus previousState;
    private LeaseStatus newState;
    private String notes;
    private Instant createdAt;
    private UUID createdBy; // Assume frontend will resolve user name or we can add createdByName
}
