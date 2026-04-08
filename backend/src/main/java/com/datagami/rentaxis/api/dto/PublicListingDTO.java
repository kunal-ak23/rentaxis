package com.datagami.rentaxis.api.dto;

import java.math.BigDecimal;

public record PublicListingDTO(
        String slug,
        String tenantSlug,
        String buildingName,
        String area,
        String emirate,
        Integer bedrooms,
        Integer bathrooms,
        String furnishing,
        String rentRangeLabel,
        String coverPhotoUrl,
        BigDecimal approxLat,
        BigDecimal approxLng,
        String seoTitle,
        String seoDescription,
        String seoKeywords,
        String ogImageUrl,
        String availableLabel,
        boolean loginRequired
) {
}
