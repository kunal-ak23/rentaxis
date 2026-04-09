package com.datagami.rentaxis.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record PublicListingDTO(
        String slug,
        String tenantSlug,
        String title,
        String description,
        String buildingName,
        String area,
        String emirate,
        Integer bedrooms,
        Integer bathrooms,
        String furnishing,
        String rentRangeLabel,
        String coverPhotoUrl,
        List<MediaItem> media,
        List<String> amenities,
        BigDecimal approxLat,
        BigDecimal approxLng,
        String seoTitle,
        String seoDescription,
        String seoKeywords,
        String ogImageUrl,
        String availableLabel,
        boolean loginRequired
) {
    public record MediaItem(String url, String mediaType, String caption, boolean isCover) {}
}
