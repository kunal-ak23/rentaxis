# Property Marketplace Listings Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a per-tenant property marketplace where landlords publish rich unit listings (photos, amenities, location, SEO), renters browse and wishlist via web + mobile, and renters are notified when upcoming units become available. Public SEO-friendly previews available without login.

**Architecture:** New `UnitListing` entity (1:1 with `Unit`) alongside existing domain — no changes to core `Property/Unit/Lease` entities. Three backend controllers (landlord, marketplace, public). Web uses Next.js SSR for public pages and client pages for dashboard/marketplace. Flutter renter app gets a new `Browse` tab; manager app gets a Listings section. Reuses existing notifications module for in-app + email + push. Feature-flagged behind `FEATURE_LISTINGS_ENABLED`.

**Tech Stack:** Java 21 / Spring Boot 4 / Liquibase / PostgreSQL 16 / Azure Blob Storage / Next.js 16 / TypeScript / Tailwind 4 / `@vis.gl/react-google-maps` / Flutter + Riverpod / `google_maps_flutter` / `geolocator`.

**Design doc:** `docs/plans/2026-04-08-property-marketplace-listings-design.md`

**Frontend skill requirement:** All frontend tasks (web + mobile) MUST use `frontend-design` and `ui-ux-pro-max` skills.

---

## Phase 0: Setup

### Task 0.1: Add feature flag

**Files:**
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/java/com/datagami/rentaxis/config/FeatureFlags.java` (create if missing)

**Steps:**
1. Add `rentaxis.features.listings-enabled: ${FEATURE_LISTINGS_ENABLED:false}` to `application.yml`.
2. Add `@ConfigurationProperties` bean `FeatureFlags` exposing `boolean listingsEnabled`.
3. Commit: `chore: add listings feature flag`.

---

## Phase 1: Backend data model

### Task 1.1: Liquibase migration — tenants.slug

**Files:**
- Create: `backend/src/main/resources/db/changelog/changesets/37a-tenants-slug.yaml`
- Modify: `backend/src/main/resources/db/changelog/changelog-master.yaml`

**Steps:**
1. Create changeset adding nullable `slug VARCHAR(128)` to `tenants`, unique index.
2. Add SQL backfill: slugify `name` (lowercase, spaces/non-alphanum → `-`, collapse dashes).
3. Add a follow-up changeset in same file marking column NOT NULL.
4. Register in master changelog.
5. Run `./gradlew bootRun` locally and verify migration applies cleanly.
6. Commit: `feat(db): add slug column to tenants`.

### Task 1.2: Liquibase migration — listings tables

**Files:**
- Create: `backend/src/main/resources/db/changelog/changesets/37-unit-listings.yaml`

**Steps:**
1. Create tables per design doc Section 1:
   - `unit_listings` (all columns, FK to `units`, unique `(tenant_id, slug)`)
   - `unit_listing_amenities`
   - `unit_listing_media`
   - `unit_listing_interests` (unique `(listing_id, renter_user_id)`)
   - `property_geo` (unique FK to `properties`)
2. Add indexes: `(tenant_id, status)`, `(status, annual_rent)`, `(status, bedrooms)`, `(listing_id)` on children.
3. Register in master changelog.
4. Run migration locally, verify tables exist (`\dt`).
5. Commit: `feat(db): add unit listings tables`.

### Task 1.3: JPA entities

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/UnitListing.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/UnitListingAmenity.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/UnitListingMedia.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/UnitListingInterest.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/PropertyGeo.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/ListingStatus.java` (`DRAFT, PUBLISHED, UNLISTED, UPCOMING`)
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/Furnishing.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/ViewType.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/ListingAmenity.java` (full enum from design)
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/ListingMediaType.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/InterestStatus.java`

