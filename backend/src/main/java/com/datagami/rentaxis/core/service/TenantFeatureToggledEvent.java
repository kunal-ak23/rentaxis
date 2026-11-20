package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.enums.TenantFeature;

import java.util.UUID;

/** Published by {@link TenantFeatureService#setEnabled} after the flag is stored and the cache invalidated. */
public record TenantFeatureToggledEvent(UUID tenantId, TenantFeature feature, boolean enabled) {
}
