# Per-Tenant Feature Toggles — Design

**Date:** 2026-04-09  
**Status:** Approved  
**Scope:** Backend (Spring Boot) + Frontend (Next.js superadmin UI)

---

## Problem

`FEATURE_LISTINGS_ENABLED` is a global env var that enables/disables listings for **all** tenants simultaneously. We need per-tenant control so premium features can be sold independently to each tenant.

---

## Approach: Sparse Join Table (Option A)

A `tenant_feature` table stores only rows where a SUPER_ADMIN has explicitly set a value. Missing rows fall back to the enum's `defaultEnabled`. Adding a new feature requires zero DB migrations for existing tenants.

---

## Data Model

### New table: `tenant_feature`

```sql
CREATE TABLE tenant_feature (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES landlord_org(id) ON DELETE CASCADE,
    feature     VARCHAR(100) NOT NULL,   -- matches TenantFeature.name()
    enabled     BOOLEAN NOT NULL,
    updated_at  TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, feature)
);
CREATE INDEX idx_tenant_feature_tenant_id ON tenant_feature(tenant_id);
```

### New enum: `TenantFeature`

```java
public enum TenantFeature {
    LISTINGS(false);   // premium — off by default for all tenants

    private final boolean defaultEnabled;

    TenantFeature(boolean defaultEnabled) {
        this.defaultEnabled = defaultEnabled;
    }

    public boolean isDefaultEnabled() {
        return defaultEnabled;
    }
}
```

Default value is canonical — defined once on the enum, consulted when no DB row exists.

---

## Backend

### `TenantFeatureRepository`

JPA repository on `TenantFeatureEntity` (maps `tenant_feature`).  
Key method: `Optional<TenantFeatureEntity> findByTenantIdAndFeature(UUID tenantId, TenantFeature feature)`.

### `TenantFeatureService`

Single source of truth for feature checks.

```java
@Service
public class TenantFeatureService {

    // Caffeine cache: tenantId → Map<TenantFeature, Boolean>
    // TTL: 30 minutes after write. Max size: 500 entries.
    private final Cache<UUID, Map<TenantFeature, Boolean>> cache;

    public boolean isEnabled(UUID tenantId, TenantFeature feature) {
        Map<TenantFeature, Boolean> tenantFlags =
            cache.get(tenantId, id -> loadAll(id));
        return tenantFlags.getOrDefault(feature, feature.isDefaultEnabled());
    }

    public void setEnabled(UUID tenantId, TenantFeature feature, boolean enabled) {
        repository.upsert(tenantId, feature.name(), enabled); // INSERT ... ON CONFLICT UPDATE
        cache.invalidate(tenantId);
    }

    public Map<TenantFeature, FeatureState> getAll(UUID tenantId) {
        // Returns ALL enum values with current enabled state + default
        // Used by the admin API GET endpoint
    }

    private Map<TenantFeature, Boolean> loadAll(UUID tenantId) {
        // SELECT all rows for this tenant, convert to Map
    }
}
```

### Controller changes

**`UnitListingController`** and **`MarketplaceController`**:
- Remove `FeatureFlags` injection
- `checkEnabled()` → `tenantFeatureService.isEnabled(TenantContextHolder.getTenantId(), TenantFeature.LISTINGS)`

**`PublicListingController`**:
- Remove `FeatureFlags` injection
- `checkEnabled(UUID tenantId)` receives tenantId (already resolved from slug before this call)
- Same service call pattern

### Admin API (added to `LandlordOrgController`)

```
GET  /api/admin/tenants/{id}/features
     → List<FeatureToggleDTO> { feature, label, defaultEnabled, enabled }

PUT  /api/admin/tenants/{id}/features/{feature}
     body: { "enabled": true }
     → 200 OK
     → Invalidates cache for this tenant
```

### Removals

- `FeatureFlags.java` — deleted entirely
- `rentaxis.features.listings-enabled` from `application.yml`
- `FEATURE_LISTINGS_ENABLED=true` from `.github/workflows/deploy.yml`
- `FEATURE_LISTINGS_ENABLED` from `docker-compose.prod.yml`

---

## Caching

- Library: **Caffeine** (already on Spring Boot classpath via `spring-boot-starter-cache`)
- Cache name: `tenantFeatures`
- TTL: 30 minutes after write
- Max size: 500 tenant entries
- Invalidation: explicit `cache.invalidate(tenantId)` on every `setEnabled()` call

---

## Frontend (Superadmin UI)

**Location:** `/superadmin/tenants` page — add a "Features" button/drawer per tenant row, or a Features section on an existing tenant detail page.

**UI pattern:**
- On open: `GET /api/admin/tenants/{id}/features` → render one toggle row per feature
- Toggle flip: optimistic update → `PUT /api/admin/tenants/{id}/features/{feature}` → revert on error
- Each row shows: feature name, description, default value badge, current toggle state

**Feature row fields:**
- `LISTINGS` → label "Listings (Marketplace)" → default: Off

---

## Migration

Liquibase changeset `38-tenant-feature.yaml`:
- Creates `tenant_feature` table
- No data backfill needed — all existing tenants get `LISTINGS = false` by enum default (sparse table, no rows = use default)

---

## What Does NOT Change

- Tenant isolation (`TenantContextHolder`, `TenantAspect`) — unchanged
- Auth/RBAC — only `SUPER_ADMIN` can call `/api/admin/tenants/{id}/features/*`
- Existing listing endpoints — same URLs, same responses; only the guard logic changes internally

---

## Open Questions / Future

- Future features (e.g. `BULK_IMPORT`, `ONLINE_PAYMENTS`) just add a new enum constant with a `defaultEnabled` value — zero DB migration needed for existing tenants
- If a feature is removed from the enum, orphan rows in `tenant_feature` are harmless (looked up by name, not found in enum → ignored)
