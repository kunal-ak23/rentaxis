package com.datagami.rentaxis.api.dto;

import java.util.List;
import java.util.UUID;

/** covered null = true; buildingIds null/empty = visible to all towers. */
public record ParkingSpotCreateRequest(
        UUID propertyId,
        String spotNumber,
        String level,
        Boolean covered,
        List<UUID> buildingIds) {
}
