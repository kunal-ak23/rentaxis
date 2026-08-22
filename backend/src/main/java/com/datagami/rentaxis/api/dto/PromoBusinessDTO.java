package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.PromoCategory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PromoBusinessDTO(
        UUID id,
        String nameEn,
        String nameAr,
        String logoUrl,
        PromoCategory category,
        String phoneE164,
        String whatsappE164,
        List<String> allowedDomains,
        boolean active,
        long adCount,
        Instant createdAt,
        Instant updatedAt) {
}
