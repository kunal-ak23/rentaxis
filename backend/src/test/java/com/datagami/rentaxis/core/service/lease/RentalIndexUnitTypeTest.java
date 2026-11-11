package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.domain.entity.enums.UnitType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** The unit-type spellings a sheet copied off the DLD page may carry. */
class RentalIndexUnitTypeTest {

    @ParameterizedTest
    @CsvSource({
            "BHK1, BHK1", "bhk2, BHK2", "Studio, STUDIO", "1 BR, BHK1", "1BHK, BHK1", "2 bed, BHK2",
            "3 Bedrooms, BHK3", "2-BR, BHK2", "Penthouse, PENTHOUSE", "Retail, RETAIL", "Shop, RETAIL",
            "Office, OFFICE", "1, BHK1", "3B, BHK3",
    })
    void knownSpellings(String raw, UnitType expected) {
        assertThat(RentalIndexService.parseUnitType(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"4 BR", "Villa", "", "BHK", "two bed"})
    void unknownSpellings(String raw) {
        assertThat(RentalIndexService.parseUnitType(raw)).isNull();
    }
}
