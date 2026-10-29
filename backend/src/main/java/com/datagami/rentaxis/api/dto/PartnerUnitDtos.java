package com.datagami.rentaxis.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Wire shapes of the promoter developer API units feed. Nothing tenant- or lease-related. */
public final class PartnerUnitDtos {

    private PartnerUnitDtos() {
    }

    public record UnitCount(int properties, long units, String fingerprint, Instant generatedAt) {
    }

    public record PartnerUnit(UUID unitId, String unitNumber, UUID propertyId, String propertyName) {
    }

    public record UnitPage(List<PartnerUnit> content, int page, int size, long totalElements, int totalPages,
                           String fingerprint) {
    }
}
