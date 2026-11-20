package com.datagami.rentaxis.api.dto;

import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Patch semantics: null = unchanged; non-null buildingIds replaces the scope set;
 * null incomeAccountId = unchanged, {@code clearIncomeAccount = true} clears it.
 */
public record AmenityUpdateRequest(
        @Size(max = 160) String nameEn,
        @Size(max = 160) String nameAr,
        String description,
        Boolean bookable,
        Boolean active,
        List<UUID> buildingIds,
        /* F14-50 */ String feeType,
        @com.datagami.rentaxis.api.validation.Money java.math.BigDecimal feeAmount,
        UUID incomeAccountId,
        Boolean clearIncomeAccount) {
    public AmenityUpdateRequest(@Size(max = 160) String nameEn,
        @Size(max = 160) String nameAr,
        String description,
        Boolean bookable,
        Boolean active,
        List<UUID> buildingIds,
        String feeType,
        java.math.BigDecimal feeAmount) {
        this(nameEn, nameAr, description, bookable, active, buildingIds, feeType, feeAmount, null, null);
    }

    public AmenityUpdateRequest(@Size(max = 160) String nameEn,
        @Size(max = 160) String nameAr,
        String description,
        Boolean bookable,
        Boolean active,
        List<UUID> buildingIds) {
        this(nameEn, nameAr, description, bookable, active, buildingIds, null, null, null, null);
    }

}
