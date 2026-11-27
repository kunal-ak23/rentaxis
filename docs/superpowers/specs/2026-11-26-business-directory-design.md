# Business directory on the renter home — design

Date: 2026-11-26 · Status: draft for approval

## Why

The client's UX expert says the renter home is too busy: every promotion is
rendered on it as a large ad card in a carousel. The client wants the ads off
the home screen and replaced by small tiles, styled like the existing
Maintain / Visitors quick-action tiles. Each tile is a **category** of
businesses. Tapping one opens a list of that category's businesses. For launch:

- **The client's group of companies** (a "group" category): the group's companies.
- **Car wash**: only QuickWash (quickwash.ae).

More categories will come later. They must be set per landlord org and fetched
by the app, with no app release needed to add one.

## Goals

- Remove the ad carousel (`HomeAdsStrip`) from the renter home.
- Add a row of category tiles under the quick actions.
- Category → list of businesses → business page with details, a website
  button that sends the renter to the business's site, Call / WhatsApp, and
  that business's current offers (coupons and redirects keep working).
- Admins create, order, rename, archive categories and pick which businesses
  are in each, in the web admin panel.

## Non-goals

- No change to how ads are created, approved, targeted or tracked.
- No renter details in the website link (the coupon redirect keeps its own
  parameters and consent sheet; the plain website button carries none).
- No tracking of tile or business-page taps (ad clicks stay tracked as today).
- No change to the manager app.

## Anti-goals

- A home screen that is still busy: the row must stay as compact as the quick
  actions, with no ad artwork on it.
- A category list that leaks across orgs (P0, tenant isolation).
- Breaking renter app versions already in the stores: they keep calling
  `/promotions/feed`, so that endpoint stays.

## Approaches

| | Approach | Trade-off |
|---|---|---|
| **A (picked)** | New admin-managed directory categories per org, with a many-to-many link to businesses | New table, API and admin tab; new categories need no release |
| B | Add `CARWASH` / `GROUP` to the fixed `PromoCategory` enum | Cheap, but every new category needs a deploy plus a store release, and a group of companies is not a kind of business |
| C | A JSON list in tenant settings | No real admin UI, no referential link to businesses |

Kunal picked A on 2026-11-26.

## Decisions

**Data (backend).** Next changeset is `195-*` (check the folder).

- `promo_directory_categories`: `id` UUID, `tenant_id`, `name_en` (required,
  ≤60), `name_ar` (≤60), `icon_url` (≤512, uploaded with the existing promo
  image upload), `sort_order` int, `active` bool, `archived_at`,
  `created_at`, `updated_at`. `BaseTenantEntity`, scoped by `TenantAspect`.
- `promo_directory_category_businesses`: `category_id`, `business_id`,
  `tenant_id`, unique `(category_id, business_id)`. **A business can be in
  several categories** (my call: lets a group company also sit under its trade).
- `promo_businesses` gains `description_en`, `description_ar` (≤500) and
  `website_url` (≤512, `https` only, host must be in the business's existing
  `allowedDomains`, enforced on the backend write path like ad links).
- The existing `PromoCategory` enum and the Offers filter chips are untouched.

**Renter API** (`RENTER` role, tenant from the token, inside a transaction:
see the tenant-filter-outside-a-transaction P0 class):

- `GET /api/v1/promotions/directory`: active, non-archived categories that hold
  at least one active, non-archived business, by `sort_order` then
  `created_at`. Each row: id, names, icon URL, business count.
- `GET /api/v1/promotions/directory/{categoryId}`: that category's businesses
  (id, names, logo, description), ordered by business `created_at` ascending.
  404 for another org's id.
- `GET /api/v1/promotions/directory/businesses/{id}`: business details for the page
  (moved under /directory in planning so renter reads never share a path with
  admin business routes).
- Offers on the page: add an optional `businessId` param to the existing
  `GET /promotions/offers`, so property targeting, schedule and approval
  rules stay in one place.

