package com.datagami.rentaxis.domain.entity.enums;

public enum TenantFeature {
    LISTINGS(false),             // premium — off by default for all tenants
    MEETINGS(false),             // premium — off by default for all tenants
    EMAIL_NOTIFICATIONS(false),  // off by default — flipped on per-tenant during phased rollout
    LEASE_RENEWALS(false),       // renewal reminders (90/60/30) + CRM — off by default, per-tenant rollout
    GATEPASS(false);             // guest gate passes + guard app — off by default, per-tenant rollout

    private final boolean defaultEnabled;

    TenantFeature(boolean defaultEnabled) {
        this.defaultEnabled = defaultEnabled;
    }

    public boolean isDefaultEnabled() {
        return defaultEnabled;
    }
}
