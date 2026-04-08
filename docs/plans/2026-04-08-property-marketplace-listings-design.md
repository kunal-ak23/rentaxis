# Property Marketplace Listings — Design

**Date:** 2026-04-08
**Status:** Approved (brainstorming)
**Author:** kunal + Claude (brainstorming session)

## Summary

Add a per-tenant property marketplace to RentAxis. Landlords can publish vacant units (or upcoming vacancies) as rich listings with photos, amenities, location, and SEO metadata. Renters browse listings in web and mobile, wishlist units, and get notified when an upcoming unit becomes available. Unauthenticated visitors see a limited SEO-friendly public preview with a login CTA.

## Goals

- Per-tenant (landlord-scoped) listings — no cross-tenant marketplace
- Rich unit-level listings with photos, amenities, pricing, location, and floor plans
- Wishlist ("show interest") for both available and upcoming units
- Landlord visibility into interested renters (count + named list)
- Notifications to interested renters when a unit becomes available (in-app + email + push)
- Public, SEO/GEO-optimized preview pages; full details require renter login
- Google Maps integration with directions deep link and "distance from me"
- Available on web (Next.js) and mobile (Flutter renter + admin apps)

## Non-goals (deferred to separate plans)

- Viewing / visit scheduling (calendar-based booking) — separate feature
- Payment / deposit collection from the marketplace
- Cross-tenant aggregated marketplace search
- Reviews / ratings
- Saved-search alerts (beyond wishlisting specific units)
- Listing moderation by super admin
- Marketplace analytics dashboard
- PostGIS radius search (bounding-box is sufficient for v1)

## Key decisions (from brainstorming)

1. **Audience**: per-tenant hybrid. Public preview = limited info, full details + wishlist require renter login.
2. **Listing primitive**: units (not properties). Property details come along as context on each unit listing.
3. **Availability**: explicit per-unit `list` flag controlled by landlord. Units with upcoming lease end auto-appear in `UPCOMING` state so renters can wishlist before vacancy.
4. **Content**: bilingual (EN/AR), fixed amenity enum + free-text "other", full media (photos, floor plan, video, 360 tour).
5. **Interest model**: silent wishlist + landlord can see count and identity of interested renters. Notification on availability.
6. **Notifications**: in-app + email + push, reusing existing notifications module.
7. **Image storage**: Azure Blob Storage (precedent in project).
8. **Maps**: Google Maps (Flutter + web), deep-link to Google Maps app for directions.
9. **Public URL**: `/l/{tenantSlug}/{unitSlug}` with SEO + GEO structured data.

## Implementation notes

**All frontend work (web + mobile) must be implemented using the `frontend-design` and `ui-ux-pro-max` skills** for consistent, high-quality UI/UX across the marketplace surfaces.

---

## 1. Data model

All new tables, tenant-scoped via `BaseTenantEntity`. No modifications to existing `properties`, `units`, `leases`.

### `unit_listings` (1:1 with `Unit`)
- `id` UUID, `tenant_id`, `unit_id` (FK, unique)
- `status` enum: `DRAFT | PUBLISHED | UNLISTED | UPCOMING`
- `title_en`, `title_ar`, `description_en`, `description_ar` (TEXT)
- `bedrooms` int, `bathrooms` int, `size_sqft` decimal, `floor` int, `parking_spaces` int
- `furnishing` enum: `UNFURNISHED | SEMI_FURNISHED | FULLY_FURNISHED`
- `view_type` enum: `SEA | CITY | POOL | GARDEN | STREET | COMMUNITY | OTHER`
- `annual_rent` decimal, `security_deposit` decimal, `min_lease_months` int, `cheques_accepted` int, `dewa_included` bool, `chiller_included` bool, `utilities_estimate` decimal
- `available_from` date (nullable — null = available now)
- `slug` (unique per tenant)
- `seo_title`, `seo_description`, `seo_keywords`, `og_image_url`
- `lat`, `lng` decimal (nullable — falls back to `property_geo` if null)
- `published_at`, `created_at`, `updated_at`

### `unit_listing_amenities`
- `id`, `listing_id`, `amenity` (enum), `custom_label` (nullable, used when `amenity = OTHER`)

