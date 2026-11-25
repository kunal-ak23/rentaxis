package com.datagami.rentaxis.api.dto.rentalindex;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A rental index range as staff see it. Revoked and expired rows are listed with their flags. The stored PDF is
 * reachable only through {@code GET /api/v1/rental-index/rates/{id}/document}; {@code hasDocument} says whether
 * there is one.
 */
public record RentalIndexRateDTO(UUID id, UUID propertyId, UUID buildingId, String buildingName,
                                 String dldPropertyType, String category, String unitType,
                                 BigDecimal indexMin, BigDecimal indexMax, BigDecimal average, String areaName,
                                 String source, UUID sourceUnitId, boolean hasDocument, Integer dldPercent,
                                 LocalDateTime dldIssuedAt, LocalDate checkedOn, LocalDate expiresOn,
                                 boolean expired, boolean revoked, UUID createdBy, String createdByName,
                                 Instant createdAt, UUID revokedBy, Instant revokedAt) {
}
