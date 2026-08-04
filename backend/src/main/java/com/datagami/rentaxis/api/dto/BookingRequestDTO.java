package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus;
import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record BookingRequestDTO(
        UUID id,
        BookingResourceType resourceType,
        UUID amenityId,
        UUID parkingSpotId,
        String resourceName,
        UUID propertyId,
        UUID unitId,
        String unitNumber,
        UUID renterUserId,
        String renterName,
        String renterEmail,
        String renterPhone,
        String note,
        LocalDate preferredDate,
        BookingRequestStatus status,
        String adminNote,
        UUID decidedByUserId,
        Instant decidedAt,
        Instant createdAt) {
}
