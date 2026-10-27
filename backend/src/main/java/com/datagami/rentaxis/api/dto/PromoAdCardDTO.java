package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.PromoCtaType;

import java.time.Instant;
import java.util.List;
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
        Instant endsAt,
        /**
         * COUPON only (addendum A): the link to open after "Copy code", already
         * resolved for the calling renter; null when the ad has none.
         */
        String redirectUrl,
        /**
         * COUPON only (addendum C): the link to open on Android instead of
         * {@code redirectUrl}, resolved for the calling renter like it; null when the
         * ad has none (the app then falls back to {@code redirectUrl}).
         */
        String redirectUrlAndroid,
        /** COUPON only (addendum C): the iPhone link, as {@code redirectUrlAndroid} is for Android. */
        String redirectUrlIos,
        /**
         * The renter-data sources present in any of the resolved links (RENTER_NAME,
         * RENTER_PHONE, RENTER_EMAIL, PROPERTY_NAME, UNIT_NUMBER), for the consent
         * sheet; empty when none. The links share their parameters.
         */
        List<String> sharedFields) {
}
