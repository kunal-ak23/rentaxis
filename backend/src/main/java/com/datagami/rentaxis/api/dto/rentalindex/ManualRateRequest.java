package com.datagami.rentaxis.api.dto.rentalindex;

import com.datagami.rentaxis.domain.entity.enums.DldPropertyType;
import com.datagami.rentaxis.domain.entity.enums.UnitType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A range entered by hand (no DLD PDF); {@code buildingId} and {@code category} are optional. */
public record ManualRateRequest(UUID propertyId, UUID buildingId, UnitType unitType, DldPropertyType dldPropertyType,
                                String category, BigDecimal indexMin, BigDecimal indexMax, LocalDate checkedOn) {
}