**Steps:**
1. Create enums first.
2. Create entities extending `BaseTenantEntity`, mapped to new tables, with Lombok `@Getter @Setter`.
3. Add `@OneToMany` collections where helpful (amenities/media on listing).
4. Compile: `./gradlew compileJava`. Expect: success.
5. Commit: `feat: add unit listing entities`.

### Task 1.4: Repositories

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/repository/UnitListingRepository.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/repository/UnitListingMediaRepository.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/repository/UnitListingInterestRepository.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/repository/PropertyGeoRepository.java`

**Steps:**
1. Extend `JpaRepository<T, UUID>` with query methods:
   - `findBySlugAndTenantId`, `findByStatusAndTenantId(Pageable)`, `existsBySlugAndTenantId`
   - `findAllByListingIdAndStatus(InterestStatus)` for interests
2. Add a paged marketplace search query (`@Query` or Specification-based). Filter by status, bedrooms min, rent range, furnishing, bbox lat/lng.
3. Compile.
4. Commit: `feat: add unit listing repositories`.

---

## Phase 2: Azure Blob storage service

### Task 2.1: Add Azure SDK dependency

**Files:**
- Modify: `backend/build.gradle`

**Steps:**
1. Add `implementation 'com.azure:azure-storage-blob:12.27.1'` (or latest compatible).
2. `./gradlew build -x test` to confirm resolution.
3. Commit: `chore: add azure blob sdk dependency`.

### Task 2.2: BlobStorageService + tests

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/BlobStorageService.java`
- Create: `backend/src/test/java/com/datagami/rentaxis/core/service/BlobStorageServiceTest.java`

**Steps:**
1. **Write failing test** using mocked `BlobContainerClient`: `upload(tenantId, listingId, file)` returns a URL containing the path `listings/{tenantId}/{listingId}/`.
2. Run test: FAIL.
3. Implement service with methods: `upload(UUID tenantId, UUID listingId, MultipartFile)`, `delete(String blobPath)`. Env config: `AZURE_STORAGE_CONNECTION_STRING`, `AZURE_STORAGE_CONTAINER`.
4. Run tests: PASS.
5. Commit: `feat: add azure blob storage service`.

---

## Phase 3: Backend services & landlord API

### Task 3.1: UnitListingService (CRUD + publish/unlist)

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/UnitListingService.java`
- Create: `backend/src/test/java/com/datagami/rentaxis/core/service/UnitListingServiceTest.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/SlugService.java` (helper)

**Steps:**
1. Write failing tests: create listing, publish (emits event), unlist, slug auto-generation ensures uniqueness within tenant (append `-2`, `-3`...).
2. Run tests: FAIL.
3. Implement service. Use `ApplicationEventPublisher` to emit `ListingPublishedEvent`, `ListingUnlistedEvent`.
4. Run tests: PASS.
5. Commit: `feat: add unit listing service with publish events`.

### Task 3.2: DTOs

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/UnitListingDTO.java` (full detail)
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/UnitListingSummaryDTO.java` (for lists)
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/UnitListingCreateRequest.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/UnitListingUpdateRequest.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/UnitListingMediaDTO.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/InterestDTO.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/PublicListingDTO.java` (redacted)

**Steps:**
1. Create Java records with Jackson annotations.
2. Add mapper methods (plain methods, not MapStruct) in each service.
3. Compile.
4. Commit: `feat: add listing DTOs`.

### Task 3.3: UnitListingController

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/api/UnitListingController.java`
- Create: `backend/src/test/java/com/datagami/rentaxis/api/UnitListingControllerTest.java`

**Steps:**
1. Write failing `@WebMvcTest` covering: list, get, create, update, publish, unlist, delete, media upload, media delete, media reorder, interests list. Use `@WithMockUser` with `TENANT_ADMIN`.
2. Run tests: FAIL.
3. Implement controller per design doc Section 2. Authorize with `@PreAuthorize`. Guard with feature flag check.
4. Run tests: PASS.
5. Commit: `feat: add landlord unit listing controller`.

### Task 3.4: Media upload wiring

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/UnitListingService.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/UnitListingController.java`

