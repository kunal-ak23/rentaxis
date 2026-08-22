# Promotions — Backend & Admin Panel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the backend and web admin panel that let a tenant admin configure ~40 cross-promoted businesses and their ads, and serve a fair, rotating six-ad slate to the renter mobile app.

**Architecture:** Four new tenant-scoped tables (`promo_business`, `promo_ad`, `promo_ad_property`, `promo_ad_event`). Two controllers — an admin CRUD surface and a renter feed surface — over three services. The renter slate is chosen server-side by weighted sampling seeded on `(adId, renterId, dateInDubai)`, so it is stable for a day and fair over a month. Ad click-through URLs are validated against the owning business's domain allowlist at write time, so the mobile client can trust what it receives.

**Tech Stack:** Java 21, Spring Boot 4.0.3, Spring Data JPA, Liquibase, PostgreSQL 16, JUnit 5 + Mockito + AssertJ. Web: Next.js 16, TypeScript 5, Tailwind 4, next-intl, Vitest.

**Spec:** `docs/superpowers/specs/2026-08-23-promotions-ads-carousel-design.md`

**Branch:** `feat/promotions` (already created)

---

## File Structure

**Backend — create:**

| File | Responsibility |
|---|---|
| `db/changelog/changesets/71-promotions.yaml` | All four tables, indexes, constraints |
| `domain/entity/enums/PromoCategory.java` | Business category |
| `domain/entity/enums/PromoCtaType.java` | What a tap does |
| `domain/entity/enums/PromoPlacement.java` | Home + offers, or offers only |
| `domain/entity/enums/PromoEventType.java` | Impression / click |
| `domain/entity/PromoBusiness.java` | Business row |
| `domain/entity/PromoAd.java` | Ad row |
| `domain/entity/PromoAdProperty.java` | Targeting join row |
| `domain/entity/PromoAdEvent.java` | Analytics event row |
| `domain/repository/PromoBusinessRepository.java` | Business queries |
| `domain/repository/PromoAdRepository.java` | Ad queries incl. the eligibility scan |
| `domain/repository/PromoAdPropertyRepository.java` | Targeting rows |
| `domain/repository/PromoAdEventRepository.java` | Event insert + aggregates |
| `core/service/PromotionUrlValidator.java` | Host-against-allowlist check, standalone and unit-testable |
| `core/service/PromotionSlate.java` | The pure rotation algorithm, no Spring, no DB |
| `core/service/PromotionService.java` | Business + ad CRUD, write-time validation |
| `core/service/PromotionFeedService.java` | Eligibility, slate assembly, event ingest |
| `core/service/PromotionStatsService.java` | Per-ad aggregates |
| `api/PromotionAdminController.java` | Admin CRUD, RBAC |
| `api/PromotionFeedController.java` | Renter feed, offers, events |
| `api/dto/Promo*.java` | 8 records (listed per task) |

`PromotionUrlValidator` and `PromotionSlate` are deliberately split out of the services: both hold the logic most likely to be wrong, and both are far cheaper to test as plain classes than through a Spring context.

**Web — create:**

| File | Responsibility |
|---|---|
| `web/src/types/promotion.ts` | DTO types mirroring the backend records |
| `web/src/lib/api/promotions.ts` | Typed fetch helpers over the proxy |
| `web/src/app/[locale]/dashboard/promotions/page.tsx` | Tab shell |
| `.../promotions/_components/BusinessesTab.tsx` | Business table + editor |
| `.../promotions/_components/BusinessEditor.tsx` | Business form drawer |
| `.../promotions/_components/AdsTab.tsx` | Ad table |
| `.../promotions/_components/AdEditor.tsx` | Ad form + live preview |
| `.../promotions/_components/AdCardPreview.tsx` | Faithful render of the mobile card |
| `.../promotions/__tests__/AdEditor.test.tsx` | CTA switching + allowlist feedback |

**Web — modify:** `web/src/lib/rbac.ts` (add `canManagePromotions`), `web/src/components/ui/MvpSidebar.tsx` (nav entry), `web/messages/en.json` and `web/messages/ar.json` (the `Promotions.*` namespace).

---

## Task 1: Database schema

**Files:**
- Create: `backend/src/main/resources/db/changelog/changesets/71-promotions.yaml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml` (append the include)

Liquibase changesets are append-only in this repo — never edit 01–70.

- [ ] **Step 1: Write the changeset**

Create `backend/src/main/resources/db/changelog/changesets/71-promotions.yaml`:

```yaml
databaseChangeLog:
  - changeSet:
      id: 71-promotions
      author: rentaxis-system
      changes:
        # ---- promo_businesses: one row per client-owned business ----
        # allowed_domains is the click-through allowlist, stored as a lowercase
        # comma-separated hostname list. It stays a column rather than a child
        # table because it is read only on admin writes, never on the renter
        # hot path, and a business has a handful of domains at most.
        # Businesses are soft-deactivated, never hard-deleted, so ad history
        # and event counts survive.
        - createTable:
            tableName: promo_businesses
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: name_en, type: varchar(160), constraints: { nullable: false } }
              - column: { name: name_ar, type: varchar(160) }
              - column: { name: logo_url, type: varchar(512) }
              - column: { name: category, type: varchar(32), constraints: { nullable: false }, defaultValue: OTHER }
              - column: { name: phone_e164, type: varchar(20) }
              - column: { name: whatsapp_e164, type: varchar(20) }
              - column: { name: allowed_domains, type: text }
              - column: { name: active, type: boolean, constraints: { nullable: false }, defaultValueBoolean: true }
              - column: { name: created_at, type: timestamptz, constraints: { nullable: false }, defaultValueComputed: now() }
              - column: { name: updated_at, type: timestamptz, constraints: { nullable: false }, defaultValueComputed: now() }
        - createIndex: { tableName: promo_businesses, indexName: idx_promo_businesses_tenant, columns: [ { column: { name: tenant_id } } ] }
        - sql:
            comment: Case-insensitive name uniqueness per tenant; expression index, so addUniqueConstraint cannot express it.
            sql: CREATE UNIQUE INDEX uq_promo_business_name ON promo_businesses (tenant_id, lower(name_en));

        # ---- promo_ads: one offer, belonging to a business ----
        # fk_promo_ad_business RESTRICTs: a business with ads must be
        # deactivated, not deleted (same reasoning as fk_amenity_property in 69).
        - createTable:
            tableName: promo_ads
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: business_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_promo_ad_business, referencedTableName: promo_businesses, referencedColumnNames: id } }
              - column: { name: title_en, type: varchar(120) }
              - column: { name: title_ar, type: varchar(120) }
              - column: { name: subtitle_en, type: varchar(160) }
              - column: { name: subtitle_ar, type: varchar(160) }
              - column: { name: background_image_url, type: varchar(512) }
              - column: { name: accent_color, type: varchar(9) }
              - column: { name: cta_type, type: varchar(16), constraints: { nullable: false }, defaultValue: NONE }
              - column: { name: cta_label_en, type: varchar(40) }
              - column: { name: cta_label_ar, type: varchar(40) }
              - column: { name: cta_url, type: varchar(1024) }
              - column: { name: coupon_code, type: varchar(64) }
              - column: { name: coupon_terms_en, type: text }
              - column: { name: coupon_terms_ar, type: text }
              - column: { name: starts_at, type: timestamptz }
              - column: { name: ends_at, type: timestamptz }
              - column: { name: priority, type: int, constraints: { nullable: false }, defaultValueNumeric: 1 }
              - column: { name: placement, type: varchar(20), constraints: { nullable: false }, defaultValue: HOME_AND_OFFERS }
              - column: { name: active, type: boolean, constraints: { nullable: false }, defaultValueBoolean: true }
              - column: { name: created_at, type: timestamptz, constraints: { nullable: false }, defaultValueComputed: now() }
              - column: { name: updated_at, type: timestamptz, constraints: { nullable: false }, defaultValueComputed: now() }
        # Deliberately just (tenant_id, active). The eligibility query also
        # range-filters starts_at/ends_at, but a btree can only narrow on one
        # range predicate after the equality prefix, and both bounds are
        # nullable and matched with `OR IS NULL` — trailing them here would be
        # residual filtering, not scan narrowing. At ~40 ads per tenant the
        # equality prefix is the whole win.
        - createIndex:
            tableName: promo_ads
            indexName: idx_promo_ads_eligibility
            columns:
              - column: { name: tenant_id }
              - column: { name: active }
        - createIndex: { tableName: promo_ads, indexName: idx_promo_ads_business, columns: [ { column: { name: business_id } } ] }
        - sql:
            comment: priority is a relative airtime weight; 0 or negative would divide by zero in the slate's -ln(u)/priority key.
            sql: ALTER TABLE promo_ads ADD CONSTRAINT ck_promo_ad_priority CHECK (priority BETWEEN 1 AND 10);
        - sql:
            comment: At least one language must carry a title, or the card renders blank.
            sql: ALTER TABLE promo_ads ADD CONSTRAINT ck_promo_ad_title CHECK (title_en IS NOT NULL OR title_ar IS NOT NULL);

        # ---- promo_ad_properties: zero rows for an ad = targets every property ----
        # Pure join rows, cascade both ways, surrogate id + unique constraint
        # on the pair — same shape as amenity_building_scopes in changeset 69.
        - createTable:
            tableName: promo_ad_properties
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: ad_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_pap_ad, referencedTableName: promo_ads, referencedColumnNames: id, deleteCascade: true } }
              - column: { name: property_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_pap_property, referencedTableName: properties, referencedColumnNames: id, deleteCascade: true } }
              - column: { name: created_at, type: timestamptz, constraints: { nullable: false }, defaultValueComputed: now() }
        - addUniqueConstraint: { tableName: promo_ad_properties, columnNames: "ad_id, property_id", constraintName: uq_promo_ad_property }
        - createIndex: { tableName: promo_ad_properties, indexName: idx_pap_property, columns: [ { column: { name: property_id } } ] }

        # ---- promo_ad_events: impressions and clicks ----
        # `day` is the Asia/Dubai calendar day, written by the service, not
        # derived in SQL — the DB's timezone is not the product's timezone.
        # fk_pae_ad RESTRICTs rather than cascading: an ad's impression and
        # click history is the whole point of the analytics, so an ad that has
        # run must be deactivated, not deleted (PromotionService.deleteAd
        # enforces the same rule with a 409, mirroring deleteBusiness).
        - createTable:
            tableName: promo_ad_events
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: ad_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_pae_ad, referencedTableName: promo_ads, referencedColumnNames: id } }
              - column: { name: renter_user_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_pae_user, referencedTableName: users, referencedColumnNames: id } }
              - column: { name: event_type, type: varchar(12), constraints: { nullable: false } }
              - column: { name: occurred_at, type: timestamptz, constraints: { nullable: false }, defaultValueComputed: now() }
              - column: { name: day, type: date, constraints: { nullable: false } }
        - createIndex: { tableName: promo_ad_events, indexName: idx_pae_ad_day, columns: [ { column: { name: ad_id } }, { column: { name: day } } ] }
        - sql:
            comment: One impression per ad, renter and day. Clicks are excluded from the constraint so repeat taps all count.
            sql: CREATE UNIQUE INDEX uq_promo_impression_per_day ON promo_ad_events (ad_id, renter_user_id, day) WHERE event_type = 'IMPRESSION';
        - sql:
            comment: Backstops the application's Asia/Dubai conversion. If that ever drifts, the dedupe index would silently double-count or silently block a legitimate impression. timezone(text, timestamptz) is IMMUTABLE, so it is legal in a CHECK.
            sql: ALTER TABLE promo_ad_events ADD CONSTRAINT ck_promo_ad_event_day CHECK (day = (occurred_at AT TIME ZONE 'Asia/Dubai')::date);
```

- [ ] **Step 2: Register the changeset**

Append to `backend/src/main/resources/db/changelog/db.changelog-master.yaml`, after the `70-reset-stale-occupied-units.yaml` include:

```yaml
  - include:
      file: db/changelog/changesets/71-promotions.yaml
```

- [ ] **Step 3: Verify the migration applies**

Run:

```bash
cd backend && ./gradlew bootRun --args='--spring.profiles.active=local' 2>&1 | grep -i "71-promotions\|ChangeSet.*ran successfully\|liquibase.*ERROR" | head -20
```

Expected: a line showing `71-promotions` ran. Stop the app once you see it. If Postgres is not up, start it first with `docker compose -f docker-compose.db.yml up -d postgres` (the DB lives in its own compose file).

- [ ] **Step 4: Confirm the tables and the partial index exist**

Run:

```bash
docker compose -f docker-compose.db.yml exec -T postgres psql -U rentaxis -d rentaxis -c "\d promo_ad_events" -c "\di uq_promo_impression_per_day"
```

Expected: the table definition, and one index row for `uq_promo_impression_per_day`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/changelog/
git commit -m "feat(promotions): schema for businesses, ads, targeting and events"
```

---

## Task 2: Enums and entities

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/{PromoCategory,PromoCtaType,PromoPlacement,PromoEventType}.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/{PromoBusiness,PromoAd,PromoAdProperty,PromoAdEvent}.java`

No tests in this task — these are data holders with no behaviour. Task 3 onward tests them through the code that uses them.

- [ ] **Step 1: Write the four enums**

`domain/entity/enums/PromoCategory.java`:

```java
package com.datagami.rentaxis.domain.entity.enums;

public enum PromoCategory {
    DINING, FITNESS, RETAIL, SERVICES, HEALTH, EDUCATION, OTHER
}
```

`domain/entity/enums/PromoCtaType.java`:

```java
package com.datagami.rentaxis.domain.entity.enums;

/** What tapping an ad card does in the renter app. */
public enum PromoCtaType {
    WEBSITE, COUPON, CALL, WHATSAPP, NONE
}
```

`domain/entity/enums/PromoPlacement.java`:

```java
package com.datagami.rentaxis.domain.entity.enums;

public enum PromoPlacement {
    HOME_AND_OFFERS, OFFERS_ONLY
}
```

`domain/entity/enums/PromoEventType.java`:

```java
package com.datagami.rentaxis.domain.entity.enums;

public enum PromoEventType {
    IMPRESSION, CLICK
}
```

- [ ] **Step 2: Write `PromoBusiness`**

`domain/entity/PromoBusiness.java`:

```java
package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.PromoCategory;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A business the client owns and cross-promotes in the renter app.
 * {@code allowedDomains} is the click-through allowlist — a lowercase,
 * comma-separated hostname list checked by
 * {@link com.datagami.rentaxis.core.service.PromotionUrlValidator} before any
 * ad URL is accepted.
 */
@Entity
@Table(name = "promo_businesses")
@Getter
@Setter
public class PromoBusiness extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name_en", nullable = false, length = 160)
    private String nameEn;

    @Column(name = "name_ar", length = 160)
    private String nameAr;

    @Column(name = "logo_url", length = 512)
    private String logoUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PromoCategory category = PromoCategory.OTHER;

    @Column(name = "phone_e164", length = 20)
    private String phoneE164;

    @Column(name = "whatsapp_e164", length = 20)
    private String whatsappE164;

    @Column(name = "allowed_domains", columnDefinition = "text")
    private String allowedDomains;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void onPreUpdate() {
        this.updatedAt = Instant.now();
    }
}
```

- [ ] **Step 3: Write `PromoAd`**

`domain/entity/PromoAd.java`:

```java
package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.PromoCtaType;
import com.datagami.rentaxis.domain.entity.enums.PromoPlacement;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One offer. A business may run several. At least one of {@code titleEn} /
 * {@code titleAr} is non-null (DB CHECK enforces it). A null window bound
 * means unbounded on that side.
 */
@Entity
@Table(name = "promo_ads")
@Getter
@Setter
public class PromoAd extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "title_en", length = 120)
    private String titleEn;

    @Column(name = "title_ar", length = 120)
    private String titleAr;

    @Column(name = "subtitle_en", length = 160)
    private String subtitleEn;

    @Column(name = "subtitle_ar", length = 160)
    private String subtitleAr;

    @Column(name = "background_image_url", length = 512)
    private String backgroundImageUrl;

    @Column(name = "accent_color", length = 9)
    private String accentColor;

    @Enumerated(EnumType.STRING)
    @Column(name = "cta_type", nullable = false, length = 16)
    private PromoCtaType ctaType = PromoCtaType.NONE;

    @Column(name = "cta_label_en", length = 40)
    private String ctaLabelEn;

    @Column(name = "cta_label_ar", length = 40)
    private String ctaLabelAr;

    @Column(name = "cta_url", length = 1024)
    private String ctaUrl;

    @Column(name = "coupon_code", length = 64)
    private String couponCode;

    @Column(name = "coupon_terms_en", columnDefinition = "text")
    private String couponTermsEn;

    @Column(name = "coupon_terms_ar", columnDefinition = "text")
    private String couponTermsAr;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(nullable = false)
    private int priority = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PromoPlacement placement = PromoPlacement.HOME_AND_OFFERS;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void onPreUpdate() {
        this.updatedAt = Instant.now();
    }
}
```

- [ ] **Step 4: Write `PromoAdProperty`**

`domain/entity/PromoAdProperty.java`:

```java
package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Targeting join. An ad with zero rows here targets every property — that is
 * the default and the common case, so no rows are written unless the client
 * narrows an ad.
 *
 * <p>Surrogate id plus a unique constraint on the pair, matching
 * {@link AmenityBuildingScope} and the other join tables in this codebase,
 * rather than a composite key — it keeps the repository a plain
 * {@code JpaRepository<..., UUID>}.
 */
@Entity
@Table(name = "promo_ad_properties")
@Getter
@Setter
public class PromoAdProperty extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ad_id", nullable = false)
    private UUID adId;

    @Column(name = "property_id", nullable = false)
    private UUID propertyId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
```

- [ ] **Step 5: Write `PromoAdEvent`**

`domain/entity/PromoAdEvent.java`:

```java
package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.PromoEventType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One impression or click. {@code day} is the Asia/Dubai calendar day, set by
 * the service rather than derived in SQL — the database's timezone is not the
 * product's. A partial unique index on (ad_id, renter_user_id, day) collapses
 * repeat impressions within a day; clicks are excluded from it.
 */
@Entity
@Table(name = "promo_ad_events")
@Getter
@Setter
public class PromoAdEvent extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ad_id", nullable = false)
    private UUID adId;

    @Column(name = "renter_user_id", nullable = false)
    private UUID renterUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 12)
    private PromoEventType eventType;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    @Column(nullable = false)
    private LocalDate day;
}
```

- [ ] **Step 6: Verify it compiles**

Run:

```bash
cd backend && ./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/domain/entity/
git commit -m "feat(promotions): entities and enums"
```

---

## Task 3: URL allowlist validator

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/PromotionUrlValidator.java`
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/PromotionUrlValidatorTest.java`

