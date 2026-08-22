# Promotions — Cross-Promotion Ads Carousel — Design

**Date:** 2026-08-23
**Status:** Approved (user sign-off on full design)
**Branch:** feat/promotions

## Summary

The client owns roughly 30–40 businesses and wants to cross-promote them inside the Miftah renter mobile app. Admins configure **businesses** (name, logo, category, contact details, allowed link domains) and **ads** (bilingual copy, artwork, a scheduling window, a priority, an optional call-to-action) in the web admin panel. The renter app shows a **rotating six-ad carousel** on the home screen and a full **Offers screen** listing everything else. Taps open the business website in an in-app browser, reveal a coupon code, place a call, or open WhatsApp. Impressions and clicks are recorded so the client can see which promotions work.

Forty ads do not fit in one carousel, so the home strip is a fair, deterministic rotation and the long tail lives on the Offers screen.

### User decisions

| Question | Decision |
|---|---|
| Scope | Full slice — backend, web admin, mobile |
| CTA types | Website, coupon reveal, call, WhatsApp (no per-ad detail screen) |
| Surfacing 30–40 ads | Rotating six on home + a "See all offers" screen |
| Per-ad controls | Schedule window + active toggle, priority/weight, property targeting, business + category tagging |
| Analytics | Impressions and clicks, aggregated per ad |
| Bilingual | AR + EN fields per ad, with fallback to whichever is filled |
| Card design | Photo-hero when artwork exists, split colour card when it does not; coupon-ticket styling reused for the coupon sheet |
| Rotation | Server-side, seeded by date + renter, weighted by priority |
| Link safety | In-app browser, https only, host must match the business's registered domains |
| Mobile data shape | A `PromoAd` value class (deliberate exception to the raw-`Map` convention) |

## Data model (Liquibase changeset `71-promotions.yaml`)

All tables carry `tenant_id UUID NOT NULL` and are read through the existing `TenantAspect`. PKs are UUIDs. All tables have `created_at`, `updated_at`.

### 1. `promo_business`

One row per client-owned business.

| Column | Type | Notes |
|---|---|---|
| `id` | UUID | PK |
| `tenant_id` | UUID | FK, indexed |
| `name_en` | varchar(160) | required |
| `name_ar` | varchar(160) | nullable |
| `logo_url` | varchar(512) | nullable, from `/assets/upload` |
| `category` | varchar(32) | `PromoCategory` enum |
| `phone_e164` | varchar(20) | nullable; required when any ad uses `CALL` |
| `whatsapp_e164` | varchar(20) | nullable; required when any ad uses `WHATSAPP` |
| `allowed_domains` | text | comma-separated hostnames, lowercased; the link allowlist |
| `active` | boolean | default true |

Unique index on `(tenant_id, lower(name_en))`.

### 2. `promo_ad`

One offer. A business may run several.

| Column | Type | Notes |
|---|---|---|
| `id` | UUID | PK |
| `tenant_id` | UUID | FK, indexed |
| `business_id` | UUID | FK → `promo_business`, indexed |
| `title_en` / `title_ar` | varchar(120) | at least one required |
| `subtitle_en` / `subtitle_ar` | varchar(160) | nullable — renders as the uppercase eyebrow |
| `background_image_url` | varchar(512) | nullable; absent → split-card fallback |
| `accent_color` | varchar(9) | nullable; `#RRGGBB` or `#AARRGGBB`; card fill when there is no image |
| `cta_type` | varchar(16) | `PromoCtaType` enum |
| `cta_label_en` / `cta_label_ar` | varchar(40) | nullable; falls back to a per-type default label |
| `cta_url` | varchar(1024) | required when `cta_type = WEBSITE` |
| `coupon_code` | varchar(64) | required when `cta_type = COUPON` |
| `coupon_terms_en` / `coupon_terms_ar` | text | nullable |
| `starts_at` | timestamptz | nullable = live immediately |
| `ends_at` | timestamptz | nullable = never expires |
| `priority` | int | default 1, range 1–10; relative airtime weight |
| `placement` | varchar(20) | `HOME_AND_OFFERS` (default) or `OFFERS_ONLY` |
| `active` | boolean | default true |

