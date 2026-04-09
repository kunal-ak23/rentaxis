package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.FeatureToggleDTO;
import com.datagami.rentaxis.domain.entity.TenantFeatureEntity;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.domain.repository.TenantFeatureRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TenantFeatureService {

    private final TenantFeatureRepository repository;

    // tenantId → Map<TenantFeature, Boolean>
    private final Cache<UUID, Map<TenantFeature, Boolean>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(30))
            .maximumSize(500)
            .build();

    public TenantFeatureService(TenantFeatureRepository repository) {
        this.repository = repository;
    }

    public boolean isEnabled(UUID tenantId, TenantFeature feature) {
        Map<TenantFeature, Boolean> flags = cache.get(tenantId, this::loadAll);
        return flags.getOrDefault(feature, feature.isDefaultEnabled());
    }

    // @Transactional is deliberately absent: cache.invalidate must run AFTER the DB commit,
    // not inside the transaction. If the DB write succeeds and the JVM crashes before invalidate,
    // the cache will serve stale data until TTL expiry (30 min) — acceptable for feature toggles.
    public void setEnabled(UUID tenantId, TenantFeature feature, boolean enabled) {
        repository.upsert(tenantId, feature.name(), enabled);
        cache.invalidate(tenantId);
    }

    public List<FeatureToggleDTO> getAll(UUID tenantId) {
        Map<TenantFeature, Boolean> flags = cache.get(tenantId, this::loadAll);
        return Arrays.stream(TenantFeature.values())
                .map(f -> new FeatureToggleDTO(
                        f,
                        toLabel(f),
                        f.isDefaultEnabled(),
                        flags.getOrDefault(f, f.isDefaultEnabled())
                ))
                .toList();
    }

    private Map<TenantFeature, Boolean> loadAll(UUID tenantId) {
        List<TenantFeatureEntity> rows = repository.findByTenantId(tenantId);
        Map<TenantFeature, Boolean> map = new HashMap<>();
        for (TenantFeatureEntity row : rows) {
            if (row.getFeature() != null) {
                map.put(row.getFeature(), row.isEnabled());
            }
        }
        return Collections.unmodifiableMap(map);
    }

    private String toLabel(TenantFeature feature) {
        return switch (feature) {
            case LISTINGS -> "Listings (Marketplace)";
            case MEETINGS -> "Meetings & Scheduling";
        };
    }
}
