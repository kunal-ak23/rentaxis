package com.datagami.rentaxis.api.dto;

import java.util.List;
import java.util.UUID;

/** Patch semantics: null = unchanged; non-null buildingIds replaces the scope set. */
public record AmenityUpdateRequest(
        String nameEn,
        String nameAr,
        String description,
        Boolean bookable,
        Boolean active,
        List<UUID> buildingIds) {
}
