package com.datagami.rentaxis.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Renter-facing facility catalogue. Carries pendingCount only — never other
 * applicants' identities. {@code parkingSpots} are the bookable bays;
 * {@code allocatedParking} (changeset 175) the bays attached to the renter's own
 * contracted units — no fee, no booking.
 */
public record MyFacilitiesDTO(
        List<RenterAmenityDTO> amenities,
        List<RenterParkingSpotDTO> parkingSpots,
        List<AllocatedParkingDTO> allocatedParking) {

    public MyFacilitiesDTO(List<RenterAmenityDTO> amenities, List<RenterParkingSpotDTO> parkingSpots) {
        this(amenities, parkingSpots, List.of());
    }

    /** A bay attached to one of the renter's contracted units. */
    public record AllocatedParkingDTO(
            UUID id,
            UUID propertyId,
            String propertyName,
            UUID unitId,
            String unitNumber,
            String spotNumber,
            String level,
            boolean covered,
            List<String> photoUrls) {
    }

    /** An approved booking's dates (no identities); a null bound is open. */
    public record BookedRangeDTO(java.time.LocalDate from, java.time.LocalDate to) {
    }

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
        java.math.BigDecimal feeAmount,
        /* Changeset 175: approved bookings not yet ended — the dates the bay is taken. */
        List<BookedRangeDTO> bookedRanges) {
    public RenterParkingSpotDTO(UUID id,
            UUID propertyId,
            String propertyName,
            String spotNumber,
            String level,
            List<String> photoUrls,
            boolean covered,
            boolean held,
            long pendingCount,
            String feeType,
            java.math.BigDecimal feeAmount) {
        this(id, propertyId, propertyName, spotNumber, level, photoUrls, covered, held, pendingCount,
                feeType, feeAmount, List.of());
    }

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