**Steps:**
1. Add `addMedia(listingId, MultipartFile)` service method that calls `BlobStorageService.upload()` and persists a `UnitListingMedia` row.
2. Add `removeMedia`, `reorderMedia`.
3. Test (integration): upload a tiny test image, verify row created.
4. Commit: `feat: wire listing media upload to blob storage`.

---

## Phase 4: Marketplace & public APIs

### Task 4.1: MarketplaceService + search

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/MarketplaceService.java`
- Create: `backend/src/test/java/com/datagami/rentaxis/core/service/MarketplaceServiceTest.java`

**Steps:**
1. Write failing tests: search with filters, distance bounding-box, sort, tenant scoping by slug.
2. Implement using JPA Specifications for composability. Bounding-box math in service (lat/lng ± radius/111km).
3. Tests PASS.
4. Commit: `feat: add marketplace search service`.

### Task 4.2: InterestService + notification events

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/InterestService.java`
- Create: `backend/src/test/java/com/datagami/rentaxis/core/service/InterestServiceTest.java`

**Steps:**
1. Write failing tests: add interest (emits `InterestReceivedEvent`), withdraw, list for landlord, dedupe on repeat add.
2. Implement. Unique constraint on `(listing_id, renter_user_id)` enforces dedupe.
3. Tests PASS.
4. Commit: `feat: add listing interest service`.

### Task 4.3: MarketplaceController

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/api/MarketplaceController.java`
- Create: `backend/src/test/java/com/datagami/rentaxis/api/MarketplaceControllerTest.java`

**Steps:**
1. Write failing `@WebMvcTest` with `RENTER` role covering all endpoints.
2. Implement per design Section 2. Scope by tenant slug → tenant id.
3. Tests PASS.
4. Commit: `feat: add marketplace controller`.

### Task 4.4: PublicListingController (unauthenticated)

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/api/PublicListingController.java`
- Create: `backend/src/test/java/com/datagami/rentaxis/api/PublicListingControllerTest.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/security/ApiSecurityFilter.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/config/SecurityConfig.java`

**Steps:**
1. Write failing test: unauthenticated GET returns limited payload; exact rent NOT in response; cover photo present.
2. Whitelist `/public/**` in `SecurityConfig` and `ApiSecurityFilter`.
3. Implement controller returning `PublicListingDTO` (rent rounded to nearest 10k, pin offset by ~200m, no gallery).
4. Add `sitemap.xml` endpoint producing XML per tenant.
5. Tests PASS. Include a test that verifies cross-tenant slug lookup returns 404.
6. Commit: `feat: add public listing controller with redacted payload`.

### Task 4.5: Rate limiting on public endpoints

**Files:**
- Modify: `backend/build.gradle` (add Bucket4j)
- Create: `backend/src/main/java/com/datagami/rentaxis/security/PublicRateLimitFilter.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/config/SecurityConfig.java`

**Steps:**
1. Add Bucket4j local in-memory bucket: 60 req/min per IP for `/public/**`.
2. Write filter test verifying 429 after threshold.
3. Register filter before security filter for the path.
4. Commit: `feat: rate limit public listing endpoints`.

---

## Phase 5: Notifications

### Task 5.1: ListingNotificationService (event listeners)

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/ListingNotificationService.java`
- Create: `backend/src/test/java/com/datagami/rentaxis/core/service/ListingNotificationServiceTest.java`
- Create email templates: `backend/src/main/resources/templates/email/listing_available_en.html`, `_ar.html`, `interest_received_en.html`, `_ar.html`

**Steps:**
1. Write failing tests: `ListingPublishedEvent` triggers `LISTING_AVAILABLE` notification for each ACTIVE interest; interests flip to `NOTIFIED`; `InterestReceivedEvent` notifies landlord.
2. Implement `@EventListener` methods calling existing `NotificationService` (in-app + email + push per type).
3. Tests PASS.
4. Commit: `feat: add listing notification dispatcher`.

### Task 5.2: Scheduled job for UPCOMING status

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/scheduled/ListingUpcomingJob.java`
- Create: `backend/src/test/java/com/datagami/rentaxis/core/scheduled/ListingUpcomingJobTest.java`

