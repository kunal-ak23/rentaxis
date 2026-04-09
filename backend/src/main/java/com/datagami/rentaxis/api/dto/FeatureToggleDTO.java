package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.TenantFeature;

public record FeatureToggleDTO(
        TenantFeature feature,
        String label,
        boolean defaultEnabled,
        boolean enabled
) {}
