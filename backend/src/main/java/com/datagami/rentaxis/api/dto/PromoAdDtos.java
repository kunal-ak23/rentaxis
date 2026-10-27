package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.core.service.PromotionStatsService;
import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.PromoBusiness;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * The one place a {@link PromoAdDTO} and a {@link PromoBusinessDTO} are built, for
 * the admin API and the promoter API alike, so the two can never map a field
 * differently.
 */
public final class PromoAdDtos {

    private PromoAdDtos() {
    }

    /**
     * 34 positional arguments, several adjacent pairs of the same type. Keep them one
     * per line-group and in the record's declared order so a transposition is visible
     * in review — the compiler cannot catch one.
     * {@code listAds_mapsEverySwapProneFieldToTheRightSlot} is the backstop.
     *
     * @param image turns a stored image route into what a DTO carries
     */
    public static PromoAdDTO ad(PromoAd a, String businessName, List<UUID> propertyIds,
                                PromotionStatsService.Totals totals, String submittedByName,
                                UnaryOperator<String> image) {
        return new PromoAdDTO(a.getId(), a.getBusinessId(), businessName,
                a.getTitleEn(), a.getTitleAr(), a.getSubtitleEn(), a.getSubtitleAr(),
                image.apply(a.getBackgroundImageUrl()), a.getAccentColor(),
                a.getCtaType(), a.getCtaLabelEn(), a.getCtaLabelAr(), a.getCtaUrl(),
                a.getCouponCode(), a.getCouponTermsEn(), a.getCouponTermsAr(),
                a.getStartsAt(), a.getEndsAt(), a.getPriority(), a.getPlacement(), a.isActive(),
                propertyIds, totals.impressions(), totals.clicks(),
                a.getCreatedAt(), a.getUpdatedAt(),
                a.getRedirectUrl(),
                a.getRedirectParams() == null ? List.of() : a.getRedirectParams(),
                a.getApprovalStatus(),
                a.getRejectionReason(),
                a.getSubmittedAt(),
                a.getReviewedAt(),
                submittedByName,
                a.isWrittenByPromoter());
    }

    public static PromoBusinessDTO business(PromoBusiness b, long adCount, UnaryOperator<String> image) {
        List<String> domains = b.getAllowedDomains() == null || b.getAllowedDomains().isBlank()
                ? List.of()
                : Arrays.stream(b.getAllowedDomains().split(",")).map(String::trim).toList();
        return new PromoBusinessDTO(b.getId(), b.getNameEn(), b.getNameAr(), image.apply(b.getLogoUrl()),
                b.getCategory(), b.getPhoneE164(), b.getWhatsappE164(), domains,
                b.isActive(), adCount, b.getCreatedAt(), b.getUpdatedAt());
    }
}
