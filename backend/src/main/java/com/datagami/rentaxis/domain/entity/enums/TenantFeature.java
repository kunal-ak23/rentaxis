package com.datagami.rentaxis.domain.entity.enums;

public enum TenantFeature {
    LISTINGS(false),             // premium — off by default for all tenants
    MEETINGS(false),             // premium — off by default for all tenants
    EMAIL_NOTIFICATIONS(false);  // off by default — flipped on per-tenant during phased rollout

    private final boolean defaultEnabled;

    TenantFeature(boolean defaultEnabled) {
        this.defaultEnabled = defaultEnabled;
    }

    public boolean isDefaultEnabled() {
        return defaultEnabled;
    }
}
