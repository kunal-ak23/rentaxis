package com.datagami.rentaxis.core.service;

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
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the admin listings filters (title search {@code q}, {@code propertyId},
 * {@code status}) against a real Postgres.
 *
 * <p>The controller always accepted {@code q} and {@code propertyId} but silently
 * dropped them, so the dashboard search box triggered a reload that returned the
 * unfiltered list. The filtering now lives in a JPA Specification; since the
 * propertyId predicate is a subquery on Unit and the title match relies on SQL
 * {@code LOWER}/{@code LIKE} semantics, this is verified against the database
 * rather than mocks. Tenant scoping is asserted throughout — the filters must
 * never widen the query beyond the caller's tenant.
 */
@SpringBootTest
class UnitListingAdminFilterIT extends AbstractPostgresIT {


    @Autowired UnitListingService service;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired UnitListingRepository listingRepo;

    private UUID newTenant(String name) {
        LandlordOrg org = new LandlordOrg();
        org.setName(name + "-" + UUID.randomUUID());
        return orgRepo.save(org).getId();
    }

    private Property newProperty(UUID tenantId, String nameEn) {
        Property property = new Property();
        property.setNameEn(nameEn + " " + UUID.randomUUID().toString().substring(0, 8));
        property.setEmirate(Emirate.DUBAI);
        property.setTenantId(tenantId);
        return propertyRepo.save(property);
    }

    private UnitListing newListing(UUID tenantId, Property property, String slug, String titleEn) {
        Unit unit = new Unit();
        unit.setProperty(property);
        unit.setUnitNumber("U-" + slug);
        unit.setTenantId(tenantId);
        unit = unitRepo.save(unit);

        UnitListing l = new UnitListing();
        l.setTenantId(tenantId);
        l.setUnitId(unit.getId());
        l.setStatus(ListingStatus.DRAFT);
        l.setSlug(slug);
        l.setTitleEn(titleEn);
        return listingRepo.save(l);
    }

    @Test
    void list_filtersByTitleQuery_propertyId_andStatus_staysTenantScoped() {
        UUID tenantA = newTenant("Filter-A");
        UUID tenantB = newTenant("Filter-B");
        Property marina = newProperty(tenantA, "Marina Heights");
        Property downtown = newProperty(tenantA, "Downtown Lofts");
        Property foreign = newProperty(tenantB, "Marina Other");

        newListing(tenantA, marina, "marina-1", "Marina View 1BR");
        newListing(tenantA, marina, "marina-2", "Cozy Studio");
        newListing(tenantA, downtown, "downtown-1", "Marina Style Loft");
        // Same title in another tenant — must never leak into tenant A's results.
        newListing(tenantB, foreign, "foreign-1", "Marina View 1BR");

        // No filters: everything in the tenant, nothing from other tenants.
        Page<UnitListing> all = service.list(tenantA, null, null, null, PageRequest.of(0, 20));
        assertThat(all.getContent())
                .extracting(UnitListing::getSlug)
                .containsExactlyInAnyOrder("marina-1", "marina-2", "downtown-1");

        // q: case-insensitive title match.
        Page<UnitListing> byQ = service.list(tenantA, null, null, "mArIna", PageRequest.of(0, 20));
        assertThat(byQ.getContent())
                .extracting(UnitListing::getSlug)
                .containsExactlyInAnyOrder("marina-1", "downtown-1");

        // propertyId: only listings whose unit belongs to the property.
        Page<UnitListing> byProperty = service.list(
                tenantA, null, marina.getId(), null, PageRequest.of(0, 20));
        assertThat(byProperty.getContent())
                .extracting(UnitListing::getSlug)
                .containsExactlyInAnyOrder("marina-1", "marina-2");

        // Combined q + propertyId.
        Page<UnitListing> combined = service.list(
                tenantA, null, marina.getId(), "marina", PageRequest.of(0, 20));
        assertThat(combined.getContent())
                .extracting(UnitListing::getSlug)
                .containsExactly("marina-1");

        // status filter still works after the derived-query -> Specification move.
        UnitListing published = listingRepo.findBySlugAndTenantId("marina-2", tenantA).orElseThrow();
        published.setStatus(ListingStatus.PUBLISHED);
        listingRepo.save(published);
        Page<UnitListing> byStatus = service.list(
                tenantA, ListingStatus.PUBLISHED, null, null, PageRequest.of(0, 20));
        assertThat(byStatus.getContent())
                .extracting(UnitListing::getSlug)
                .containsExactly("marina-2");
    }
}
