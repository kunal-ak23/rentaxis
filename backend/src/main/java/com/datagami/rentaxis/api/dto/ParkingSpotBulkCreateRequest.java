package com.datagami.rentaxis.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Creates one spot per entry in spotNumbers, all sharing level/covered/buildingIds. */
public record ParkingSpotBulkCreateRequest(
        UUID propertyId,
        List<@NotBlank @Size(max = 32) String> spotNumbers,
        @Size(max = 32) String level,
        Boolean covered,
        List<UUID> buildingIds) {
}
