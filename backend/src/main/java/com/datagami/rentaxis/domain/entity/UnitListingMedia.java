package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.ListingMediaType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "unit_listing_media")
@Getter
@Setter
public class UnitListingMedia {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "listing_id", nullable = false)
    private UUID listingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false, length = 30)
    private ListingMediaType mediaType;

    @Column(nullable = false, length = 1000)
    private String url;

    @Column(length = 500)
    private String caption;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @Column(name = "is_cover")
    private Boolean isCover = false;
}
