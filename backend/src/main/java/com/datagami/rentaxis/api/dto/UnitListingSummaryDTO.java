package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.ListingStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record UnitListingSummaryDTO(
        UUID id,
        String title,
        String propertyName,
        Integer bedrooms,
        Integer bathrooms,
        BigDecimal annualRent,
        ListingStatus status,
        String coverPhotoUrl,
        long interestsCount,
        BigDecimal lat,
        BigDecimal lng,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String slug
) {
}