### `unit_listing_media`
- `id`, `listing_id`
- `media_type` enum: `PHOTO | FLOOR_PLAN | VIDEO_URL | TOUR_360_URL`
- `url`, `caption`, `sort_order`, `is_cover` bool

### `property_geo` (1:1 with `Property`)
- `property_id` unique FK, `lat`, `lng`, `google_place_id`, `nearby_landmarks` JSONB `[{name, type, distance_m}]`

### `unit_listing_interests`
- `id`, `tenant_id`, `listing_id`, `renter_user_id`, `note`
- `status` enum: `ACTIVE | NOTIFIED | WITHDRAWN`
- `created_at`, `notified_at`
- Unique (`listing_id`, `renter_user_id`)

### Amenities enum (initial set)
```
POOL, GYM, SAUNA, STEAM_ROOM, JACUZZI,
KIDS_PLAY_AREA, KIDS_POOL, BBQ_AREA, GARDEN, ROOFTOP_LOUNGE,
SECURITY_24_7, CCTV, CONCIERGE, INTERCOM,
COVERED_PARKING, VISITOR_PARKING, EV_CHARGING,
ELEVATOR, CENTRAL_AC, DISTRICT_COOLING,
MAIDS_ROOM, STUDY_ROOM, STORAGE_ROOM, LAUNDRY_ROOM,
BUILT_IN_WARDROBES, BALCONY, PRIVATE_GARDEN, MAID_SERVICE,
PET_FRIENDLY, SMART_HOME, SOLAR_POWER,
NEAR_METRO, NEAR_SCHOOL, NEAR_MALL, SEA_VIEW,
OTHER
```

### Migrations
- `37-unit-listings.yaml` — all five tables + indexes
- `37a-tenants-slug.yaml` — add `slug` to `tenants` (backfill from name, then NOT NULL)
- Indexes: `(tenant_id, status)`, `(status, annual_rent)`, `(status, bedrooms)`, `(listing_id, renter_user_id)` unique, `slug` unique per tenant

---

## 2. Backend APIs

Three new controllers under `com.datagami.rentaxis.api.*`.

### `UnitListingController` — landlord-facing (`TENANT_ADMIN`, `PROPERTY_MANAGER`)
- `GET    /api/listings` — paginated list with filters (status, property, availableFrom)
- `GET    /api/listings/{id}` — full detail
- `POST   /api/listings` — create
- `PUT    /api/listings/{id}` — update
- `POST   /api/listings/{id}/publish`
- `POST   /api/listings/{id}/unlist`
- `DELETE /api/listings/{id}` — soft delete
- `POST   /api/listings/{id}/media` — multipart upload → Azure Blob
- `DELETE /api/listings/{id}/media/{mediaId}`
- `PUT    /api/listings/{id}/media/reorder`
- `GET    /api/listings/{id}/interests` — list of interested renters with contact info

### `MarketplaceController` — renter-facing (`RENTER`)
- `GET    /api/marketplace/{tenantSlug}/listings` — filters: `bedrooms`, `minRent`, `maxRent`, `amenities[]`, `furnishing`, `propertyId`, `availableNow`, `nearLat/nearLng/radiusKm`, `sort`
- `GET    /api/marketplace/{tenantSlug}/listings/{slug}`
- `POST   /api/marketplace/listings/{id}/interest` — add to wishlist
- `DELETE /api/marketplace/listings/{id}/interest` — withdraw
- `GET    /api/marketplace/me/wishlist`

### `PublicListingController` — unauthenticated
- `GET    /public/l/{tenantSlug}/{unitSlug}` — limited preview payload
- `GET    /public/l/{tenantSlug}/sitemap.xml`
- Path prefix `/public/**` bypassed in `ApiSecurityFilter`

