package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.UnitListingCreateRequest;
import com.datagami.rentaxis.api.dto.UnitListingSummaryDTO;
import com.datagami.rentaxis.api.dto.UnitListingUpdateRequest;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.core.service.UnitListingService;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.UnitListing;
import com.datagami.rentaxis.domain.entity.enums.ListingStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnitListingControllerTest {

    @Mock
    UnitListingService service;

    @Mock
    TenantFeatureService tenantFeatureService;

    @Mock
    LandlordOrgRepository landlordOrgRepository;

    @Mock
    UnitRepository unitRepository;

    @Mock
    UserPropertyAssignmentRepository assignmentRepository;

    @InjectMocks
    UnitListingController controller;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(tenantId);
        lenient().when(tenantFeatureService.isEnabled(any(), eq(TenantFeature.LISTINGS))).thenReturn(true);
        lenient().when(landlordOrgRepository.findById(any(UUID.class))).thenReturn(Optional.empty());
        lenient().when(service.getSummaryData(any())).thenReturn(Map.of());
        // Auth context for endpoints that check authorities (e.g. checkPropertyManagerAccess)
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "admin", null,
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN"))));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    private UnitListing sampleListing(UUID id) {
        UnitListing l = new UnitListing();
        l.setId(id);
        l.setTenantId(tenantId);
        l.setUnitId(UUID.randomUUID());
        l.setStatus(ListingStatus.DRAFT);
        l.setTitleEn("Nice Flat");
        l.setSlug("nice-flat");
        return l;
    }

    @Test
    void list_returns200WithPage() {
        UUID id = UUID.randomUUID();
        Page<UnitListing> page = new PageImpl<>(List.of(sampleListing(id)));
        when(service.list(eq(tenantId), any(), any(), any(), any(Pageable.class))).thenReturn(page);

        ResponseEntity<?> response = controller.list(null, null, null,
                org.springframework.data.domain.PageRequest.of(0, 20));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    void list_forwardsPropertyIdAndQToService() {
        UUID propertyId = UUID.randomUUID();
        Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        when(service.list(eq(tenantId), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        controller.list(ListingStatus.PUBLISHED, propertyId, "marina", pageable);

        // The dashboard search box and property filter depend on these reaching
        // the service — they used to be accepted but silently dropped.
        verify(service).list(tenantId, ListingStatus.PUBLISHED, propertyId, "marina", pageable);
    }

    @Test
    void list_populatesPropertyCoverAndActiveInterestCount() {
        UUID id = UUID.randomUUID();
        UnitListing listing = sampleListing(id);
        java.time.LocalDateTime created = java.time.LocalDateTime.of(2026, 2, 1, 9, 0);
        listing.setCreatedAt(created);

        when(service.list(eq(tenantId), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(listing)));
        when(service.getSummaryData(List.of(listing))).thenReturn(Map.of(
                id,
                new UnitListingService.ListingSummaryData(
                        "Marina Heights", "https://cdn/cover.jpg", 3L)));

        ResponseEntity<Page<UnitListingSummaryDTO>> response = controller.list(
                null, null, null, org.springframework.data.domain.PageRequest.of(0, 20));
        UnitListingSummaryDTO summary = response.getBody().getContent().getFirst();

        assertThat(summary.propertyName()).isEqualTo("Marina Heights");
        assertThat(summary.coverPhotoUrl()).isEqualTo("https://cdn/cover.jpg");
        assertThat(summary.interestsCount()).isEqualTo(3L);
        // The dashboard's sortable "Created" column renders this field — it must
        // be the createdAt timestamp, not updatedAt.
        assertThat(summary.createdAt()).isEqualTo(created);
    }

    @Test
    void get_returns200() {
        UUID id = UUID.randomUUID();
        when(service.get(tenantId, id)).thenReturn(sampleListing(id));

        ResponseEntity<?> response = controller.get(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    void get_throwsNotFoundException_whenNotFound() {
        UUID id = UUID.randomUUID();
        when(service.get(tenantId, id)).thenThrow(new NotFoundException("Listing not found"));

        assertThatThrownBy(() -> controller.get(id))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Listing not found");
    }

    @Test
    void create_returns201() {
        UUID id = UUID.randomUUID();
        // 27 fields: unitId, titleEn, titleAr, descriptionEn, descriptionAr,
        // bedrooms, bathrooms, sizeSqft, floor, parkingSpaces, furnishing, viewType,
        // annualRent, securityDeposit, minLeaseMonths, chequesAccepted, dewaIncluded,
        // chillerIncluded, utilitiesEstimate, availableFrom, seoTitle, seoDescription,
        // seoKeywords, ogImageUrl, lat, lng, amenities
        UnitListingCreateRequest req = new UnitListingCreateRequest(
                UUID.randomUUID(), "Nice Flat", null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null
        );
        when(service.create(eq(tenantId), any())).thenReturn(sampleListing(id));

        ResponseEntity<?> response = controller.create(req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    void update_returns200() {
        UUID id = UUID.randomUUID();
        // 26 fields: titleEn, titleAr, descriptionEn, descriptionAr,
        // bedrooms, bathrooms, sizeSqft, floor, parkingSpaces, furnishing, viewType,
        // annualRent, securityDeposit, minLeaseMonths, chequesAccepted, dewaIncluded,
        // chillerIncluded, utilitiesEstimate, availableFrom, seoTitle, seoDescription,
        // seoKeywords, ogImageUrl, lat, lng, amenities
        UnitListingUpdateRequest req = new UnitListingUpdateRequest(
                "Updated", null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null
        );
        when(service.update(eq(tenantId), eq(id), any())).thenReturn(sampleListing(id));

        ResponseEntity<?> response = controller.update(id, req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void publish_returns204AndDelegates() {
        UUID id = UUID.randomUUID();
        ResponseEntity<Void> response = controller.publish(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).publish(tenantId, id);
    }

    @Test
    void unlist_returns204AndDelegates() {
        UUID id = UUID.randomUUID();
        ResponseEntity<Void> response = controller.unlist(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).unlist(tenantId, id);
    }

    @Test
    void delete_returns204AndDelegates() {
        UUID id = UUID.randomUUID();
        ResponseEntity<Void> response = controller.delete(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).delete(tenantId, id);
    }

    @Test
    void interests_returns200() {
        UUID id = UUID.randomUUID();
        when(service.listInterests(eq(tenantId), eq(id), any(Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

        ResponseEntity<?> response = controller.interests(id,
                org.springframework.data.domain.PageRequest.of(0, 20));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void reorderMedia_acceptsMediaIdsWrapperObject_rejectsBareArray() throws Exception {
        UUID listingId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        org.springframework.test.web.servlet.MockMvc mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders
                        .standaloneSetup(controller).build();

        // Contract: the body is an object wrapping the array — {"mediaIds": [...]}.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/listings/" + listingId + "/media/reorder")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"mediaIds\":[\"" + first + "\",\"" + second + "\"]}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isNoContent());
        verify(service).reorderMedia(tenantId, listingId, List.of(first, second));

        // A bare JSON array (the shape the web client used to send) must not bind.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/listings/" + listingId + "/media/reorder")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("[\"" + first + "\",\"" + second + "\"]"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isBadRequest());
    }

    @Test
    void featureFlagOff_throwsNotFoundException() {
        when(tenantFeatureService.isEnabled(any(), eq(TenantFeature.LISTINGS))).thenReturn(false);

        assertThatThrownBy(() -> controller.list(null, null, null,
                org.springframework.data.domain.PageRequest.of(0, 20)))
                .isInstanceOf(NotFoundException.class);
    }
}
