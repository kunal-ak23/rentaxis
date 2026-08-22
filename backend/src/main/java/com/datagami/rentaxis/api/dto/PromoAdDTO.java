package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.PromoCtaType;
import com.datagami.rentaxis.domain.entity.enums.PromoPlacement;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PromoAdDTO(
        UUID id,
        UUID businessId,
        String businessNameEn,
        String titleEn,
        String titleAr,
        String subtitleEn,
        String subtitleAr,
        String backgroundImageUrl,
        String accentColor,
        PromoCtaType ctaType,
        String ctaLabelEn,
        String ctaLabelAr,
        String ctaUrl,
        String couponCode,
        String couponTermsEn,
        String couponTermsAr,
        Instant startsAt,
        Instant endsAt,
        int priority,
        PromoPlacement placement,
        boolean active,
        List<UUID> propertyIds,
        long impressions,
        long clicks,
        Instant createdAt,
        Instant updatedAt) {
}
