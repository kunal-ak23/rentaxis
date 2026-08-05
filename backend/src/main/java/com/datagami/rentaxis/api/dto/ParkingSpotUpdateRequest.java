package com.datagami.rentaxis.api.dto;

import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Patch semantics: null = unchanged; non-null buildingIds replaces the scope set. */
public record ParkingSpotUpdateRequest(
        @Size(max = 32) String spotNumber,
        @Size(max = 32) String level,
        Boolean covered,
        Boolean active,
        List<UUID> buildingIds) {
}
