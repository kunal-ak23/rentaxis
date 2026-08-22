package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Targeting join. An ad with zero rows here targets every property — that is
 * the default and the common case, so no rows are written unless the client
 * narrows an ad.
 *
 * <p>Surrogate id plus a unique constraint on the pair, matching
 * {@link AmenityBuildingScope} and the other join tables in this codebase,
 * rather than a composite key — it keeps the repository a plain
 * {@code JpaRepository<..., UUID>}.
 */
@Entity
@Table(name = "promo_ad_properties")
@Getter
@Setter
public class PromoAdProperty extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ad_id", nullable = false)
    private UUID adId;

    @Column(name = "property_id", nullable = false)
    private UUID propertyId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
