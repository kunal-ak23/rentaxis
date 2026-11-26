package com.datagami.rentaxis.api.dto.rentalindex;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What a DLD result PDF says, plus Miftah's cross-checks: each check is {@code true}/{@code false}, or null when
 * there was nothing to compare (no unit or lease given, or the unit has no DEWA premise number). Nothing is stored.
 *
 * @param checkedOn        the date a range from this PDF would be checked on (issue date when read, else today)
 * @param proposedUnitType the Miftah unit type DLD's category maps to (spec §14.3), or null
 */
public record ParsedResultDTO(int dldPercent, BigDecimal increase, BigDecimal rentAfter, String premiseNo,
                              LocalDate contractEnd, BigDecimal currentRent, BigDecimal indexMin, BigDecimal indexMax,
                              BigDecimal average, String category, String building, String area,
                              String dldPropertyType, String issuedAt, LocalDate checkedOn, String proposedUnitType,
                              Boolean premiseMatches, Boolean endMatches, Boolean rentMatches) {
}
