package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Zero rows for an amenity = visible to all towers of its property. */
@Entity
@Table(name = "amenity_building_scopes")
@Getter
@Setter
public class AmenityBuildingScope extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "amenity_id", nullable = false)
    private UUID amenityId;

    @Column(name = "building_id", nullable = false)
    private UUID buildingId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
