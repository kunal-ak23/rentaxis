package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus;
import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record BookingRequestDTO(
        UUID id,
        BookingResourceType resourceType,
        UUID amenityId,
        UUID parkingSpotId,
        String resourceName,
        UUID propertyId,
        String propertyNameEn,
        String propertyNameAr,
        UUID unitId,
        String unitNumber,
        UUID renterUserId,
        String renterName,
        String renterEmail,
        String renterPhone,
        String note,
        LocalDate preferredDate,
        LocalDate preferredEndDate,
        LocalTime preferredStartTime,
        LocalTime preferredEndTime,
        BookingRequestStatus status,
        String adminNote,
        UUID decidedByUserId,
        Instant decidedAt,
        Instant createdAt) {
}
