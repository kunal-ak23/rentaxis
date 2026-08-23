package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.PromoCtaType;

import java.time.Instant;
import java.util.UUID;

/**
 * What the mobile carousel renders. Deliberately omits priority, placement and
 * targeting — the feed must not leak how the rotation is configured.
 *
 * <p>Both language variants are sent; the client resolves them, so changing the
 * app's language re-renders without a refetch. {@code ctaPhone} is populated
 * only for CALL and WHATSAPP ads, and {@code ctaUrl} only for WEBSITE ads that
 * already passed the allowlist check on write.
 */
public record PromoAdCardDTO(
        UUID id,
        PromoBusinessRefDTO business,
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
        String ctaPhone,
        String couponCode,
        String couponTermsEn,
        String couponTermsAr,
        Instant endsAt) {
}
