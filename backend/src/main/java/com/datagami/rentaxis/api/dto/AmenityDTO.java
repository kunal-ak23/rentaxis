package com.datagami.rentaxis.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AmenityDTO(
        UUID id,
        UUID propertyId,
        String nameEn,
        String nameAr,
        String description,
        List<String> photoUrls,
        boolean bookable,
        boolean active,
        List<UUID> buildingIds,
        long pendingCount,
        Instant createdAt,
        Instant updatedAt) {
}