Index on `(tenant_id, active, starts_at, ends_at)` for the eligibility scan.

### 3. `promo_ad_property`

Targeting join. **An ad with zero rows targets every property** — that is the default and the common case, so no rows are written unless the client narrows an ad.

| Column | Type |
|---|---|
| `ad_id` | UUID FK → `promo_ad`, cascade delete |
| `property_id` | UUID FK → `property` |

PK `(ad_id, property_id)`.

### 4. `promo_ad_event`

| Column | Type | Notes |
|---|---|---|
| `id` | UUID | PK |
| `tenant_id` | UUID | indexed |
| `ad_id` | UUID | FK → `promo_ad`, indexed |
| `renter_user_id` | UUID | FK → `app_user` |
| `event_type` | varchar(12) | `IMPRESSION` or `CLICK` |
| `occurred_at` | timestamptz | |
| `day` | date | derived from `occurred_at` in Asia/Dubai |

Unique index on `(ad_id, renter_user_id, day)` **where `event_type = 'IMPRESSION'`** (partial index) — one renter opening the app eight times in a day counts as one impression. Clicks always insert.

### Enums (new, in `domain/entity/enums/`)

- `PromoCategory` — `DINING`, `FITNESS`, `RETAIL`, `SERVICES`, `HEALTH`, `EDUCATION`, `OTHER`
- `PromoCtaType` — `WEBSITE`, `COUPON`, `CALL`, `WHATSAPP`, `NONE`
- `PromoPlacement` — `HOME_AND_OFFERS`, `OFFERS_ONLY`
- `PromoEventType` — `IMPRESSION`, `CLICK`

## Rotation algorithm

Eligibility for a given renter, at request time:

```
active = true
AND business.active = true
AND (starts_at IS NULL OR starts_at <= now)
AND (ends_at   IS NULL OR ends_at   >  now)
AND (ad has no promo_ad_property rows
     OR one of those property_ids is the property of an ACTIVE lease held by this renter)
```

The home slate additionally requires `placement = HOME_AND_OFFERS`.

A renter with **no active lease** still sees untargeted ads — the targeting clause only constrains ads that have `promo_ad_property` rows. This is deliberate: a renter between leases is still a renter, and the client's businesses still want to reach them.

From the eligible set, pick six by **weighted sampling without replacement** (the exponential-race / A-Res method):

```
seed  = hash(adId, renterUserId, todayInDubai)      // stable 64-bit
u     = (seed normalised into (0, 1])
key   = -ln(u) / priority
slate = the six ads with the smallest keys, in key order
```

Properties this buys us:

- **Deterministic** — the same renter on the same day gets the same six ads in the same order, so pull-to-refresh does not reshuffle the strip and impression counts are not inflated by refreshes.
- **Fair over time** — the seed changes daily, so every business surfaces over a month rather than the top six by priority dominating forever.
- **Priority means something concrete** — a priority-2 ad gets roughly twice the airtime of a priority-1 ad.

Slate size is a constant `HOME_SLATE_SIZE = 6` in the service, not a magic number at the call site.

## Backend architecture

### New files

```
domain/entity/PromoBusiness.java
domain/entity/PromoAd.java
domain/entity/PromoAdProperty.java
domain/entity/PromoAdEvent.java
domain/entity/enums/{PromoCategory,PromoCtaType,PromoPlacement,PromoEventType}.java
domain/repository/{PromoBusinessRepository,PromoAdRepository,PromoAdEventRepository}.java
core/service/PromotionService.java        // CRUD + URL validation
core/service/PromotionFeedService.java    // eligibility + rotation + event ingest
core/service/PromotionStatsService.java   // per-ad aggregates
api/PromotionAdminController.java
api/PromotionFeedController.java
api/dto/{PromoBusinessDTO,PromoBusinessRequest,PromoAdDTO,PromoAdRequest,
         PromoAdCardDTO,PromoAdStatsDTO,PromoEventBatchRequest}.java
```

