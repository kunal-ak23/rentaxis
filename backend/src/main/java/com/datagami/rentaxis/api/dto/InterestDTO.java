package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.InterestStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record InterestDTO(
        UUID id,
        UUID listingId,
        UUID renterUserId,
        String renterName,
        String renterEmail,
        String renterPhone,
        String note,
        InterestStatus status,
        LocalDateTime createdAt,
        /* F14-51 (PR #361 R1): the draft lease a CONVERTED enquiry became. */
        UUID leaseId
) {
    public InterestDTO(UUID id, UUID listingId, UUID renterUserId, String renterName, String renterEmail,
                       String renterPhone, String note, InterestStatus status, LocalDateTime createdAt) {
        this(id, listingId, renterUserId, renterName, renterEmail, renterPhone, note, status, createdAt, null);
    }
}
