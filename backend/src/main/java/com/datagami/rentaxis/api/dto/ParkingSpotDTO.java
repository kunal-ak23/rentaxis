package com.datagami.rentaxis.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ParkingSpotDTO(
        UUID id,
        UUID propertyId,
        String spotNumber,
        String level,
        boolean covered,
        boolean active,
        List<UUID> buildingIds,
        boolean held,
        long pendingCount,
        Instant createdAt,
        Instant updatedAt) {
}
