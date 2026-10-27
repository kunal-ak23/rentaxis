package com.datagami.rentaxis.api.dto;

import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/v1/promotions/promoters}: a new PROMOTER login in the caller's
 * organisation (set-password invite email) and its access. Null lists = none;
 * {@code canCreateBusinesses} null = false.
 */
public record PromoterInviteRequest(
        @Size(max = 255) String name,
        @Size(max = 255) String email,
        @Size(max = 32) String phoneNumber,
        Boolean canCreateBusinesses,
        @Size(max = 500) List<UUID> businessIds,
        @Size(max = 500) List<UUID> propertyIds) {
}
