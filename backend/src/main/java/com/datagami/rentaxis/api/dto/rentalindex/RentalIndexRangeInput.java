package com.datagami.rentaxis.api.dto.rentalindex;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * A range to add or replace: the key (unit type, year) and the band. The web's
 * CSV import also sends the sheet's {@code building} column; it must name this
 * property (or be blank), so a sheet pasted onto the wrong building is refused
 * row by row rather than silently filed here.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RentalIndexRangeInput(String building, String unitType, Integer indexYear,
                                    BigDecimal minValue, BigDecimal maxValue, String sourceNote) {
}