`PromoAdCardDTO` is the renter-facing projection — it deliberately omits admin-only fields (priority, targeting, placement, event counts) so the feed does not leak configuration.

### Admin endpoints — `@PreAuthorize hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')`

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/promotions/businesses` | paginated, sorted by `createdAt` ascending |
| POST | `/api/v1/promotions/businesses` | |
| PUT | `/api/v1/promotions/businesses/{id}` | |
| DELETE | `/api/v1/promotions/businesses/{id}` | refused with 409 if ads reference it; deactivate instead |
| GET | `/api/v1/promotions/ads` | filters: `businessId`, `category`, `status` (live / scheduled / expired / paused) |
| POST | `/api/v1/promotions/ads` | |
| PUT | `/api/v1/promotions/ads/{id}` | |
| DELETE | `/api/v1/promotions/ads/{id}` | |
| GET | `/api/v1/promotions/ads/{id}/stats` | impressions, clicks, tap-through rate, daily series |

Artwork and logos reuse the existing `POST /api/v1/assets/upload` (already image-only, 2 MB capped, `SUPER_ADMIN`/`TENANT_ADMIN`).

**Write-time validation in `PromotionService`, not only in the form:**

- `cta_type = WEBSITE` → `cta_url` must parse, must be scheme `https`, and its host (or a parent of it) must appear in the owning business's `allowed_domains`. Rejected with a field-level 400 otherwise.
- `cta_type = COUPON` → `coupon_code` required.
- `cta_type = CALL` / `WHATSAPP` → the business must have the matching E.164 number.
- `ends_at`, when present, must be after `starts_at`.
- At least one of `title_en` / `title_ar` non-blank.

Because the check lives at the write boundary, the mobile app can trust any `cta_url` it receives — the allowlist is not re-derived on the client.

### Renter endpoints — `@PreAuthorize hasRole('RENTER')`

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/promotions/feed` | ≤ 6 `PromoAdCardDTO`, rotation applied; empty array when nothing is eligible |
| GET | `/api/v1/promotions/offers` | all eligible ads, paginated, optional `category` filter |
| POST | `/api/v1/promotions/events` | body `{ events: [{ adId, type }] }`, max 50 per call |

The events endpoint ignores unknown or ineligible `adId`s silently rather than 400-ing — a stale batch from a backgrounded app must not surface an error to the renter.

## Web (Next.js) — `/[locale]/dashboard/promotions`

Two tabs: **Businesses** and **Ads**.

**Businesses tab** — table-first with the standard view toggle and pagination, sorted by `createdAt` ascending. Columns: logo, name (EN/AR), category, contact, ad count, active. Editor is a side sheet; `allowed_domains` is a chip input that strips scheme and path so the client can paste a full URL and get a hostname.

**Ads tab** — table-first, same conventions. Columns: artwork thumbnail, title, business, category, CTA type, window, priority, status badge (live / scheduled / expired / paused), views, taps, tap-through rate.

**Ad editor** — a two-column form with a **live preview of the real card** on the right, rendering exactly what the mobile carousel will draw (photo-hero or split fallback, in both AR and EN via a language toggle). Left column: business picker, bilingual copy pairs, artwork upload, accent colour picker, date-range picker, priority slider, placement, and a CTA-type selector that swaps the lower half of the form between the URL field, the coupon code + terms fields, and a read-only confirmation of the business's phone/WhatsApp number.

All strings via `useTranslations()`; new keys under `promotions.*` in both message catalogues.

## Mobile (Flutter)

### `packages/rentaxis_core`

- `lib/api/services/promotion_service.dart` — `getFeed()`, `getOffers({category, page})`, `postEvents(List<PromoEvent>)`.
- `lib/models/promo_ad.dart` — a small immutable `PromoAd` (plus `PromoBusinessRef`) with `fromJson`. **Deliberate exception to the raw-`Map` convention**: the card has eighteen fields and every text field needs a bilingual pick with fallback (`title(isAr)` returns AR when present and non-blank, else EN, else AR). Repeating that at six call sites is where bugs would live. Documented as an exception in the file's doc comment.

