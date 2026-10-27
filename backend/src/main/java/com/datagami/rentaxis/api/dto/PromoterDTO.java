package com.datagami.rentaxis.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * One promoter of the organisation and what an admin granted it
 * ({@code GET /api/v1/promotions/promoters}). {@code status} is the login's
 * {@code UserStatus}: ACTIVE or INACTIVE.
 */
public record PromoterDTO(
        UUID userId,
        String name,
        String email,
        String phoneNumber,
        String status,
        boolean canCreateBusinesses,
        List<UUID> businessIds,
        List<UUID> propertyIds) {
}
