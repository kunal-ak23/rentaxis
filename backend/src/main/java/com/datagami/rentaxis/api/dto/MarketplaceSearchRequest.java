package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.Furnishing;

import java.math.BigDecimal;
import java.time.LocalDate;

public record MarketplaceSearchRequest(
        Integer minBedrooms,
        BigDecimal minRent,
        BigDecimal maxRent,
        Furnishing furnishing,
        Boolean availableNow,
        LocalDate availableByDate,
        Double nearLat,
        Double nearLng,
        Double radiusKm
) {
}
