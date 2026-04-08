package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.Furnishing;
import com.datagami.rentaxis.domain.entity.enums.ListingStatus;
import com.datagami.rentaxis.domain.entity.enums.ViewType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "unit_listings")
@Getter
@Setter
public class UnitListing extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "unit_id", nullable = false)
    private UUID unitId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ListingStatus status = ListingStatus.DRAFT;

    @Column(name = "title_en")
    private String titleEn;

    @Column(name = "title_ar")
    private String titleAr;

    @Column(name = "description_en", columnDefinition = "text")
    private String descriptionEn;

    @Column(name = "description_ar", columnDefinition = "text")
    private String descriptionAr;

    private Integer bedrooms;

    private Integer bathrooms;

    @Column(name = "size_sqft")
    private BigDecimal sizeSqft;

    private Integer floor;

    @Column(name = "parking_spaces")
    private Integer parkingSpaces;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private Furnishing furnishing;

    @Enumerated(EnumType.STRING)
    @Column(name = "view_type", length = 30)
    private ViewType viewType;

    @Column(name = "annual_rent")
    private BigDecimal annualRent;

    @Column(name = "security_deposit")
    private BigDecimal securityDeposit;

    @Column(name = "min_lease_months")
    private Integer minLeaseMonths;

    @Column(name = "cheques_accepted")
    private Integer chequesAccepted;

    @Column(name = "dewa_included")
    private Boolean dewaIncluded = false;

    @Column(name = "chiller_included")
    private Boolean chillerIncluded = false;

    @Column(name = "utilities_estimate")
    private BigDecimal utilitiesEstimate;

    @Column(name = "available_from")
    private LocalDate availableFrom;

    @Column(nullable = false, length = 200)
    private String slug;

    @Column(name = "seo_title")
    private String seoTitle;

    @Column(name = "seo_description", length = 500)
    private String seoDescription;

    @Column(name = "seo_keywords", length = 500)
    private String seoKeywords;

    @Column(name = "og_image_url", length = 1000)
    private String ogImageUrl;

    private BigDecimal lat;

    private BigDecimal lng;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @Override
    public void onPrePersist() {
        super.onPrePersist();
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void onPreUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