### Renter app (`apps/renter`)

```
lib/providers/promotion_provider.dart      // feed + offers providers, event queue
lib/widgets/ads_carousel.dart              // the carousel
lib/widgets/ad_card.dart                   // photo-hero / split fallback
lib/widgets/coupon_sheet.dart              // ticket-styled bottom sheet
lib/screens/offers_screen.dart             // route /offers
```

**`AdsCarousel`** implements the behaviour contract exactly:

1. `PageView.builder` with `PageController(viewportFraction: 0.88)` and `padEnds: false`. First page 14px left padding, last page 14px right padding, 6px inner gaps.
2. `Timer.periodic` every 4s animating to `(page + 1) % length` over 450ms with `Curves.easeOutCubic`; never started when length ≤ 1.
3. `NotificationListener<ScrollNotification>` cancels the timer on `ScrollStartNotification` with non-null `dragDetails`, and restarts it 3s after the matching `ScrollEndNotification`, guarded by a `_userDragInProgress` bool so programmatic scrolls never hit the resume path.
4. `MediaQuery.disableAnimationsOf(context)` true → never auto-advance; manual swipe still works. Re-evaluated each build through `_syncAutoAdvance(length, reducedMotion)`, which early-returns unless length or the flag changed.
5. Dots below the strip only when length > 1: `AnimatedContainer`, 250ms `easeOutCubic`, active 18×6 in `MiftahColors.brass`, inactive 6×6 in `MiftahColors.borderStrong`, 5px gaps, radius 3.
6. Card height `140.0 * MediaQuery.textScalerOf(context).scale(1).clamp(1.0, 1.5)`; the copy block sits in `Flexible > ClipRect > OverflowBox(alignment: topLeft, minHeight: 0, maxHeight: infinity)` so it lays out at natural size and clips instead of throwing `RenderFlex overflowed`. The comment explaining this is kept.
7. Card radius `MiftahRadii.card` (20). Background image via `CachedNetworkImageProvider` with a `Colors.black` 0.35 `BlendMode.darken` filter. Foreground white over an image, `MiftahColors.textPrimary` otherwise.
8. `InkWell` with matching radius; the tap handler dispatches on `ctaType` (see below).
9. `dispose()` cancels both timers and the `PageController`; the resume callback guards `mounted`, the tick guards `_pageController.hasClients`.

Empty feed → `SizedBox.shrink()`.

**Theme mapping** from the source app's tokens: `t.primary` → `MiftahColors.brass`, `t.outlineVariant` → `MiftahColors.borderStrong`, `t.secondaryContainer` / `t.onSecondaryContainer` → `MiftahColors.surfaceAlt` / `MiftahColors.textPrimary`, `t.onSurface` / `t.surface` → `MiftahColors.ink` / `MiftahColors.surface`. `ThType.eyebrow(color)` → `MiftahType` at 11px, `FontWeight.w800`, `letterSpacing: 0.08`.

**Full-bleed placement.** The home `ListView` has `EdgeInsets.fromLTRB(20, 6, 20, 24)`, but the carousel must span the full screen width for the peek to read correctly. The strip is wrapped in an `OverflowBox` sized to `MediaQuery.sizeOf(context).width` so it escapes the 20px gutter without changing the padding of every sibling.

**Home position:** in `home_screen.dart`, after `_QuickActions` and before `_FacilitiesCard`.

**Last card** in the strip is a "See all offers" tile pushing `/offers`. It is a real extra page in the `PageView` — it counts toward the page total, gets its own dot, and auto-advance cycles through it — but it is not an ad, so it fires no impression and no click event. It is appended only when the offers list holds more ads than the slate; when the client has six or fewer ads total there is nothing more to see and the tile is omitted.

