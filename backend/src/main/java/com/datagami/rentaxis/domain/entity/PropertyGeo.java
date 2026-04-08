package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 1:1 geo metadata for a Property. nearbyLandmarks is a JSONB blob of
 * [{name, type, distance_m}] — stored as a raw JSON string to keep the
 * mapping simple; parsed into a typed DTO at the service layer.
 */
@Entity
@Table(name = "property_geo")
@Getter
@Setter
public class PropertyGeo {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "property_id", nullable = false, unique = true)
    private UUID propertyId;

    private BigDecimal lat;

    private BigDecimal lng;

    @Column(name = "google_place_id")
    private String googlePlaceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "nearby_landmarks", columnDefinition = "jsonb")
    private String nearbyLandmarks;
}