### Cross-cutting
- **Tenant slug**: add `slug` to `tenants` table; auto-generated from name.
- **Azure Blob**: new `BlobStorageService` wrapping Azure SDK. Env: `AZURE_STORAGE_CONNECTION_STRING`, `AZURE_STORAGE_CONTAINER=listings`. Path: `listings/{tenantId}/{listingId}/{uuid}.jpg`. Images served via CDN with generated widths (400/800/1600) + SAS tokens.
- **Scheduled job**: daily 02:00 — any active lease ending in ≤ 30 days whose unit has a listing flips listing to `UPCOMING` with `available_from = leaseEnd + 1`.
- **Rate limiting**: `/public/l/**` rate-limited per IP.
- **Feature flag**: `FEATURE_LISTINGS_ENABLED` env var gates controllers + routes.

---

## 3. Web frontend (Next.js)

**Must use `frontend-design` and `ui-ux-pro-max` skills.**

### A. Landlord dashboard — `/dashboard/listings`
- Table-first list (per UI standard), filters (status, property, search), sort by `createdAt` asc, pagination, view toggle (table/grid).
- Create/Edit `/dashboard/listings/[id]` with tabs: **Details · Location · Amenities · Media · SEO · Pricing**
  - Media: drag-drop uploader, reorderable gallery, set cover, captions
  - Location: Google Maps picker with Places autocomplete + auto landmark fill
  - SEO: slug, seoTitle, seoDescription, keywords, OG image, live Google snippet preview
  - Amenities: fixed-enum checkbox grid + "Add custom" row
- Interests drawer per listing: name, email, phone, note, date + CSV export

### B. Renter marketplace — `/marketplace/[tenantSlug]`
- Airbnb-style card grid, filter sidebar (bedrooms, rent range slider, amenities, furnishing, availability), map/list toggle (split view with clustered pins), distance-from-me sort via geolocation
- Detail page: hero gallery (lightbox), quick facts, description, amenities icon grid, map + "Get directions" (Google Maps deep link) + "~3.4 km from you", landmarks, floor plan, video/360 embeds, wishlist CTA, building context card
- Wishlist page `/marketplace/wishlist`

### C. Public preview — `/l/[tenantSlug]/[unitSlug]`
- SSR/ISR with `generateMetadata` injecting SEO meta + JSON-LD
- Limited payload (see section 5 for redaction rules)
- Login CTA with `?returnTo=...`
- `robots.txt` + `/l/[tenantSlug]/sitemap.xml` from backend

### i18n
- `useTranslations()` for all strings
- Locale-specific `title_en` / `title_ar`, SEO meta in the active locale
- `hreflang` alternates in public previews

### Maps integration
- `@vis.gl/react-google-maps`, API key via `NEXT_PUBLIC_GOOGLE_MAPS_API_KEY`
- Lazy-loaded per page

---

## 4. Mobile apps (Flutter)

**Must use `frontend-design` and `ui-ux-pro-max` skills.**

### A. Renter app (`mobile/apps/renter`)
New bottom tab **Browse**.

- `BrowseScreen` (`/browse`): search + filter chips, List/Map toggle, card list with distance chip + wishlist heart, map with clustered pins, pull-to-refresh, infinite scroll, filter bottom sheet
- `ListingDetailScreen` (`/browse/:slug`): hero photo carousel (`photo_view`), quick facts, price card, description, amenities grid, map preview + deep link to Google Maps app via `url_launcher`, landmarks, floor plan, video/360, wishlist FAB (relabels to "Notify me when available" for `UPCOMING`), building card
- `WishlistScreen`: status chips (Available / Upcoming / Notified), swipe-to-remove
- `BrowseFiltersSheet` reusable bottom sheet
- Location permission prompt on first entry (skippable)

### B. Admin app (`mobile/apps/manager`)
New "Listings" menu item.
- `ListingsListScreen`, `ListingEditScreen` (same tabs as web), `ListingInterestsScreen` (tap-to-call/email), Google Maps picker

### Shared package (`rentaxis_core`)
- `ListingApiService`, `LocationService` (wrapping `geolocator`)
- Widgets: `ListingCard`, `AmenityChip`, `PriceLabel`, `DistanceChip`, `ListingMapPin`

### Packages added
`google_maps_flutter`, `geolocator`, `permission_handler`, `photo_view`, `url_launcher`, `image_picker`

---

## 5. Notifications & public preview rules

