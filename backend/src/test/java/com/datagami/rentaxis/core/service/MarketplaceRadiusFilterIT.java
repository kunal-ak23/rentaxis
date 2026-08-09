package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.MarketplaceSearchRequest;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.UnitListing;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.ListingStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.UnitListingRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the near-me radius filter does not silently hide listings that have no
 * coordinates.
 *
 * <p>Listing lat/lng are optional (landlords can skip the map pin), and the renter
 * app auto-seeds a 10 km near-me filter whenever the device grants location. The
 * original spec used a plain {@code BETWEEN} on lat/lng, which is never true for
 * NULL — so every coordinate-less listing vanished from browse for location-enabled
 * renters, with nothing telling the landlord why. The spec now keeps NULL-coordinate
 * listings and only excludes listings whose coordinates fall outside the box. That
 * is SQL NULL semantics, not Java logic, so this is exercised against a real
 * Postgres rather than mocks.
 */
@SpringBootTest
@Testcontainers
class MarketplaceRadiusFilterIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired MarketplaceService service;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired UnitListingRepository listingRepo;

    private UnitListing publishListing(UUID tenantId, Property property, String slug,
                                       BigDecimal lat, BigDecimal lng) {
        Unit unit = new Unit();
        unit.setProperty(property);
        unit.setUnitNumber("U-" + slug);
        unit.setTenantId(tenantId);
        unit = unitRepo.save(unit);

        UnitListing l = new UnitListing();
        l.setTenantId(tenantId);
        l.setUnitId(unit.getId());
        l.setStatus(ListingStatus.PUBLISHED);
        l.setSlug(slug);
        l.setTitleEn(slug);
        l.setLat(lat);
        l.setLng(lng);
        return listingRepo.save(l);
    }

    @Test
    void radiusFilter_keepsCoordinateLessListings_dropsOutOfRangeOnes() {
        LandlordOrg org = new LandlordOrg();
        org.setName("Radius-" + UUID.randomUUID());
        UUID tenantId = orgRepo.save(org).getId();

        Property property = new Property();
        property.setNameEn("Radius Property " + UUID.randomUUID().toString().substring(0, 8));
        property.setEmirate(Emirate.DUBAI);
        property.setTenantId(tenantId);
        property = propertyRepo.save(property);

        publishListing(tenantId, property, "near-flat",
                new BigDecimal("25.2000"), new BigDecimal("55.2700"));
        publishListing(tenantId, property, "far-flat",
                new BigDecimal("24.4539"), new BigDecimal("54.3773")); // Abu Dhabi, ~130 km away
        publishListing(tenantId, property, "no-coords-flat", null, null);

        MarketplaceSearchRequest req = new MarketplaceSearchRequest(
                null, null, null, null, null, null,
                25.2048, 55.2708, 10.0);
        Page<UnitListing> page = service.search(tenantId, req, PageRequest.of(0, 20));

        assertThat(page.getContent())
                .extracting(UnitListing::getSlug)
                .containsExactlyInAnyOrder("near-flat", "no-coords-flat");
    }
}
