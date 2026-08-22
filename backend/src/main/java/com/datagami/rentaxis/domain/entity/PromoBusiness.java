package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.PromoCategory;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A business the client owns and cross-promotes in the renter app.
 * {@code allowedDomains} is the click-through allowlist — a lowercase,
 * comma-separated hostname list checked by
 * {@link com.datagami.rentaxis.core.service.PromotionUrlValidator} before any
 * ad URL is accepted.
 */
@Entity
@Table(name = "promo_businesses")
@Getter
@Setter
public class PromoBusiness extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name_en", nullable = false, length = 160)
    private String nameEn;

    @Column(name = "name_ar", length = 160)
    private String nameAr;

    @Column(name = "logo_url", length = 512)
    private String logoUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PromoCategory category = PromoCategory.OTHER;

    @Column(name = "phone_e164", length = 20)
    private String phoneE164;

    @Column(name = "whatsapp_e164", length = 20)
    private String whatsappE164;

    @Column(name = "allowed_domains", columnDefinition = "text")
    private String allowedDomains;

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