**Steps:**
1. Write failing test: a unit with a lease ending in 15 days and an existing listing flips to `UPCOMING` with correct `available_from`.
2. Implement `@Scheduled(cron = "0 0 2 * * *")` job. Enable `@EnableScheduling` if not already.
3. Test PASS.
4. Commit: `feat: add nightly upcoming listing job`.

---

## Phase 6: Web frontend — landlord dashboard

**NOTE: All web tasks use `frontend-design` and `ui-ux-pro-max` skills.**

### Task 6.1: Listings index page

**Files:**
- Create: `web/src/app/[locale]/dashboard/listings/page.tsx`
- Create: `web/src/app/[locale]/dashboard/listings/_components/ListingsTable.tsx`
- Create: `web/src/lib/api/listings.ts` (client API wrapper)
- Create: `web/src/types/listing.ts`

**Steps:**
1. Invoke `frontend-design` / `ui-ux-pro-max` skill for the list layout.
2. Implement table-first list with filters, pagination, view toggle (per UI standard memory), sort by `createdAt` asc.
3. Wire API client using Next.js proxy rewrite (no hardcoded URLs).
4. Manual verify locally.
5. Commit: `feat(web): add listings dashboard index`.

### Task 6.2: Listing create/edit page with tabbed form

**Files:**
- Create: `web/src/app/[locale]/dashboard/listings/[id]/page.tsx`
- Create: `web/src/app/[locale]/dashboard/listings/[id]/_components/{DetailsTab,LocationTab,AmenitiesTab,MediaTab,SeoTab,PricingTab}.tsx`
- Create: `web/src/components/maps/PropertyMap.tsx`

**Steps:**
1. Invoke design skills per tab.
2. Implement tabs. Location tab uses `@vis.gl/react-google-maps` + Places autocomplete. SEO tab includes live Google snippet preview.
3. Add publish / unlist buttons in header.
4. Wire to API.
5. Commit per tab: `feat(web): add listing [details|location|amenities|media|seo|pricing] tab`.

### Task 6.3: Interests drawer + CSV export

**Files:**
- Create: `web/src/app/[locale]/dashboard/listings/_components/InterestsDrawer.tsx`

**Steps:**
1. Implement drawer showing interested renters with contact info.
2. Add CSV export button (client-side generation from fetched list).
3. Commit: `feat(web): add listing interests drawer`.

---

## Phase 7: Web frontend — renter marketplace

### Task 7.1: Marketplace list with filters + map toggle

**Files:**
- Create: `web/src/app/[locale]/marketplace/[tenantSlug]/page.tsx`
- Create: `web/src/app/[locale]/marketplace/[tenantSlug]/_components/{ListingCard,FilterSidebar,MapView}.tsx`
- Create: `web/src/hooks/useGeolocation.ts`

**Steps:**
1. Invoke design skills.
2. Implement Airbnb-style card grid + filter sidebar + map/list toggle.
3. Use geolocation hook (opt-in) for distance sort.
4. Commit: `feat(web): add renter marketplace list`.

### Task 7.2: Listing detail page

**Files:**
- Create: `web/src/app/[locale]/marketplace/[tenantSlug]/[unitSlug]/page.tsx`
- Create: `web/src/app/[locale]/marketplace/[tenantSlug]/[unitSlug]/_components/{Gallery,QuickFacts,AmenitiesGrid,MapCard,BuildingCard}.tsx`

**Steps:**
1. Invoke design skills.
2. Implement hero gallery (lightbox), quick facts, description, amenities grid, map card with "Get directions" deep link, floor plan, video/360 embeds, wishlist button, building card.
3. Compute distance client-side via Haversine.
4. Commit: `feat(web): add marketplace listing detail`.

