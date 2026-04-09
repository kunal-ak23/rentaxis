# Per-Tenant Feature Toggles Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the global `FEATURE_LISTINGS_ENABLED` env var with a DB-backed per-tenant feature toggle system, controlled by SUPER_ADMIN via API, cached in Caffeine (30-min TTL).

**Architecture:** A sparse join table `tenant_feature` stores only explicitly-set values; missing rows fall back to the enum's `defaultEnabled`. A `TenantFeatureService` wraps a Caffeine cache keyed by `tenantId` and invalidates on every write. Three controllers (`UnitListingController`, `MarketplaceController`, `PublicListingController`) replace their `FeatureFlags` check with the new service. `LandlordOrgController` gains two new endpoints for SUPER_ADMIN to read/write toggles. The superadmin UI gets a Features drawer per tenant row.

**Tech Stack:** Java 21, Spring Boot 4.0.3, Spring Data JPA, Caffeine 3.x, Liquibase, Next.js 16 + TypeScript

---

## Task 1: Add Caffeine dependency to build.gradle

**Files:**
- Modify: `backend/build.gradle`

**Step 1: Add dependencies**

Open `backend/build.gradle`. In the `dependencies` block, after the existing `implementation` lines, add:

```groovy
implementation 'org.springframework.boot:spring-boot-starter-cache'
implementation 'com.github.ben-manes.caffeine:caffeine:3.1.8'
```

**Step 2: Verify the build resolves**

```bash
cd backend && ./gradlew dependencies --configuration runtimeClasspath | grep caffeine
```

Expected: `com.github.ben-manes.caffeine:caffeine:3.1.8`

**Step 3: Commit**

```bash
git add backend/build.gradle
git commit -m "chore: add Caffeine and spring-cache dependency"
```

---

## Task 2: Liquibase migration — create `tenant_feature` table

**Files:**
- Create: `backend/src/main/resources/db/changelog/changesets/38-tenant-feature.yaml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml`

**Step 1: Create the changeset file**

```yaml
databaseChangeLog:
  - changeSet:
      id: 38-create-tenant-feature
      author: rentaxis
      changes:
        - createTable:
            tableName: tenant_feature
            columns:
              - column:
                  name: id
                  type: uuid
                  defaultValueComputed: gen_random_uuid()
                  constraints:
                    primaryKey: true
                    nullable: false
              - column:
                  name: tenant_id
                  type: uuid
                  constraints:
                    nullable: false
              - column:
                  name: feature
                  type: varchar(100)
                  constraints:
                    nullable: false
              - column:
                  name: enabled
                  type: boolean
                  constraints:
                    nullable: false
              - column:
                  name: updated_at
                  type: timestamp
                  defaultValueComputed: now()
                  constraints:
                    nullable: false
        - addForeignKeyConstraint:
            baseTableName: tenant_feature
            baseColumnNames: tenant_id
            constraintName: fk_tenant_feature_tenant_id
            referencedTableName: landlord_org
            referencedColumnNames: id
            onDelete: CASCADE
        - addUniqueConstraint:
            tableName: tenant_feature
            columnNames: tenant_id, feature
            constraintName: uq_tenant_feature_tenant_feature
        - createIndex:
            indexName: idx_tenant_feature_tenant_id
            tableName: tenant_feature
            columns:
              - column:
                  name: tenant_id
```

**Step 2: Register it in the master changelog**

In `db.changelog-master.yaml`, add at the end of the `include` list:

```yaml
- include:
    file: changesets/38-tenant-feature.yaml
    relativeToChangelogFile: true
```

**Step 3: Verify migration applies**

```bash
cd backend && ./gradlew bootRun &
sleep 15
curl -s http://localhost:8080/actuator/health | grep UP
kill %1
```

Or simply run the app in your IDE and check Liquibase logs for `38-create-tenant-feature EXECUTED`.

**Step 4: Commit**

