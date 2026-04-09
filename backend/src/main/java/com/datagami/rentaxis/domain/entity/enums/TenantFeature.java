package com.datagami.rentaxis.domain.entity.enums;

public enum TenantFeature {
    LISTINGS(false),   // premium — off by default for all tenants
    MEETINGS(false);   // premium — off by default for all tenants

    private final boolean defaultEnabled;

    TenantFeature(boolean defaultEnabled) {
        this.defaultEnabled = defaultEnabled;
    }

    public boolean isDefaultEnabled() {
        return defaultEnabled;
    }
}
