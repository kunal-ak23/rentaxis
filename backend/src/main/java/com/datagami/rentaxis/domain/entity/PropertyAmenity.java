package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "property_amenities")
@Getter
@Setter
public class PropertyAmenity extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "property_id", nullable = false)
    private UUID propertyId;

    @Column(name = "name_en", nullable = false, length = 160)
    private String nameEn;

    @Column(name = "name_ar", length = 160)
    private String nameAr;

    @Column(columnDefinition = "text")
    private String description;

    /** Newline-delimited HTTPS image URLs, exposed as a gallery to renters. */
    @Column(name = "photo_urls", columnDefinition = "text")
    private String photoUrls;

    @Column(nullable = false)
    private boolean bookable = true;

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

    /** F14-50: FREE, PER_BOOKING or PER_HOUR (parking: FREE or PER_BOOKING). */
    @Column(name = "fee_type", nullable = false, length = 12)
    private String feeType = "FREE";

    @Column(name = "fee_amount", nullable = false, precision = 14, scale = 2)
    private java.math.BigDecimal feeAmount = java.math.BigDecimal.ZERO;
}
