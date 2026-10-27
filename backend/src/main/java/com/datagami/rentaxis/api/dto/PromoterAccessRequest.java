package com.datagami.rentaxis.api.dto;

import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * {@code PUT /api/v1/promotions/promoters/{userId}}: replaces a promoter's access in
 * full. Null lists = none; {@code canCreateBusinesses} null = false.
 */
public record PromoterAccessRequest(
        Boolean canCreateBusinesses,
        @Size(max = 500) List<UUID> businessIds,
        @Size(max = 500) List<UUID> propertyIds) {
}