### Task 7.3: Wishlist page

**Files:**
- Create: `web/src/app/[locale]/marketplace/wishlist/page.tsx`

**Steps:**
1. Implement wishlist with status chips (Available / Upcoming / Notified).
2. Commit: `feat(web): add renter wishlist page`.

---

## Phase 8: Web frontend — public SEO preview

### Task 8.1: Public listing page with SSR SEO

**Files:**
- Create: `web/src/app/l/[tenantSlug]/[unitSlug]/page.tsx`
- Create: `web/src/app/l/[tenantSlug]/[unitSlug]/_components/LimitedView.tsx`
- Create: `web/src/lib/seo/listingJsonLd.ts`

**Steps:**
1. Implement `generateMetadata` returning title, description, OG, Twitter, canonical, hreflang en/ar.
2. Inject JSON-LD `RealEstateListing` + `BreadcrumbList`.
3. SSR/ISR with `revalidate: 3600`.
4. Limited payload: cover photo, building name, area, rent rounded, approximate pin, login CTA.
5. Commit: `feat(web): add public listing preview with SEO`.

### Task 8.2: Sitemap + robots

**Files:**
- Create: `web/src/app/l/[tenantSlug]/sitemap.xml/route.ts`
- Create: `web/src/app/sitemap-index.xml/route.ts`
- Modify: `web/public/robots.txt`

**Steps:**
1. Implement sitemap routes proxying backend sitemap endpoint.
2. Update `robots.txt` (allow `/l/`, disallow `/marketplace/`, reference sitemap index).
3. Commit: `feat(web): add listing sitemap and robots`.

---

## Phase 9: Mobile renter app — Browse tab

**NOTE: All mobile tasks use `frontend-design` and `ui-ux-pro-max` skills.**

### Task 9.1: Add dependencies + core service

**Files:**
- Modify: `mobile/apps/renter/pubspec.yaml`
- Modify: `mobile/packages/rentaxis_core/pubspec.yaml`
- Create: `mobile/packages/rentaxis_core/lib/src/services/listing_api_service.dart`
- Create: `mobile/packages/rentaxis_core/lib/src/services/location_service.dart`

**Steps:**
1. Add `google_maps_flutter`, `geolocator`, `permission_handler`, `photo_view`, `url_launcher`.
2. Configure Google Maps API key in `AndroidManifest.xml` and `AppDelegate.swift` per app.
3. Implement `ListingApiService` and `LocationService` in shared package.
4. Run `melos bootstrap`.
5. Commit: `feat(mobile): add listing service and maps dependencies`.

### Task 9.2: Browse tab skeleton

**Files:**
- Modify: `mobile/apps/renter/lib/router.dart` (add `/browse` route)
- Modify: bottom nav config
- Create: `mobile/apps/renter/lib/screens/browse/browse_screen.dart`

**Steps:**
1. Invoke design skills.
2. Add bottom tab "Browse"; create `BrowseScreen` with search bar, filter chips, list/map toggle.
3. Commit: `feat(mobile): add browse tab scaffold`.

### Task 9.3: Listing card + list mode

**Files:**
- Create: `mobile/packages/rentaxis_core/lib/src/widgets/listing_card.dart`
- Create: `mobile/packages/rentaxis_core/lib/src/widgets/distance_chip.dart`
- Modify: browse screen

**Steps:**
1. Invoke design skills.
2. Implement card widget, wishlist heart toggle, distance chip.
3. Wire provider for paginated listings.
4. Commit: `feat(mobile): add listing card and list mode`.

### Task 9.4: Map mode with clustered pins

**Files:**
- Create: `mobile/apps/renter/lib/screens/browse/browse_map.dart`

