package com.datagami.rentaxis.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Renter-facing facility catalogue. Carries pendingCount only — never other
 * applicants' identities.
 */
public record MyFacilitiesDTO(
        List<RenterAmenityDTO> amenities,
        List<RenterParkingSpotDTO> parkingSpots) {

    public record RenterAmenityDTO(
            UUID id,
            UUID propertyId,
            String propertyName,
            String nameEn,
            String nameAr,
            String description,
            List<String> photoUrls,
            boolean bookable,
            long pendingCount,
        /* F14-50 */ String feeType,
        java.math.BigDecimal feeAmount) {
    public RenterAmenityDTO(UUID id,
            UUID propertyId,
            String propertyName,
            String nameEn,
            String nameAr,
            String description,
            List<String> photoUrls,
            boolean bookable,
            long pendingCount) {
        this(id, propertyId, propertyName, nameEn, nameAr, description, photoUrls, bookable, pendingCount, "FREE", java.math.BigDecimal.ZERO);
    }

    }

    public record RenterParkingSpotDTO(
            UUID id,
            UUID propertyId,
            String propertyName,
            String spotNumber,
            String level,
            List<String> photoUrls,
            boolean covered,
            boolean held,
            long pendingCount,
        /* F14-50 */ String feeType,
        java.math.BigDecimal feeAmount) {
    public RenterParkingSpotDTO(UUID id,
            UUID propertyId,
            String propertyName,
            String spotNumber,
            String level,
            List<String> photoUrls,
            boolean covered,
            boolean held,
            long pendingCount) {
        this(id, propertyId, propertyName, spotNumber, level, photoUrls, covered, held, pendingCount, "FREE", java.math.BigDecimal.ZERO);
    }

    }
}
