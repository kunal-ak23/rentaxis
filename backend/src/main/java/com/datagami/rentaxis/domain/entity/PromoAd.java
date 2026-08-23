package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.PromoCtaType;
import com.datagami.rentaxis.domain.entity.enums.PromoPlacement;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One offer. A business may run several. At least one of {@code titleEn} /
 * {@code titleAr} is non-null (DB CHECK enforces it). A null window bound
 * means unbounded on that side.
 */
@Entity
@Table(name = "promo_ads")
@Getter
@Setter
public class PromoAd extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "title_en", length = 120)
    private String titleEn;

    @Column(name = "title_ar", length = 120)
    private String titleAr;

    @Column(name = "subtitle_en", length = 160)
    private String subtitleEn;

    @Column(name = "subtitle_ar", length = 160)
    private String subtitleAr;

    @Column(name = "background_image_url", length = 512)
    private String backgroundImageUrl;

    @Column(name = "accent_color", length = 9)
    private String accentColor;

    @Enumerated(EnumType.STRING)
    @Column(name = "cta_type", nullable = false, length = 16)
    private PromoCtaType ctaType = PromoCtaType.NONE;

    @Column(name = "cta_label_en", length = 40)
    private String ctaLabelEn;

    @Column(name = "cta_label_ar", length = 40)
    private String ctaLabelAr;

    @Column(name = "cta_url", length = 1024)
    private String ctaUrl;

    @Column(name = "coupon_code", length = 64)
    private String couponCode;

    @Column(name = "coupon_terms_en", columnDefinition = "text")
    private String couponTermsEn;

    @Column(name = "coupon_terms_ar", columnDefinition = "text")
    private String couponTermsAr;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(nullable = false)
    private int priority = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PromoPlacement placement = PromoPlacement.HOME_AND_OFFERS;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void onPreUpdate() {
        this.updatedAt = Instant.now();
    }
}
