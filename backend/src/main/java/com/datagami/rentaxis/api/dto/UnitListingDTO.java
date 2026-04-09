package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.Furnishing;
import com.datagami.rentaxis.domain.entity.enums.ListingAmenity;
import com.datagami.rentaxis.domain.entity.enums.ListingStatus;
import com.datagami.rentaxis.domain.entity.enums.ViewType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record UnitListingDTO(
        UUID id,
        UUID unitId,
        ListingStatus status,
        String titleEn,
        String titleAr,
        String descriptionEn,
        String descriptionAr,
        Integer bedrooms,
        Integer bathrooms,
        BigDecimal sizeSqft,
        Integer floor,
        Integer parkingSpaces,
        Furnishing furnishing,
        ViewType viewType,
        BigDecimal annualRent,
        BigDecimal securityDeposit,
        Integer minLeaseMonths,
        Integer chequesAccepted,
        Boolean dewaIncluded,
        Boolean chillerIncluded,
        BigDecimal utilitiesEstimate,
        LocalDate availableFrom,
        String tenantSlug,
        String slug,
        String seoTitle,
        String seoDescription,
        String seoKeywords,
        String ogImageUrl,
        BigDecimal lat,
        BigDecimal lng,
        LocalDateTime publishedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<AmenityEntry> amenities,
        List<UnitListingMediaDTO> media
) {
    public record AmenityEntry(ListingAmenity amenity, String customLabel) {
    }
}
