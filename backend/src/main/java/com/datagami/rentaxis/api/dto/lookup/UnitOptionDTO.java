package com.datagami.rentaxis.api.dto.lookup;

import java.util.UUID;

/** One unit in a picker or a names lookup (scale P1-6). */
public record UnitOptionDTO(UUID id, String unitNumber, UUID propertyId, String propertyName,
                            UUID buildingId, String buildingName, String status) {
}