**Admin API and web** (`SUPER_ADMIN`, `TENANT_ADMIN`, same as
`PromotionAdminController`):

- CRUD + archive/restore + reorder under `/api/v1/promotions/directory-categories`.
- A new **Directory** tab on `/dashboard/promotions`: table of categories
  (icon, names, business count, active), editor with EN/AR names, icon upload
  (`PromoImageField`), sort order, active toggle, and a multi-select of the
  org's businesses. Table-first, paginated, per the UI standard.
- `BusinessEditor` gains description EN/AR and website fields.
- Promoters: cannot see or manage categories (endpoints stay closed in
  `PromoterFenceFilter`; update `PromoterFenceSweepIT`). They can edit
  description and website on their own businesses via
  `PromoterBusinessesTab`, through the same validation.

**Renter app (Flutter).**

- `home_screen.dart`: remove `HomeAdsStrip`; add a directory row under
  `_QuickActions` using the same tile look. The 38px box shows the category
  icon image, with a fallback icon. Four per row, wrapping onto more rows.
- If the list is empty or fails to load, the row is hidden (no error state on
  home; the home screen must not degrade because of it).
- New routes: `/directory/:categoryId` (business list) and
  `/directory/business/:id` (business page). The business page shows logo, name,
  description, **Visit <host>** (opens externally after the existing
  `promo_url` scheme/host checks), Call and WhatsApp (when set), then
  "Current offers" using the existing `AdCard` and coupon / redirect sheets.
  No offers → the section is hidden.
- The Offers screen loses its only entry point (the strip). Remove
  `HomeAdsStrip`, `AdsCarousel` and the `/offers` route if nothing else uses
  them; keep `AdCard` and the coupon widgets.
- Bilingual EN/AR, RTL checked on every new screen. Renter tests pump
  `MiftahTheme.light`.

## Calls settled after the builder check (Claude's, open to correction)

- **Who sees a business.** The directory is unfiltered by property: every
  renter in the org sees every active, non-archived business in a category,
  whether or not it has ads. A business page with no live offers just hides
  that section.
- **Offers on a business page.** These are every ad of that business that
  `/offers` would show this renter today (approved, in schedule, targeted at
  their property), **regardless of placement**. FEED and OFFERS_ONLY both show.
  Placement stays in the data, for older app versions.
- **Ads with no categorised business** stay unreachable in the new app. That
  is accepted: the admin fixes it by putting the business in a category.
- **App `/offers` route and `OffersScreen`** are deleted along with the strip.
  The backend `/feed` and `/offers` endpoints stay.
- **Promoters (revised in planning).** Code check: promoters already set
  `allowedDomains` on their own businesses, and a promoter's business edit goes
  live at once (only its ads are re-submitted for approval). So a promoter
  could point the directory's website button anywhere with no review.
  **Promoters therefore cannot set description or website.** `PromoterService`
  keeps the stored values whatever the request carries, and the promoter
  business form doesn't show the fields. Only admins place businesses in
  categories.
- **Rollout.** No feature flag. An org with no categories gets no row, which
  is effectively the flag. Older app versions keep the carousel through
  `/feed` until they update. The backend and web can ship before the store
  release.
- **Launch categories** are per org. Only the client's org gets them. No
  defaults for other orgs.
- **Launch data (Kunal, 2026-11-26):** the client's admin enters the
  categories, the group companies and QuickWash through the admin panel. There
  is no prod data migration. The admin tab must therefore be usable on its own
  before the app release. The demo seed may add sample categories for local
  testing only.

## Left to the builder

- Exact tile spacing, the business-list row layout, and the fallback icon.
- Whether reorder is drag-and-drop or a number field in the admin tab.
- Adding launch categories to `scripts/seed_demo_tenant.py` for demos.
- Test split: tenant-isolation tests on every new endpoint, website allowlist
  tests, widget tests for the row (empty, error, wrap) and the business page.
