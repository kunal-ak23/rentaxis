package com.datagami.rentaxis.api.dto.rentalindex;

import com.datagami.rentaxis.domain.entity.RentalIndexRange;
import com.datagami.rentaxis.domain.entity.enums.UnitType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One DLD index range on a property, as the property page lists it. */
public record RentalIndexRangeDTO(UUID id, UUID propertyId, UnitType unitType, int indexYear,
                                  BigDecimal minValue, BigDecimal maxValue, String sourceNote,
                                  UUID enteredBy, Instant enteredAt) {

    public static RentalIndexRangeDTO of(RentalIndexRange r) {
        return new RentalIndexRangeDTO(r.getId(), r.getProperty().getId(), r.getUnitType(), r.getIndexYear(),
                r.getMinValue(), r.getMaxValue(), r.getSourceNote(), r.getEnteredBy(), r.getEnteredAt());
    }
}
