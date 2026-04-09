package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.FeatureToggleDTO;
import com.datagami.rentaxis.domain.entity.TenantFeatureEntity;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.domain.repository.TenantFeatureRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantFeatureServiceTest {

    @Mock
    TenantFeatureRepository repository;

    TenantFeatureService service;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Create a fresh service each test to reset the Caffeine cache
        service = new TenantFeatureService(repository);
    }

    @Test
    void isEnabled_returnsEnumDefault_whenNoDbRow() {
        when(repository.findByTenantId(tenantId)).thenReturn(List.of());

        boolean result = service.isEnabled(tenantId, TenantFeature.LISTINGS);

        assertThat(result).isFalse(); // LISTINGS default is false
        verify(repository).findByTenantId(tenantId);
    }

    @Test
    void isEnabled_returnsDbValue_whenRowExists() {
        TenantFeatureEntity entity = new TenantFeatureEntity();
        entity.setTenantId(tenantId);
        entity.setFeature(TenantFeature.LISTINGS);
        entity.setEnabled(true);
        when(repository.findByTenantId(tenantId)).thenReturn(List.of(entity));

        boolean result = service.isEnabled(tenantId, TenantFeature.LISTINGS);

        assertThat(result).isTrue();
    }

    @Test
    void isEnabled_usesCache_onSecondCall() {
        when(repository.findByTenantId(tenantId)).thenReturn(List.of());

        service.isEnabled(tenantId, TenantFeature.LISTINGS);
        service.isEnabled(tenantId, TenantFeature.LISTINGS);

        // Repository should only be called once — second call is cached
        verify(repository, times(1)).findByTenantId(tenantId);
    }

    @Test
    void setEnabled_invalidatesCache() {
        TenantFeatureEntity entity = new TenantFeatureEntity();
        entity.setTenantId(tenantId);
        entity.setFeature(TenantFeature.LISTINGS);
        entity.setEnabled(false);
        when(repository.findByTenantId(tenantId)).thenReturn(List.of(entity));

        // First call populates cache
        service.isEnabled(tenantId, TenantFeature.LISTINGS);
        verify(repository, times(1)).findByTenantId(tenantId);

        // setEnabled invalidates the cache
        service.setEnabled(tenantId, TenantFeature.LISTINGS, true);
        verify(repository).upsert(tenantId, "LISTINGS", true);

        // Next isEnabled should reload from repository (cache was invalidated)
        entity.setEnabled(true);
        service.isEnabled(tenantId, TenantFeature.LISTINGS);
        verify(repository, times(2)).findByTenantId(tenantId);
    }

    @Test
    void getAll_returnsAllEnumValues_withDefaultsAndOverrides() {
        TenantFeatureEntity entity = new TenantFeatureEntity();
        entity.setTenantId(tenantId);
        entity.setFeature(TenantFeature.LISTINGS);
        entity.setEnabled(true);
        when(repository.findByTenantId(tenantId)).thenReturn(List.of(entity));

        List<FeatureToggleDTO> result = service.getAll(tenantId);

        assertThat(result).hasSize(TenantFeature.values().length);
        FeatureToggleDTO listings = result.stream()
                .filter(f -> f.feature() == TenantFeature.LISTINGS)
                .findFirst().orElseThrow();
        assertThat(listings.enabled()).isTrue();
        assertThat(listings.defaultEnabled()).isFalse();
        assertThat(listings.label()).isEqualTo("Listings (Marketplace)");
    }

    @Test
    void getAll_returnsDefaults_whenNoDbRows() {
        when(repository.findByTenantId(tenantId)).thenReturn(List.of());

        List<FeatureToggleDTO> result = service.getAll(tenantId);

        assertThat(result).hasSize(TenantFeature.values().length);
        FeatureToggleDTO listings = result.stream()
                .filter(f -> f.feature() == TenantFeature.LISTINGS)
                .findFirst().orElseThrow();
        assertThat(listings.enabled()).isFalse();
        assertThat(listings.defaultEnabled()).isFalse();
    }

    @Test
    void loadAll_ignoresNullFeatures() {
        TenantFeatureEntity nullFeature = new TenantFeatureEntity();
        nullFeature.setTenantId(tenantId);
        nullFeature.setFeature(null); // orphan row from removed enum value
        nullFeature.setEnabled(true);
        when(repository.findByTenantId(tenantId)).thenReturn(List.of(nullFeature));

        boolean result = service.isEnabled(tenantId, TenantFeature.LISTINGS);

        assertThat(result).isFalse(); // falls back to default
    }
}