**Steps:**
1. Invoke design skills.
2. Implement `GoogleMap` with cluster manager and bottom peek card on pin tap.
3. Commit: `feat(mobile): add browse map mode`.

### Task 9.5: Filters bottom sheet

**Files:**
- Create: `mobile/apps/renter/lib/screens/browse/browse_filters_sheet.dart`

**Steps:**
1. Invoke design skills.
2. Implement modal filter sheet with all controls.
3. Commit: `feat(mobile): add browse filters sheet`.

### Task 9.6: Listing detail screen

**Files:**
- Create: `mobile/apps/renter/lib/screens/browse/listing_detail_screen.dart`

**Steps:**
1. Invoke design skills.
2. Implement hero carousel (`photo_view`), quick facts, price card, description, amenities grid, map preview with deep link (`url_launcher`), floor plan, video/360 buttons, wishlist FAB.
3. Relabel FAB for `UPCOMING` state.
4. Commit: `feat(mobile): add listing detail screen`.

### Task 9.7: Wishlist screen

**Files:**
- Create: `mobile/apps/renter/lib/screens/wishlist/wishlist_screen.dart`

**Steps:**
1. Invoke design skills.
2. Implement with status chips and swipe-to-remove.
3. Commit: `feat(mobile): add wishlist screen`.

---

## Phase 10: Mobile admin app — Listings management

### Task 10.1: Listings list + detail forms

**Files:**
- Create: `mobile/apps/manager/lib/screens/listings/listings_list_screen.dart`
- Create: `mobile/apps/manager/lib/screens/listings/listing_edit_screen.dart`
- Create: `mobile/apps/manager/lib/screens/listings/listing_interests_screen.dart`

**Steps:**
1. Invoke design skills.
2. Implement list with cover, status pill, interests badge, FAB.
3. Implement edit screen with tabbed form matching web.
4. Implement interests screen with tap-to-call / tap-to-email via `url_launcher`.
5. Add to manager app navigation menu.
6. Commit per screen.

### Task 10.2: Media picker

**Files:**
- Modify: `mobile/apps/manager/pubspec.yaml` (add `image_picker`)
- Modify: listing edit screen

**Steps:**
1. Implement multi-select from gallery + camera.
2. Upload each file through listing API.
3. Commit: `feat(mobile-admin): add listing media picker`.

---

## Phase 11: End-to-end validation & rollout

### Task 11.1: E2E manual test checklist

**Files:**
- Create: `docs/plans/2026-04-08-listings-e2e-checklist.md`

**Steps:**
1. Write checklist covering: create → publish → public preview → renter browse → wishlist → status change → notification received → unlist → URL returns 404.
2. Execute manually in dev env.
3. Fix any issues as discovered (new commits).
4. Commit: `docs: add listings e2e checklist`.

### Task 11.2: Enable feature flag in staging

**Files:**
- Modify: staging env config / GitHub Actions secrets

**Steps:**
1. Set `FEATURE_LISTINGS_ENABLED=true` in staging.
2. Deploy, smoke-test.
3. Commit/PR-merge as needed.

### Task 11.3: Pilot tenant rollout

**Steps:**
1. Identify pilot tenant.
2. Enable flag in prod (or per-tenant toggle).
3. Monitor logs + notifications for 1 week.
4. GA decision.

---

## Notes for the executor

- **Tenant isolation is P0**: every query MUST filter by `tenant_id`. Add a cross-tenant access test for every new endpoint.
- **Liquibase is append-only**: never modify existing changesets. Next number after `37` is `38`.
- **Frontend API calls**: always through Next.js proxy rewrite — never hardcode backend URLs in client components.
- **Mobile iterative installs**: `adb install -r` to preserve login state.
- **Commit cadence**: commit after every passing test / green build. Use conventional commit prefixes (`feat:`, `fix:`, `docs:`, `chore:`).
- **Use frontend skills**: every web and mobile task must invoke `frontend-design` and `ui-ux-pro-max` at the start.
