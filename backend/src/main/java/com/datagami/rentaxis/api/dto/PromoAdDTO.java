package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.PromoCtaType;
import com.datagami.rentaxis.domain.entity.enums.PromoPlacement;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The admin view of an ad — everything, including the configuration the renter
 * feed withholds. Nullable depending on how the ad is configured: {@code titleAr},
 * both subtitles, {@code backgroundImageUrl}, {@code accentColor}, {@code ctaUrl}
 * (only for WEBSITE), {@code couponCode} and both coupon terms (only for COUPON),
 * both cta labels, and both window bounds ({@code startsAt} null = live now,
 * {@code endsAt} null = never expires).
 */
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
