package com.datagami.rentaxis.api.dto;

import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Patch semantics: null = unchanged; non-null buildingIds replaces the scope set.
 *
 * <p>Attached parking (changeset 175): {@code setAttachment = true} also sets the bay's
 * unit to {@code attachedUnitId} (null = detach) in the same transaction as the rest
 * of the edit, so a refused part leaves the bay exactly as it was. Absent or false
 * leaves the attachment alone.</p>
 */
public record ParkingSpotUpdateRequest(
        @Size(max = 32) String spotNumber,
        @Size(max = 32) String level,
        Boolean covered,
        Boolean active,
        List<UUID> buildingIds,
        /* F14-50 */ String feeType,
        @com.datagami.rentaxis.api.validation.Money java.math.BigDecimal feeAmount,
        Boolean setAttachment,
        UUID attachedUnitId) {
    public ParkingSpotUpdateRequest(@Size(max = 32) String spotNumber,
        @Size(max = 32) String level,
        Boolean covered,
        Boolean active,
        List<UUID> buildingIds,
        String feeType,
        java.math.BigDecimal feeAmount) {
        this(spotNumber, level, covered, active, buildingIds, feeType, feeAmount, null, null);
    }
    public ParkingSpotUpdateRequest(@Size(max = 32) String spotNumber,
        @Size(max = 32) String level,
        Boolean covered,
        Boolean active,
        List<UUID> buildingIds) {
        this(spotNumber, level, covered, active, buildingIds, null, null, null, null);
    }

}
