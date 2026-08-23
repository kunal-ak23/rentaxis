package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.PromoCategory;

import java.util.UUID;

/** The slice of a business a renter is allowed to see. */
public record PromoBusinessRefDTO(
        UUID id,
        String nameEn,
        String nameAr,
        String logoUrl,
        PromoCategory category) {
}
