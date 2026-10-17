package com.datagami.rentaxis.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ParkingSpotDTO(
        UUID id,
        UUID propertyId,
        String spotNumber,
        String level,
        List<String> photoUrls,
        boolean covered,
        boolean active,
        List<UUID> buildingIds,
        boolean held,
        long pendingCount,
        Instant createdAt,
        Instant updatedAt,
        /* F14-50 */ String feeType,
        java.math.BigDecimal feeAmount,
        /* Attached parking (changeset 175): the unit this bay is allocated to; null = bookable. */
        UUID attachedUnitId,
        String attachedUnitNumber,
        String attachedBuildingName) {
    public ParkingSpotDTO(UUID id,
        UUID propertyId,
        String spotNumber,
        String level,
        List<String> photoUrls,
        boolean covered,
        boolean active,
        List<UUID> buildingIds,
        boolean held,
        long pendingCount,
        Instant createdAt,
        Instant updatedAt,
        String feeType,
        java.math.BigDecimal feeAmount) {
        this(id, propertyId, spotNumber, level, photoUrls, covered, active, buildingIds, held, pendingCount,
                createdAt, updatedAt, feeType, feeAmount, null, null, null);
    }

    public ParkingSpotDTO(UUID id,
        UUID propertyId,
        String spotNumber,
        String level,
        List<String> photoUrls,
        boolean covered,
        boolean active,
        List<UUID> buildingIds,
        boolean held,
        long pendingCount,
        Instant createdAt,
        Instant updatedAt) {
        this(id, propertyId, spotNumber, level, photoUrls, covered, active, buildingIds, held, pendingCount, createdAt, updatedAt, "FREE", java.math.BigDecimal.ZERO);
    }

}
