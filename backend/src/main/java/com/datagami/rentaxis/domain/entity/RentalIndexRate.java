package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.DldPropertyType;
import com.datagami.rentaxis.domain.entity.enums.RentalIndexSource;
import com.datagami.rentaxis.domain.entity.enums.UnitType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One rental index check: DLD's range for a building (or a property without buildings) and one Miftah unit type.
 * Never edited; a wrong row is revoked (spec 2026-11-25 §5). {@code documentPath} is the blob path inside the
 * tenant's container, served only through the authenticated document endpoint.
 */
@Entity
@Table(name = "rental_index_rates")
@Getter
@Setter
public class RentalIndexRate extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "property_id", nullable = false) private UUID propertyId;
    @Column(name = "building_id") private UUID buildingId;
    @Enumerated(EnumType.STRING)
    @Column(name = "dld_property_type", nullable = false, length = 20) private DldPropertyType dldPropertyType;
    @Column(length = 100) private String category;
    @Enumerated(EnumType.STRING)
    @Column(name = "unit_type", nullable = false, length = 20) private UnitType unitType;
    @Column(name = "index_min", nullable = false, precision = 14, scale = 2) private BigDecimal indexMin;
    @Column(name = "index_max", nullable = false, precision = 14, scale = 2) private BigDecimal indexMax;
    @Column(name = "area_name", length = 200) private String areaName;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20) private RentalIndexSource source;
    @Column(name = "source_unit_id") private UUID sourceUnitId;
    @Column(name = "document_path", length = 500) private String documentPath;
    @Column(name = "dld_percent") private Integer dldPercent;
    @Column(name = "dld_issued_at") private LocalDateTime dldIssuedAt;
    @Column(name = "checked_on", nullable = false) private LocalDate checkedOn;
    @Column(name = "expires_on", nullable = false) private LocalDate expiresOn;
    @Column(name = "created_by", nullable = false) private UUID createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "revoked_by") private UUID revokedBy;
    @Column(name = "revoked_at") private Instant revokedAt;
}
