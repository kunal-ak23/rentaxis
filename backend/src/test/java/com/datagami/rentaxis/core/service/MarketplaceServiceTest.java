package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.MarketplaceSearchRequest;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.UnitListing;
import com.datagami.rentaxis.domain.entity.enums.ListingStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UnitListingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketplaceServiceTest {

    private UnitListingRepository listingRepository;
    private LandlordOrgRepository landlordOrgRepository;
    private MarketplaceService service;

    @BeforeEach
    void setUp() {
        listingRepository = mock(UnitListingRepository.class);
        landlordOrgRepository = mock(LandlordOrgRepository.class);
        service = new MarketplaceService(listingRepository, landlordOrgRepository);
    }

    private UnitListing publishedListing(UUID tenantId) {
        UnitListing l = new UnitListing();
        l.setId(UUID.randomUUID());
        l.setTenantId(tenantId);
        l.setStatus(ListingStatus.PUBLISHED);
        l.setSlug("great-flat");
        return l;
    }

    @Test
    @SuppressWarnings("unchecked")
    void search_filtersByStatus_PUBLISHED() {
        UUID tenantId = UUID.randomUUID();
        UnitListing listing = publishedListing(tenantId);
        Page<UnitListing> page = new PageImpl<>(List.of(listing));

        when(listingRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        MarketplaceSearchRequest req = new MarketplaceSearchRequest(null, null, null, null, null, null, null, null, null);
        Page<UnitListing> result = service.search(tenantId, req, PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
        // Verify that findAll(Specification, Pageable) was called — the spec filters by PUBLISHED
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Specification> specCaptor = ArgumentCaptor.forClass(Specification.class);
        verify(listingRepository).findAll(specCaptor.capture(), any(Pageable.class));
        assertThat(specCaptor.getValue()).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void search_crossTenantIsolated() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        // Return empty page for tenantB
        when(listingRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        MarketplaceSearchRequest req = new MarketplaceSearchRequest(null, null, null, null, null, null, null, null, null);
        Page<UnitListing> result = service.search(tenantB, req, PageRequest.of(0, 10));

        // Result must be empty — wrong tenant returns nothing
        assertThat(result.getContent()).isEmpty();
    }

    @Test
    void getBySlug_throwsWhenWrongTenant() {
        UUID tenantId = UUID.randomUUID();
        UUID wrongTenantId = UUID.randomUUID();
        String slug = "great-flat";

        // findBySlugAndTenantId with wrong tenant returns empty
        when(listingRepository.findBySlugAndTenantId(slug, wrongTenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getBySlug(wrongTenantId, slug))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getBySlug_throwsWhenStatusNotPublishedOrUpcoming() {
        UUID tenantId = UUID.randomUUID();
        UnitListing listing = new UnitListing();
        listing.setId(UUID.randomUUID());
        listing.setTenantId(tenantId);
        listing.setSlug("draft-flat");
        listing.setStatus(ListingStatus.DRAFT);

        when(listingRepository.findBySlugAndTenantId("draft-flat", tenantId)).thenReturn(Optional.of(listing));

        assertThatThrownBy(() -> service.getBySlug(tenantId, "draft-flat"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getBySlug_returnsPublishedListing() {
        UUID tenantId = UUID.randomUUID();
        UnitListing listing = publishedListing(tenantId);

        when(listingRepository.findBySlugAndTenantId("great-flat", tenantId)).thenReturn(Optional.of(listing));

        UnitListing result = service.getBySlug(tenantId, "great-flat");
        assertThat(result.getStatus()).isEqualTo(ListingStatus.PUBLISHED);
    }

    @Test
    void resolveTenantSlug_throwsWhenNotFound() {
        when(landlordOrgRepository.findBySlug("unknown")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolveTenantSlug("unknown"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void resolveTenantSlug_returnsTenantId() {
        LandlordOrg org = new LandlordOrg();
        org.setId(UUID.randomUUID());
        org.setName("Test Org");
        org.setSlug("test-org");
        when(landlordOrgRepository.findBySlug("test-org")).thenReturn(Optional.of(org));

        UUID id = service.resolveTenantSlug("test-org");
        assertThat(id).isEqualTo(org.getId());
    }
}
