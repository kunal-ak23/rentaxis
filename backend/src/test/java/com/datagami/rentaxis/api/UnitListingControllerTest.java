package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.UnitListingCreateRequest;
import com.datagami.rentaxis.api.dto.UnitListingUpdateRequest;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.config.FeatureFlags;
import com.datagami.rentaxis.core.service.UnitListingService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.UnitListing;
import com.datagami.rentaxis.domain.entity.enums.ListingStatus;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnitListingControllerTest {

    @Mock
    UnitListingService service;

    @Mock
    FeatureFlags featureFlags;

    @InjectMocks
    UnitListingController controller;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(tenantId);
        when(featureFlags.isListingsEnabled()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
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
        when(service.list(eq(tenantId), any(), any(Pageable.class))).thenReturn(page);

        ResponseEntity<?> response = controller.list(null, null, null,
                org.springframework.data.domain.PageRequest.of(0, 20));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
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
        when(service.listInterests(tenantId, id)).thenReturn(List.of());

        ResponseEntity<?> response = controller.interests(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void featureFlagOff_throwsNotFoundException() {
        when(featureFlags.isListingsEnabled()).thenReturn(false);

        assertThatThrownBy(() -> controller.list(null, null, null,
                org.springframework.data.domain.PageRequest.of(0, 20)))
                .isInstanceOf(NotFoundException.class);
    }
}
