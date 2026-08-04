package com.datagami.rentaxis.api.dto;

import java.util.List;
import java.util.UUID;

/** Patch semantics: null = unchanged; non-null buildingIds replaces the scope set. */
public record ParkingSpotUpdateRequest(
        String spotNumber,
        String level,
        Boolean covered,
        Boolean active,
        List<UUID> buildingIds) {
}
