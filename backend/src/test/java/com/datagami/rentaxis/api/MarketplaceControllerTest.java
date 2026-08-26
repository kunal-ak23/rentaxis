package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.InterestService;
import com.datagami.rentaxis.core.service.BlobStorageService;
import com.datagami.rentaxis.core.service.MarketplaceService;
import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.domain.entity.UnitListing;
import com.datagami.rentaxis.domain.entity.UnitListingInterest;
import com.datagami.rentaxis.domain.entity.enums.InterestStatus;
import com.datagami.rentaxis.domain.entity.enums.ListingStatus;
import com.datagami.rentaxis.domain.repository.UnitListingAmenityRepository;
import com.datagami.rentaxis.domain.repository.UnitListingMediaRepository;
import com.datagami.rentaxis.domain.repository.UnitListingRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

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
class MarketplaceControllerTest {

    @Mock
    MarketplaceService marketplaceService;
    @Mock
    InterestService interestService;
    @Mock
    UnitListingMediaRepository mediaRepository;
    @Mock
    UnitListingAmenityRepository amenityRepository;
    @Mock
    UnitListingRepository listingRepository;
    @Mock
    TenantFeatureService tenantFeatureService;
    @Mock
    UnitRepository unitRepository;
    @Mock
    BlobStorageService blobStorageService;

    @InjectMocks
    MarketplaceController controller;

    private final UUID renterId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(tenantFeatureService.isEnabled(any(), eq(TenantFeature.LISTINGS))).thenReturn(true);
        // Set up SecurityContext with renter user
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                renterId.toString(), null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_RENTER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        // Default: empty media + amenities
        when(mediaRepository.findByListingIdOrderBySortOrderAsc(any())).thenReturn(List.of());
        when(amenityRepository.findByListingId(any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private UnitListing publishedListing() {
        UnitListing l = new UnitListing();
        l.setId(UUID.randomUUID());
        l.setTenantId(tenantId);
        l.setStatus(ListingStatus.PUBLISHED);
        l.setSlug("nice-flat");
        l.setTitleEn("Nice Flat");
        return l;
    }

    @Test
    void listListings_200() {
        when(marketplaceService.resolveTenantSlug("acme")).thenReturn(tenantId);
        Page<UnitListing> page = new PageImpl<>(List.of(publishedListing()));
        when(marketplaceService.search(eq(tenantId), any(), any(Pageable.class))).thenReturn(page);

        ResponseEntity<?> resp = controller.listListings(
                "acme", null, null, null, null, null, null, null, null, null,
                PageRequest.of(0, 10));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
    }

    @Test
    void listListings_summaryCarriesCoordinatesBathroomsAndCreatedAt() {
        when(marketplaceService.resolveTenantSlug("acme")).thenReturn(tenantId);
        UnitListing listing = publishedListing();
        listing.setBathrooms(2);
        listing.setLat(new java.math.BigDecimal("25.2048"));
        listing.setLng(new java.math.BigDecimal("55.2708"));
        java.time.LocalDateTime created = java.time.LocalDateTime.of(2026, 1, 15, 10, 30);
        listing.setCreatedAt(created);
        Page<UnitListing> page = new PageImpl<>(List.of(listing));
        when(marketplaceService.search(eq(tenantId), any(), any(Pageable.class))).thenReturn(page);

        ResponseEntity<Page<com.datagami.rentaxis.api.dto.UnitListingSummaryDTO>> resp =
                controller.listListings(
                        "acme", null, null, null, null, null, null, null, null, null,
                        PageRequest.of(0, 10));

        // The renter map and web distance chips read these off the summary —
        // they must survive the entity -> summary mapping.
        var summary = resp.getBody().getContent().getFirst();
        assertThat(summary.bathrooms()).isEqualTo(2);
        assertThat(summary.lat()).isEqualByComparingTo("25.2048");
        assertThat(summary.lng()).isEqualByComparingTo("55.2708");
        assertThat(summary.createdAt()).isEqualTo(created);
    }

    @Test
    void getBySlug_200() {
        UnitListing listing = publishedListing();
        when(marketplaceService.resolveByTenantSlugAndUnitSlug("acme", "nice-flat")).thenReturn(listing);

        ResponseEntity<?> resp = controller.getBySlug("acme", "nice-flat");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
    }

    @Test
    void addInterest_201() {
        UnitListing listing = publishedListing();
        when(marketplaceService.getListingById(listing.getId())).thenReturn(listing);
        UnitListingInterest interest = new UnitListingInterest();
        interest.setId(UUID.randomUUID());
        when(interestService.addInterest(eq(tenantId), eq(listing.getId()), eq(renterId), any()))
                .thenReturn(interest);

        ResponseEntity<Void> resp = controller.addInterest(listing.getId(), new MarketplaceController.NoteRequest("hello"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(interestService).addInterest(tenantId, listing.getId(), renterId, "hello");
    }

    @Test
    void withdrawInterest_204() {
        UnitListing listing = publishedListing();
        when(marketplaceService.getListingById(listing.getId())).thenReturn(listing);

        ResponseEntity<Void> resp = controller.withdrawInterest(listing.getId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(interestService).withdraw(tenantId, listing.getId(), renterId);
    }

    @Test
    void wishlist_200() {
        UnitListingInterest interest = new UnitListingInterest();
        interest.setId(UUID.randomUUID());
        interest.setTenantId(tenantId);
        UUID listingId = UUID.randomUUID();
        interest.setListingId(listingId);
        interest.setRenterUserId(renterId);
        interest.setStatus(InterestStatus.ACTIVE);

        when(interestService.wishlistForRenter(renterId)).thenReturn(List.of(interest));
        UnitListing listing = publishedListing();
        listing.setId(listingId);
        when(listingRepository.findAllByIdIn(any())).thenReturn(List.of(listing));

        ResponseEntity<List<com.datagami.rentaxis.api.dto.UnitListingSummaryDTO>> resp = controller.wishlist();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(1);
    }

    @Test
    void featureFlagOff_404() {
        when(marketplaceService.resolveTenantSlug("acme")).thenReturn(tenantId);
        when(tenantFeatureService.isEnabled(any(), eq(TenantFeature.LISTINGS))).thenReturn(false);

        assertThatThrownBy(() -> controller.listListings(
                "acme", null, null, null, null, null, null, null, null, null,
                PageRequest.of(0, 10)))
                .isInstanceOf(NotFoundException.class);
    }
}
