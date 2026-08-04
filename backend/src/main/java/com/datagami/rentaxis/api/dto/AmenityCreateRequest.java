package com.datagami.rentaxis.api.dto;

import java.util.List;
import java.util.UUID;

/** bookable null = true; buildingIds null/empty = visible to all towers. */
public record AmenityCreateRequest(
        UUID propertyId,
        String nameEn,
        String nameAr,
        String description,
        Boolean bookable,
        List<UUID> buildingIds) {
}