This is the security boundary for the whole feature: an admin-entered URL that passes here is one the mobile app will open. It is a plain class with no Spring dependencies so it can be tested exhaustively and cheaply.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/datagami/rentaxis/core/service/PromotionUrlValidatorTest.java`:

```java
package com.datagami.rentaxis.core.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromotionUrlValidatorTest {

    private final PromotionUrlValidator validator = new PromotionUrlValidator();

    @Test
    void parseDomains_lowercasesStripsSchemeAndPath() {
        assertThat(validator.parseDomains("HTTPS://Www.Spice-Bazaar.AE/menu, gym.example.com "))
                .containsExactly("www.spice-bazaar.ae", "gym.example.com");
    }

    @Test
    void parseDomains_nullOrBlankYieldsEmpty() {
        assertThat(validator.parseDomains(null)).isEmpty();
        assertThat(validator.parseDomains("   ")).isEmpty();
    }

    @Test
    void isAllowed_exactHostMatches() {
        assertThat(validator.isAllowed("https://spice-bazaar.ae/friday", "spice-bazaar.ae")).isTrue();
    }

    @Test
    void isAllowed_subdomainOfAnAllowedHostMatches() {
        assertThat(validator.isAllowed("https://offers.spice-bazaar.ae/x", "spice-bazaar.ae")).isTrue();
    }

    @Test
    void isAllowed_hostIsCaseInsensitive() {
        assertThat(validator.isAllowed("https://SPICE-BAZAAR.AE/", "spice-bazaar.ae")).isTrue();
    }

    @Test
    void isAllowed_rejectsSuffixLookalikeDomain() {
        // evil-spice-bazaar.ae must not pass because it ends with the allowed host.
        assertThat(validator.isAllowed("https://evil-spice-bazaar.ae/", "spice-bazaar.ae")).isFalse();
    }

    @Test
    void isAllowed_rejectsHostEmbeddedInPathOrUserInfo() {
        assertThat(validator.isAllowed("https://evil.com/spice-bazaar.ae", "spice-bazaar.ae")).isFalse();
        assertThat(validator.isAllowed("https://spice-bazaar.ae@evil.com/", "spice-bazaar.ae")).isFalse();
    }

    @Test
    void isAllowed_rejectsNonHttpsSchemes() {
        assertThat(validator.isAllowed("http://spice-bazaar.ae/", "spice-bazaar.ae")).isFalse();
        assertThat(validator.isAllowed("javascript:alert(1)", "spice-bazaar.ae")).isFalse();
        assertThat(validator.isAllowed("data:text/html;base64,PHNjcmlwdD4=", "spice-bazaar.ae")).isFalse();
        assertThat(validator.isAllowed("file:///etc/passwd", "spice-bazaar.ae")).isFalse();
    }

    @Test
    void isAllowed_rejectsWhenAllowlistIsEmpty() {
        assertThat(validator.isAllowed("https://spice-bazaar.ae/", null)).isFalse();
        assertThat(validator.isAllowed("https://spice-bazaar.ae/", "")).isFalse();
    }

    // ---- the storage seam: parseDomains output feeding isAllowed ----
    // Every test above hands isAllowed a hand-written bare domain. The bug
    // class that actually bit here lives between the two, so these exercise
    // a domain as it would really be stored.

    @Test
    void parseDomains_ignoresAnAtSignInTheQueryString() {
        // Regression: stripping userinfo before cutting the path turned
        // "?email=owner@gmail.com" into an allowlist of gmail.com, which both
        // locked out the real domain and opened up an unrelated one.
        assertThat(validator.parseDomains("https://spice-bazaar.ae/signup?email=owner@gmail.com"))
                .containsExactly("spice-bazaar.ae");
        assertThat(validator.parseDomains("https://spice-bazaar.ae/promo?cb=x@com"))
                .containsExactly("spice-bazaar.ae");
        assertThat(validator.parseDomains("https://spice-bazaar.ae/menu#contact@us"))
                .containsExactly("spice-bazaar.ae");
    }

    @Test
    void parseDomains_keepsTheHostFromARealUserinfoUrl() {
        assertThat(validator.parseDomains("https://user:pw@spice-bazaar.ae/menu"))
                .containsExactly("spice-bazaar.ae");
    }

    @Test
    void parseDomains_stripsPortAndTrailingDot() {
        assertThat(validator.parseDomains("https://spice-bazaar.ae:8443/x"))
                .containsExactly("spice-bazaar.ae");
        assertThat(validator.parseDomains("spice-bazaar.ae.")).containsExactly("spice-bazaar.ae");
    }

    @Test
    void parseDomains_dropsEntriesThatCouldNeverMatchAUrl() {
        // A wildcard is the most likely thing an admin types meaning "and
        // subdomains" — storing it verbatim yields an allowlist that permits
        // nothing, with no feedback anywhere. Dropping it keeps this
        // fail-closed and lets the caller report the entry as rejected.
        assertThat(validator.parseDomains("*.spice-bazaar.ae")).isEmpty();
        assertThat(validator.parseDomains(".ae")).isEmpty();
        assertThat(validator.parseDomains("spice-bazaar..ae")).isEmpty();
        assertThat(validator.parseDomains("not a domain")).isEmpty();
        assertThat(validator.parseDomains("localhost")).isEmpty();
    }

    @Test
    void roundTrip_aStoredDomainAlwaysAllowsItsOwnApexAndSubdomains() {
        String stored = String.join(",",
                validator.parseDomains("https://spice-bazaar.ae/menu?ref=a@b.com"));

        assertThat(validator.isAllowed("https://spice-bazaar.ae/", stored)).isTrue();
        assertThat(validator.isAllowed("https://offers.spice-bazaar.ae/", stored)).isTrue();
        assertThat(validator.isAllowed("https://b.com/", stored)).isFalse();
        assertThat(validator.isAllowed("https://evil.com/", stored)).isFalse();
    }

    @Test
    void isAllowed_rejectsUserinfoEvenOnAnAllowedHost() {
        // Navigates to the allowed host, but reads as the bank in an in-app
        // browser's minimal URL chrome, and can trigger a basic-auth prompt.
        assertThat(validator.isAllowed(
                "https://secure-login.my-bank.com@spice-bazaar.ae/pay", "spice-bazaar.ae"))
                .isFalse();
    }

    @Test
    void isAllowed_rejectsHostConfusionVariants() {
        // A single trailing dot is the FQDN form of the same host, and is
        // normalised on both sides, so this is allowed. A doubled or empty
        // label is not a host at all.
        assertThat(validator.isAllowed("https://spice-bazaar.ae./", "spice-bazaar.ae")).isTrue();
        assertThat(validator.isAllowed("https://spice-bazaar..ae/", "spice-bazaar.ae")).isFalse();
        assertThat(validator.isAllowed("https:/\\evil.com", "spice-bazaar.ae")).isFalse();
        assertThat(validator.isAllowed("//spice-bazaar.ae/", "spice-bazaar.ae")).isFalse();
    }

    @Test
    void isAllowed_rejectsMalformedUrls() {
        assertThat(validator.isAllowed("not a url", "spice-bazaar.ae")).isFalse();
        assertThat(validator.isAllowed(null, "spice-bazaar.ae")).isFalse();
        assertThat(validator.isAllowed("https://", "spice-bazaar.ae")).isFalse();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.PromotionUrlValidatorTest'
```

Expected: FAIL — compilation error, `PromotionUrlValidator` does not exist.

- [ ] **Step 3: Write the implementation**

Create `backend/src/main/java/com/datagami/rentaxis/core/service/PromotionUrlValidator.java`:

```java
package com.datagami.rentaxis.core.service;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The security boundary for ad click-throughs. An admin-entered URL that
 * passes here is one the renter mobile app will open, so this rejects by
 * default: https only, host must be an allowlisted hostname or a subdomain of
 * one, and anything unparseable is refused rather than guessed at.
 *
 * <p>Subdomain matching compares label boundaries, not string suffixes —
 * {@code evil-spice-bazaar.ae} ends with {@code spice-bazaar.ae} but is a
 * different domain and must not pass.
 *
 * <p><b>Known limitation:</b> internationalised (non-ASCII) domains are not
 * supported. {@code URI.getHost()} returns null for them, so an Arabic-script
 * domain entered in the admin panel is dropped by {@link #parseDomains} and
 * can never match. This is fail-closed, not a hole — a punycode host is a
 * distinct ASCII string that cannot collide with an allowlisted one, so
 * homograph attacks are impossible. If Arabic-script domains are ever needed,
 * run both sides through {@link java.net.IDN#toASCII} and compare punycode.
 */
@Component
public class PromotionUrlValidator {

    /**
     * A hostname as {@link URI#getHost()} would return it: dot-separated
     * alphanumeric-or-hyphen labels, at least two of them. Anything else an
     * admin types — a wildcard, a bare TLD, a stray word — could never match
     * a real URL, so it is dropped here rather than stored as an entry that
     * silently allows nothing.
     */
    private static final Pattern HOSTNAME = Pattern.compile(
            "^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$");

    /** Normalises admin input into bare lowercase hostnames, dropping anything invalid. */
    public List<String> parseDomains(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(this::toHost)
                .filter(HOSTNAME.asMatchPredicate())
                .distinct()
                .toList();
    }

    /**
     * Reduces whatever the admin pasted to a bare hostname.
     *
     * <p>Order matters and is the whole correctness argument. The authority
     * ends at the first {@code /}, {@code ?} or {@code #}, so the path, query
     * and fragment are cut FIRST — a {@code @} inside a query string
     * ({@code ?email=owner@gmail.com}) is not a credential separator, and
     * stripping userinfo before the cut would store {@code gmail.com} as the
     * business's allowlist. Only then is userinfo removed, splitting on the
     * LAST {@code @} because RFC 3986 permits {@code @} inside userinfo and
     * browsers split there too. Port comes off last, and a single trailing
     * dot is normalised away because {@code URI.getHost()} never returns one.
     */
    private String toHost(String value) {
        String s = value.trim().toLowerCase(Locale.ROOT);
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        }
        int cut = s.length();
        for (char c : new char[]{'/', '?', '#'}) {
            int i = s.indexOf(c);
            if (i >= 0 && i < cut) {
                cut = i;
            }
        }
        s = s.substring(0, cut);
        int at = s.lastIndexOf('@');
        if (at >= 0) {
            s = s.substring(at + 1);
        }
        int port = s.indexOf(':');
        if (port >= 0) {
            s = s.substring(0, port);
        }
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    public boolean isAllowed(String url, String allowedDomainsRaw) {
        List<String> allowed = parseDomains(allowedDomainsRaw);
        if (allowed.isEmpty() || url == null || url.isBlank()) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        // Refuse userinfo outright. Nothing legitimate needs it in an ad link,
        // and `https://my-bank.com@spice-bazaar.ae/` reads as the bank in an
        // in-app browser's minimal URL chrome even though it navigates to the
        // allowed host. Checked on the raw authority so an unparsed '@' in a
        // registry-based authority is caught too.
        String authority = uri.getRawAuthority();
        if (authority == null || authority.indexOf('@') >= 0) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        // `host.` is the FQDN form of `host` and a browser treats them the
        // same. toHost strips it when storing, so strip it here too — without
        // this the two sides disagree and a business's own FQDN-form link is
        // refused. Stripping cannot widen anything: a stored entry can never
        // carry a trailing dot, so the "." + d suffix test is unaffected.
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        // Copied to a final local because `h` is reassigned above, and a lambda
        // may only capture an effectively-final variable.
        final String normalizedHost = h;
        return allowed.stream()
                .anyMatch(d -> normalizedHost.equals(d) || normalizedHost.endsWith("." + d));
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.PromotionUrlValidatorTest'
```

Expected: PASS, 17 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/PromotionUrlValidator.java backend/src/test/java/com/datagami/rentaxis/core/service/PromotionUrlValidatorTest.java
git commit -m "feat(promotions): https + domain allowlist validation for ad links"
```

---

## Task 4: The rotation algorithm

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/PromotionSlate.java`
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/PromotionSlateTest.java`

The whole fairness promise of the feature lives in this one function, so it is a pure static utility with no Spring, no clock and no database — everything it needs is passed in.

**How it works.** For each candidate ad, derive a stable 64-bit hash from `(adId, renterId, day)`, map it to a uniform `u` in `(0, 1]`, and score the ad `key = -ln(u) / priority`. Take the ads with the smallest keys. This is the exponential-race method for weighted sampling without replacement: an ad with priority 2 is drawn about twice as often as one with priority 1, and because the hash is deterministic, the same renter gets the same slate all day.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/datagami/rentaxis/core/service/PromotionSlateTest.java`:

```java
package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.service.PromotionSlate.Candidate;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PromotionSlateTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 23);

    private List<Candidate> candidates(int count, int priority) {
        List<Candidate> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // Deterministic ids so a failure is reproducible.
            out.add(new Candidate(new UUID(1000L + i, 7L), priority));
        }
        return out;
    }

    @Test
    void pick_isDeterministicForTheSameRenterAndDay() {
        List<Candidate> pool = candidates(40, 1);
        UUID renter = new UUID(42L, 42L);

        List<UUID> first = PromotionSlate.pick(pool, renter, DAY, 6);
        List<UUID> second = PromotionSlate.pick(pool, renter, DAY, 6);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void pick_returnsAtMostTheRequestedSize() {
        assertThat(PromotionSlate.pick(candidates(40, 1), new UUID(1L, 1L), DAY, 6)).hasSize(6);
    }

    @Test
    void pick_returnsEverythingWhenPoolIsSmallerThanSize() {
        assertThat(PromotionSlate.pick(candidates(3, 1), new UUID(1L, 1L), DAY, 6)).hasSize(3);
    }

    @Test
    void pick_returnsEmptyForAnEmptyPool() {
        assertThat(PromotionSlate.pick(List.of(), new UUID(1L, 1L), DAY, 6)).isEmpty();
    }

    @Test
    void pick_neverRepeatsAnAd() {
        List<UUID> slate = PromotionSlate.pick(candidates(40, 1), new UUID(9L, 9L), DAY, 6);
        assertThat(slate).doesNotHaveDuplicates();
    }

    @Test
    void pick_rotatesAcrossDays() {
        List<Candidate> pool = candidates(40, 1);
        UUID renter = new UUID(42L, 42L);

        List<UUID> monday = PromotionSlate.pick(pool, renter, DAY, 6);
        List<UUID> tuesday = PromotionSlate.pick(pool, renter, DAY.plusDays(1), 6);

        assertThat(monday).isNotEqualTo(tuesday);
    }

    @Test
    void pick_differsBetweenRenters() {
        List<Candidate> pool = candidates(40, 1);

        assertThat(PromotionSlate.pick(pool, new UUID(1L, 1L), DAY, 6))
                .isNotEqualTo(PromotionSlate.pick(pool, new UUID(2L, 2L), DAY, 6));
    }

    @Test
    void pick_givesEveryAdAirtimeOverAMonth() {
        List<Candidate> pool = candidates(40, 1);
        UUID renter = new UUID(7L, 7L);

        List<UUID> seen = new ArrayList<>();
        for (int d = 0; d < 30; d++) {
            seen.addAll(PromotionSlate.pick(pool, renter, DAY.plusDays(d), 6));
        }

        // 30 days x 6 slots over a 40-ad pool: every ad should surface at least once.
        assertThat(seen.stream().distinct().toList()).hasSize(40);
    }

    @Test
    void pick_weightsHigherPriorityHigher() {
        // One heavy ad among 39 light ones, sampled across 2000 renters.
        List<Candidate> pool = new ArrayList<>(candidates(39, 1));
        UUID heavy = new UUID(999L, 999L);
        pool.add(new Candidate(heavy, 10));

        int heavyHits = 0;
        int lightHits = 0;
        UUID firstLight = pool.get(0).adId();
        for (int r = 0; r < 2000; r++) {
            List<UUID> slate = PromotionSlate.pick(pool, new UUID(r, 5L), DAY, 6);
            if (slate.contains(heavy)) heavyHits++;
            if (slate.contains(firstLight)) lightHits++;
        }

        // Priority 10 vs 1: the heavy ad should appear far more often. The bound
        // is loose on purpose — this asserts the weighting works, not an exact rate.
        assertThat(heavyHits).isGreaterThan(lightHits * 3);
    }

    @Test
    void pick_givesEqualPriorityAdsEqualAirtime() {
        // The airtime test above uses sequential ids and a single renter — the
        // most favourable possible input, which even a badly weakened hash
        // passes. This uses random UUIDs across many renters, which is what
        // production looks like, and is the test that catches poor avalanche.
        Random rnd = new Random(2026L);
        List<Candidate> pool = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            pool.add(new Candidate(new UUID(rnd.nextLong(), rnd.nextLong()), 1));
        }

        Map<UUID, Integer> hits = new HashMap<>();
        pool.forEach(c -> hits.put(c.adId(), 0));
        int renters = 20_000;
        for (int r = 0; r < renters; r++) {
            UUID renter = new UUID(rnd.nextLong(), rnd.nextLong());
            for (UUID id : PromotionSlate.pick(pool, renter, DAY, 6)) {
                hits.merge(id, 1, Integer::sum);
            }
        }

        // Fair share is 6/40 = 15%. Binomial se at n=20k is 0.25pp, so with a
        // good hash the worst of 40 ads sits near 14.5%. 13% is ~8 sigma out —
        // only a biased hash lands there.
        double worst = Collections.min(hits.values()) / (double) renters;
        assertThat(worst).isGreaterThan(0.13);
    }

    @Test
    void pick_usesEveryByteOfTheIds() {
        // Guards the "mix folds all eight bytes" invariant directly, which the
        // statistical tests cannot: once the avalanche finalizer is in place it
        // spreads even a badly degraded mix well enough to clear a fairness
        // bound. These ids differ ONLY in bytes that a mix folding just the low
        // byte would discard, so under that mutation every ad hashes to the
        // same seed, every key ties, the stable sort degenerates to encounter
        // order, and every renter sees the same six ads. Deterministic — no
        // statistics, no flakiness.
        List<Candidate> pool = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            pool.add(new Candidate(new UUID(((long) i) << 24, 0L), 1));
        }
        List<UUID> firstSix = pool.subList(0, 6).stream().map(Candidate::adId).toList();

        // Renter ids that also differ only in discarded bytes.
        List<UUID> slateA = PromotionSlate.pick(pool, new UUID(1L << 24, 0L), DAY, 6);
        List<UUID> slateB = PromotionSlate.pick(pool, new UUID(2L << 24, 0L), DAY, 6);

        assertThat(slateA).isNotEqualTo(firstSix);
        assertThat(slateA).isNotEqualTo(slateB);
    }

    @Test
    void pick_doesNotCorrelateConsecutiveDays() {
        // The day is the last value folded, so without a finalizer a one-day
        // step barely moves the seed and the slate stops rotating.
        Random rnd = new Random(99L);
        List<Candidate> pool = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            pool.add(new Candidate(new UUID(rnd.nextLong(), rnd.nextLong()), 1));
        }

        int carried = 0;
        int renters = 2_000;
        for (int r = 0; r < renters; r++) {
            UUID renter = new UUID(rnd.nextLong(), rnd.nextLong());
            List<UUID> today = PromotionSlate.pick(pool, renter, DAY, 6);
            List<UUID> tomorrow = PromotionSlate.pick(pool, renter, DAY.plusDays(1), 6);
            carried += (int) today.stream().filter(tomorrow::contains).count();
        }

        // Independent draws carry 6 * 6/40 = 0.9 slots on average. A correlated
        // hash carries far more. Under 1.5 is healthy.
        assertThat(carried / (double) renters).isLessThan(1.5);
    }

    @Test
    void pick_toleratesNonPositivePriority() {
        // The DB CHECK forbids it, but a bad backfill must not divide by zero.
        List<Candidate> pool = List.of(
                new Candidate(new UUID(1L, 1L), 0),
                new Candidate(new UUID(2L, 2L), -5));

        assertThat(PromotionSlate.pick(pool, new UUID(3L, 3L), DAY, 6)).hasSize(2);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.PromotionSlateTest'
```

Expected: FAIL — compilation error, `PromotionSlate` does not exist.

- [ ] **Step 3: Write the implementation**

Create `backend/src/main/java/com/datagami/rentaxis/core/service/PromotionSlate.java`:

```java
package com.datagami.rentaxis.core.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Picks which ads a given renter sees today.
 *
 * <p>Weighted sampling without replacement by the exponential-race method: each
 * candidate gets {@code key = -ln(u) / priority} where {@code u} is a uniform
 * draw in {@code (0, 1]} derived from a stable hash of
 * {@code (adId, renterId, day)}; the smallest keys win. Priority is a relative
 * airtime weight — a priority-2 ad is drawn about twice as often as a
 * priority-1 ad.
 *
 * <p>Weighting is exact for a single slot. Taking 6 of ~40 compresses the top
 * end, because inclusion probability saturates, so a priority-10 ad gets
 * roughly 6–9x a priority-1 ad rather than a literal 10x, and no ad can take
 * more than one slot per renter per day. Priority 2 vs 1 measures at 1.9x.
 * Do not promise a client a literal 10x.
 *
 * <p>Because the draw is hashed rather than random, the same renter gets the
 * same slate in the same order all day. That is deliberate: pull-to-refresh
 * must not reshuffle the strip, and impressions must not inflate with refreshes.
 * The day is part of the seed, so the slate rotates at midnight Dubai time.
 *
 * <p>Pure and static by design — no clock, no database, no Spring. Everything
 * it needs is passed in, which is what makes the fairness claim testable.
 */
public final class PromotionSlate {

    private PromotionSlate() {
    }

    public record Candidate(UUID adId, int priority) {
    }

    public static List<UUID> pick(List<Candidate> candidates, UUID renterId, LocalDate day, int size) {
        if (candidates == null || candidates.isEmpty() || size <= 0) {
            return List.of();
        }
        // Key is computed once per candidate, not once per comparison — a
        // comparator that recomputes runs the hash ~215 times for a 40-ad pool.
        record Scored(UUID adId, double key) {
        }
        return candidates.stream()
                .map(c -> new Scored(c.adId(), key(c, renterId, day)))
                .sorted(Comparator.comparingDouble(Scored::key))
                .limit(size)
                .map(Scored::adId)
                .toList();
    }

    private static double key(Candidate c, UUID renterId, LocalDate day) {
        double u = uniform(seed(c.adId(), renterId, day));
        // A non-positive priority would divide by zero or invert the ordering.
        // The DB CHECK forbids it; this guards against a bad backfill anyway.
        int weight = Math.max(1, c.priority());
        return -Math.log(u) / weight;
    }

    /** Maps a 64-bit seed onto (0, 1] — never 0, which would make -ln(u) infinite. */
    private static double uniform(long seed) {
        return ((seed >>> 11) + 1) * 0x1.0p-53;
    }

    /**
     * FNV-1a over the two longs of each UUID plus the epoch day, finished with
     * a SplitMix64 avalanche. Chosen over {@code Objects.hash} because this
     * value must stay stable across JVM versions and restarts — a renter's
     * slate changing mid-day because the app redeployed would double-count
     * impressions. Pure integer arithmetic, so it is bit-identical everywhere.
     *
     * <p><b>Two invariants a future reader must not break.</b> First,
     * {@link #mix} folds all eight bytes of every input; a loop that folds
     * fewer silently discards most of the adId, renterId and day, and the
     * slate still looks plausibly varied while one business is starved.
     * Second, {@link #uniform} consumes the HIGH bits ({@code >>> 11}), so the
     * finalizer is what puts entropy there.
     */
    private static long seed(UUID adId, UUID renterId, LocalDate day) {
        long h = 0xcbf29ce484222325L;
        h = mix(h, adId.getMostSignificantBits());
        h = mix(h, adId.getLeastSignificantBits());
        h = mix(h, renterId.getMostSignificantBits());
        h = mix(h, renterId.getLeastSignificantBits());
        h = mix(h, day.toEpochDay());
        return avalanche(h);
    }

    private static long mix(long h, long value) {
        long result = h;
        for (int i = 0; i < 8; i++) {
            result ^= (value >>> (i * 8)) & 0xFF;
            result *= 0x100000001b3L;
        }
        return result;
    }

    /**
     * SplitMix64 finalizer. FNV-1a alone avalanches weakly — its multiply
     * propagates bit differences only upward, so two unrelated adIds can land
     * on seeds that agree in their top bits for every renter, and one ad then
     * loses the race to the other ~99% of the time, every day, permanently.
     * Measured without this step: in 18 of 20 random 40-ad pools some ad was
     * strongly correlated with another, and the worst ad drew 12.0% of slots
     * against a fair share of 15.0%. With it, the worst drew 14.7%.
     */
    private static long avalanche(long z) {
        long x = z;
        x ^= (x >>> 30);
        x *= 0xbf58476d1ce4e5b9L;
        x ^= (x >>> 27);
        x *= 0x94d049bb133111ebL;
        return x ^ (x >>> 31);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.PromotionSlateTest'
```

Expected: PASS, 13 tests. If `pick_givesEveryAdAirtimeOverAMonth` fails, the hash is not spreading well — check `mix` folds all eight bytes.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/PromotionSlate.java backend/src/test/java/com/datagami/rentaxis/core/service/PromotionSlateTest.java
git commit -m "feat(promotions): date-seeded weighted rotation for the ad slate"
```

---

## Task 5: Repositories

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/repository/{PromoBusinessRepository,PromoAdRepository,PromoAdPropertyRepository,PromoAdEventRepository}.java`

Query methods only — no tests here; they are exercised through the service and controller tasks that follow.

- [ ] **Step 1: Write `PromoBusinessRepository`**

```java
package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PromoBusiness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PromoBusinessRepository extends JpaRepository<PromoBusiness, UUID> {

    Page<PromoBusiness> findByTenantId(UUID tenantId, Pageable pageable);

    List<PromoBusiness> findByTenantIdOrderByCreatedAtAsc(UUID tenantId);

    List<PromoBusiness> findByIdIn(List<UUID> ids);
}
```

- [ ] **Step 2: Write `PromoAdRepository`**

The eligibility query is the hot path — it runs on every home-screen load, so targeting is resolved in SQL rather than by loading every ad and filtering in Java.

```java
package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.enums.PromoPlacement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface PromoAdRepository extends JpaRepository<PromoAd, UUID> {

    Page<PromoAd> findByTenantId(UUID tenantId, Pageable pageable);

    Page<PromoAd> findByTenantIdAndBusinessId(UUID tenantId, UUID businessId, Pageable pageable);

    long countByBusinessId(UUID businessId);

    /**
     * Every ad this renter is eligible to see right now.
     *
     * <p>An ad with no rows in promo_ad_property targets every property — the
     * NOT EXISTS arm — otherwise one of its targeted properties must be a
     * property the renter holds an active lease in. Both arms are evaluated in
     * SQL so the home screen never loads the full ad table.
     *
     * <p>Placement is passed as a list rather than an equality check so one
     * query serves both the home slate (HOME_AND_OFFERS only) and the offers
     * screen (both values).
     *
     * <p>The ordering is load-bearing: {@code offers()} returns this order
     * straight to the client. {@code createdAt} is not unique, so {@code id}
     * breaks ties and keeps paging stable when a seed or bulk import creates
     * several ads in the same instant.
     */
    @Query("""
            SELECT a FROM PromoAd a
            WHERE a.tenantId = :tenantId
              AND a.active = true
              AND a.placement IN :placements
              AND (a.startsAt IS NULL OR a.startsAt <= :now)
              AND (a.endsAt IS NULL OR a.endsAt > :now)
              AND EXISTS (SELECT 1 FROM PromoBusiness b
                          WHERE b.id = a.businessId AND b.active = true)
              AND (NOT EXISTS (SELECT 1 FROM PromoAdProperty p WHERE p.adId = a.id)
                   OR EXISTS (SELECT 1 FROM PromoAdProperty p
                              WHERE p.adId = a.id AND p.propertyId IN :propertyIds))
            ORDER BY a.createdAt ASC, a.id ASC
            """)
    List<PromoAd> findEligible(@Param("tenantId") UUID tenantId,
                               @Param("now") Instant now,
                               @Param("placements") List<PromoPlacement> placements,
                               @Param("propertyIds") List<UUID> propertyIds);
}
```

**Note for the implementer:** `PromotionFeedService` passes a single-element list holding a sentinel UUID when the renter has no active lease, rather than an empty list. Hibernate 6+ does render an empty `IN` as `1=0`, which would in fact behave correctly here (the `EXISTS` arm goes false and the untargeted `NOT EXISTS` arm still matches) — so this is for explicitness, not because an empty list breaks. Keep the sentinel so the intent is readable at the call site, and do not add empty-list special-casing to the JPQL.

- [ ] **Step 3: Write `PromoAdPropertyRepository`**

```java
package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PromoAdProperty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PromoAdPropertyRepository extends JpaRepository<PromoAdProperty, UUID> {

    List<PromoAdProperty> findByAdId(UUID adId);

    /** Batch fetch for list responses — one query per page, not one per row. */
    List<PromoAdProperty> findByAdIdIn(List<UUID> adIds);

    /**
     * Bulk delete, so it executes immediately rather than deferring to flush —
     * which is what lets {@code PromotionService.replaceTargeting} delete then
     * re-insert in one transaction without tripping {@code uq_promo_ad_property}.
     * (The derived-delete form defers, which is why the analogous
     * {@code FacilityService.replaceAmenityScopes} has to call {@code flush()}.)
     * No {@code clearAutomatically}/{@code flushAutomatically} needed: nothing
     * mutates a loaded PromoAdProperty, so there is no stale-entity hazard.
     *
     * <p>Scoped by tenant explicitly. The Hibernate filter would cover it, but a
     * destructive statement should not lean on a single layer of defence.
     */
    @Modifying
    @Query("DELETE FROM PromoAdProperty p WHERE p.tenantId = :tenantId AND p.adId = :adId")
    void deleteByTenantIdAndAdId(@Param("tenantId") UUID tenantId, @Param("adId") UUID adId);
}
```

- [ ] **Step 4: Write `PromoAdEventRepository`**

```java
package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PromoAdEvent;
import com.datagami.rentaxis.domain.entity.enums.PromoEventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface PromoAdEventRepository extends JpaRepository<PromoAdEvent, UUID> {

    /**
     * Impression and click totals for a page of ads, as {@code [adId, eventType, count]}
     * rows. Two-column grouping keeps this to one query per page rather than
     * two per ad.
     */
    @Query("""
            SELECT e.adId, e.eventType, COUNT(e)
            FROM PromoAdEvent e
            WHERE e.tenantId = :tenantId AND e.adId IN :adIds
            GROUP BY e.adId, e.eventType
            """)
    List<Object[]> countByAdIdIn(@Param("tenantId") UUID tenantId, @Param("adIds") List<UUID> adIds);

    /** Daily series for one ad's detail view. Rows are {@code [day, eventType, count]}. */
    @Query("""
            SELECT e.day, e.eventType, COUNT(e)
            FROM PromoAdEvent e
            WHERE e.tenantId = :tenantId AND e.adId = :adId
            GROUP BY e.day, e.eventType
            ORDER BY e.day ASC
            """)
    List<Object[]> dailySeries(@Param("tenantId") UUID tenantId, @Param("adId") UUID adId);

    /** Guards the hard-delete path in PromotionService.deleteAd. */
    long countByAdId(UUID adId);

    /**
     * Which of these ads this renter already has an impression for today.
     *
     * <p>Deliberately batched. This runs on every home-screen load — the client
     * flushes up to six impressions each time — so a per-ad `exists` check
     * would put six round trips on the renter hot path, all day, forever, for
     * a result that is `true` every time after the first load. Served by the
     * partial index `uq_promo_impression_per_day`.
     */
    @Query("""
            SELECT e.adId FROM PromoAdEvent e
            WHERE e.tenantId = :tenantId
              AND e.adId IN :adIds
              AND e.renterUserId = :renterUserId
              AND e.day = :day
              AND e.eventType = com.datagami.rentaxis.domain.entity.enums.PromoEventType.IMPRESSION
            """)
    List<UUID> findAdIdsWithImpressionOn(@Param("tenantId") UUID tenantId,
                                         @Param("adIds") Collection<UUID> adIds,
                                         @Param("renterUserId") UUID renterUserId,
                                         @Param("day") LocalDate day);
}
```

- [ ] **Step 5: Verify it compiles**

Run:

```bash
cd backend && ./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/domain/repository/
git commit -m "feat(promotions): repositories including the renter eligibility query"
```

---

## Task 6: DTOs

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/PromoBusinessDTO.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/PromoBusinessRequest.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/PromoAdDTO.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/PromoAdRequest.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/PromoBusinessRefDTO.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/PromoAdCardDTO.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/PromoAdStatsDTO.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/PromoEventBatchRequest.java`

`PromoAdCardDTO` is the renter-facing projection and deliberately omits priority, targeting and placement — the feed must not leak how the rotation is configured. It carries **both** language variants; the mobile client resolves them, so switching app language does not require a refetch.

- [ ] **Step 1: Write the business DTOs**

`PromoBusinessDTO.java`:

```java
package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.PromoCategory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PromoBusinessDTO(
        UUID id,
        String nameEn,
        String nameAr,
        String logoUrl,
        PromoCategory category,
        String phoneE164,
        String whatsappE164,
        List<String> allowedDomains,
        boolean active,
        long adCount,
        Instant createdAt,
        Instant updatedAt) {
}
```

`PromoBusinessRequest.java`:

```java
package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.PromoCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** category null = OTHER; active null = true; allowedDomains null = none allowed. */
public record PromoBusinessRequest(
        @NotBlank @Size(max = 160) String nameEn,
        @Size(max = 160) String nameAr,
        @Size(max = 512) String logoUrl,
        PromoCategory category,
        @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "phone must be E.164, e.g. +971501234567")
        String phoneE164,
        @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "whatsapp must be E.164, e.g. +971501234567")
        String whatsappE164,
        List<String> allowedDomains,
        Boolean active) {
}
```

- [ ] **Step 2: Write the ad DTOs**

`PromoAdDTO.java` (admin view — everything):

```java
package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.PromoCtaType;
import com.datagami.rentaxis.domain.entity.enums.PromoPlacement;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PromoAdDTO(
        UUID id,
        UUID businessId,
        String businessNameEn,
        String titleEn,
        String titleAr,
        String subtitleEn,
        String subtitleAr,
        String backgroundImageUrl,
        String accentColor,
        PromoCtaType ctaType,
        String ctaLabelEn,
        String ctaLabelAr,
        String ctaUrl,
        String couponCode,
        String couponTermsEn,
        String couponTermsAr,
        Instant startsAt,
        Instant endsAt,
        int priority,
        PromoPlacement placement,
        boolean active,
        List<UUID> propertyIds,
        long impressions,
        long clicks,
        Instant createdAt,
        Instant updatedAt) {
}
```

`PromoAdRequest.java`:

```java
package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.PromoCtaType;
import com.datagami.rentaxis.domain.entity.enums.PromoPlacement;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Cross-field rules (at least one title, URL required for WEBSITE, coupon code
 * required for COUPON, contact number required for CALL/WHATSAPP, window order,
 * URL against the business allowlist) are enforced in PromotionService, not by
 * bean validation — they need the owning business row to decide.
 */
public record PromoAdRequest(
        @NotNull UUID businessId,
        @Size(max = 120) String titleEn,
        @Size(max = 120) String titleAr,
        @Size(max = 160) String subtitleEn,
        @Size(max = 160) String subtitleAr,
        @Size(max = 512) String backgroundImageUrl,
        @Pattern(regexp = "^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$",
                message = "accentColor must be #RRGGBB or #AARRGGBB")
        String accentColor,
        PromoCtaType ctaType,
        @Size(max = 40) String ctaLabelEn,
        @Size(max = 40) String ctaLabelAr,
        @Size(max = 1024) String ctaUrl,
        @Size(max = 64) String couponCode,
        String couponTermsEn,
        String couponTermsAr,
        Instant startsAt,
        Instant endsAt,
        @Min(1) @Max(10) Integer priority,
        PromoPlacement placement,
        List<UUID> propertyIds,
        Boolean active) {
}
```

- [ ] **Step 3: Write the renter-facing DTOs**

`PromoBusinessRefDTO.java`:

```java
package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.PromoCategory;

import java.util.UUID;

/** The slice of a business a renter is allowed to see. */
public record PromoBusinessRefDTO(
        UUID id,
        String nameEn,
        String nameAr,
        String logoUrl,
        PromoCategory category) {
}
```

`PromoAdCardDTO.java`:

```java
package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.PromoCtaType;

import java.time.Instant;
import java.util.UUID;

/**
 * What the mobile carousel renders. Deliberately omits priority, placement and
 * targeting — the feed must not leak how the rotation is configured.
 *
 * <p>Both language variants are sent; the client resolves them, so changing the
 * app's language re-renders without a refetch. {@code ctaPhone} is populated
 * only for CALL and WHATSAPP ads, and {@code ctaUrl} only for WEBSITE ads that
 * already passed the allowlist check on write.
 */
public record PromoAdCardDTO(
        UUID id,
        PromoBusinessRefDTO business,
        String titleEn,
        String titleAr,
        String subtitleEn,
        String subtitleAr,
        String backgroundImageUrl,
        String accentColor,
        PromoCtaType ctaType,
        String ctaLabelEn,
        String ctaLabelAr,
        String ctaUrl,
        String ctaPhone,
        String couponCode,
        String couponTermsEn,
        String couponTermsAr,
        Instant endsAt) {
}
```

`PromoAdStatsDTO.java`:

```java
package com.datagami.rentaxis.api.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PromoAdStatsDTO(
        UUID adId,
        long impressions,
        long clicks,
        double tapThroughRate,
        List<DayPoint> series) {

    public record DayPoint(LocalDate day, long impressions, long clicks) {
    }
}
```

`PromoEventBatchRequest.java`:

```java
package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.PromoEventType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Batched from the app on carousel dispose and on app background. */
public record PromoEventBatchRequest(
        @NotEmpty @Size(max = 50) List<Event> events) {

    public record Event(@NotNull UUID adId, @NotNull PromoEventType type) {
    }
}
```

- [ ] **Step 4: Verify it compiles**

Run:

```bash
cd backend && ./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/dto/Promo*.java
git commit -m "feat(promotions): admin and renter-facing DTOs"
```

---

## Task 7: PromotionService — CRUD and write-time validation

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/PromotionService.java`
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/PromotionServiceTest.java`

No role logic here — RBAC lives in the controller, same split as `FacilityService` and the gate-pass module. Cross-tenant lookups throw `NotFoundException` (404, never 403) so ids cannot be probed.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/datagami/rentaxis/core/service/PromotionServiceTest.java`:

```java
package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PromoAdRequest;
import com.datagami.rentaxis.api.dto.PromoBusinessRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.PromoBusiness;
import com.datagami.rentaxis.domain.entity.enums.PromoCtaType;
import com.datagami.rentaxis.domain.repository.PromoAdEventRepository;
import com.datagami.rentaxis.domain.repository.PromoAdPropertyRepository;
import com.datagami.rentaxis.domain.repository.PromoAdRepository;
import com.datagami.rentaxis.domain.repository.PromoBusinessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionServiceTest {

    @Mock PromoBusinessRepository businessRepository;
    @Mock PromoAdRepository adRepository;
    @Mock PromoAdPropertyRepository adPropertyRepository;
    @Mock PromoAdEventRepository eventRepository;

    PromotionService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID businessId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PromotionService(businessRepository, adRepository,
                adPropertyRepository, eventRepository, new PromotionUrlValidator());
    }

    private PromoBusiness business() {
        PromoBusiness b = new PromoBusiness();
        b.setId(businessId);
        b.setTenantId(tenantId);
        b.setNameEn("Spice Bazaar");
        b.setAllowedDomains("spice-bazaar.ae");
        b.setPhoneE164("+971501234567");
        b.setWhatsappE164("+971501234567");
        return b;
    }

    private PromoAdRequest adRequest(PromoCtaType type, String url, String coupon) {
        return new PromoAdRequest(businessId, "Friday brunch", null, null, null,
                null, null, type, null, null, url, coupon, null, null,
                null, null, 1, null, List.of(), null);
    }

    // ---------------------------------------------------------------- business

    @Test
    void createBusiness_normalisesAllowedDomains() {
        when(businessRepository.save(any(PromoBusiness.class))).thenAnswer(i -> i.getArgument(0));

        PromoBusiness saved = service.createBusiness(tenantId, new PromoBusinessRequest(
                "Spice Bazaar", null, null, null, null, null,
                List.of("HTTPS://Www.Spice-Bazaar.AE/menu", " gym.example.com "), null));

        assertThat(saved.getAllowedDomains()).isEqualTo("www.spice-bazaar.ae,gym.example.com");
    }

    @Test
    void createBusiness_reportsDomainEntriesItCannotUse() {
        // A wildcard is the most likely thing an admin types meaning "and
        // subdomains". Storing nothing and failing every ad link later is the
        // worst outcome; say so at the point of entry instead.
        assertThatThrownBy(() -> service.createBusiness(tenantId, new PromoBusinessRequest(
                "Spice Bazaar", null, null, null, null, null,
                List.of("*.spice-bazaar.ae"), null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("*.spice-bazaar.ae");
        verify(businessRepository, never()).save(any(PromoBusiness.class));
    }

    @Test
    void getBusiness_crossTenantThrowsNotFound() {
        PromoBusiness other = business();
        other.setTenantId(UUID.randomUUID());
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.getBusiness(tenantId, businessId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteBusiness_refusesWhenAdsExist() {
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(business()));
        when(adRepository.countByBusinessId(businessId)).thenReturn(3L);

        assertThatThrownBy(() -> service.deleteBusiness(tenantId, businessId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("deactivate");
        verify(businessRepository, never()).delete(any());
    }

    // --------------------------------------------------------------------- ad

    @Test
    void createAd_acceptsUrlOnAnAllowedDomain() {
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(business()));
        when(adRepository.save(any(PromoAd.class))).thenAnswer(i -> i.getArgument(0));

        PromoAd saved = service.createAd(tenantId,
                adRequest(PromoCtaType.WEBSITE, "https://offers.spice-bazaar.ae/friday", null));

        assertThat(saved.getCtaUrl()).isEqualTo("https://offers.spice-bazaar.ae/friday");
    }

    @Test
    void createAd_rejectsUrlOffTheAllowlist() {
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(business()));

        assertThatThrownBy(() -> service.createAd(tenantId,
                adRequest(PromoCtaType.WEBSITE, "https://evil.com/x", null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("allowed domains");
        verify(adRepository, never()).save(any());
    }

    @Test
    void createAd_rejectsNonHttpsUrl() {
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(business()));

        assertThatThrownBy(() -> service.createAd(tenantId,
                adRequest(PromoCtaType.WEBSITE, "http://spice-bazaar.ae/x", null)))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void createAd_rejectsWebsiteWithoutUrl() {
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(business()));

        assertThatThrownBy(() -> service.createAd(tenantId,
                adRequest(PromoCtaType.WEBSITE, null, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("ctaUrl");
    }

    @Test
    void createAd_rejectsCouponWithoutCode() {
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(business()));

        assertThatThrownBy(() -> service.createAd(tenantId,
                adRequest(PromoCtaType.COUPON, null, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("couponCode");
    }

    @Test
    void createAd_rejectsCallWhenBusinessHasNoPhone() {
        PromoBusiness b = business();
        b.setPhoneE164(null);
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.createAd(tenantId,
                adRequest(PromoCtaType.CALL, null, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("phone");
    }

    @Test
    void createAd_rejectsBlankTitleInBothLanguages() {
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(business()));

        PromoAdRequest req = new PromoAdRequest(businessId, "  ", "", null, null,
                null, null, PromoCtaType.NONE, null, null, null, null, null, null,
                null, null, 1, null, List.of(), null);

        assertThatThrownBy(() -> service.createAd(tenantId, req))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("title");
    }

    @Test
    void createAd_rejectsEndBeforeStart() {
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(business()));
        Instant start = Instant.now();

        PromoAdRequest req = new PromoAdRequest(businessId, "Brunch", null, null, null,
                null, null, PromoCtaType.NONE, null, null, null, null, null, null,
                start, start.minus(1, ChronoUnit.DAYS), 1, null, List.of(), null);

        assertThatThrownBy(() -> service.createAd(tenantId, req))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("endsAt");
    }

    @Test
    void createAd_rejectsBusinessFromAnotherTenant() {
        PromoBusiness other = business();
        other.setTenantId(UUID.randomUUID());
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.createAd(tenantId,
                adRequest(PromoCtaType.NONE, null, null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void createAd_clearsUnusedCtaFields() {
        // A COUPON ad must not carry a stale URL from an earlier edit.
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(business()));
        when(adRepository.save(any(PromoAd.class))).thenAnswer(i -> i.getArgument(0));

        PromoAd saved = service.createAd(tenantId,
                adRequest(PromoCtaType.COUPON, "https://spice-bazaar.ae/x", "MIFTAH25"));

        assertThat(saved.getCouponCode()).isEqualTo("MIFTAH25");
        assertThat(saved.getCtaUrl()).isNull();
    }

    @Test
    void deleteAd_refusesWhenTheAdHasViewHistory() {
        PromoAd existing = new PromoAd();
        existing.setId(UUID.randomUUID());
        existing.setTenantId(tenantId);
        when(adRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(eventRepository.countByAdId(existing.getId())).thenReturn(42L);

        assertThatThrownBy(() -> service.deleteAd(tenantId, existing.getId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Deactivate");
        verify(adRepository, never()).delete(any());
    }

    @Test
    void deleteAd_allowsDeletingAnAdThatNeverRan() {
        PromoAd existing = new PromoAd();
        existing.setId(UUID.randomUUID());
        existing.setTenantId(tenantId);
        when(adRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(eventRepository.countByAdId(existing.getId())).thenReturn(0L);

        service.deleteAd(tenantId, existing.getId());

        verify(adRepository).delete(existing);
    }

    @Test
    void updateAd_replacesTargetingRows() {
        PromoAd existing = new PromoAd();
        existing.setId(UUID.randomUUID());
        existing.setTenantId(tenantId);
        existing.setBusinessId(businessId);
        UUID propertyId = UUID.randomUUID();

        when(adRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(businessRepository.findById(businessId)).thenReturn(Optional.of(business()));
        when(adRepository.save(any(PromoAd.class))).thenAnswer(i -> i.getArgument(0));

        PromoAdRequest req = new PromoAdRequest(businessId, "Brunch", null, null, null,
                null, null, PromoCtaType.NONE, null, null, null, null, null, null,
                null, null, 1, null, List.of(propertyId), null);

        service.updateAd(tenantId, existing.getId(), req);

        verify(adPropertyRepository).deleteByTenantIdAndAdId(tenantId, existing.getId());
        verify(adPropertyRepository).saveAll(any());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.PromotionServiceTest'
```

Expected: FAIL — compilation error, `PromotionService` does not exist.

- [ ] **Step 3: Write the implementation**

Create `backend/src/main/java/com/datagami/rentaxis/core/service/PromotionService.java`:

```java
package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PromoAdRequest;
import com.datagami.rentaxis.api.dto.PromoBusinessRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.PromoAdProperty;
import com.datagami.rentaxis.domain.entity.PromoBusiness;
import com.datagami.rentaxis.domain.entity.enums.PromoCategory;
import com.datagami.rentaxis.domain.entity.enums.PromoCtaType;
import com.datagami.rentaxis.domain.entity.enums.PromoEventType;
import com.datagami.rentaxis.domain.entity.enums.PromoPlacement;
import com.datagami.rentaxis.domain.repository.PromoAdEventRepository;
import com.datagami.rentaxis.domain.repository.PromoAdPropertyRepository;
import com.datagami.rentaxis.domain.repository.PromoAdRepository;
import com.datagami.rentaxis.domain.repository.PromoBusinessRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Business and ad CRUD. No role logic — RBAC and any property-manager checks
 * live in {@link com.datagami.rentaxis.api.PromotionAdminController}, the same
 * split as {@link FacilityService}. Cross-tenant lookups throw
 * {@link NotFoundException} (404, never 403) so ids cannot be probed.
 *
 * <p>Every cross-field rule that needs the owning business row is enforced here
 * rather than by bean validation, and in particular the click-through URL is
 * checked against the business's allowlist on write. That is what lets the
 * mobile app open a stored {@code ctaUrl} without re-deriving trust.
 */
@Service
@Transactional
public class PromotionService {

    private final PromoBusinessRepository businessRepository;
    private final PromoAdRepository adRepository;
    private final PromoAdPropertyRepository adPropertyRepository;
    private final PromoAdEventRepository eventRepository;
    private final PromotionUrlValidator urlValidator;

    public PromotionService(PromoBusinessRepository businessRepository,
                            PromoAdRepository adRepository,
                            PromoAdPropertyRepository adPropertyRepository,
                            PromoAdEventRepository eventRepository,
                            PromotionUrlValidator urlValidator) {
        this.businessRepository = businessRepository;
        this.adRepository = adRepository;
        this.adPropertyRepository = adPropertyRepository;
        this.eventRepository = eventRepository;
        this.urlValidator = urlValidator;
    }

    // ------------------------------------------------------------- businesses

    @Transactional(readOnly = true)
    public Page<PromoBusiness> listBusinesses(UUID tenantId, Pageable pageable) {
        return businessRepository.findByTenantId(tenantId, pageable);
    }

    @Transactional(readOnly = true)
    public PromoBusiness getBusiness(UUID tenantId, UUID id) {
        PromoBusiness b = businessRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Business not found"));
        if (!Objects.equals(b.getTenantId(), tenantId)) {
            throw new NotFoundException("Business not found");
        }
        return b;
    }

    public PromoBusiness createBusiness(UUID tenantId, PromoBusinessRequest req) {
        PromoBusiness b = new PromoBusiness();
        b.setTenantId(tenantId);
        applyBusiness(b, req);
        return businessRepository.save(b);
    }

    public PromoBusiness updateBusiness(UUID tenantId, UUID id, PromoBusinessRequest req) {
        PromoBusiness b = getBusiness(tenantId, id);
        applyBusiness(b, req);
        return businessRepository.save(b);
    }

    /**
     * Hard delete only when nothing references the business — ad history and
     * event counts must survive, so anything in use is deactivated instead.
     */
    public void deleteBusiness(UUID tenantId, UUID id) {
        PromoBusiness b = getBusiness(tenantId, id);
        if (adRepository.countByBusinessId(id) > 0) {
            throw new BusinessRuleViolationException(
                    "This business has ads. Deactivate it instead of deleting it.");
        }
        businessRepository.delete(b);
    }

    private void applyBusiness(PromoBusiness b, PromoBusinessRequest req) {
        b.setNameEn(req.nameEn().trim());
        b.setNameAr(trimToNull(req.nameAr()));
        b.setLogoUrl(trimToNull(req.logoUrl()));
        b.setCategory(req.category() == null ? PromoCategory.OTHER : req.category());
        b.setPhoneE164(trimToNull(req.phoneE164()));
        b.setWhatsappE164(trimToNull(req.whatsappE164()));
        // Normalise through the validator so the stored list and the check that
        // guards ad URLs can never disagree about what a domain looks like.
        // Entries the validator cannot turn into a hostname are reported rather
        // than dropped: silently discarding them would store an empty allowlist
        // and then refuse every one of this business's ad links, with nothing
        // anywhere explaining why.
        List<String> requested = req.allowedDomains() == null
                ? List.of()
                : req.allowedDomains().stream().map(String::trim).filter(d -> !d.isEmpty()).toList();
        List<String> domains = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (String entry : requested) {
            List<String> parsed = urlValidator.parseDomains(entry);
            if (parsed.isEmpty()) {
                rejected.add(entry);
            } else {
                parsed.stream().filter(d -> !domains.contains(d)).forEach(domains::add);
            }
        }
        if (!rejected.isEmpty()) {
            throw new BusinessRuleViolationException(
                    "Not valid domains: " + String.join(", ", rejected)
                            + ". Enter a hostname like spice-bazaar.ae — a domain already "
                            + "covers its subdomains, so wildcards are not needed.");
        }
        b.setAllowedDomains(domains.isEmpty() ? null : String.join(",", domains));
        if (req.active() != null) {
            b.setActive(req.active());
        }
    }

    // -------------------------------------------------------------------- ads

    @Transactional(readOnly = true)
    public Page<PromoAd> listAds(UUID tenantId, UUID businessId, Pageable pageable) {
        if (businessId != null) {
            return adRepository.findByTenantIdAndBusinessId(tenantId, businessId, pageable);
        }
        return adRepository.findByTenantId(tenantId, pageable);
    }

    @Transactional(readOnly = true)
    public PromoAd getAd(UUID tenantId, UUID id) {
        PromoAd a = adRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Ad not found"));
        if (!Objects.equals(a.getTenantId(), tenantId)) {
            throw new NotFoundException("Ad not found");
        }
        return a;
    }

    public PromoAd createAd(UUID tenantId, PromoAdRequest req) {
        PromoAd a = new PromoAd();
        a.setTenantId(tenantId);
        a.setBusinessId(req.businessId());
        applyAd(tenantId, a, req);
        PromoAd saved = adRepository.save(a);
        replaceTargeting(tenantId, saved.getId(), req.propertyIds());
        return saved;
    }

    public PromoAd updateAd(UUID tenantId, UUID id, PromoAdRequest req) {
        PromoAd a = getAd(tenantId, id);
        a.setBusinessId(req.businessId());
        applyAd(tenantId, a, req);
        PromoAd saved = adRepository.save(a);
        replaceTargeting(tenantId, id, req.propertyIds());
        return saved;
    }

    /**
     * Hard delete only when the ad never ran. Impression and click history is
     * the entire point of the analytics, and fk_pae_ad RESTRICTs, so an ad
     * with events is deactivated instead — the same rule deleteBusiness
     * applies one level up.
     */
    public void deleteAd(UUID tenantId, UUID id) {
        PromoAd ad = getAd(tenantId, id);
        if (eventRepository.countByAdId(id) > 0) {
            throw new BusinessRuleViolationException(
                    "This ad has view history. Deactivate it instead of deleting it.");
        }
        adRepository.delete(ad);
    }

    @Transactional(readOnly = true)
    public List<UUID> targetedPropertyIds(UUID adId) {
        return adPropertyRepository.findByAdId(adId).stream()
                .map(PromoAdProperty::getPropertyId)
                .toList();
    }

    private void replaceTargeting(UUID tenantId, UUID adId, List<UUID> propertyIds) {
        adPropertyRepository.deleteByTenantIdAndAdId(tenantId, adId);
        if (propertyIds == null || propertyIds.isEmpty()) {
            return; // zero rows = every property
        }
        List<PromoAdProperty> rows = propertyIds.stream().distinct().map(pid -> {
            PromoAdProperty row = new PromoAdProperty();
            row.setTenantId(tenantId);
            row.setAdId(adId);
            row.setPropertyId(pid);
            return row;
        }).toList();
        adPropertyRepository.saveAll(rows);
    }

    private void applyAd(UUID tenantId, PromoAd a, PromoAdRequest req) {
        PromoBusiness business = getBusiness(tenantId, req.businessId());
        PromoCtaType ctaType = req.ctaType() == null ? PromoCtaType.NONE : req.ctaType();

        String titleEn = trimToNull(req.titleEn());
        String titleAr = trimToNull(req.titleAr());
        if (titleEn == null && titleAr == null) {
            throw new BusinessRuleViolationException("An ad needs a title in at least one language");
        }
        if (req.startsAt() != null && req.endsAt() != null && !req.endsAt().isAfter(req.startsAt())) {
            throw new BusinessRuleViolationException("endsAt must be after startsAt");
        }

        String ctaUrl = null;
        String couponCode = null;
        switch (ctaType) {
            case WEBSITE -> {
                String url = trimToNull(req.ctaUrl());
                if (url == null) {
                    throw new BusinessRuleViolationException("ctaUrl is required for a website ad");
                }
                if (!urlValidator.isAllowed(url, business.getAllowedDomains())) {
                    throw new BusinessRuleViolationException(
                            "ctaUrl must be https and on one of this business's allowed domains");
                }
                ctaUrl = url;
            }
            case COUPON -> {
                couponCode = trimToNull(req.couponCode());
                if (couponCode == null) {
                    throw new BusinessRuleViolationException("couponCode is required for a coupon ad");
                }
            }
            case CALL -> {
                if (trimToNull(business.getPhoneE164()) == null) {
                    throw new BusinessRuleViolationException(
                            "This business has no phone number — add one before using a call ad");
                }
            }
            case WHATSAPP -> {
                if (trimToNull(business.getWhatsappE164()) == null) {
                    throw new BusinessRuleViolationException(
                            "This business has no WhatsApp number — add one before using a WhatsApp ad");
                }
            }
            case NONE -> {
                // nothing to validate
            }
        }

        a.setTitleEn(titleEn);
        a.setTitleAr(titleAr);
        a.setSubtitleEn(trimToNull(req.subtitleEn()));
        a.setSubtitleAr(trimToNull(req.subtitleAr()));
        a.setBackgroundImageUrl(trimToNull(req.backgroundImageUrl()));
        a.setAccentColor(trimToNull(req.accentColor()));
        a.setCtaType(ctaType);
        a.setCtaLabelEn(trimToNull(req.ctaLabelEn()));
        a.setCtaLabelAr(trimToNull(req.ctaLabelAr()));
        // Fields belonging to other CTA types are cleared, never carried over
        // from a previous edit — a coupon ad must not keep a stale URL.
        a.setCtaUrl(ctaUrl);
        a.setCouponCode(couponCode);
        a.setCouponTermsEn(ctaType == PromoCtaType.COUPON ? trimToNull(req.couponTermsEn()) : null);
        a.setCouponTermsAr(ctaType == PromoCtaType.COUPON ? trimToNull(req.couponTermsAr()) : null);
        a.setStartsAt(req.startsAt());
        a.setEndsAt(req.endsAt());
        a.setPriority(req.priority() == null ? 1 : req.priority());
        a.setPlacement(req.placement() == null ? PromoPlacement.HOME_AND_OFFERS : req.placement());
        if (req.active() != null) {
            a.setActive(req.active());
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.PromotionServiceTest'
```

Expected: PASS, 17 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/PromotionService.java backend/src/test/java/com/datagami/rentaxis/core/service/PromotionServiceTest.java
git commit -m "feat(promotions): business and ad CRUD with write-time CTA validation"
```

---

## Task 8: PromotionFeedService — eligibility, slate, event ingest

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/PromotionFeedService.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/repository/LeaseRepository.java` (add one projection query)
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/PromotionFeedServiceTest.java`

- [ ] **Step 1: Add the lease-to-property projection**

Add to `backend/src/main/java/com/datagami/rentaxis/domain/repository/LeaseRepository.java`, inside the interface:

```java
    /**
     * Properties a renter currently holds an active lease in. Used by the
     * promotions feed to resolve ad targeting; returns ids only so the feed
     * never materialises whole Lease graphs on a home-screen load.
     */
    @Query("""
            SELECT DISTINCT l.unit.property.id FROM Lease l
            WHERE l.tenantId = :tenantId
              AND l.renter.userId = :userId
              AND l.status = com.datagami.rentaxis.domain.entity.enums.LeaseStatus.ACTIVE
            """)
    List<UUID> findActivePropertyIdsForRenterUser(@Param("tenantId") UUID tenantId,
                                                  @Param("userId") UUID userId);
```

If `LeaseRepository` does not already import `java.util.UUID`, `java.util.List`, `org.springframework.data.jpa.repository.Query` and `org.springframework.data.repository.query.Param`, add them.

- [ ] **Step 2: Write the failing test**

Create `backend/src/test/java/com/datagami/rentaxis/core/service/PromotionFeedServiceTest.java`:

```java
package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PromoAdCardDTO;
import com.datagami.rentaxis.api.dto.PromoEventBatchRequest;
import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.PromoAdEvent;
import com.datagami.rentaxis.domain.entity.PromoBusiness;
import com.datagami.rentaxis.domain.entity.enums.PromoCtaType;
import com.datagami.rentaxis.domain.entity.enums.PromoEventType;
import com.datagami.rentaxis.domain.entity.enums.PromoPlacement;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PromoAdEventRepository;
import com.datagami.rentaxis.domain.repository.PromoAdRepository;
import com.datagami.rentaxis.domain.repository.PromoBusinessRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionFeedServiceTest {

    @Mock PromoAdRepository adRepository;
    @Mock PromoBusinessRepository businessRepository;
    @Mock PromoAdEventRepository eventRepository;
    @Mock LeaseRepository leaseRepository;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID renterId = UUID.randomUUID();
    private final UUID businessId = UUID.randomUUID();

    private PromotionFeedService service() {
        return new PromotionFeedService(adRepository, businessRepository,
                eventRepository, leaseRepository);
    }

    private PromoBusiness business() {
        PromoBusiness b = new PromoBusiness();
        b.setId(businessId);
        b.setTenantId(tenantId);
        b.setNameEn("Spice Bazaar");
        b.setPhoneE164("+971501234567");
        b.setWhatsappE164("+971509999999");
        return b;
    }

    private PromoAd ad(int priority) {
        PromoAd a = new PromoAd();
        a.setId(UUID.randomUUID());
        a.setTenantId(tenantId);
        a.setBusinessId(businessId);
        a.setTitleEn("Brunch");
        a.setPriority(priority);
        a.setPlacement(PromoPlacement.HOME_AND_OFFERS);
        return a;
    }

    private List<PromoAd> ads(int count) {
        List<PromoAd> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(ad(1));
        }
        return out;
    }

    @Test
    void homeFeed_capsAtSixCards() {
        when(leaseRepository.findActivePropertyIdsForRenterUser(tenantId, renterId))
                .thenReturn(List.of(UUID.randomUUID()));
        when(adRepository.findEligible(eq(tenantId), any(), anyList(), anyList()))
                .thenReturn(ads(40));
        when(businessRepository.findByIdIn(anyList())).thenReturn(List.of(business()));

        assertThat(service().homeFeed(tenantId, renterId)).hasSize(6);
    }

    @Test
    void homeFeed_isStableAcrossCalls() {
        when(leaseRepository.findActivePropertyIdsForRenterUser(tenantId, renterId))
                .thenReturn(List.of());
        List<PromoAd> pool = ads(40);
        when(adRepository.findEligible(eq(tenantId), any(), anyList(), anyList())).thenReturn(pool);
        when(businessRepository.findByIdIn(anyList())).thenReturn(List.of(business()));

        PromotionFeedService s = service();
        assertThat(s.homeFeed(tenantId, renterId).stream().map(PromoAdCardDTO::id).toList())
                .isEqualTo(s.homeFeed(tenantId, renterId).stream().map(PromoAdCardDTO::id).toList());
    }

    @Test
    void homeFeed_passesSentinelPropertyIdWhenRenterHasNoActiveLease() {
        when(leaseRepository.findActivePropertyIdsForRenterUser(tenantId, renterId))
                .thenReturn(List.of());
        when(adRepository.findEligible(eq(tenantId), any(), anyList(), anyList()))
                .thenReturn(List.of());

        service().homeFeed(tenantId, renterId);

        ArgumentCaptor<List<UUID>> captor = ArgumentCaptor.forClass(List.class);
        verify(adRepository).findEligible(eq(tenantId), any(), anyList(), captor.capture());
        // Empty IN lists are invalid JPQL on Postgres — a sentinel keeps the
        // untargeted arm of the query working for a renter between leases.
        assertThat(captor.getValue()).containsExactly(new UUID(0L, 0L));
    }

    @Test
    void homeFeed_requestsOnlyHomePlacement() {
        when(leaseRepository.findActivePropertyIdsForRenterUser(tenantId, renterId))
                .thenReturn(List.of());
        when(adRepository.findEligible(eq(tenantId), any(), anyList(), anyList()))
                .thenReturn(List.of());

        service().homeFeed(tenantId, renterId);

        ArgumentCaptor<List<PromoPlacement>> captor = ArgumentCaptor.forClass(List.class);
        verify(adRepository).findEligible(eq(tenantId), any(), captor.capture(), anyList());
        assertThat(captor.getValue()).containsExactly(PromoPlacement.HOME_AND_OFFERS);
    }

    @Test
    void offers_requestsBothPlacements() {
        when(leaseRepository.findActivePropertyIdsForRenterUser(tenantId, renterId))
                .thenReturn(List.of());
        when(adRepository.findEligible(eq(tenantId), any(), anyList(), anyList()))
                .thenReturn(List.of());

        service().offers(tenantId, renterId, null);

        ArgumentCaptor<List<PromoPlacement>> captor = ArgumentCaptor.forClass(List.class);
        verify(adRepository).findEligible(eq(tenantId), any(), captor.capture(), anyList());
        assertThat(captor.getValue())
                .containsExactlyInAnyOrder(PromoPlacement.HOME_AND_OFFERS, PromoPlacement.OFFERS_ONLY);
    }

    @Test
    void toCard_omitsPriorityPlacementAndTargeting() {
        // Compile-time guarantee via the record's component list — assert the
        // renter DTO simply has no such accessors.
        assertThat(PromoAdCardDTO.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("priority", "placement", "propertyIds");
    }

    @Test
    void toCard_populatesPhoneForCallAdsOnly() {
        when(leaseRepository.findActivePropertyIdsForRenterUser(tenantId, renterId))
                .thenReturn(List.of());
        PromoAd call = ad(1);
        call.setCtaType(PromoCtaType.CALL);
        PromoAd plain = ad(1);
        plain.setCtaType(PromoCtaType.NONE);
        when(adRepository.findEligible(eq(tenantId), any(), anyList(), anyList()))
                .thenReturn(List.of(call, plain));
        when(businessRepository.findByIdIn(anyList())).thenReturn(List.of(business()));

        List<PromoAdCardDTO> cards = service().offers(tenantId, renterId, null);

        assertThat(cards).filteredOn(c -> c.ctaType() == PromoCtaType.CALL)
                .allMatch(c -> "+971501234567".equals(c.ctaPhone()));
        assertThat(cards).filteredOn(c -> c.ctaType() == PromoCtaType.NONE)
                .allMatch(c -> c.ctaPhone() == null);
    }

    @Test
    void recordEvents_writesOneRowPerEventWithDubaiDay() {
        PromoAd a = ad(1);
        when(adRepository.findEligible(eq(tenantId), any(), anyList(), anyList()))
                .thenReturn(List.of(a));
        when(leaseRepository.findActivePropertyIdsForRenterUser(tenantId, renterId))
                .thenReturn(List.of());

        service().recordEvents(tenantId, renterId, new PromoEventBatchRequest(List.of(
                new PromoEventBatchRequest.Event(a.getId(), PromoEventType.IMPRESSION),
                new PromoEventBatchRequest.Event(a.getId(), PromoEventType.CLICK))));

        ArgumentCaptor<List<PromoAdEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(eventRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue()).allMatch(e ->
                e.getDay().equals(java.time.LocalDate.ofInstant(
                        Instant.now(), java.time.ZoneId.of("Asia/Dubai"))));
    }

    @Test
    void recordEvents_skipsImpressionsAlreadyRecordedToday() {
        PromoAd a = ad(1);
        when(adRepository.findEligible(eq(tenantId), any(), anyList(), anyList()))
                .thenReturn(List.of(a));
        when(leaseRepository.findActivePropertyIdsForRenterUser(tenantId, renterId))
                .thenReturn(List.of());
        when(eventRepository.findAdIdsWithImpressionOn(
                eq(tenantId), anyCollection(), eq(renterId), any()))
                .thenReturn(List.of(a.getId()));

        service().recordEvents(tenantId, renterId, new PromoEventBatchRequest(List.of(
                new PromoEventBatchRequest.Event(a.getId(), PromoEventType.IMPRESSION),
                new PromoEventBatchRequest.Event(a.getId(), PromoEventType.CLICK))));

        ArgumentCaptor<List<PromoAdEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(eventRepository).saveAll(captor.capture());
        // The click still lands; the repeat impression is dropped before the
        // insert, because the partial unique index would only fail at commit.
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getEventType()).isEqualTo(PromoEventType.CLICK);
    }

    @Test
    void recordEvents_looksUpTodaysImpressionsInOneQuery() {
        List<PromoAd> pool = List.of(ad(1), ad(1), ad(1), ad(1), ad(1), ad(1));
        when(adRepository.findEligible(eq(tenantId), any(), anyList(), anyList()))
                .thenReturn(pool);
        when(leaseRepository.findActivePropertyIdsForRenterUser(tenantId, renterId))
                .thenReturn(List.of());
        when(eventRepository.findAdIdsWithImpressionOn(
                eq(tenantId), anyCollection(), eq(renterId), any()))
                .thenReturn(List.of());

        service().recordEvents(tenantId, renterId, new PromoEventBatchRequest(
                pool.stream()
                        .map(a -> new PromoEventBatchRequest.Event(
                                a.getId(), PromoEventType.IMPRESSION))
                        .toList()));

        // Six impressions, one lookup — not one per ad. This is the renter
        // hot path; a per-ad check would run on every home-screen load.
        verify(eventRepository, times(1)).findAdIdsWithImpressionOn(
                any(), anyCollection(), any(), any());
    }

    @Test
    void recordEvents_dropsAdsTheRenterIsNotEligibleFor() {
        when(adRepository.findEligible(eq(tenantId), any(), anyList(), anyList()))
                .thenReturn(List.of());
        when(leaseRepository.findActivePropertyIdsForRenterUser(tenantId, renterId))
                .thenReturn(List.of());

        service().recordEvents(tenantId, renterId, new PromoEventBatchRequest(List.of(
                new PromoEventBatchRequest.Event(UUID.randomUUID(), PromoEventType.CLICK))));

        // A stale batch from a backgrounded app must be ignored silently, not 400.
        verify(eventRepository, never()).saveAll(anyList());
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run:

```bash
cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.PromotionFeedServiceTest'
```

Expected: FAIL — compilation error, `PromotionFeedService` does not exist.

- [ ] **Step 4: Write the implementation**

Create `backend/src/main/java/com/datagami/rentaxis/core/service/PromotionFeedService.java`:

```java
package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PromoAdCardDTO;
import com.datagami.rentaxis.api.dto.PromoBusinessRefDTO;
import com.datagami.rentaxis.api.dto.PromoEventBatchRequest;
import com.datagami.rentaxis.core.service.PromotionSlate.Candidate;
import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.PromoAdEvent;
import com.datagami.rentaxis.domain.entity.PromoBusiness;
import com.datagami.rentaxis.domain.entity.enums.PromoCategory;
import com.datagami.rentaxis.domain.entity.enums.PromoCtaType;
import com.datagami.rentaxis.domain.entity.enums.PromoEventType;
import com.datagami.rentaxis.domain.entity.enums.PromoPlacement;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PromoAdEventRepository;
import com.datagami.rentaxis.domain.repository.PromoAdRepository;
import com.datagami.rentaxis.domain.repository.PromoBusinessRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What a renter actually sees. Resolves eligibility in SQL, then hands the
 * surviving ads to {@link PromotionSlate} for the home strip.
 */
@Service
@Transactional
public class PromotionFeedService {

    static final int HOME_SLATE_SIZE = 6;

    /** The product's timezone. The database's is not necessarily the same. */
    static final ZoneId DUBAI = ZoneId.of("Asia/Dubai");

    /**
     * Stand-in property id for a renter with no active lease. An empty
     * {@code IN} list is invalid JPQL on PostgreSQL, and the untargeted arm of
     * the eligibility query still has to run for these renters.
     */
    private static final UUID NO_PROPERTY = new UUID(0L, 0L);

    private static final List<PromoPlacement> HOME_ONLY = List.of(PromoPlacement.HOME_AND_OFFERS);
    private static final List<PromoPlacement> ALL_PLACEMENTS =
            List.of(PromoPlacement.HOME_AND_OFFERS, PromoPlacement.OFFERS_ONLY);

    private final PromoAdRepository adRepository;
    private final PromoBusinessRepository businessRepository;
    private final PromoAdEventRepository eventRepository;
    private final LeaseRepository leaseRepository;

    public PromotionFeedService(PromoAdRepository adRepository,
                                PromoBusinessRepository businessRepository,
                                PromoAdEventRepository eventRepository,
                                LeaseRepository leaseRepository) {
        this.adRepository = adRepository;
        this.businessRepository = businessRepository;
        this.eventRepository = eventRepository;
        this.leaseRepository = leaseRepository;
    }

    @Transactional(readOnly = true)
    public List<PromoAdCardDTO> homeFeed(UUID tenantId, UUID renterUserId) {
        List<PromoAd> eligible = eligible(tenantId, renterUserId, HOME_ONLY);
        if (eligible.isEmpty()) {
            return List.of();
        }
        List<UUID> slate = PromotionSlate.pick(
                eligible.stream().map(a -> new Candidate(a.getId(), a.getPriority())).toList(),
                renterUserId, LocalDate.now(DUBAI), HOME_SLATE_SIZE);

        Map<UUID, PromoAd> byId = eligible.stream()
                .collect(Collectors.toMap(PromoAd::getId, Function.identity()));
        List<PromoAd> ordered = slate.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
        return toCards(ordered);
    }

    /** Every eligible ad, newest business content last, optionally category-filtered. */
    @Transactional(readOnly = true)
    public List<PromoAdCardDTO> offers(UUID tenantId, UUID renterUserId, PromoCategory category) {
        List<PromoAd> eligible = eligible(tenantId, renterUserId, ALL_PLACEMENTS);
        List<PromoAdCardDTO> cards = toCards(eligible);
        if (category == null) {
            return cards;
        }
        return cards.stream().filter(c -> c.business().category() == category).toList();
    }

    /**
     * Ignores unknown or ineligible ad ids rather than rejecting the batch — a
     * stale flush from a backgrounded app must never surface an error to the
     * renter. Impressions this renter already has on record today are filtered
     * out BEFORE the insert in one batched lookup: the partial unique index
     * only fires at commit,
     * where the transaction is already rollback-only and no catch could save
     * the batch (its clicks included). Two simultaneous batches can still race
     * past the exists-check; that lone failed request is accepted — the client
     * fires and forgets.
     */
    public void recordEvents(UUID tenantId, UUID renterUserId, PromoEventBatchRequest batch) {
        List<UUID> allowed = eligible(tenantId, renterUserId, ALL_PLACEMENTS).stream()
                .map(PromoAd::getId).toList();
        LocalDate day = LocalDate.now(DUBAI);

        // One query for the whole batch rather than one per ad. This runs on
        // every home-screen load with up to six impressions, so a per-ad check
        // would be six round trips each time, all day, for a result that is
        // already-seen every time after the first load.
        List<UUID> candidates = batch.events().stream()
                .filter(e -> e.type() == PromoEventType.IMPRESSION && allowed.contains(e.adId()))
                .map(PromoEventBatchRequest.Event::adId)
                .distinct()
                .toList();
        Set<UUID> seenToday = candidates.isEmpty()
                ? Set.of()
                : new HashSet<>(eventRepository.findAdIdsWithImpressionOn(
                        tenantId, candidates, renterUserId, day));

        // Also guards the same ad appearing twice within one batch — seenToday
        // only knows about committed rows.
        Set<UUID> impressionsInBatch = new HashSet<>();

        List<PromoAdEvent> rows = batch.events().stream()
                .filter(e -> allowed.contains(e.adId()))
                .filter(e -> e.type() != PromoEventType.IMPRESSION
                        || (impressionsInBatch.add(e.adId()) && !seenToday.contains(e.adId())))
                .map(e -> {
                    PromoAdEvent row = new PromoAdEvent();
                    row.setTenantId(tenantId);
                    row.setAdId(e.adId());
                    row.setRenterUserId(renterUserId);
                    row.setEventType(e.type());
                    row.setOccurredAt(Instant.now());
                    row.setDay(day);
                    return row;
                })
                .toList();

        if (rows.isEmpty()) {
            return;
        }
        eventRepository.saveAll(rows);
    }

    private List<PromoAd> eligible(UUID tenantId, UUID renterUserId, List<PromoPlacement> placements) {
        List<UUID> propertyIds =
                leaseRepository.findActivePropertyIdsForRenterUser(tenantId, renterUserId);
        if (propertyIds.isEmpty()) {
            propertyIds = List.of(NO_PROPERTY);
        }
        return adRepository.findEligible(tenantId, Instant.now(), placements, propertyIds);
    }

    private List<PromoAdCardDTO> toCards(List<PromoAd> ads) {
        if (ads.isEmpty()) {
            return List.of();
        }
        List<UUID> businessIds = ads.stream().map(PromoAd::getBusinessId).distinct().toList();
        Map<UUID, PromoBusiness> businesses = new LinkedHashMap<>();
        for (PromoBusiness b : businessRepository.findByIdIn(businessIds)) {
            businesses.put(b.getId(), b);
        }
        return ads.stream()
                .map(a -> toCard(a, businesses.get(a.getBusinessId())))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private PromoAdCardDTO toCard(PromoAd a, PromoBusiness b) {
        if (b == null) {
            return null;
        }
        String phone = switch (a.getCtaType()) {
            case CALL -> b.getPhoneE164();
            case WHATSAPP -> b.getWhatsappE164();
            default -> null;
        };
        return new PromoAdCardDTO(
                a.getId(),
                new PromoBusinessRefDTO(b.getId(), b.getNameEn(), b.getNameAr(),
                        b.getLogoUrl(), b.getCategory()),
                a.getTitleEn(), a.getTitleAr(),
                a.getSubtitleEn(), a.getSubtitleAr(),
                a.getBackgroundImageUrl(), a.getAccentColor(),
                a.getCtaType(), a.getCtaLabelEn(), a.getCtaLabelAr(),
                a.getCtaType() == PromoCtaType.WEBSITE ? a.getCtaUrl() : null,
                phone,
                a.getCouponCode(), a.getCouponTermsEn(), a.getCouponTermsAr(),
                a.getEndsAt());
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run:

```bash
cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.PromotionFeedServiceTest'
```

Expected: PASS, 17 tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/PromotionFeedService.java backend/src/main/java/com/datagami/rentaxis/domain/repository/LeaseRepository.java backend/src/test/java/com/datagami/rentaxis/core/service/PromotionFeedServiceTest.java
git commit -m "feat(promotions): renter feed with targeting, rotation and event ingest"
```

---

## Task 9: PromotionStatsService

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/PromotionStatsService.java`
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/PromotionStatsServiceTest.java`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/datagami/rentaxis/core/service/PromotionStatsServiceTest.java`:

```java
package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PromoAdStatsDTO;
import com.datagami.rentaxis.domain.entity.enums.PromoEventType;
import com.datagami.rentaxis.domain.repository.PromoAdEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionStatsServiceTest {

    @Mock PromoAdEventRepository eventRepository;
    @InjectMocks PromotionStatsService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID adId = UUID.randomUUID();

    @Test
    void totals_foldsGroupedRowsIntoImpressionsAndClicks() {
        when(eventRepository.countByAdIdIn(tenantId, List.of(adId))).thenReturn(List.of(
                new Object[]{adId, PromoEventType.IMPRESSION, 1240L},
                new Object[]{adId, PromoEventType.CLICK, 87L}));

        Map<UUID, PromotionStatsService.Totals> totals = service.totals(tenantId, List.of(adId));

        assertThat(totals.get(adId).impressions()).isEqualTo(1240L);
        assertThat(totals.get(adId).clicks()).isEqualTo(87L);
    }

    @Test
    void totals_skipsTheQueryForAnEmptyPage() {
        assertThat(service.totals(tenantId, List.of())).isEmpty();
        verify(eventRepository, never()).countByAdIdIn(any(), anyList());
    }

    @Test
    void totals_defaultsToZeroForAnAdWithNoEvents() {
        when(eventRepository.countByAdIdIn(tenantId, List.of(adId))).thenReturn(List.of());

        Map<UUID, PromotionStatsService.Totals> totals = service.totals(tenantId, List.of(adId));

        assertThat(totals.getOrDefault(adId, new PromotionStatsService.Totals(0, 0)).impressions())
                .isZero();
    }

    @Test
    void stats_computesTapThroughRate() {
        when(eventRepository.countByAdIdIn(tenantId, List.of(adId))).thenReturn(List.of(
                new Object[]{adId, PromoEventType.IMPRESSION, 200L},
                new Object[]{adId, PromoEventType.CLICK, 50L}));
        when(eventRepository.dailySeries(tenantId, adId)).thenReturn(List.of());

        PromoAdStatsDTO stats = service.stats(tenantId, adId);

        assertThat(stats.tapThroughRate()).isEqualTo(0.25);
    }

    @Test
    void stats_tapThroughRateIsZeroWithNoImpressions() {
        // List.<Object[]>of — a bare List.of with ONE array varargs-expands
        // into List<Object> and does not compile against List<Object[]>.
        when(eventRepository.countByAdIdIn(tenantId, List.of(adId)))
                .thenReturn(List.<Object[]>of(new Object[]{adId, PromoEventType.CLICK, 3L}));
        when(eventRepository.dailySeries(tenantId, adId)).thenReturn(List.of());

        // No division by zero, and no misleading "300%".
        assertThat(service.stats(tenantId, adId).tapThroughRate()).isZero();
    }

    @Test
    void stats_mergesDailyRowsIntoOnePointPerDay() {
        LocalDate day = LocalDate.of(2026, 8, 20);
        when(eventRepository.countByAdIdIn(tenantId, List.of(adId))).thenReturn(List.of());
        when(eventRepository.dailySeries(tenantId, adId)).thenReturn(List.of(
                new Object[]{day, PromoEventType.IMPRESSION, 10L},
                new Object[]{day, PromoEventType.CLICK, 2L},
                new Object[]{day.plusDays(1), PromoEventType.IMPRESSION, 7L}));

        List<PromoAdStatsDTO.DayPoint> series = service.stats(tenantId, adId).series();

        assertThat(series).hasSize(2);
        assertThat(series.get(0)).isEqualTo(new PromoAdStatsDTO.DayPoint(day, 10L, 2L));
        assertThat(series.get(1)).isEqualTo(new PromoAdStatsDTO.DayPoint(day.plusDays(1), 7L, 0L));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.PromotionStatsServiceTest'
```

Expected: FAIL — compilation error, `PromotionStatsService` does not exist.

- [ ] **Step 3: Write the implementation**

Create `backend/src/main/java/com/datagami/rentaxis/core/service/PromotionStatsService.java`:

```java
package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PromoAdStatsDTO;
import com.datagami.rentaxis.domain.entity.enums.PromoEventType;
import com.datagami.rentaxis.domain.repository.PromoAdEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Impression and click aggregates for the admin panel. */
@Service
@Transactional(readOnly = true)
public class PromotionStatsService {

    public record Totals(long impressions, long clicks) {
    }

    private final PromoAdEventRepository eventRepository;

    public PromotionStatsService(PromoAdEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    /** One query for a whole page of ads, not two per row. */
    public Map<UUID, Totals> totals(UUID tenantId, List<UUID> adIds) {
        if (adIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, long[]> acc = new HashMap<>();
        for (Object[] row : eventRepository.countByAdIdIn(tenantId, adIds)) {
            UUID adId = (UUID) row[0];
            PromoEventType type = (PromoEventType) row[1];
            long count = (Long) row[2];
            long[] pair = acc.computeIfAbsent(adId, k -> new long[2]);
            if (type == PromoEventType.IMPRESSION) {
                pair[0] += count;
            } else {
                pair[1] += count;
            }
        }
        Map<UUID, Totals> out = new HashMap<>();
        acc.forEach((adId, pair) -> out.put(adId, new Totals(pair[0], pair[1])));
        return out;
    }

    public PromoAdStatsDTO stats(UUID tenantId, UUID adId) {
        Totals t = totals(tenantId, List.of(adId)).getOrDefault(adId, new Totals(0, 0));
        // Guarded so an ad with clicks but no recorded impressions reports 0,
        // not a misleading rate above 1.
        double rate = t.impressions() == 0 ? 0d : (double) t.clicks() / t.impressions();

        Map<LocalDate, long[]> byDay = new TreeMap<>();
        for (Object[] row : eventRepository.dailySeries(tenantId, adId)) {
            LocalDate day = (LocalDate) row[0];
            PromoEventType type = (PromoEventType) row[1];
            long count = (Long) row[2];
            long[] pair = byDay.computeIfAbsent(day, k -> new long[2]);
            if (type == PromoEventType.IMPRESSION) {
                pair[0] += count;
            } else {
                pair[1] += count;
            }
        }
        List<PromoAdStatsDTO.DayPoint> series = new ArrayList<>();
        byDay.forEach((day, pair) -> series.add(new PromoAdStatsDTO.DayPoint(day, pair[0], pair[1])));

        return new PromoAdStatsDTO(adId, t.impressions(), t.clicks(), rate, series);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.PromotionStatsServiceTest'
```

Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/PromotionStatsService.java backend/src/test/java/com/datagami/rentaxis/core/service/PromotionStatsServiceTest.java
git commit -m "feat(promotions): per-ad impression and click aggregates"
```

---

## Task 10: PromotionAdminController

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/api/PromotionAdminController.java`
- Test: `backend/src/test/java/com/datagami/rentaxis/api/PromotionAdminControllerTest.java`

Promotions are tenant-wide, not per-property, so `PROPERTY_MANAGER` is deliberately **not** granted access — no property-assignment check is needed here, unlike `AmenityController`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/datagami/rentaxis/api/PromotionAdminControllerTest.java`:

```java
package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.PromoAdDTO;
import com.datagami.rentaxis.api.dto.PromoAdRequest;
import com.datagami.rentaxis.api.dto.PromoBusinessDTO;
import com.datagami.rentaxis.api.dto.PromoBusinessRequest;
import com.datagami.rentaxis.core.service.PromotionService;
import com.datagami.rentaxis.core.service.PromotionStatsService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.PromoBusiness;
import com.datagami.rentaxis.domain.entity.enums.PromoCategory;
import com.datagami.rentaxis.domain.entity.enums.PromoCtaType;
import com.datagami.rentaxis.domain.repository.PromoAdPropertyRepository;
import com.datagami.rentaxis.domain.repository.PromoAdRepository;
import com.datagami.rentaxis.domain.repository.PromoBusinessRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionAdminControllerTest {

    @Mock PromotionService promotionService;
    @Mock PromotionStatsService statsService;
    @Mock PromoAdRepository adRepository;
    @Mock PromoAdPropertyRepository adPropertyRepository;
    @Mock PromoBusinessRepository businessRepository;

    @InjectMocks PromotionAdminController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID businessId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private PromoBusiness business() {
        PromoBusiness b = new PromoBusiness();
        b.setId(businessId);
        b.setTenantId(tenantId);
        b.setNameEn("Spice Bazaar");
        b.setCategory(PromoCategory.DINING);
        b.setAllowedDomains("spice-bazaar.ae,gym.example.com");
        return b;
    }

    private PromoAd ad() {
        PromoAd a = new PromoAd();
        a.setId(UUID.randomUUID());
        a.setTenantId(tenantId);
        a.setBusinessId(businessId);
        a.setTitleEn("Friday brunch");
        a.setCtaType(PromoCtaType.COUPON);
        return a;
    }

    @Test
    void listBusinesses_splitsAllowedDomainsIntoAList() {
        Page<PromoBusiness> page = new PageImpl<>(List.of(business()));
        when(promotionService.listBusinesses(eq(tenantId), any(Pageable.class))).thenReturn(page);
        when(adRepository.countByBusinessId(businessId)).thenReturn(2L);

        ResponseEntity<Page<PromoBusinessDTO>> res =
                controller.listBusinesses(PageRequest.of(0, 10));

        PromoBusinessDTO dto = res.getBody().getContent().get(0);
        assertThat(dto.allowedDomains()).containsExactly("spice-bazaar.ae", "gym.example.com");
        assertThat(dto.adCount()).isEqualTo(2L);
    }

    @Test
    void createBusiness_returns201() {
        when(promotionService.createBusiness(eq(tenantId), any(PromoBusinessRequest.class)))
                .thenReturn(business());

        ResponseEntity<PromoBusinessDTO> res = controller.createBusiness(new PromoBusinessRequest(
                "Spice Bazaar", null, null, PromoCategory.DINING, null, null, List.of(), null));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void listAds_batchesTargetingAndStatsRatherThanQueryingPerRow() {
        PromoAd a1 = ad();
        PromoAd a2 = ad();
        Page<PromoAd> page = new PageImpl<>(List.of(a1, a2));
        when(promotionService.listAds(eq(tenantId), eq(null), any(Pageable.class))).thenReturn(page);
        when(adPropertyRepository.findByAdIdIn(anyList())).thenReturn(List.of());
        when(statsService.totals(eq(tenantId), anyList())).thenReturn(Map.of(
                a1.getId(), new PromotionStatsService.Totals(100, 10)));
        when(businessRepository.findByIdIn(anyList())).thenReturn(List.of(business()));

        ResponseEntity<Page<PromoAdDTO>> res = controller.listAds(null, PageRequest.of(0, 10));

        List<PromoAdDTO> rows = res.getBody().getContent();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).impressions()).isEqualTo(100);
        assertThat(rows.get(1).impressions()).isZero();
        assertThat(rows.get(0).businessNameEn()).isEqualTo("Spice Bazaar");
        // Three batched calls for the whole page, never one per row.
        verify(adPropertyRepository).findByAdIdIn(anyList());
        verify(statsService).totals(eq(tenantId), anyList());
        verify(businessRepository).findByIdIn(anyList());
        verify(promotionService, never()).targetedPropertyIds(any());
        verify(promotionService, never()).getBusiness(any(), any());
    }

    @Test
    void listAds_skipsBatchQueriesForAnEmptyPage() {
        when(promotionService.listAds(eq(tenantId), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        controller.listAds(null, PageRequest.of(0, 10));

        verify(adPropertyRepository, never()).findByAdIdIn(anyList());
        verify(statsService, never()).totals(any(), anyList());
        verify(businessRepository, never()).findByIdIn(anyList());
    }

    @Test
    void deleteAd_returns204() {
        assertThat(controller.deleteAd(UUID.randomUUID()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        verify(promotionService).deleteAd(eq(tenantId), any(UUID.class));
    }

    @Test
    void stats_delegatesToTheStatsService() {
        UUID adId = UUID.randomUUID();
        controller.stats(adId);
        verify(statsService).stats(tenantId, adId);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
cd backend && ./gradlew test --tests 'com.datagami.rentaxis.api.PromotionAdminControllerTest'
```

Expected: FAIL — compilation error, `PromotionAdminController` does not exist.

- [ ] **Step 3: Write the implementation**

Create `backend/src/main/java/com/datagami/rentaxis/api/PromotionAdminController.java`:

```java
package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.PromoAdDTO;
import com.datagami.rentaxis.api.dto.PromoAdRequest;
import com.datagami.rentaxis.api.dto.PromoAdStatsDTO;
import com.datagami.rentaxis.api.dto.PromoBusinessDTO;
import com.datagami.rentaxis.api.dto.PromoBusinessRequest;
import com.datagami.rentaxis.core.service.PromotionService;
import com.datagami.rentaxis.core.service.PromotionStatsService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.PromoAdProperty;
import com.datagami.rentaxis.domain.entity.PromoBusiness;
import com.datagami.rentaxis.domain.repository.PromoAdPropertyRepository;
import com.datagami.rentaxis.domain.repository.PromoAdRepository;
import com.datagami.rentaxis.domain.repository.PromoBusinessRepository;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Admin configuration surface for cross-promotion. RBAC lives here, not in
 * {@link PromotionService} — the same split as the amenities and gate-pass
 * modules.
 *
 * <p>Promotions are tenant-wide rather than per-property, so PROPERTY_MANAGER
 * is deliberately excluded: there is no property assignment that would scope a
 * manager's view of the ad catalogue meaningfully.
 */
@RestController
@RequestMapping("/api/v1/promotions")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
public class PromotionAdminController {

    private final PromotionService promotionService;
    private final PromotionStatsService statsService;
    private final PromoAdRepository adRepository;
    private final PromoAdPropertyRepository adPropertyRepository;
    private final PromoBusinessRepository businessRepository;

    public PromotionAdminController(PromotionService promotionService,
                                    PromotionStatsService statsService,
                                    PromoAdRepository adRepository,
                                    PromoAdPropertyRepository adPropertyRepository,
                                    PromoBusinessRepository businessRepository) {
        this.promotionService = promotionService;
        this.statsService = statsService;
        this.adRepository = adRepository;
        this.adPropertyRepository = adPropertyRepository;
        this.businessRepository = businessRepository;
    }

    // ------------------------------------------------------------- businesses

    @GetMapping("/businesses")
    public ResponseEntity<Page<PromoBusinessDTO>> listBusinesses(
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable) {
        UUID tenantId = TenantContextHolder.getTenantId();
        return ResponseEntity.ok(promotionService.listBusinesses(tenantId, pageable)
                .map(b -> toDTO(b, adRepository.countByBusinessId(b.getId()))));
    }

    @PostMapping("/businesses")
    public ResponseEntity<PromoBusinessDTO> createBusiness(@Valid @RequestBody PromoBusinessRequest req) {
        UUID tenantId = TenantContextHolder.getTenantId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(toDTO(promotionService.createBusiness(tenantId, req), 0L));
    }

    @PutMapping("/businesses/{id}")
    public ResponseEntity<PromoBusinessDTO> updateBusiness(@PathVariable UUID id,
                                                           @Valid @RequestBody PromoBusinessRequest req) {
        UUID tenantId = TenantContextHolder.getTenantId();
        PromoBusiness updated = promotionService.updateBusiness(tenantId, id, req);
        return ResponseEntity.ok(toDTO(updated, adRepository.countByBusinessId(id)));
    }

    @DeleteMapping("/businesses/{id}")
    public ResponseEntity<Void> deleteBusiness(@PathVariable UUID id) {
        promotionService.deleteBusiness(TenantContextHolder.getTenantId(), id);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------- ads

    @GetMapping("/ads")
    public ResponseEntity<Page<PromoAdDTO>> listAds(
            @RequestParam(required = false) UUID businessId,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable) {
        UUID tenantId = TenantContextHolder.getTenantId();
        Page<PromoAd> page = promotionService.listAds(tenantId, businessId, pageable);

        List<UUID> adIds = page.getContent().stream().map(PromoAd::getId).toList();
        // Three batched queries for the whole page, never one per row.
        Map<UUID, List<UUID>> targeting = batchTargeting(adIds);
        Map<UUID, PromotionStatsService.Totals> totals =
                adIds.isEmpty() ? Map.of() : statsService.totals(tenantId, adIds);
        Map<UUID, String> businessNames = batchBusinessNames(page.getContent());

        return ResponseEntity.ok(page.map(a -> toDTO(a,
                businessNames.getOrDefault(a.getBusinessId(), ""),
                targeting.getOrDefault(a.getId(), List.of()),
                totals.getOrDefault(a.getId(), new PromotionStatsService.Totals(0, 0)))));
    }

    @PostMapping("/ads")
    public ResponseEntity<PromoAdDTO> createAd(@Valid @RequestBody PromoAdRequest req) {
        UUID tenantId = TenantContextHolder.getTenantId();
        PromoAd created = promotionService.createAd(tenantId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDTO(created,
                promotionService.getBusiness(tenantId, created.getBusinessId()).getNameEn(),
                promotionService.targetedPropertyIds(created.getId()),
                new PromotionStatsService.Totals(0, 0)));
    }

    @PutMapping("/ads/{id}")
    public ResponseEntity<PromoAdDTO> updateAd(@PathVariable UUID id,
                                               @Valid @RequestBody PromoAdRequest req) {
        UUID tenantId = TenantContextHolder.getTenantId();
        PromoAd updated = promotionService.updateAd(tenantId, id, req);
        return ResponseEntity.ok(toDTO(updated,
                promotionService.getBusiness(tenantId, updated.getBusinessId()).getNameEn(),
                promotionService.targetedPropertyIds(id),
                statsService.totals(tenantId, List.of(id))
                        .getOrDefault(id, new PromotionStatsService.Totals(0, 0))));
    }

    @DeleteMapping("/ads/{id}")
    public ResponseEntity<Void> deleteAd(@PathVariable UUID id) {
        promotionService.deleteAd(TenantContextHolder.getTenantId(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/ads/{id}/stats")
    public ResponseEntity<PromoAdStatsDTO> stats(@PathVariable UUID id) {
        return ResponseEntity.ok(statsService.stats(TenantContextHolder.getTenantId(), id));
    }

    // ----------------------------------------------------------------- mapping

    private Map<UUID, String> batchBusinessNames(List<PromoAd> ads) {
        if (ads.isEmpty()) {
            return Map.of();
        }
        List<UUID> businessIds = ads.stream().map(PromoAd::getBusinessId).distinct().toList();
        return businessRepository.findByIdIn(businessIds).stream()
                .collect(Collectors.toMap(PromoBusiness::getId, PromoBusiness::getNameEn));
    }

    private Map<UUID, List<UUID>> batchTargeting(List<UUID> adIds) {
        if (adIds.isEmpty()) {
            return Map.of();
        }
        return adPropertyRepository.findByAdIdIn(adIds).stream()
                .collect(Collectors.groupingBy(PromoAdProperty::getAdId,
                        Collectors.mapping(PromoAdProperty::getPropertyId, Collectors.toList())));
    }

    private PromoBusinessDTO toDTO(PromoBusiness b, long adCount) {
        List<String> domains = b.getAllowedDomains() == null || b.getAllowedDomains().isBlank()
                ? List.of()
                : Arrays.stream(b.getAllowedDomains().split(",")).map(String::trim).toList();
        return new PromoBusinessDTO(b.getId(), b.getNameEn(), b.getNameAr(), b.getLogoUrl(),
                b.getCategory(), b.getPhoneE164(), b.getWhatsappE164(), domains,
                b.isActive(), adCount, b.getCreatedAt(), b.getUpdatedAt());
    }

    private PromoAdDTO toDTO(PromoAd a, String businessName, List<UUID> propertyIds,
                            PromotionStatsService.Totals totals) {
        return new PromoAdDTO(a.getId(), a.getBusinessId(), businessName,
                a.getTitleEn(), a.getTitleAr(), a.getSubtitleEn(), a.getSubtitleAr(),
                a.getBackgroundImageUrl(), a.getAccentColor(),
                a.getCtaType(), a.getCtaLabelEn(), a.getCtaLabelAr(), a.getCtaUrl(),
                a.getCouponCode(), a.getCouponTermsEn(), a.getCouponTermsAr(),
                a.getStartsAt(), a.getEndsAt(), a.getPriority(), a.getPlacement(), a.isActive(),
                propertyIds, totals.impressions(), totals.clicks(),
                a.getCreatedAt(), a.getUpdatedAt());
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
cd backend && ./gradlew test --tests 'com.datagami.rentaxis.api.PromotionAdminControllerTest'
```

Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/PromotionAdminController.java backend/src/test/java/com/datagami/rentaxis/api/PromotionAdminControllerTest.java
git commit -m "feat(promotions): admin CRUD endpoints for businesses and ads"
```

---

## Task 11: PromotionFeedController

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/api/PromotionFeedController.java`
- Test: `backend/src/test/java/com/datagami/rentaxis/api/PromotionFeedControllerTest.java`

**Important:** identify the caller from the `SecurityContext` (`auth.getName()` is the user id, as in `AmenityController.checkPropertyManagerAccess`), **not** from the `X-User-Id` request header. Some older controllers read that header; do not copy them here — a client-supplied header is not an identity.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/datagami/rentaxis/api/PromotionFeedControllerTest.java`:

```java
package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.PromoEventBatchRequest;
import com.datagami.rentaxis.core.service.PromotionFeedService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.enums.PromoCategory;
import com.datagami.rentaxis.domain.entity.enums.PromoEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionFeedControllerTest {

    @Mock PromotionFeedService feedService;
    @InjectMocks PromotionFeedController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID renterId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(tenantId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(renterId.toString(), null,
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_RENTER"))));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void feed_identifiesTheRenterFromTheSecurityContext() {
        when(feedService.homeFeed(tenantId, renterId)).thenReturn(List.of());

        controller.feed();

        // Never from an X-User-Id header — a client-supplied header is not an identity.
        verify(feedService).homeFeed(tenantId, renterId);
    }

    @Test
    void offers_passesTheCategoryFilterThrough() {
        when(feedService.offers(tenantId, renterId, PromoCategory.DINING)).thenReturn(List.of());

        controller.offers(PromoCategory.DINING);

        verify(feedService).offers(tenantId, renterId, PromoCategory.DINING);
    }

    @Test
    void events_returns202AndDelegates() {
        PromoEventBatchRequest batch = new PromoEventBatchRequest(List.of(
                new PromoEventBatchRequest.Event(UUID.randomUUID(), PromoEventType.IMPRESSION)));

        assertThat(controller.events(batch).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(feedService).recordEvents(tenantId, renterId, batch);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
cd backend && ./gradlew test --tests 'com.datagami.rentaxis.api.PromotionFeedControllerTest'
```

Expected: FAIL — compilation error, `PromotionFeedController` does not exist.

- [ ] **Step 3: Write the implementation**

Create `backend/src/main/java/com/datagami/rentaxis/api/PromotionFeedController.java`:

```java
package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.PromoAdCardDTO;
import com.datagami.rentaxis.api.dto.PromoEventBatchRequest;
import com.datagami.rentaxis.core.service.PromotionFeedService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.enums.PromoCategory;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * What the renter mobile app calls. The caller's identity comes from the
 * SecurityContext, never from an X-User-Id request header — a client-supplied
 * header is not an identity, and this endpoint decides what a specific renter
 * is allowed to see.
 */
@RestController
@RequestMapping("/api/v1/promotions")
@PreAuthorize("hasRole('RENTER')")
public class PromotionFeedController {

    private final PromotionFeedService feedService;

    public PromotionFeedController(PromotionFeedService feedService) {
        this.feedService = feedService;
    }

    /** The home carousel slate — at most six cards, stable for the day. */
    @GetMapping("/feed")
    public ResponseEntity<List<PromoAdCardDTO>> feed() {
        return ResponseEntity.ok(
                feedService.homeFeed(TenantContextHolder.getTenantId(), currentRenterId()));
    }

    @GetMapping("/offers")
    public ResponseEntity<List<PromoAdCardDTO>> offers(
            @RequestParam(required = false) PromoCategory category) {
        return ResponseEntity.ok(
                feedService.offers(TenantContextHolder.getTenantId(), currentRenterId(), category));
    }

    /**
     * 202, not 200 — the app fires this on dispose and on app background and
     * does not wait on the result. Unknown or ineligible ad ids are dropped by
     * the service rather than failing the batch.
     */
    @PostMapping("/events")
    public ResponseEntity<Void> events(@Valid @RequestBody PromoEventBatchRequest batch) {
        feedService.recordEvents(TenantContextHolder.getTenantId(), currentRenterId(), batch);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    private UUID currentRenterId() {
        return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName());
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
cd backend && ./gradlew test --tests 'com.datagami.rentaxis.api.PromotionFeedControllerTest'
```

Expected: PASS, 3 tests.

- [ ] **Step 5: Run the whole backend suite**

Run:

```bash
cd backend && ./gradlew test
```

Expected: `BUILD SUCCESSFUL`, no regressions in the existing suites.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/PromotionFeedController.java backend/src/test/java/com/datagami/rentaxis/api/PromotionFeedControllerTest.java
git commit -m "feat(promotions): renter feed, offers and event endpoints"
```

---

## Task 12: Web foundations — types, API client, RBAC, nav, translations

**Files:**
- Create: `web/src/types/promotion.ts`
- Create: `web/src/lib/api/promotions.ts`
- Modify: `web/src/lib/rbac.ts`
- Modify: `web/src/components/ui/MvpSidebar.tsx`
- Modify: `web/messages/en.json`, `web/messages/ar.json`

No tests in this task — types and thin fetch wrappers. Task 14 tests the behaviour built on top of them.

- [ ] **Step 1: Write the types**

Create `web/src/types/promotion.ts`:

```typescript
export type PromoCategory =
  | 'DINING' | 'FITNESS' | 'RETAIL' | 'SERVICES' | 'HEALTH' | 'EDUCATION' | 'OTHER'

export type PromoCtaType = 'WEBSITE' | 'COUPON' | 'CALL' | 'WHATSAPP' | 'NONE'

export type PromoPlacement = 'HOME_AND_OFFERS' | 'OFFERS_ONLY'

export const PROMO_CATEGORIES: PromoCategory[] =
  ['DINING', 'FITNESS', 'RETAIL', 'SERVICES', 'HEALTH', 'EDUCATION', 'OTHER']

export const PROMO_CTA_TYPES: PromoCtaType[] =
  ['NONE', 'WEBSITE', 'COUPON', 'CALL', 'WHATSAPP']

export interface PromoBusinessDTO {
  id: string
  nameEn: string
  nameAr: string | null
  logoUrl: string | null
  category: PromoCategory
  phoneE164: string | null
  whatsappE164: string | null
  allowedDomains: string[]
  active: boolean
  adCount: number
  createdAt: string
  updatedAt: string
}

export interface PromoBusinessRequest {
  nameEn: string
  nameAr?: string | null
  logoUrl?: string | null
  category?: PromoCategory
  phoneE164?: string | null
  whatsappE164?: string | null
  allowedDomains?: string[]
  active?: boolean
}

export interface PromoAdDTO {
  id: string
  businessId: string
  businessNameEn: string
  titleEn: string | null
  titleAr: string | null
  subtitleEn: string | null
  subtitleAr: string | null
  backgroundImageUrl: string | null
  accentColor: string | null
  ctaType: PromoCtaType
  ctaLabelEn: string | null
  ctaLabelAr: string | null
  ctaUrl: string | null
  couponCode: string | null
  couponTermsEn: string | null
  couponTermsAr: string | null
  startsAt: string | null
  endsAt: string | null
  priority: number
  placement: PromoPlacement
  active: boolean
  propertyIds: string[]
  impressions: number
  clicks: number
  createdAt: string
  updatedAt: string
}

export interface PromoAdRequest {
  businessId: string
  titleEn?: string | null
  titleAr?: string | null
  subtitleEn?: string | null
  subtitleAr?: string | null
  backgroundImageUrl?: string | null
  accentColor?: string | null
  ctaType?: PromoCtaType
  ctaLabelEn?: string | null
  ctaLabelAr?: string | null
  ctaUrl?: string | null
  couponCode?: string | null
  couponTermsEn?: string | null
  couponTermsAr?: string | null
  startsAt?: string | null
  endsAt?: string | null
  priority?: number
  placement?: PromoPlacement
  propertyIds?: string[]
  active?: boolean
}

export interface PromoAdStatsDTO {
  adId: string
  impressions: number
  clicks: number
  tapThroughRate: number
  series: Array<{ day: string; impressions: number; clicks: number }>
}

/** Derived, not stored — the backend keeps `active` and the window separately. */
export type AdStatus = 'LIVE' | 'SCHEDULED' | 'EXPIRED' | 'PAUSED'

export function adStatus(ad: PromoAdDTO, now: Date = new Date()): AdStatus {
  if (!ad.active) return 'PAUSED'
  if (ad.startsAt && new Date(ad.startsAt) > now) return 'SCHEDULED'
  if (ad.endsAt && new Date(ad.endsAt) <= now) return 'EXPIRED'
  return 'LIVE'
}
```

- [ ] **Step 2: Write the API client**

Create `web/src/lib/api/promotions.ts`:

```typescript
import type { PageResponse } from '@/types/listing'
import { ApiError, throwIfNotOk } from '@/lib/api/facilities'
import type {
  PromoAdDTO,
  PromoAdRequest,
  PromoAdStatsDTO,
  PromoBusinessDTO,
  PromoBusinessRequest,
} from '@/types/promotion'

// Auth is injected by the Next.js middleware for every /api/proxy/* request
// (see src/proxy.ts) — no Authorization header needed here.
const BUSINESSES = '/api/proxy/v1/promotions/businesses'
const ADS = '/api/proxy/v1/promotions/ads'

const JSON_HEADERS = { 'Content-Type': 'application/json' }

export { ApiError }

export async function fetchBusinesses(
  page: number, size: number,
): Promise<PageResponse<PromoBusinessDTO>> {
  const res = await fetch(`${BUSINESSES}?page=${page}&size=${size}`)
  await throwIfNotOk(res)
  return res.json()
}

export async function createBusiness(body: PromoBusinessRequest): Promise<PromoBusinessDTO> {
  const res = await fetch(BUSINESSES, {
    method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(body),
  })
  await throwIfNotOk(res)
  return res.json()
}

export async function updateBusiness(
  id: string, body: PromoBusinessRequest,
): Promise<PromoBusinessDTO> {
  const res = await fetch(`${BUSINESSES}/${id}`, {
    method: 'PUT', headers: JSON_HEADERS, body: JSON.stringify(body),
  })
  await throwIfNotOk(res)
  return res.json()
}

export async function deleteBusiness(id: string): Promise<void> {
  const res = await fetch(`${BUSINESSES}/${id}`, { method: 'DELETE' })
  await throwIfNotOk(res)
}

export async function fetchAds(
  page: number, size: number, businessId?: string,
): Promise<PageResponse<PromoAdDTO>> {
  const q = new URLSearchParams({ page: String(page), size: String(size) })
  if (businessId) q.set('businessId', businessId)
  const res = await fetch(`${ADS}?${q}`)
  await throwIfNotOk(res)
  return res.json()
}

export async function createAd(body: PromoAdRequest): Promise<PromoAdDTO> {
  const res = await fetch(ADS, {
    method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(body),
  })
  await throwIfNotOk(res)
  return res.json()
}

export async function updateAd(id: string, body: PromoAdRequest): Promise<PromoAdDTO> {
  const res = await fetch(`${ADS}/${id}`, {
    method: 'PUT', headers: JSON_HEADERS, body: JSON.stringify(body),
  })
  await throwIfNotOk(res)
  return res.json()
}

export async function deleteAd(id: string): Promise<void> {
  const res = await fetch(`${ADS}/${id}`, { method: 'DELETE' })
  await throwIfNotOk(res)
}

export async function fetchAdStats(id: string): Promise<PromoAdStatsDTO> {
  const res = await fetch(`${ADS}/${id}/stats`)
  await throwIfNotOk(res)
  return res.json()
}

/** Reuses the shared asset endpoint — image-only, 2 MB cap, enforced server-side. */
export async function uploadPromoImage(file: File, folder: string): Promise<string> {
  const form = new FormData()
  form.append('file', file)
  form.append('folder', folder)
  const res = await fetch(`/api/proxy/v1/assets/upload?folder=${encodeURIComponent(folder)}`, {
    method: 'POST', body: form,
  })
  await throwIfNotOk(res)
  const data: { url?: string } = await res.json()
  if (!data.url) throw new ApiError(500, 'Upload succeeded but returned no URL')
  return data.url
}
```

**Note for the implementer:** confirm `throwIfNotOk` and `ApiError` are exported from `web/src/lib/api/facilities.ts`. If they are not exported, export them there rather than duplicating the error-parsing logic — that parser handles several distinct backend error shapes and must not fork.

- [ ] **Step 3: Add the permission**

In `web/src/lib/rbac.ts`, inside the `PERMISSIONS` object, next to `canManageFacilities`:

```typescript
    canManagePromotions: ['SUPER_ADMIN', 'TENANT_ADMIN'] as UserRole[],
```

Promotions are tenant-wide, so `PROPERTY_MANAGER` is excluded — matching `PromotionAdminController`'s `@PreAuthorize`.

- [ ] **Step 4: Add the nav entry**

In `web/src/components/ui/MvpSidebar.tsx`, alongside the existing bookings entry (around line 91), add — using the same conditional-spread shape the file already uses, and importing `Megaphone` from `lucide-react`:

```tsx
                    ...(hasPermission(userRole, "canManagePromotions")
                        ? [{ name: tPromotions("navLabel"), href: "/dashboard/promotions", icon: Megaphone, tourId: 'sidebar-promotions' }]
                        : []),
```

Add `const tPromotions = useTranslations("Promotions");` next to the file's other `useTranslations` calls.

- [ ] **Step 5: Add the translation keys**

Add a `Promotions` namespace to `web/messages/en.json`:

```json
  "Promotions": {
    "navLabel": "Promotions",
    "title": "Promotions",
    "businessesTab": "Businesses",
    "adsTab": "Ads",
    "addBusiness": "Add business",
    "addAd": "Add ad",
    "name": "Name",
    "nameEn": "Name (English)",
    "nameAr": "Name (Arabic)",
    "category": "Category",
    "logo": "Logo",
    "phone": "Phone",
    "whatsapp": "WhatsApp",
    "allowedDomains": "Allowed link domains",
    "allowedDomainsHint": "Ads for this business can only link to these domains. Paste a full URL and we will keep the domain.",
    "active": "Active",
    "adCount": "Ads",
    "artwork": "Artwork",
    "adTitle": "Title",
    "adTitleEn": "Title (English)",
    "adTitleAr": "Title (Arabic)",
    "subtitleEn": "Eyebrow (English)",
    "subtitleAr": "Eyebrow (Arabic)",
    "business": "Business",
    "ctaType": "What happens on tap",
    "ctaTypeNONE": "Nothing",
    "ctaTypeWEBSITE": "Open website",
    "ctaTypeCOUPON": "Show coupon code",
    "ctaTypeCALL": "Call the business",
    "ctaTypeWHATSAPP": "Open WhatsApp",
    "ctaLabelEn": "Button label (English)",
    "ctaLabelAr": "Button label (Arabic)",
    "ctaUrl": "Link",
    "couponCode": "Coupon code",
    "couponTermsEn": "Terms (English)",
    "couponTermsAr": "Terms (Arabic)",
    "accentColor": "Card colour",
    "startsAt": "Starts",
    "endsAt": "Ends",
    "priority": "Priority",
    "priorityHint": "Higher priority means more airtime in the home carousel.",
    "placement": "Where it shows",
    "placementHOME_AND_OFFERS": "Home carousel and offers",
    "placementOFFERS_ONLY": "Offers screen only",
    "targeting": "Show to",
    "targetingAll": "Everyone",
    "targetingSome": "Selected properties",
    "status": "Status",
    "statusLIVE": "Live",
    "statusSCHEDULED": "Scheduled",
    "statusEXPIRED": "Expired",
    "statusPAUSED": "Paused",
    "views": "Views",
    "taps": "Taps",
    "tapRate": "Tap rate",
    "preview": "Preview",
    "previewEn": "English",
    "previewAr": "Arabic",
    "save": "Save",
    "cancel": "Cancel",
    "edit": "Edit",
    "delete": "Delete",
    "deleteBusinessBlocked": "This business has ads. Deactivate it instead of deleting it.",
    "domainNotAllowed": "This link is not on one of the business's allowed domains.",
    "httpsRequired": "Links must start with https://",
    "loadError": "Could not load promotions. Try again.",
    "saveError": "Could not save. Check the highlighted fields.",
    "emptyBusinesses": "Add your first business to start cross-promoting.",
    "emptyAds": "No ads yet. Create one to fill the home carousel."
  },
```

Add the same keys to `web/messages/ar.json` with Arabic values. Use `"navLabel": "العروض"`, `"title": "العروض"`, `"businessesTab": "الشركات"`, `"adsTab": "الإعلانات"`, `"addBusiness": "إضافة شركة"`, `"addAd": "إضافة إعلان"`, `"statusLIVE": "نشط"`, `"statusSCHEDULED": "مجدول"`, `"statusEXPIRED": "منتهي"`, `"statusPAUSED": "متوقف"`, `"views": "المشاهدات"`, `"taps": "النقرات"`, `"save": "حفظ"`, `"cancel": "إلغاء"`, `"edit": "تعديل"`, `"delete": "حذف"`, and translate the rest in the same register as the neighbouring `Bookings` namespace.

- [ ] **Step 6: Verify it type-checks**

Run:

```bash
cd web && npx tsc --noEmit
```

Expected: no errors. Unused-import warnings from the sidebar edit are errors under this config — remove any import you did not use.

- [ ] **Step 7: Commit**

```bash
git add web/src/types/promotion.ts web/src/lib/api/promotions.ts web/src/lib/rbac.ts web/src/components/ui/MvpSidebar.tsx web/messages/
git commit -m "feat(promotions): web types, api client, permission and nav entry"
```

---

## Task 13: AdCardPreview — the mobile card, rendered in the admin panel

**Files:**
- Create: `web/src/app/[locale]/dashboard/promotions/_components/AdCardPreview.tsx`

The whole point of the live preview is that the client can see what a renter will see before saving. It must therefore mirror the Flutter card's rules exactly: photo-hero when there is a background image, split colour card when there is not, uppercase eyebrow, two-line clamps, and a pill CTA. The Miftah token values are hardcoded here deliberately — this is a picture of another app's UI, not a piece of this app's chrome.

- [ ] **Step 1: Write the component**

Create `web/src/app/[locale]/dashboard/promotions/_components/AdCardPreview.tsx`:

```tsx
"use client";

import type { PromoCtaType } from "@/types/promotion";

/** Miftah mobile tokens — see mobile/packages/rentaxis_core/lib/ui/miftah_tokens.dart. */
const INK = "#12101A";
const SURFACE = "#FFFFFF";
const SURFACE_ALT = "#F4F2F9";
const BRASS_TINT = "#FBF3E2";
const WARNING = "#8A6412";
const TEXT_SECONDARY = "#4A4358";

export interface AdCardPreviewProps {
    title: string;
    subtitle?: string | null;
    businessName?: string | null;
    backgroundImageUrl?: string | null;
    accentColor?: string | null;
    ctaType: PromoCtaType;
    ctaLabel?: string | null;
    rtl?: boolean;
}

/** Matches AdsCarousel's default labels so the preview never over-promises. */
export function defaultCtaLabel(type: PromoCtaType, rtl: boolean): string {
    switch (type) {
        case "WEBSITE": return rtl ? "زيارة الموقع" : "Visit site";
        case "COUPON": return rtl ? "استخدام الكوبون" : "Redeem coupon";
        case "CALL": return rtl ? "اتصال" : "Call";
        case "WHATSAPP": return rtl ? "واتساب" : "WhatsApp";
        default: return "";
    }
}

export function AdCardPreview({
    title, subtitle, businessName, backgroundImageUrl, accentColor,
    ctaType, ctaLabel, rtl = false,
}: AdCardPreviewProps) {
    const hasImage = Boolean(backgroundImageUrl);
    const fill = accentColor ?? SURFACE_ALT;
    const fg = hasImage ? "#FFFFFF" : INK;
    const label = (ctaLabel?.trim() || defaultCtaLabel(ctaType, rtl));

    const clamp2: React.CSSProperties = {
        display: "-webkit-box",
        WebkitLineClamp: 2,
        WebkitBoxOrient: "vertical",
        overflow: "hidden",
    };

    return (
        <div
            dir={rtl ? "rtl" : "ltr"}
            data-testid="ad-card-preview"
            className="relative flex h-[140px] w-[300px] flex-col justify-between overflow-hidden p-[14px]"
            style={{
                borderRadius: 20,
                background: hasImage ? undefined : fill,
                border: hasImage ? undefined : `1px solid ${SURFACE_ALT}`,
            }}
        >
            {hasImage && (
                <>
                    <img
                        src={backgroundImageUrl!}
                        alt=""
                        className="absolute inset-0 h-full w-full object-cover"
                    />
                    {/* The Flutter card darkens the image by 0.35 so white copy stays legible. */}
                    <div className="absolute inset-0" style={{ background: "rgba(0,0,0,0.35)" }} />
                </>
            )}

            <div className="relative">
                {(subtitle || businessName) && (
                    <p
                        style={{
                            ...clamp2,
                            color: hasImage ? "#E7C883" : WARNING,
                            fontSize: 11,
                            fontWeight: 800,
                            letterSpacing: "0.08em",
                            textTransform: "uppercase",
                            margin: 0,
                        }}
                    >
                        {subtitle || businessName}
                    </p>
                )}
                <p
                    style={{
                        ...clamp2,
                        color: fg,
                        fontSize: 18,
                        fontWeight: 800,
                        lineHeight: 1.1,
                        margin: "3px 0 0",
                    }}
                >
                    {title || (rtl ? "عنوان الإعلان" : "Ad title")}
                </p>
                {!hasImage && businessName && subtitle && (
                    <p style={{ color: TEXT_SECONDARY, fontSize: 12, margin: "3px 0 0" }}>
                        {businessName}
                    </p>
                )}
            </div>

            {ctaType !== "NONE" && (
                <div className="relative">
                    <span
                        data-testid="ad-card-cta"
                        style={{
                            display: "inline-block",
                            background: hasImage ? SURFACE : INK,
                            color: hasImage ? INK : SURFACE,
                            borderRadius: 9999,
                            padding: "7px 14px",
                            fontSize: 11,
                            fontWeight: 800,
                        }}
                    >
                        {`${label} ${rtl ? "←" : "→"}`}
                    </span>
                </div>
            )}

            {!hasImage && !accentColor && (
                <span className="sr-only">{`Fallback card colour ${BRASS_TINT}`}</span>
            )}
        </div>
    );
}
```

- [ ] **Step 2: Verify it type-checks**

Run:

```bash
cd web && npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add "web/src/app/[locale]/dashboard/promotions/_components/AdCardPreview.tsx"
git commit -m "feat(promotions): live preview of the mobile ad card"
```

---

## Task 14: AdEditor — the ad form

**Files:**
- Create: `web/src/app/[locale]/dashboard/promotions/_components/AdEditor.tsx`
- Test: `web/src/app/[locale]/dashboard/promotions/__tests__/AdEditor.test.tsx`

- [ ] **Step 1: Write the failing test**

Create `web/src/app/[locale]/dashboard/promotions/__tests__/AdEditor.test.tsx`:

```tsx
import { describe, expect, it, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { NextIntlClientProvider } from 'next-intl'
import messages from '../../../../../../messages/en.json'
import { AdEditor } from '../_components/AdEditor'
import type { PromoBusinessDTO } from '@/types/promotion'

const business: PromoBusinessDTO = {
    id: 'b-1',
    nameEn: 'Spice Bazaar',
    nameAr: null,
    logoUrl: null,
    category: 'DINING',
    phoneE164: '+971501234567',
    whatsappE164: null,
    allowedDomains: ['spice-bazaar.ae'],
    active: true,
    adCount: 0,
    createdAt: '2026-08-01T00:00:00Z',
    updatedAt: '2026-08-01T00:00:00Z',
}

function renderEditor(onSave = vi.fn()) {
    render(
        <NextIntlClientProvider locale="en" messages={messages}>
            <AdEditor
                businesses={[business]}
                properties={[]}
                ad={null}
                onSave={onSave}
                onCancel={vi.fn()}
            />
        </NextIntlClientProvider>,
    )
    return { onSave }
}

describe('AdEditor', () => {
    it('shows the link field only for a website ad', () => {
        renderEditor()
        expect(screen.queryByLabelText('Link')).not.toBeInTheDocument()

        fireEvent.change(screen.getByLabelText('What happens on tap'), {
            target: { value: 'WEBSITE' },
        })
        expect(screen.getByLabelText('Link')).toBeInTheDocument()
        expect(screen.queryByLabelText('Coupon code')).not.toBeInTheDocument()
    })

    it('shows the coupon fields only for a coupon ad', () => {
        renderEditor()
        fireEvent.change(screen.getByLabelText('What happens on tap'), {
            target: { value: 'COUPON' },
        })
        expect(screen.getByLabelText('Coupon code')).toBeInTheDocument()
        expect(screen.queryByLabelText('Link')).not.toBeInTheDocument()
    })

    it('rejects a link that is not on an allowed domain, before submitting', () => {
        const { onSave } = renderEditor()
        fireEvent.change(screen.getByLabelText('Title (English)'), {
            target: { value: 'Friday brunch' },
        })
        fireEvent.change(screen.getByLabelText('What happens on tap'), {
            target: { value: 'WEBSITE' },
        })
        fireEvent.change(screen.getByLabelText('Link'), {
            target: { value: 'https://evil.com/x' },
        })
        fireEvent.click(screen.getByRole('button', { name: 'Save' }))

        expect(screen.getByText(/not on one of the business's allowed domains/i))
            .toBeInTheDocument()
        expect(onSave).not.toHaveBeenCalled()
    })

    it('rejects a non-https link', () => {
        const { onSave } = renderEditor()
        fireEvent.change(screen.getByLabelText('Title (English)'), {
            target: { value: 'Friday brunch' },
        })
        fireEvent.change(screen.getByLabelText('What happens on tap'), {
            target: { value: 'WEBSITE' },
        })
        fireEvent.change(screen.getByLabelText('Link'), {
            target: { value: 'http://spice-bazaar.ae/x' },
        })
        fireEvent.click(screen.getByRole('button', { name: 'Save' }))

        expect(screen.getByText(/must start with https/i)).toBeInTheDocument()
        expect(onSave).not.toHaveBeenCalled()
    })

    it('rejects a link carrying userinfo, matching the backend', () => {
        const { onSave } = renderEditor()
        fireEvent.change(screen.getByLabelText('Title (English)'), {
            target: { value: 'Friday brunch' },
        })
        fireEvent.change(screen.getByLabelText('What happens on tap'), {
            target: { value: 'WEBSITE' },
        })
        fireEvent.change(screen.getByLabelText('Link'), {
            target: { value: 'https://my-bank.com@spice-bazaar.ae/pay' },
        })
        fireEvent.click(screen.getByRole('button', { name: 'Save' }))

        expect(onSave).not.toHaveBeenCalled()
    })

    it('accepts a subdomain of an allowed domain', () => {
        const { onSave } = renderEditor()
        fireEvent.change(screen.getByLabelText('Title (English)'), {
            target: { value: 'Friday brunch' },
        })
        fireEvent.change(screen.getByLabelText('What happens on tap'), {
            target: { value: 'WEBSITE' },
        })
        fireEvent.change(screen.getByLabelText('Link'), {
            target: { value: 'https://offers.spice-bazaar.ae/friday' },
        })
        fireEvent.click(screen.getByRole('button', { name: 'Save' }))

        expect(onSave).toHaveBeenCalledTimes(1)
        expect(onSave.mock.calls[0][0]).toMatchObject({
            ctaType: 'WEBSITE',
            ctaUrl: 'https://offers.spice-bazaar.ae/friday',
        })
    })

    it('refuses to save without a title in either language', () => {
        const { onSave } = renderEditor()
        fireEvent.click(screen.getByRole('button', { name: 'Save' }))
        expect(onSave).not.toHaveBeenCalled()
    })

    it('refuses a call ad when the business has no phone number', () => {
        const noPhone = { ...business, phoneE164: null }
        const onSave = vi.fn()
        render(
            <NextIntlClientProvider locale="en" messages={messages}>
                <AdEditor businesses={[noPhone]} properties={[]} ad={null}
                    onSave={onSave} onCancel={vi.fn()} />
            </NextIntlClientProvider>,
        )
        fireEvent.change(screen.getByLabelText('Title (English)'), {
            target: { value: 'Call us' },
        })
        fireEvent.change(screen.getByLabelText('What happens on tap'), {
            target: { value: 'CALL' },
        })
        fireEvent.click(screen.getByRole('button', { name: 'Save' }))

        expect(onSave).not.toHaveBeenCalled()
    })

    it('renders the live preview with the typed title', () => {
        renderEditor()
        fireEvent.change(screen.getByLabelText('Title (English)'), {
            target: { value: 'Friday brunch' },
        })
        expect(screen.getByTestId('ad-card-preview')).toHaveTextContent('Friday brunch')
    })

    it('drops the coupon code when the CTA switches away from coupon', () => {
        const { onSave } = renderEditor()
        fireEvent.change(screen.getByLabelText('Title (English)'), {
            target: { value: 'Brunch' },
        })
        fireEvent.change(screen.getByLabelText('What happens on tap'), {
            target: { value: 'COUPON' },
        })
        fireEvent.change(screen.getByLabelText('Coupon code'), {
            target: { value: 'MIFTAH25' },
        })
        fireEvent.change(screen.getByLabelText('What happens on tap'), {
            target: { value: 'NONE' },
        })
        fireEvent.click(screen.getByRole('button', { name: 'Save' }))

        expect(onSave.mock.calls[0][0].couponCode).toBeNull()
    })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
cd web && npx vitest run src/app/\[locale\]/dashboard/promotions/__tests__/AdEditor.test.tsx
```

Expected: FAIL — cannot resolve `../_components/AdEditor`.

- [ ] **Step 3: Write the implementation**

Create `web/src/app/[locale]/dashboard/promotions/_components/AdEditor.tsx`:

```tsx
"use client";

import { useMemo, useState } from "react";
import { useTranslations } from "next-intl";
import { AdCardPreview } from "./AdCardPreview";
import {
    PROMO_CTA_TYPES,
    type PromoAdDTO,
    type PromoAdRequest,
    type PromoBusinessDTO,
    type PromoCtaType,
    type PromoPlacement,
} from "@/types/promotion";

export interface PropertyOption {
    id: string;
    nameEn: string;
}

interface AdEditorProps {
    businesses: PromoBusinessDTO[];
    properties: PropertyOption[];
    ad: PromoAdDTO | null;
    onSave: (body: PromoAdRequest) => void;
    onCancel: () => void;
}

/**
 * Mirrors PromotionUrlValidator.isAllowed so the client sees a field-level
 * error instead of a round-trip 400. The backend check is still the authority
 * — this is a convenience layer, never the security boundary.
 *
 * Two implementations of a security predicate drift. If you change the rules
 * here, change them in `PromotionUrlValidator` too, and vice versa. In
 * particular the backend rejects userinfo outright, so this must as well or
 * the form will call a URL valid that the server then refuses.
 *
 * Note `domains` arrives already normalised by the backend (it is the parsed
 * `allowedDomains` list off the business DTO), so no host surgery is needed
 * here — matching the raw string the admin typed is not this function's job.
 */
function hostIsAllowed(url: string, domains: string[]): boolean {
    let parsed: URL;
    try {
        parsed = new URL(url);
    } catch {
        return false;
    }
    if (parsed.username !== "" || parsed.password !== "") return false;
    const host = parsed.hostname.toLowerCase();
    return domains.some(d => {
        const clean = d.trim().toLowerCase();
        return clean !== "" && (host === clean || host.endsWith(`.${clean}`));
    });
}

const trimOrNull = (s: string): string | null => (s.trim() === "" ? null : s.trim());

export function AdEditor({ businesses, properties, ad, onSave, onCancel }: AdEditorProps) {
    const t = useTranslations("Promotions");

    const [businessId, setBusinessId] = useState(ad?.businessId ?? businesses[0]?.id ?? "");
    const [titleEn, setTitleEn] = useState(ad?.titleEn ?? "");
    const [titleAr, setTitleAr] = useState(ad?.titleAr ?? "");
    const [subtitleEn, setSubtitleEn] = useState(ad?.subtitleEn ?? "");
    const [subtitleAr, setSubtitleAr] = useState(ad?.subtitleAr ?? "");
    const [backgroundImageUrl, setBackgroundImageUrl] = useState(ad?.backgroundImageUrl ?? "");
    const [accentColor, setAccentColor] = useState(ad?.accentColor ?? "");
    const [ctaType, setCtaType] = useState<PromoCtaType>(ad?.ctaType ?? "NONE");
    const [ctaLabelEn, setCtaLabelEn] = useState(ad?.ctaLabelEn ?? "");
    const [ctaLabelAr, setCtaLabelAr] = useState(ad?.ctaLabelAr ?? "");
    const [ctaUrl, setCtaUrl] = useState(ad?.ctaUrl ?? "");
    const [couponCode, setCouponCode] = useState(ad?.couponCode ?? "");
    const [couponTermsEn, setCouponTermsEn] = useState(ad?.couponTermsEn ?? "");
    const [couponTermsAr, setCouponTermsAr] = useState(ad?.couponTermsAr ?? "");
    const [startsAt, setStartsAt] = useState(ad?.startsAt?.slice(0, 10) ?? "");
    const [endsAt, setEndsAt] = useState(ad?.endsAt?.slice(0, 10) ?? "");
    const [priority, setPriority] = useState(ad?.priority ?? 1);
    const [placement, setPlacement] = useState<PromoPlacement>(ad?.placement ?? "HOME_AND_OFFERS");
    const [propertyIds, setPropertyIds] = useState<string[]>(ad?.propertyIds ?? []);
    const [active, setActive] = useState(ad?.active ?? true);
    const [previewAr, setPreviewAr] = useState(false);
    const [errors, setErrors] = useState<Record<string, string>>({});

    const business = useMemo(
        () => businesses.find(b => b.id === businessId) ?? null,
        [businesses, businessId],
    );

    function validate(): Record<string, string> {
        const next: Record<string, string> = {};
        if (trimOrNull(titleEn) === null && trimOrNull(titleAr) === null) {
            next.title = t("saveError");
        }
        if (startsAt && endsAt && new Date(endsAt) <= new Date(startsAt)) {
            next.endsAt = t("saveError");
        }
        if (ctaType === "WEBSITE") {
            const url = ctaUrl.trim();
            if (!url.toLowerCase().startsWith("https://")) {
                next.ctaUrl = t("httpsRequired");
            } else if (!hostIsAllowed(url, business?.allowedDomains ?? [])) {
                next.ctaUrl = t("domainNotAllowed");
            }
        }
        if (ctaType === "COUPON" && trimOrNull(couponCode) === null) {
            next.couponCode = t("saveError");
        }
        if (ctaType === "CALL" && !business?.phoneE164) {
            next.ctaType = t("saveError");
        }
        if (ctaType === "WHATSAPP" && !business?.whatsappE164) {
            next.ctaType = t("saveError");
        }
        return next;
    }

    function submit() {
        const found = validate();
        setErrors(found);
        if (Object.keys(found).length > 0) return;

        // Fields belonging to other CTA types are dropped, never carried over —
        // the backend clears them too, and the preview must agree with both.
        onSave({
            businessId,
            titleEn: trimOrNull(titleEn),
            titleAr: trimOrNull(titleAr),
            subtitleEn: trimOrNull(subtitleEn),
            subtitleAr: trimOrNull(subtitleAr),
            backgroundImageUrl: trimOrNull(backgroundImageUrl),
            accentColor: trimOrNull(accentColor),
            ctaType,
            ctaLabelEn: trimOrNull(ctaLabelEn),
            ctaLabelAr: trimOrNull(ctaLabelAr),
            ctaUrl: ctaType === "WEBSITE" ? trimOrNull(ctaUrl) : null,
            couponCode: ctaType === "COUPON" ? trimOrNull(couponCode) : null,
            couponTermsEn: ctaType === "COUPON" ? trimOrNull(couponTermsEn) : null,
            couponTermsAr: ctaType === "COUPON" ? trimOrNull(couponTermsAr) : null,
            startsAt: startsAt ? new Date(`${startsAt}T00:00:00Z`).toISOString() : null,
            endsAt: endsAt ? new Date(`${endsAt}T23:59:59Z`).toISOString() : null,
            priority,
            placement,
            propertyIds,
            active,
        });
    }

    const err = (key: string) =>
        errors[key] ? <p className="mt-1 text-sm text-red-600">{errors[key]}</p> : null;

    return (
        <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_320px]">
            <div className="space-y-4">
                <label className="block">
                    <span className="mb-1 block text-sm font-medium">{t("business")}</span>
                    <select
                        aria-label={t("business")}
                        className="w-full rounded-lg border px-3 py-2"
                        value={businessId}
                        onChange={e => setBusinessId(e.target.value)}
                    >
                        {businesses.map(b => (
                            <option key={b.id} value={b.id}>{b.nameEn}</option>
                        ))}
                    </select>
                </label>

                <div className="grid gap-4 sm:grid-cols-2">
                    <label className="block">
                        <span className="mb-1 block text-sm font-medium">{t("adTitleEn")}</span>
                        <input aria-label={t("adTitleEn")} className="w-full rounded-lg border px-3 py-2"
                            value={titleEn} onChange={e => setTitleEn(e.target.value)} />
                    </label>
                    <label className="block">
                        <span className="mb-1 block text-sm font-medium">{t("adTitleAr")}</span>
                        <input aria-label={t("adTitleAr")} dir="rtl" className="w-full rounded-lg border px-3 py-2"
                            value={titleAr} onChange={e => setTitleAr(e.target.value)} />
                    </label>
                    <label className="block">
                        <span className="mb-1 block text-sm font-medium">{t("subtitleEn")}</span>
                        <input aria-label={t("subtitleEn")} className="w-full rounded-lg border px-3 py-2"
                            value={subtitleEn} onChange={e => setSubtitleEn(e.target.value)} />
                    </label>
                    <label className="block">
                        <span className="mb-1 block text-sm font-medium">{t("subtitleAr")}</span>
                        <input aria-label={t("subtitleAr")} dir="rtl" className="w-full rounded-lg border px-3 py-2"
                            value={subtitleAr} onChange={e => setSubtitleAr(e.target.value)} />
                    </label>
                </div>
                {err("title")}

                <div className="grid gap-4 sm:grid-cols-2">
                    <label className="block">
                        <span className="mb-1 block text-sm font-medium">{t("artwork")}</span>
                        <input aria-label={t("artwork")} className="w-full rounded-lg border px-3 py-2"
                            value={backgroundImageUrl}
                            onChange={e => setBackgroundImageUrl(e.target.value)} />
                    </label>
                    <label className="block">
                        <span className="mb-1 block text-sm font-medium">{t("accentColor")}</span>
                        <input aria-label={t("accentColor")} placeholder="#FBF3E2"
                            className="w-full rounded-lg border px-3 py-2"
                            value={accentColor} onChange={e => setAccentColor(e.target.value)} />
                    </label>
                </div>

                <label className="block">
                    <span className="mb-1 block text-sm font-medium">{t("ctaType")}</span>
                    <select
                        aria-label={t("ctaType")}
                        className="w-full rounded-lg border px-3 py-2"
                        value={ctaType}
                        onChange={e => setCtaType(e.target.value as PromoCtaType)}
                    >
                        {PROMO_CTA_TYPES.map(type => (
                            <option key={type} value={type}>{t(`ctaType${type}`)}</option>
                        ))}
                    </select>
                </label>
                {err("ctaType")}

                {ctaType === "WEBSITE" && (
                    <label className="block">
                        <span className="mb-1 block text-sm font-medium">{t("ctaUrl")}</span>
                        <input aria-label={t("ctaUrl")} className="w-full rounded-lg border px-3 py-2"
                            value={ctaUrl} onChange={e => setCtaUrl(e.target.value)} />
                        <span className="mt-1 block text-xs text-gray-500">
                            {(business?.allowedDomains ?? []).join(", ")}
                        </span>
                        {err("ctaUrl")}
                    </label>
                )}

                {ctaType === "COUPON" && (
                    <div className="space-y-4">
                        <label className="block">
                            <span className="mb-1 block text-sm font-medium">{t("couponCode")}</span>
                            <input aria-label={t("couponCode")} className="w-full rounded-lg border px-3 py-2"
                                value={couponCode} onChange={e => setCouponCode(e.target.value)} />
                            {err("couponCode")}
                        </label>
                        <div className="grid gap-4 sm:grid-cols-2">
                            <label className="block">
                                <span className="mb-1 block text-sm font-medium">{t("couponTermsEn")}</span>
                                <textarea aria-label={t("couponTermsEn")} className="w-full rounded-lg border px-3 py-2"
                                    value={couponTermsEn} onChange={e => setCouponTermsEn(e.target.value)} />
                            </label>
                            <label className="block">
                                <span className="mb-1 block text-sm font-medium">{t("couponTermsAr")}</span>
                                <textarea aria-label={t("couponTermsAr")} dir="rtl" className="w-full rounded-lg border px-3 py-2"
                                    value={couponTermsAr} onChange={e => setCouponTermsAr(e.target.value)} />
                            </label>
                        </div>
                    </div>
                )}

                {(ctaType === "CALL" || ctaType === "WHATSAPP") && (
                    <p className="rounded-lg bg-gray-50 px-3 py-2 text-sm text-gray-600">
                        {ctaType === "CALL"
                            ? `${t("phone")}: ${business?.phoneE164 ?? "—"}`
                            : `${t("whatsapp")}: ${business?.whatsappE164 ?? "—"}`}
                    </p>
                )}

                <div className="grid gap-4 sm:grid-cols-2">
                    <label className="block">
                        <span className="mb-1 block text-sm font-medium">{t("startsAt")}</span>
                        <input type="date" aria-label={t("startsAt")} className="w-full rounded-lg border px-3 py-2"
                            value={startsAt} onChange={e => setStartsAt(e.target.value)} />
                    </label>
                    <label className="block">
                        <span className="mb-1 block text-sm font-medium">{t("endsAt")}</span>
                        <input type="date" aria-label={t("endsAt")} className="w-full rounded-lg border px-3 py-2"
                            value={endsAt} onChange={e => setEndsAt(e.target.value)} />
                        {err("endsAt")}
                    </label>
                </div>

                <label className="block">
                    <span className="mb-1 block text-sm font-medium">
                        {t("priority")} — {priority}
                    </span>
                    <input type="range" min={1} max={10} step={1} aria-label={t("priority")}
                        className="w-full" value={priority}
                        onChange={e => setPriority(Number(e.target.value))} />
                    <span className="text-xs text-gray-500">{t("priorityHint")}</span>
                </label>

                <label className="block">
                    <span className="mb-1 block text-sm font-medium">{t("placement")}</span>
                    <select aria-label={t("placement")} className="w-full rounded-lg border px-3 py-2"
                        value={placement}
                        onChange={e => setPlacement(e.target.value as PromoPlacement)}>
                        <option value="HOME_AND_OFFERS">{t("placementHOME_AND_OFFERS")}</option>
                        <option value="OFFERS_ONLY">{t("placementOFFERS_ONLY")}</option>
                    </select>
                </label>

                <fieldset>
                    <legend className="mb-1 text-sm font-medium">{t("targeting")}</legend>
                    <p className="mb-2 text-xs text-gray-500">
                        {propertyIds.length === 0 ? t("targetingAll") : t("targetingSome")}
                    </p>
                    <div className="max-h-40 space-y-1 overflow-auto">
                        {properties.map(p => (
                            <label key={p.id} className="flex items-center gap-2 text-sm">
                                <input
                                    type="checkbox"
                                    checked={propertyIds.includes(p.id)}
                                    onChange={e => setPropertyIds(prev =>
                                        e.target.checked
                                            ? [...prev, p.id]
                                            : prev.filter(id => id !== p.id))}
                                />
                                {p.nameEn}
                            </label>
                        ))}
                    </div>
                </fieldset>

                <label className="flex items-center gap-2 text-sm">
                    <input type="checkbox" checked={active}
                        onChange={e => setActive(e.target.checked)} />
                    {t("active")}
                </label>

                <div className="flex gap-2 pt-2">
                    <button type="button" onClick={submit}
                        className="rounded-lg bg-gray-900 px-4 py-2 text-white">
                        {t("save")}
                    </button>
                    <button type="button" onClick={onCancel}
                        className="rounded-lg border px-4 py-2">
                        {t("cancel")}
                    </button>
                </div>
            </div>

            <aside className="space-y-3">
                <div className="flex items-center gap-2">
                    <span className="text-sm font-medium">{t("preview")}</span>
                    <button type="button" onClick={() => setPreviewAr(false)}
                        className={`rounded-md border px-2 py-1 text-xs ${previewAr ? "" : "bg-gray-900 text-white"}`}>
                        {t("previewEn")}
                    </button>
                    <button type="button" onClick={() => setPreviewAr(true)}
                        className={`rounded-md border px-2 py-1 text-xs ${previewAr ? "bg-gray-900 text-white" : ""}`}>
                        {t("previewAr")}
                    </button>
                </div>
                <div className="rounded-2xl bg-[#F6F5FA] p-4">
                    <AdCardPreview
                        title={previewAr ? (titleAr || titleEn) : (titleEn || titleAr)}
                        subtitle={previewAr ? (subtitleAr || subtitleEn) : (subtitleEn || subtitleAr)}
                        businessName={previewAr ? (business?.nameAr ?? business?.nameEn) : business?.nameEn}
                        backgroundImageUrl={backgroundImageUrl || null}
                        accentColor={accentColor || null}
                        ctaType={ctaType}
                        ctaLabel={previewAr ? (ctaLabelAr || ctaLabelEn) : (ctaLabelEn || ctaLabelAr)}
                        rtl={previewAr}
                    />
                </div>
                <div className="grid gap-4 sm:grid-cols-2">
                    <label className="block">
                        <span className="mb-1 block text-sm font-medium">{t("ctaLabelEn")}</span>
                        <input aria-label={t("ctaLabelEn")} className="w-full rounded-lg border px-3 py-2"
                            value={ctaLabelEn} onChange={e => setCtaLabelEn(e.target.value)} />
                    </label>
                    <label className="block">
                        <span className="mb-1 block text-sm font-medium">{t("ctaLabelAr")}</span>
                        <input aria-label={t("ctaLabelAr")} dir="rtl" className="w-full rounded-lg border px-3 py-2"
                            value={ctaLabelAr} onChange={e => setCtaLabelAr(e.target.value)} />
                    </label>
                </div>
            </aside>
        </div>
    );
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
cd web && npx vitest run src/app/\[locale\]/dashboard/promotions/__tests__/AdEditor.test.tsx
```

Expected: PASS, 10 tests. If `toBeInTheDocument` is unavailable, check that the repo's vitest setup imports `@testing-library/jest-dom` — the existing `web/src/app/[locale]/dashboard/__tests__` suites rely on it, so do not add a second setup file.

- [ ] **Step 5: Commit**

```bash
git add "web/src/app/[locale]/dashboard/promotions/"
git commit -m "feat(promotions): ad editor with live card preview and link validation"
```

---

## Task 15: BusinessEditor and BusinessesTab

**Files:**
- Create: `web/src/app/[locale]/dashboard/promotions/_components/BusinessEditor.tsx`
- Create: `web/src/app/[locale]/dashboard/promotions/_components/BusinessesTab.tsx`

- [ ] **Step 1: Write `BusinessEditor`**

Create `web/src/app/[locale]/dashboard/promotions/_components/BusinessEditor.tsx`:

```tsx
"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import {
    PROMO_CATEGORIES,
    type PromoBusinessDTO,
    type PromoBusinessRequest,
    type PromoCategory,
} from "@/types/promotion";

interface BusinessEditorProps {
    business: PromoBusinessDTO | null;
    onSave: (body: PromoBusinessRequest) => void;
    onCancel: () => void;
}

/**
 * Turns whatever the client pastes into a bare hostname. The same
 * normalisation runs server-side in PromotionUrlValidator.parseDomains — this
 * copy exists so the chip shows the real stored value immediately, not so the
 * client can be trusted.
 */
function toHost(value: string): string {
    let s = value.trim().toLowerCase();
    const scheme = s.indexOf("://");
    if (scheme >= 0) s = s.slice(scheme + 3);
    const at = s.indexOf("@");
    if (at >= 0) s = s.slice(at + 1);
    const cut = ["/", "?", "#", ":"]
        .map(c => s.indexOf(c))
        .filter(i => i >= 0)
        .reduce((min, i) => Math.min(min, i), s.length);
    return s.slice(0, cut);
}

const trimOrNull = (s: string): string | null => (s.trim() === "" ? null : s.trim());

export function BusinessEditor({ business, onSave, onCancel }: BusinessEditorProps) {
    const t = useTranslations("Promotions");

    const [nameEn, setNameEn] = useState(business?.nameEn ?? "");
    const [nameAr, setNameAr] = useState(business?.nameAr ?? "");
    const [logoUrl, setLogoUrl] = useState(business?.logoUrl ?? "");
    const [category, setCategory] = useState<PromoCategory>(business?.category ?? "OTHER");
    const [phoneE164, setPhoneE164] = useState(business?.phoneE164 ?? "");
    const [whatsappE164, setWhatsappE164] = useState(business?.whatsappE164 ?? "");
    const [domains, setDomains] = useState<string[]>(business?.allowedDomains ?? []);
    const [domainDraft, setDomainDraft] = useState("");
    const [active, setActive] = useState(business?.active ?? true);
    const [error, setError] = useState<string | null>(null);

    function addDomain() {
        const host = toHost(domainDraft);
        if (host === "" || domains.includes(host)) {
            setDomainDraft("");
            return;
        }
        setDomains([...domains, host]);
        setDomainDraft("");
    }

    function submit() {
        if (trimOrNull(nameEn) === null) {
            setError(t("saveError"));
            return;
        }
        setError(null);
        onSave({
            nameEn: nameEn.trim(),
            nameAr: trimOrNull(nameAr),
            logoUrl: trimOrNull(logoUrl),
            category,
            phoneE164: trimOrNull(phoneE164),
            whatsappE164: trimOrNull(whatsappE164),
            allowedDomains: domains,
            active,
        });
    }

    return (
        <div className="space-y-4">
            <div className="grid gap-4 sm:grid-cols-2">
                <label className="block">
                    <span className="mb-1 block text-sm font-medium">{t("nameEn")}</span>
                    <input aria-label={t("nameEn")} className="w-full rounded-lg border px-3 py-2"
                        value={nameEn} onChange={e => setNameEn(e.target.value)} />
                </label>
                <label className="block">
                    <span className="mb-1 block text-sm font-medium">{t("nameAr")}</span>
                    <input aria-label={t("nameAr")} dir="rtl" className="w-full rounded-lg border px-3 py-2"
                        value={nameAr} onChange={e => setNameAr(e.target.value)} />
                </label>
                <label className="block">
                    <span className="mb-1 block text-sm font-medium">{t("category")}</span>
                    <select aria-label={t("category")} className="w-full rounded-lg border px-3 py-2"
                        value={category}
                        onChange={e => setCategory(e.target.value as PromoCategory)}>
                        {PROMO_CATEGORIES.map(c => <option key={c} value={c}>{c}</option>)}
                    </select>
                </label>
                <label className="block">
                    <span className="mb-1 block text-sm font-medium">{t("logo")}</span>
                    <input aria-label={t("logo")} className="w-full rounded-lg border px-3 py-2"
                        value={logoUrl} onChange={e => setLogoUrl(e.target.value)} />
                </label>
                <label className="block">
                    <span className="mb-1 block text-sm font-medium">{t("phone")}</span>
                    <input aria-label={t("phone")} placeholder="+971501234567"
                        className="w-full rounded-lg border px-3 py-2"
                        value={phoneE164} onChange={e => setPhoneE164(e.target.value)} />
                </label>
                <label className="block">
                    <span className="mb-1 block text-sm font-medium">{t("whatsapp")}</span>
                    <input aria-label={t("whatsapp")} placeholder="+971501234567"
                        className="w-full rounded-lg border px-3 py-2"
                        value={whatsappE164} onChange={e => setWhatsappE164(e.target.value)} />
                </label>
            </div>

            <div>
                <span className="mb-1 block text-sm font-medium">{t("allowedDomains")}</span>
                <div className="mb-2 flex flex-wrap gap-2">
                    {domains.map(d => (
                        <span key={d} className="flex items-center gap-1 rounded-full bg-gray-100 px-3 py-1 text-sm">
                            {d}
                            <button type="button" aria-label={`${t("delete")} ${d}`}
                                onClick={() => setDomains(domains.filter(x => x !== d))}>×</button>
                        </span>
                    ))}
                </div>
                <div className="flex gap-2">
                    <input aria-label={t("allowedDomains")} className="flex-1 rounded-lg border px-3 py-2"
                        value={domainDraft}
                        onChange={e => setDomainDraft(e.target.value)}
                        onKeyDown={e => { if (e.key === "Enter") { e.preventDefault(); addDomain(); } }} />
                    <button type="button" className="rounded-lg border px-3 py-2" onClick={addDomain}>+</button>
                </div>
                <p className="mt-1 text-xs text-gray-500">{t("allowedDomainsHint")}</p>
            </div>

            <label className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={active} onChange={e => setActive(e.target.checked)} />
                {t("active")}
            </label>

            {error && <p className="text-sm text-red-600">{error}</p>}

            <div className="flex gap-2">
                <button type="button" onClick={submit}
                    className="rounded-lg bg-gray-900 px-4 py-2 text-white">{t("save")}</button>
                <button type="button" onClick={onCancel}
                    className="rounded-lg border px-4 py-2">{t("cancel")}</button>
            </div>
        </div>
    );
}
```

- [ ] **Step 2: Write `BusinessesTab`**

Create `web/src/app/[locale]/dashboard/promotions/_components/BusinessesTab.tsx`:

```tsx
"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import { Loader2 } from "lucide-react";
import { Pagination } from "@/components/ui/Pagination";
import {
    ApiError, createBusiness, deleteBusiness, fetchBusinesses, updateBusiness,
} from "@/lib/api/promotions";
import type { PromoBusinessDTO, PromoBusinessRequest } from "@/types/promotion";
import { BusinessEditor } from "./BusinessEditor";

const PAGE_SIZE = 10;

export function BusinessesTab({ onChanged }: { onChanged?: () => void }) {
    const t = useTranslations("Promotions");

    const [rows, setRows] = useState<PromoBusinessDTO[]>([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(0);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [editing, setEditing] = useState<PromoBusinessDTO | null | undefined>(undefined);

    // Guards a slow response from clobbering a newer one (same pattern as
    // dashboard/bookings/page.tsx).
    const requestIdRef = useRef(0);

    const load = useCallback(async (p: number) => {
        const id = ++requestIdRef.current;
        setLoading(true);
        try {
            const data = await fetchBusinesses(p, PAGE_SIZE);
            if (id !== requestIdRef.current) return;
            setRows(data.content);
            setTotal(data.totalElements);
            setError(null);
        } catch (e) {
            if (id !== requestIdRef.current) return;
            setError(e instanceof ApiError ? e.message : t("loadError"));
        } finally {
            if (id === requestIdRef.current) setLoading(false);
        }
    }, [t]);

    useEffect(() => { void load(page); }, [load, page]);

    async function save(body: PromoBusinessRequest) {
        try {
            if (editing) {
                await updateBusiness(editing.id, body);
            } else {
                await createBusiness(body);
            }
            setEditing(undefined);
            await load(page);
            onChanged?.();
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("saveError"));
        }
    }

    async function remove(row: PromoBusinessDTO) {
        try {
            await deleteBusiness(row.id);
            await load(page);
            onChanged?.();
        } catch (e) {
            // 409 from the backend when ads still reference it.
            setError(e instanceof ApiError && e.status === 409
                ? t("deleteBusinessBlocked")
                : t("saveError"));
        }
    }

    if (editing !== undefined) {
        return <BusinessEditor business={editing} onSave={save} onCancel={() => setEditing(undefined)} />;
    }

    return (
        <div className="space-y-4">
            <div className="flex justify-end">
                <button type="button" onClick={() => setEditing(null)}
                    className="rounded-lg bg-gray-900 px-4 py-2 text-white">{t("addBusiness")}</button>
            </div>

            {error && <p className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>}

            {loading ? (
                <div className="flex justify-center py-10"><Loader2 className="animate-spin" /></div>
            ) : rows.length === 0 ? (
                <p className="py-10 text-center text-sm text-gray-500">{t("emptyBusinesses")}</p>
            ) : (
                <table className="w-full text-sm">
                    <thead>
                        <tr className="border-b text-left text-gray-500">
                            <th className="py-2">{t("name")}</th>
                            <th>{t("category")}</th>
                            <th>{t("phone")}</th>
                            <th>{t("adCount")}</th>
                            <th>{t("active")}</th>
                            <th />
                        </tr>
                    </thead>
                    <tbody>
                        {rows.map(row => (
                            <tr key={row.id} className="border-b">
                                <td className="py-2">{row.nameEn}</td>
                                <td>{row.category}</td>
                                <td>{row.phoneE164 ?? "—"}</td>
                                <td>{row.adCount}</td>
                                <td>{row.active ? "✓" : "—"}</td>
                                <td className="text-right">
                                    <button type="button" className="mr-3 underline"
                                        onClick={() => setEditing(row)}>{t("edit")}</button>
                                    <button type="button" className="text-red-600 underline"
                                        onClick={() => void remove(row)}>{t("delete")}</button>
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            )}

            {/* Pagination is 1-indexed; the backend's page param is 0-indexed. */}
            <Pagination
                currentPage={page + 1}
                totalItems={total}
                itemsPerPage={PAGE_SIZE}
                onPageChange={p => setPage(p - 1)}
            />
        </div>
    );
}
```

- [ ] **Step 3: Verify it type-checks**

Run:

```bash
cd web && npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add "web/src/app/[locale]/dashboard/promotions/_components/"
git commit -m "feat(promotions): businesses tab with domain-allowlist editor"
```

---

## Task 16: AdsTab and the page shell

**Files:**
- Create: `web/src/app/[locale]/dashboard/promotions/_components/AdsTab.tsx`
- Create: `web/src/app/[locale]/dashboard/promotions/page.tsx`

- [ ] **Step 1: Write `AdsTab`**

Create `web/src/app/[locale]/dashboard/promotions/_components/AdsTab.tsx`:

```tsx
"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import { Loader2 } from "lucide-react";
import { Pagination } from "@/components/ui/Pagination";
import { ApiError, createAd, deleteAd, fetchAds, updateAd } from "@/lib/api/promotions";
import { adStatus, type PromoAdDTO, type PromoAdRequest, type PromoBusinessDTO } from "@/types/promotion";
import { AdEditor, type PropertyOption } from "./AdEditor";

const PAGE_SIZE = 10;

const STATUS_CLASSES: Record<string, string> = {
    LIVE: "bg-green-50 text-green-700",
    SCHEDULED: "bg-blue-50 text-blue-700",
    EXPIRED: "bg-gray-100 text-gray-600",
    PAUSED: "bg-amber-50 text-amber-700",
};

interface AdsTabProps {
    businesses: PromoBusinessDTO[];
    properties: PropertyOption[];
}

export function AdsTab({ businesses, properties }: AdsTabProps) {
    const t = useTranslations("Promotions");

    const [rows, setRows] = useState<PromoAdDTO[]>([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(0);
    const [businessId, setBusinessId] = useState("");
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [editing, setEditing] = useState<PromoAdDTO | null | undefined>(undefined);

    const requestIdRef = useRef(0);

    const load = useCallback(async (p: number, filterBusinessId: string) => {
        const id = ++requestIdRef.current;
        setLoading(true);
        try {
            const data = await fetchAds(p, PAGE_SIZE, filterBusinessId || undefined);
            if (id !== requestIdRef.current) return;
            setRows(data.content);
            setTotal(data.totalElements);
            setError(null);
        } catch (e) {
            if (id !== requestIdRef.current) return;
            setError(e instanceof ApiError ? e.message : t("loadError"));
        } finally {
            if (id === requestIdRef.current) setLoading(false);
        }
    }, [t]);

    useEffect(() => { void load(page, businessId); }, [load, page, businessId]);

    async function save(body: PromoAdRequest) {
        try {
            if (editing) {
                await updateAd(editing.id, body);
            } else {
                await createAd(body);
            }
            setEditing(undefined);
            await load(page, businessId);
        } catch (e) {
            // The backend re-checks the URL allowlist; surface its message verbatim.
            setError(e instanceof ApiError ? e.message : t("saveError"));
        }
    }

    if (editing !== undefined) {
        return (
            <AdEditor businesses={businesses} properties={properties} ad={editing}
                onSave={save} onCancel={() => setEditing(undefined)} />
        );
    }

    return (
        <div className="space-y-4">
            <div className="flex items-center justify-between gap-3">
                <select aria-label={t("business")} className="rounded-lg border px-3 py-2 text-sm"
                    value={businessId}
                    onChange={e => { setPage(0); setBusinessId(e.target.value); }}>
                    <option value="">{t("business")}</option>
                    {businesses.map(b => <option key={b.id} value={b.id}>{b.nameEn}</option>)}
                </select>
                <button type="button" disabled={businesses.length === 0}
                    onClick={() => setEditing(null)}
                    className="rounded-lg bg-gray-900 px-4 py-2 text-white disabled:opacity-40">
                    {t("addAd")}
                </button>
            </div>

            {error && <p className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>}

            {loading ? (
                <div className="flex justify-center py-10"><Loader2 className="animate-spin" /></div>
            ) : rows.length === 0 ? (
                <p className="py-10 text-center text-sm text-gray-500">{t("emptyAds")}</p>
            ) : (
                <table className="w-full text-sm">
                    <thead>
                        <tr className="border-b text-left text-gray-500">
                            <th className="py-2">{t("adTitle")}</th>
                            <th>{t("business")}</th>
                            <th>{t("ctaType")}</th>
                            <th>{t("priority")}</th>
                            <th>{t("status")}</th>
                            <th className="text-right">{t("views")}</th>
                            <th className="text-right">{t("taps")}</th>
                            <th className="text-right">{t("tapRate")}</th>
                            <th />
                        </tr>
                    </thead>
                    <tbody>
                        {rows.map(row => {
                            const status = adStatus(row);
                            const rate = row.impressions === 0
                                ? 0
                                : row.clicks / row.impressions;
                            return (
                                <tr key={row.id} className="border-b">
                                    <td className="py-2">{row.titleEn ?? row.titleAr}</td>
                                    <td>{row.businessNameEn}</td>
                                    <td>{t(`ctaType${row.ctaType}`)}</td>
                                    <td>{row.priority}</td>
                                    <td>
                                        <span className={`rounded-full px-2 py-1 text-xs ${STATUS_CLASSES[status]}`}>
                                            {t(`status${status}`)}
                                        </span>
                                    </td>
                                    <td className="text-right">{row.impressions.toLocaleString()}</td>
                                    <td className="text-right">{row.clicks.toLocaleString()}</td>
                                    <td className="text-right">{(rate * 100).toFixed(1)}%</td>
                                    <td className="text-right">
                                        <button type="button" className="mr-3 underline"
                                            onClick={() => setEditing(row)}>{t("edit")}</button>
                                        <button type="button" className="text-red-600 underline"
                                            onClick={async () => {
                                                await deleteAd(row.id);
                                                await load(page, businessId);
                                            }}>{t("delete")}</button>
                                    </td>
                                </tr>
                            );
                        })}
                    </tbody>
                </table>
            )}

            <Pagination
                currentPage={page + 1}
                totalItems={total}
                itemsPerPage={PAGE_SIZE}
                onPageChange={p => setPage(p - 1)}
            />
        </div>
    );
}
```

- [ ] **Step 2: Write the page shell**

Create `web/src/app/[locale]/dashboard/promotions/page.tsx`:

```tsx
"use client";

import { useCallback, useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { useSession } from "next-auth/react";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { fetchBusinesses } from "@/lib/api/promotions";
import type { PromoBusinessDTO } from "@/types/promotion";
import { BusinessesTab } from "./_components/BusinessesTab";
import { AdsTab } from "./_components/AdsTab";
import type { PropertyOption } from "./_components/AdEditor";

type Tab = "businesses" | "ads";

export default function PromotionsPage() {
    const t = useTranslations("Promotions");
    const { data: session, status: sessionStatus } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const canView = hasPermission(userRole, "canManagePromotions");

    const [tab, setTab] = useState<Tab>("ads");
    const [businesses, setBusinesses] = useState<PromoBusinessDTO[]>([]);
    const [properties, setProperties] = useState<PropertyOption[]>([]);

    // The ad editor needs the full business list (for the picker and the
    // allowlist hint) and the property list (for targeting), so both are
    // loaded once at the shell rather than per tab render.
    const loadLookups = useCallback(async () => {
        const page = await fetchBusinesses(0, 200);
        setBusinesses(page.content);
        const res = await fetch("/api/proxy/v1/properties");
        if (res.ok) {
            const data: Array<{ property: { id: string; nameEn: string } }> = await res.json();
            setProperties(data.map(s => ({ id: s.property.id, nameEn: s.property.nameEn })));
        }
    }, []);

    useEffect(() => {
        if (sessionStatus !== "authenticated" || !canView) return;
        void loadLookups();
    }, [sessionStatus, canView, loadLookups]);

    if (sessionStatus === "loading") return null;
    if (!canView) return null;

    return (
        <div className="space-y-6 p-6">
            <h1 className="text-2xl font-semibold">{t("title")}</h1>

            <div className="flex gap-2 border-b">
                <button type="button" onClick={() => setTab("ads")}
                    className={`px-4 py-2 text-sm ${tab === "ads" ? "border-b-2 border-gray-900 font-medium" : "text-gray-500"}`}>
                    {t("adsTab")}
                </button>
                <button type="button" onClick={() => setTab("businesses")}
                    className={`px-4 py-2 text-sm ${tab === "businesses" ? "border-b-2 border-gray-900 font-medium" : "text-gray-500"}`}>
                    {t("businessesTab")}
                </button>
            </div>

            {tab === "ads"
                ? <AdsTab businesses={businesses} properties={properties} />
                : <BusinessesTab onChanged={loadLookups} />}
        </div>
    );
}
```

- [ ] **Step 3: Verify it type-checks and lints**

Run:

```bash
cd web && npx tsc --noEmit && npm run lint
```

Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add "web/src/app/[locale]/dashboard/promotions/"
git commit -m "feat(promotions): ads tab and promotions page shell"
```

---

## Task 17: Full verification

- [ ] **Step 1: Run the whole backend suite**

Run:

```bash
cd backend && ./gradlew test
```

Expected: `BUILD SUCCESSFUL`. Record the test count in your report.

- [ ] **Step 2: Run the whole web suite**

Run:

```bash
cd web && npm test
```

Expected: all suites pass, including the pre-existing dashboard tests.

- [ ] **Step 3: Lint and type-check the web app**

Run:

```bash
cd web && npm run lint && npx tsc --noEmit
```

Expected: clean.

- [ ] **Step 4: Smoke-test the API against a running stack**

Run:

```bash
docker compose -f docker-compose.db.yml up -d && docker compose up -d && sleep 20 && curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/v1/promotions/ads
```

Expected: `401` or `403` — the endpoint exists and is not publicly readable. A `404` means the controller did not register.

- [ ] **Step 5: Report**

Report to the user: backend test count and result, web test count and result, lint/type-check result, and the smoke-test status code. Do not claim success for any step you did not run.

---

## Notes carried into the mobile plan

- The feed contract is `GET /api/v1/promotions/feed` → `PromoAdCardDTO[]`, at most 6, already ordered. The app renders them in the order received and must not re-sort.
- Both language variants are sent; the app resolves them.
- `ctaUrl` is present only for `WEBSITE` ads and has already passed an https + allowlist check server-side. The app still re-checks `https://` before launching, as defence in depth.
- `ctaPhone` is present only for `CALL` and `WHATSAPP`.
- `POST /api/v1/promotions/events` accepts at most 50 events per call and returns 202. Unknown ids are dropped silently — the app must not treat a non-2xx as a reason to retry forever.
