package com.datagami.rentaxis.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** covered null = true; buildingIds null/empty = visible to all towers. */
public record ParkingSpotCreateRequest(
        UUID propertyId,
        @NotBlank @Size(max = 32) String spotNumber,
        @Size(max = 32) String level,
        Boolean covered,
        List<UUID> buildingIds) {
}
