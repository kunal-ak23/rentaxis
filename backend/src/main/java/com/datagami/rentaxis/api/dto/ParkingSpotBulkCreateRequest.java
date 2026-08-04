package com.datagami.rentaxis.api.dto;

import java.util.List;
import java.util.UUID;

/** Creates one spot per entry in spotNumbers, all sharing level/covered/buildingIds. */
public record ParkingSpotBulkCreateRequest(
        UUID propertyId,
        List<String> spotNumbers,
        String level,
        Boolean covered,
        List<UUID> buildingIds) {
}
