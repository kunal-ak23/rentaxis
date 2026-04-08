package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.ListingAmenity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "unit_listing_amenities")
@Getter
@Setter
public class UnitListingAmenityEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "listing_id", nullable = false)
    private UUID listingId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ListingAmenity amenity;

    @Column(name = "custom_label", length = 100)
    private String customLabel;
}
