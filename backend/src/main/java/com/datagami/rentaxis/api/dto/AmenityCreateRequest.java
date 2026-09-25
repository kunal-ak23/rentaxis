package com.datagami.rentaxis.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** bookable null = true; buildingIds null/empty = visible to all towers. */
public record AmenityCreateRequest(
        UUID propertyId,
        @NotBlank @Size(max = 160) String nameEn,
        @Size(max = 160) String nameAr,
        String description,
        Boolean bookable,
        List<UUID> buildingIds,
        /* F14-50 */ String feeType,
        java.math.BigDecimal feeAmount) {
    public AmenityCreateRequest(UUID propertyId,
        @NotBlank @Size(max = 160) String nameEn,
        @Size(max = 160) String nameAr,
        String description,
        Boolean bookable,
        List<UUID> buildingIds) {
        this(propertyId, nameEn, nameAr, description, bookable, buildingIds, null, null);
    }

}