**Tap handling** by `ctaType`:

- `WEBSITE` — open in an in-app browser (`url_launcher` with `LaunchMode.inAppBrowserView`) showing the host. Defensive re-check that the URL is https before launching; anything else is a no-op.
- `COUPON` — `showModalBottomSheet` with `CouponSheet`: the ticket layout, the code in a monospace pill with a Copy button (`Clipboard.setData` + a confirmation `SnackBar`), terms, and the expiry date.
- `CALL` — `tel:` launch.
- `WHATSAPP` — `https://wa.me/<e164>` launch.
- `NONE` — no-op, and the card renders without a CTA pill.

Every one of these first enqueues a `CLICK` event.

**Impression tracking.** A page is counted once it settles (`onPageChanged`, plus the initial page on first build). The provider holds a `Set<String>` of ad ids seen this session, dedup client-side, and flushes the batch to `/promotions/events` on carousel dispose and on `AppLifecycleState.paused`. The server dedups per day regardless, so a lost flush costs nothing but a missing count.

**Offers screen** — a scrollable list of every eligible ad, grouped under category filter chips, using the same `AdCard` at a taller fixed height. Standard `EmptyState` when the client has configured nothing. No FAB (renter shell rule).

All user-facing strings follow the existing `_L(context.isAr)` pattern; ad content itself comes from the API already resolved by locale.

## Testing

**Backend**

- Rotation determinism — the same `(renter, day)` yields the same slate across repeated calls.
- Priority weighting — over many simulated days, a priority-2 ad appears roughly twice as often as a priority-1 ad.
- Eligibility — expired, not-yet-started, paused, and inactive-business ads are excluded; an ad with no targeting rows reaches every renter; an ad targeted at property A does not reach a renter leasing in property B.
- Impression dedupe — two impressions for the same ad, renter, and day collapse to one row; two clicks do not.
- Cross-tenant isolation — a renter in tenant B never receives tenant A's ads (P0).
- URL allowlist — an off-domain or non-https `cta_url` is rejected at the admin endpoint.
- `PromoAdCardDTO` omits priority, targeting, and placement.

**Mobile — `apps/renter/test/ads_carousel_test.dart`**

- Empty list renders nothing (`SizedBox.shrink`, no `PageView`).
- Single banner shows no dots and starts no timer (pumping 10s does not change the page).
- Multi-banner auto-advances after 4s (`tester.pump(Duration(seconds: 4))` then settle).
- A drag pauses auto-advance, and it resumes 3s after the drag ends.
- `MediaQuery(disableAnimations: true)` never advances, but a manual swipe still changes the page.
- Card falls back to the split layout when `backgroundImageUrl` is null, and uses `accentColor` as the fill.
- Tapping a `COUPON` ad opens the sheet and the Copy button puts the code on the clipboard.
- Text scale 1.5 does not overflow (no `RenderFlex overflowed` exception).

**Web** — a test for the ad editor's CTA-type switching and for domain-allowlist client-side feedback.

## Phasing

1. **Backend + admin panel** — tables, entities, services, both controllers, the `/dashboard/promotions` screens. The client can start entering their 40 businesses.
2. **Mobile carousel + Offers screen** — `promotion_service`, `PromoAd`, `AdsCarousel`, `AdCard`, `CouponSheet`, `offers_screen`, home wiring, widget tests.
3. **Analytics surfacing** — event ingest hardening, the stats endpoint's daily series, and the stats column and per-ad detail in the admin panel.

Each phase is independently shippable; phase 2 renders `SizedBox.shrink()` until phase 1 has data.

## Out of scope (explicitly)

- Paid advertising, billing, budgets, or impression caps. Every ad here is the client's own business.
- Third-party ad networks or any external ad SDK.
- Per-ad detail screens inside the app.
- Push notifications for new offers.
- Geofencing or time-of-day targeting.
- Manager and security apps — the carousel is renter-only.
- Web renter portal. Mobile only for now; the feed endpoint is portal-ready if that changes.
