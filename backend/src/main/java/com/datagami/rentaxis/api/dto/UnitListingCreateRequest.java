package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.Furnishing;
import com.datagami.rentaxis.domain.entity.enums.ListingAmenity;
import com.datagami.rentaxis.domain.entity.enums.ViewType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record UnitListingCreateRequest(
        UUID unitId,
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
        String seoTitle,
        String seoDescription,
        String seoKeywords,
        String ogImageUrl,
        BigDecimal lat,
        BigDecimal lng,
        List<AmenityRequest> amenities
) {
    public record AmenityRequest(ListingAmenity amenity, String customLabel) {
    }
}
