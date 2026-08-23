package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.PromoCtaType;
import com.datagami.rentaxis.domain.entity.enums.PromoPlacement;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Null-means-default: ctaType null = NONE; placement null = HOME_AND_OFFERS;
 * priority null = 1; active null = true; propertyIds null/empty = every
 * property.
 *
 * <p>Cross-field rules (at least one title, URL required for WEBSITE, coupon
 * code required for COUPON, contact number required for CALL/WHATSAPP, window
 * order, URL against the business allowlist) are enforced in PromotionService,
 * not by bean validation — they need the owning business row to decide.
 */
public record PromoAdRequest(
        @NotNull UUID businessId,
        @Size(max = 120) String titleEn,
        @Size(max = 120) String titleAr,
        @Size(max = 160) String subtitleEn,
        @Size(max = 160) String subtitleAr,
        @Size(max = 512) String backgroundImageUrl,
        @Pattern(regexp = "^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$",
                message = "accentColor must be #RRGGBB or #AARRGGBB")
        String accentColor,
        PromoCtaType ctaType,
        @Size(max = 40) String ctaLabelEn,
        @Size(max = 40) String ctaLabelAr,
        @Size(max = 1024) String ctaUrl,
        @Size(max = 64) String couponCode,
        String couponTermsEn,
        String couponTermsAr,
        Instant startsAt,
        Instant endsAt,
        @Min(1) @Max(10) Integer priority,
        PromoPlacement placement,
        // Bounded like PromoEventBatchRequest. A tenant has far fewer than 500
        // properties, and an unbounded list would flow straight into bulk
        // inserts on promo_ad_properties with no DTO-level backstop. 500 mirrors
        // FacilityService.MAX_BULK_SPOT_NUMBERS.
        @Size(max = 500) List<UUID> propertyIds,
        Boolean active) {
}