### Notifications (reuses existing module)
| Type | Trigger | Recipient | Channels |
|---|---|---|---|
| `LISTING_AVAILABLE` | Status → `PUBLISHED` with ACTIVE interests (or `UPCOMING` → `PUBLISHED`) | Renters w/ ACTIVE interest | in-app + email + push |
| `INTEREST_RECEIVED` | Renter adds interest | Landlord (tenant admins + property manager) | in-app + email |
| `LISTING_UPCOMING` | Scheduled job flips listing to `UPCOMING` | Landlord | in-app |

- Dispatcher: `ListingNotificationService` subscribes to Spring `ApplicationEventPublisher`
- Dedupe: interest → `NOTIFIED` after fire; no re-notify
- Bilingual email templates: `listing_available.html`, `interest_received.html`

### Public preview redaction
| Shown publicly | Hidden (login required) |
|---|---|
| Cover photo (1) | Full gallery |
| Building name, area, emirate | Exact address, Makani |
| Bedrooms, bathrooms, furnishing | Floor, exact view |
| Rent rounded to nearest 10k | Exact rent, deposit, utilities |
| Amenities list | Description (truncated 200 chars) |
| Map with ~200m offset pin | Exact pin, directions |
| SEO meta | Wishlist button, floor plan, video/360, interests count |
| Available / Upcoming (month only) | Exact available-from date |

---

## 6. SEO & GEO optimization

### JSON-LD (`RealEstateListing` / `Apartment`)
Injected server-side with `name`, `description`, `image`, `numberOfBedrooms`, `numberOfBathroomsTotal`, `floorSize`, `address`, `geo`, `amenityFeature[]`, `offers` (price/currency/availability).

### HTML meta
- `<title>`, `<meta name="description">`, `<meta name="keywords">` from listing SEO fields with auto-fallbacks
- `<link rel="canonical">`, `hreflang` alternates (en/ar)
- Full Open Graph + Twitter Card
- `robots` = `index,follow` for PUBLISHED only

### GEO
- Local SEO keywords auto-injected (emirate + area + type)
- `<address>` microdata
- Nearby landmarks rendered as crawlable `<ul>`
- Breadcrumb JSON-LD (Home → Emirate → Area → Building → Unit)

### Sitemaps & robots
- `/l/{tenantSlug}/sitemap.xml` per tenant
- `/sitemap-index.xml` referencing all tenant sitemaps
- `robots.txt` allows `/l/`, disallows `/marketplace/`

### Performance
- Next.js `<Image>` with AVIF/WebP + blur placeholder
- SSR/ISR with `revalidate: 3600`
- Lazy-loaded map (post-interaction)
- Preloaded fonts, purged Tailwind

---

## 7. Rollout

### Build order
1. Backend data model + migrations
2. Backend landlord APIs + Azure Blob + tests
3. Backend marketplace + public APIs + rate limiting + tests
4. Notification events + templates
5. Web landlord dashboard
6. Web renter marketplace
7. Web public preview + SEO + sitemap
8. Mobile renter Browse tab
9. Mobile admin listings management
10. E2E testing + docs

Each step is independently commit-able. Implementation tasks will be broken down by `writing-plans`.

### Feature flag
`FEATURE_LISTINGS_ENABLED` env var. Rollout: dev → staging → pilot tenant → GA.

### Risks

| Risk | Mitigation |
|---|---|
| Multi-tenant data leak via public URLs | Tenant slug in path + enforced filter + cross-tenant scrape tests |
| Blob cost / bandwidth abuse | Azure CDN, resized variants, rate limiting |
| Scraping pricing | Rent rounded, pin offset, IP rate limit |
| Google Maps cost | Client-side only, deep links for directions (no server calls) |
| Stale listings | Nightly job + "needs refresh" badge after 7 days |
| SEO duplicate content | `hreflang` + per-locale canonicals |
| Notification spam on publish toggling | Interest → `NOTIFIED` dedupe |
| Wishlist on lease-extended unit | "Availability changed" notification (v2) |

---

## Approval

Brainstorming validated with user (2026-04-08). Next step: invoke `writing-plans` skill to produce detailed implementation plan.
