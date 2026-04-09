package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.PublicListingDTO;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.MarketplaceService;
import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.domain.entity.UnitListing;
import com.datagami.rentaxis.domain.entity.enums.ListingStatus;
import com.datagami.rentaxis.domain.repository.UnitListingMediaRepository;
import com.datagami.rentaxis.domain.repository.UnitListingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PublicListingControllerTest {

    @Mock
    MarketplaceService marketplaceService;
    @Mock
    UnitListingMediaRepository mediaRepository;
    @Mock
    UnitListingRepository listingRepository;
    @Mock
    TenantFeatureService tenantFeatureService;

    @InjectMocks
    PublicListingController controller;

    private UUID tenantId;
    private UnitListing listing;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        listing = new UnitListing();
        // Fixed UUID so computeOffset() is deterministic (seeded from UUID bits)
        listing.setId(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
        listing.setTenantId(tenantId);
        listing.setSlug("marina-2br");
        listing.setTitleEn("Marina 2BR");
        listing.setStatus(ListingStatus.PUBLISHED);
        listing.setAnnualRent(new BigDecimal("85000"));
        listing.setLat(new BigDecimal("25.0819"));
        listing.setLng(new BigDecimal("55.1367"));
        listing.setBedrooms(2);

        when(tenantFeatureService.isEnabled(any(), eq(TenantFeature.LISTINGS))).thenReturn(true);
        when(mediaRepository.findByListingIdOrderBySortOrderAsc(any())).thenReturn(Collections.emptyList());
    }

    // ── getPublicListing ─────────────────────────────────────────────────────────

    @Test
    void getPublicListing_returnsOk_whenPublished() {
        when(marketplaceService.resolveByTenantSlugAndUnitSlug("acme", "marina-2br"))
                .thenReturn(listing);

        ResponseEntity<PublicListingDTO> response = controller.getPublicListing("acme", "marina-2br");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PublicListingDTO dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.slug()).isEqualTo("marina-2br");
        assertThat(dto.tenantSlug()).isEqualTo("acme");
        assertThat(dto.bedrooms()).isEqualTo(2);
    }

    @Test
    void getPublicListing_rentIsObfuscated_notExact() {
        // Annual rent 85000 should be exposed as a range, not the exact figure
        when(marketplaceService.resolveByTenantSlugAndUnitSlug(any(), any())).thenReturn(listing);

        ResponseEntity<PublicListingDTO> response = controller.getPublicListing("acme", "marina-2br");

        PublicListingDTO dto = response.getBody();
        assertThat(dto).isNotNull();
        // buildRentLabel(85000) rounds down to nearest 10k → "AED 80k-90k" (deterministic)
        assertThat(dto.rentRangeLabel()).isEqualTo("AED 80k-90k");
    }

    @Test
    void getPublicListing_coordinatesAreOffset_notExact() {
        when(marketplaceService.resolveByTenantSlugAndUnitSlug(any(), any())).thenReturn(listing);

        ResponseEntity<PublicListingDTO> response = controller.getPublicListing("acme", "marina-2br");

        PublicListingDTO dto = response.getBody();
        assertThat(dto).isNotNull();
        // Offset is seeded from fixed UUID → deterministic and non-zero
        // Magnitude must be within ±MAX_OFFSET_DEG (0.002 degrees, ~220m)
        BigDecimal maxOffset = new BigDecimal("0.002");
        assertThat(dto.approxLat()).isNotEqualByComparingTo(listing.getLat());
        assertThat(dto.approxLng()).isNotEqualByComparingTo(listing.getLng());
        assertThat(dto.approxLat().subtract(listing.getLat()).abs()).isLessThanOrEqualTo(maxOffset);
        assertThat(dto.approxLng().subtract(listing.getLng()).abs()).isLessThanOrEqualTo(maxOffset);
    }

    @Test
    void getPublicListing_crossTenantRequest_throws404() {
        // MarketplaceService enforces tenant scoping; wrong tenant slug → NotFoundException
        when(marketplaceService.resolveByTenantSlugAndUnitSlug("wrong-tenant", "marina-2br"))
                .thenThrow(new NotFoundException("Not found"));

        assertThatThrownBy(() -> controller.getPublicListing("wrong-tenant", "marina-2br"))
                .isInstanceOf(NotFoundException.class);
        // Verify the exception originated from the tenant-scoped service call, not another path
        verify(marketplaceService).resolveByTenantSlugAndUnitSlug("wrong-tenant", "marina-2br");
    }

    @Test
    void getPublicListing_returns404_whenFeatureDisabled() {
        when(tenantFeatureService.isEnabled(any(), eq(TenantFeature.LISTINGS))).thenReturn(false);
        when(marketplaceService.resolveByTenantSlugAndUnitSlug("acme", "marina-2br"))
                .thenReturn(listing);

        assertThatThrownBy(() -> controller.getPublicListing("acme", "marina-2br"))
                .isInstanceOf(NotFoundException.class);
    }

    // ── sitemap ──────────────────────────────────────────────────────────────────

    @Test
    void sitemap_returnsXml_containsListingUrl() {
        UUID tid = UUID.randomUUID();
        UnitListing pub = new UnitListing();
        pub.setId(UUID.randomUUID());
        pub.setTenantId(tid);
        pub.setSlug("villa-5br");
        pub.setStatus(ListingStatus.PUBLISHED);

        when(marketplaceService.resolveTenantSlug("acme")).thenReturn(tid);
        when(listingRepository.findByTenantIdAndStatus(eq(tid), eq(ListingStatus.PUBLISHED), any()))
                .thenReturn(new PageImpl<>(List.of(pub)));

        ResponseEntity<String> response = controller.sitemap("acme");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("<urlset");
        assertThat(response.getBody()).contains("/l/acme/villa-5br");
    }

    @Test
    void sitemap_returnsEmpty_whenNoPublishedListings() {
        UUID tid = UUID.randomUUID();
        when(marketplaceService.resolveTenantSlug("acme")).thenReturn(tid);
        when(listingRepository.findByTenantIdAndStatus(any(), any(), any()))
                .thenReturn(Page.empty());

        ResponseEntity<String> response = controller.sitemap("acme");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("<urlset");
        assertThat(response.getBody()).doesNotContain("<url>");
    }
}