```bash
git add backend/src/main/resources/db/changelog/
git commit -m "feat: add tenant_feature Liquibase migration (38)"
```

---

## Task 3: `TenantFeature` enum

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/TenantFeature.java`

**Step 1: Write the enum**

```java
package com.datagami.rentaxis.domain.entity.enums;

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

**Step 2: Compile check**

```bash
cd backend && ./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

**Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/TenantFeature.java
git commit -m "feat: add TenantFeature enum with LISTINGS(false) default"
```

---

## Task 4: `TenantFeatureEntity` + `TenantFeatureRepository`

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/TenantFeatureEntity.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/repository/TenantFeatureRepository.java`

**Step 1: Write the entity**

Do NOT extend `BaseTenantEntity` — the service and admin API manage `tenantId` explicitly and do not run inside tenant Hibernate filter context.

```java
package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "tenant_feature")
public class TenantFeatureEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "feature", nullable = false, length = 100)
    private TenantFeature feature;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
```

**Step 2: Write the repository**

```java
package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.TenantFeatureEntity;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantFeatureRepository extends JpaRepository<TenantFeatureEntity, UUID> {

    Optional<TenantFeatureEntity> findByTenantIdAndFeature(UUID tenantId, TenantFeature feature);

    List<TenantFeatureEntity> findByTenantId(UUID tenantId);

    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO tenant_feature (tenant_id, feature, enabled, updated_at)
        VALUES (:tenantId, :feature, :enabled, now())
        ON CONFLICT (tenant_id, feature) DO UPDATE
            SET enabled = EXCLUDED.enabled, updated_at = now()
        """, nativeQuery = true)
    void upsert(UUID tenantId, String feature, boolean enabled);
}
```

**Step 3: Compile check**

```bash
cd backend && ./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

**Step 4: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/domain/entity/TenantFeatureEntity.java \
        backend/src/main/java/com/datagami/rentaxis/domain/repository/TenantFeatureRepository.java
git commit -m "feat: add TenantFeatureEntity and TenantFeatureRepository"
```

---

## Task 5: `TenantFeatureService` with Caffeine cache

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/TenantFeatureService.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/FeatureToggleDTO.java`

**Step 1: Write the DTO**

```java
package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.TenantFeature;

public record FeatureToggleDTO(
        TenantFeature feature,
        String label,
        boolean defaultEnabled,
        boolean enabled
) {}
```

**Step 2: Write the service**

```java
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
        return map;
    }

    private String toLabel(TenantFeature feature) {
        return switch (feature) {
            case LISTINGS -> "Listings (Marketplace)";
        };
    }
}
```

**Step 3: Compile check**

```bash
cd backend && ./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

**Step 4: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/TenantFeatureService.java \
        backend/src/main/java/com/datagami/rentaxis/api/dto/FeatureToggleDTO.java
git commit -m "feat: add TenantFeatureService with Caffeine cache"
```

---

## Task 6: Admin API endpoints in `LandlordOrgController`

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/LandlordOrgController.java`

**Step 1: Add imports and inject `TenantFeatureService`**

Add to the import section:

```java
import com.datagami.rentaxis.api.dto.FeatureToggleDTO;
import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import java.util.List;
```

Update the constructor to inject `TenantFeatureService`:

```java
private final LandlordOrgService service;
private final TenantFeatureService tenantFeatureService;

public LandlordOrgController(LandlordOrgService service, TenantFeatureService tenantFeatureService) {
    this.service = service;
    this.tenantFeatureService = tenantFeatureService;
}
```

**Step 2: Add the two new endpoints at the end of the class (before the closing `}`)**

```java
@GetMapping("/{id}/features")
public ResponseEntity<List<FeatureToggleDTO>> getFeatures(@PathVariable UUID id) {
    return ResponseEntity.ok(tenantFeatureService.getAll(id));
}

@PutMapping("/{id}/features/{feature}")
public ResponseEntity<Void> setFeature(
        @PathVariable UUID id,
        @PathVariable TenantFeature feature,
        @RequestBody Map<String, Boolean> body) {
    Boolean enabled = body.get("enabled");
    if (enabled == null) {
        return ResponseEntity.badRequest().build();
    }
    tenantFeatureService.setEnabled(id, feature, enabled);
    return ResponseEntity.ok().build();
}
```

**Step 3: Compile check**

```bash
cd backend && ./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

**Step 4: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/LandlordOrgController.java
git commit -m "feat: add GET/PUT /api/admin/tenants/{id}/features endpoints"
```

---

## Task 7: Wire `TenantFeatureService` into `UnitListingController`

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/UnitListingController.java`

**Step 1: Remove `FeatureFlags` and inject `TenantFeatureService`**

Find and remove:
```java
import com.datagami.rentaxis.config.FeatureFlags;
```

Add:
```java
import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
```

In the class fields, replace:
```java
private final FeatureFlags featureFlags;
```
with:
```java
private final TenantFeatureService tenantFeatureService;
```

Update the constructor accordingly (remove `FeatureFlags featureFlags` parameter, add `TenantFeatureService tenantFeatureService`).

**Step 2: Replace `checkEnabled()` implementation**

Find the existing private method `checkEnabled()` and replace its body:

```java
private void checkEnabled() {
    UUID tenantId = TenantContextHolder.getTenantId();
    if (!tenantFeatureService.isEnabled(tenantId, TenantFeature.LISTINGS)) {
        throw new NotFoundException("Listings feature is disabled");
    }
}
```

**Step 3: Compile check**

```bash
cd backend && ./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

**Step 4: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/UnitListingController.java
git commit -m "feat: UnitListingController uses per-tenant TenantFeatureService"
```

---

## Task 8: Wire `TenantFeatureService` into `MarketplaceController`

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/MarketplaceController.java`

**Step 1: Remove `FeatureFlags` and inject `TenantFeatureService`**

Remove import:
```java
import com.datagami.rentaxis.config.FeatureFlags;
```

Add imports:
```java
import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
```

Replace the `featureFlags` field with `tenantFeatureService`. Update the constructor accordingly.

**Step 2: Replace `checkEnabled()` implementation**

`MarketplaceController` is `@PreAuthorize("hasRole('RENTER')")` — the tenant context is set per request. The renter's JWT includes tenant context via `TenantAspect`, so `TenantContextHolder.getTenantId()` is available.

```java
private void checkEnabled() {
    UUID tenantId = TenantContextHolder.getTenantId();
    if (!tenantFeatureService.isEnabled(tenantId, TenantFeature.LISTINGS)) {
        throw new NotFoundException("Listings feature is disabled");
    }
}
```

Add import if not already present:
```java
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
```

**Step 3: Compile check**

```bash
cd backend && ./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

**Step 4: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/MarketplaceController.java
git commit -m "feat: MarketplaceController uses per-tenant TenantFeatureService"
```

---

## Task 9: Wire `TenantFeatureService` into `PublicListingController`

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/PublicListingController.java`

**Step 1: Remove `FeatureFlags` and inject `TenantFeatureService`**

Remove import:
```java
import com.datagami.rentaxis.config.FeatureFlags;
```

Add imports:
```java
import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
```

Replace the `featureFlags` field with `tenantFeatureService`. Update the constructor.

**Step 2: Change `checkEnabled()` to accept `tenantId`**

`PublicListingController` is unauthenticated — there is no `TenantContextHolder`. The tenant is resolved from the slug. Update the private method:

```java
private void checkEnabled(UUID tenantId) {
    if (!tenantFeatureService.isEnabled(tenantId, TenantFeature.LISTINGS)) {
        throw new NotFoundException("Listings feature is disabled");
    }
}
```

**Step 3: Update all call sites to pass tenantId**

In `listPublicListings()`:
```java
UUID tenantId = marketplaceService.resolveTenantSlug(tenantSlug);
checkEnabled(tenantId);   // moved AFTER resolving tenantId
```

In `getPublicListing()`:
```java
UnitListing listing = marketplaceService.resolveByTenantSlugAndUnitSlug(tenantSlug, unitSlug);
checkEnabled(listing.getTenantId());
```

In `sitemap()`:
```java
UUID tenantId = marketplaceService.resolveTenantSlug(tenantSlug);
checkEnabled(tenantId);
```

Note: The existing `checkEnabled()` call at the top of each method called `resolveTenantSlug` after — you're now calling `checkEnabled(tenantId)` after resolving. This means an invalid slug returns 404 (from `resolveTenantSlug`) rather than the feature-disabled error, which is the correct behavior.

**Step 4: Compile check**

```bash
cd backend && ./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

**Step 5: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/PublicListingController.java
git commit -m "feat: PublicListingController uses per-tenant TenantFeatureService"
```

---

## Task 10: Delete `FeatureFlags.java` and clean up config

**Files:**
- Delete: `backend/src/main/java/com/datagami/rentaxis/config/FeatureFlags.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `.github/workflows/deploy.yml`
- Modify: `docker-compose.prod.yml`

**Step 1: Delete `FeatureFlags.java`**

```bash
rm backend/src/main/java/com/datagami/rentaxis/config/FeatureFlags.java
```

**Step 2: Remove from `application.yml`**

Find and remove the entire `features:` block under `rentaxis:`:

```yaml
  features:
    listings-enabled: ${FEATURE_LISTINGS_ENABLED:false}
```

**Step 3: Remove from `deploy.yml`**

Find the line `FEATURE_LISTINGS_ENABLED=true` in the `.env` file creation step and remove it.

**Step 4: Remove from `docker-compose.prod.yml`**

Find and remove the line:
```yaml
- FEATURE_LISTINGS_ENABLED=${FEATURE_LISTINGS_ENABLED:-false}
```
from the backend container's `environment:` block.

**Step 5: Compile check**

```bash
cd backend && ./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL` (no remaining references to `FeatureFlags`)

**Step 6: Grep check — ensure no dangling references**

```bash
grep -r "FeatureFlags\|FEATURE_LISTINGS_ENABLED\|listings-enabled" \
  backend/src web/ .github/ docker-compose.prod.yml 2>/dev/null
```

Expected: zero matches.

**Step 7: Commit**

```bash
git add -A
git commit -m "feat: remove FeatureFlags — replaced by TenantFeatureService"
```

---

## Task 11: Run full backend test suite

**Step 1: Run all tests**

```bash
cd backend && ./gradlew test
```

Expected: `BUILD SUCCESSFUL` with all tests passing.

**Step 2: If any test imports `FeatureFlags` or references `featureFlags`, update it**

Search for any test that still uses `FeatureFlags`:

```bash
grep -r "FeatureFlags\|featureFlags\|listingsEnabled" backend/src/test/
```

For each such test, replace the injection with `TenantFeatureService` (mocked) and adjust the stubbing accordingly. Typical pattern:

```java
// Old
@Mock FeatureFlags featureFlags;
when(featureFlags.isListingsEnabled()).thenReturn(true);

// New
@Mock TenantFeatureService tenantFeatureService;
when(tenantFeatureService.isEnabled(any(UUID.class), eq(TenantFeature.LISTINGS))).thenReturn(true);
```

**Step 3: Commit any test fixes**

```bash
git add backend/src/test/
git commit -m "test: update mocks from FeatureFlags to TenantFeatureService"
```

---

## Task 12: Superadmin UI — Features drawer in tenants page

**Files:**
- Modify: `web/src/app/[locale]/superadmin/tenants/page.tsx`

**Step 1: Add types at the top of the file**

After the existing `type Tenant = ...` definition, add:

```ts
type FeatureToggle = {
  feature: string;
  label: string;
  defaultEnabled: boolean;
  enabled: boolean;
};
```

**Step 2: Add state for the features drawer**

In the component body, after the existing state declarations, add:

```ts
const [featuresDrawerTenant, setFeaturesDrawerTenant] = useState<Tenant | null>(null);
const [features, setFeatures] = useState<FeatureToggle[]>([]);
const [featuresLoading, setFeaturesLoading] = useState(false);
const [featuresUpdating, setFeaturesUpdating] = useState<string | null>(null);
```

**Step 3: Add helper functions**

```ts
const openFeaturesDrawer = async (tenant: Tenant) => {
    setFeaturesDrawerTenant(tenant);
    setFeaturesLoading(true);
    try {
        const res = await fetch(`/api/proxy/admin/tenants/${tenant.id}/features`);
        if (res.ok) setFeatures(await res.json());
    } finally {
        setFeaturesLoading(false);
    }
};

const toggleFeature = async (feature: string, enabled: boolean) => {
    if (!featuresDrawerTenant) return;
    // Optimistic update
    setFeatures(prev => prev.map(f => f.feature === feature ? { ...f, enabled } : f));
    setFeaturesUpdating(feature);
    try {
        const res = await fetch(
            `/api/proxy/admin/tenants/${featuresDrawerTenant.id}/features/${feature}`,
            { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ enabled }) }
        );
        if (!res.ok) {
            // Revert on error
            setFeatures(prev => prev.map(f => f.feature === feature ? { ...f, enabled: !enabled } : f));
        }
    } catch {
        setFeatures(prev => prev.map(f => f.feature === feature ? { ...f, enabled: !enabled } : f));
    } finally {
        setFeaturesUpdating(null);
    }
};
```

**Step 4: Add `Zap` import to the lucide-react import line**

The existing import line includes icons like `Settings2`. Add `Zap` to it:

```ts
import { Plus, X, Building2, Hash, Settings2, ShieldCheck, Loader2, Search, Pencil, Copy, Check, Zap } from "lucide-react";
```

**Step 5: Add a "Features" button to each tenant row**

Find the place where tenant action buttons are rendered (look for the `Pencil` icon button or the copy button). Add a Features button next to them:

```tsx
<button
  onClick={() => openFeaturesDrawer(tenant)}
  title="Feature Toggles"
  className="p-1.5 rounded hover:bg-neutral-100 text-neutral-500 hover:text-blue-600 transition-colors cursor-pointer"
>
  <Zap size={15} />
</button>
```

**Step 6: Add the features drawer JSX**

At the end of the component return (just before the last `</div>`), add the drawer:

```tsx
{/* Features Drawer */}
{featuresDrawerTenant && (
  <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
    <div className="bg-white rounded-xl shadow-xl w-full max-w-md mx-4">
      <div className="flex items-center justify-between px-5 py-4 border-b border-neutral-100">
        <div>
          <h2 className="font-semibold text-neutral-900 text-sm">Feature Toggles</h2>
          <p className="text-xs text-neutral-500 mt-0.5">{featuresDrawerTenant.name}</p>
        </div>
        <button
          onClick={() => setFeaturesDrawerTenant(null)}
          className="p-1.5 rounded hover:bg-neutral-100 text-neutral-500 cursor-pointer"
        >
          <X size={16} />
        </button>
      </div>
      <div className="p-5">
        {featuresLoading ? (
          <div className="flex items-center justify-center py-8 text-neutral-400">
            <Loader2 size={20} className="animate-spin mr-2" /> Loading…
          </div>
        ) : features.length === 0 ? (
          <p className="text-sm text-neutral-500 text-center py-6">No features available.</p>
        ) : (
          <div className="space-y-4">
            {features.map(f => (
              <div key={f.feature} className="flex items-center justify-between gap-4">
                <div>
                  <p className="text-sm font-medium text-neutral-800">{f.label}</p>
                  <p className="text-xs text-neutral-400 mt-0.5">
                    Default: {f.defaultEnabled ? "On" : "Off"}
                  </p>
                </div>
                <button
                  disabled={featuresUpdating === f.feature}
                  onClick={() => toggleFeature(f.feature, !f.enabled)}
                  className={cn(
                    "relative w-11 h-6 rounded-full transition-colors cursor-pointer flex-shrink-0 disabled:opacity-60",
                    f.enabled ? "bg-blue-600" : "bg-neutral-200"
                  )}
                >
                  <span className={cn(
                    "absolute top-0.5 left-0.5 w-5 h-5 bg-white rounded-full shadow transition-transform",
                    f.enabled ? "translate-x-5" : "translate-x-0"
                  )} />
                </button>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  </div>
)}
```

**Step 7: TypeScript check**

```bash
cd web && npx tsc --noEmit
```

Expected: no errors.

**Step 8: Commit**

```bash
git add web/src/app/\[locale\]/superadmin/tenants/page.tsx
git commit -m "feat: add feature toggles drawer to superadmin tenants page"
```

---

## Task 13: Final integration smoke test and PR

**Step 1: Start the stack**

```bash
docker compose up -d
```

**Step 2: Verify migration ran**

```bash
docker compose exec db psql -U postgres -d rentaxis -c "\d tenant_feature"
```

Expected: table with columns `id, tenant_id, feature, enabled, updated_at`.

**Step 3: Verify LISTINGS defaults to disabled**

As SUPER_ADMIN, create or pick a tenant, then:

```bash
curl -s -H "Authorization: Bearer <superadmin_token>" \
  http://localhost:8080/api/admin/tenants/<tenant_id>/features
```

Expected response:
```json
[{"feature":"LISTINGS","label":"Listings (Marketplace)","defaultEnabled":false,"enabled":false}]
```

**Step 4: Enable LISTINGS for a tenant**

```bash
curl -s -X PUT \
  -H "Authorization: Bearer <superadmin_token>" \
  -H "Content-Type: application/json" \
  -d '{"enabled":true}' \
  http://localhost:8080/api/admin/tenants/<tenant_id>/features/LISTINGS
```

Expected: `200 OK`

**Step 5: Verify listing endpoints now work for that tenant**

```bash
curl -s http://localhost:8080/public/l/<tenant_slug>
```

Expected: `200 OK` with listings JSON.

**Step 6: Push and create PR**

```bash
git push -u origin <branch-name>
gh pr create \
  --title "feat: per-tenant feature toggles — replace global FEATURE_LISTINGS_ENABLED" \
  --body "$(cat <<'EOF'
## Summary
- Adds `tenant_feature` DB table (migration 38) with sparse per-tenant feature rows
- `TenantFeature` enum with `LISTINGS(false)` default
- `TenantFeatureService` with Caffeine cache (30-min TTL, 500-entry max, invalidate on write)
- `UnitListingController`, `MarketplaceController`, `PublicListingController` — replaced `FeatureFlags` with `TenantFeatureService`
- Admin API: `GET/PUT /api/admin/tenants/{id}/features[/{feature}]` (SUPER_ADMIN only)
- Superadmin UI: feature toggles drawer per tenant row
- Removed `FeatureFlags.java` and all `FEATURE_LISTINGS_ENABLED` env var references

## Test Plan
- [ ] Migration 38 applies cleanly on fresh DB
- [ ] LISTINGS is disabled by default (no DB row)
- [ ] SUPER_ADMIN can enable LISTINGS for a specific tenant via API
- [ ] Cache invalidates on toggle — subsequent request reflects new value within same second
- [ ] Other tenants remain unaffected when one is toggled
- [ ] Superadmin UI drawer shows correct toggle state and persists changes
- [ ] All backend tests pass

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```
