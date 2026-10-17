package com.datagami.rentaxis.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * covered null = true; buildingIds null/empty = visible to all towers;
 * attachedUnitId (changeset 175) null = a bookable bay, otherwise the unit it is
 * allocated to from the start.
 */
public record ParkingSpotCreateRequest(
        UUID propertyId,
        @NotBlank @Size(max = 32) String spotNumber,
        @Size(max = 32) String level,
        Boolean covered,
        List<UUID> buildingIds,
        UUID attachedUnitId) {

    public ParkingSpotCreateRequest(UUID propertyId, String spotNumber, String level, Boolean covered,
                                    List<UUID> buildingIds) {
        this(propertyId, spotNumber, level, covered, buildingIds, null);
    }
}
