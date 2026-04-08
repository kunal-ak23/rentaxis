# Property Marketplace Listings — E2E Test Checklist

> Execute this checklist against the staging environment with `FEATURE_LISTINGS_ENABLED=true`.
> Check each item manually unless marked [automated].
> Record pass/fail and any notes in the right-hand column.

---

## 0. Prerequisites

| # | Check | Result |
|---|-------|--------|
| 0.1 | `FEATURE_LISTINGS_ENABLED=true` set in staging env | |
| 0.2 | At least one tenant with a `slug` populated in DB | |
| 0.3 | At least one `Unit` belonging to that tenant | |
| 0.4 | Renter user account exists and can log in | |
| 0.5 | Landlord/admin user account exists and can log in | |
| 0.6 | Azure Blob container is reachable (env vars set) | |
| 0.7 | Google Maps API key configured in web and mobile | |

---

## 1. Landlord — Create & Publish (Web Dashboard)

| # | Step | Expected | Result |
|---|------|----------|--------|
| 1.1 | Log in as landlord → Dashboard → Listings → "New Listing" | Create/edit page loads, all tabs visible (Details, Pricing, Location, Amenities, Media, SEO) | |
| 1.2 | Fill Details tab (title EN+AR, bedrooms, furnishing) → Save | `DRAFT` listing created, row appears in Listings table | |
| 1.3 | Open Location tab → search for address via Places autocomplete → pin dropped | Map pin appears, lat/lng populated | |
| 1.4 | Open Amenities tab → select 5 amenities → Save | Amenities saved, visible in details | |
| 1.5 | Open Media tab → upload 3 photos → set one as cover | Photos visible in gallery, cover marked | |
| 1.6 | Open Pricing tab → set annual rent + deposit + cheques | Values persisted on reload | |
| 1.7 | Open SEO tab → set SEO title, description, keywords | Google snippet preview renders correctly | |
| 1.8 | Click "Publish" button in page header | Listing status changes to `PUBLISHED`; publish button becomes "Unlist" | |
| 1.9 | Attempt to access another tenant's listing slug URL | 404 response — cross-tenant isolation confirmed | |

---

## 2. Public Preview — SEO Page (unauthenticated)

| # | Step | Expected | Result |
|---|------|----------|--------|
| 2.1 | Visit `/l/{tenantSlug}/{unitSlug}` without being logged in | Page loads (SSR), shows cover photo, building/area, approximate rent range (not exact), login CTA | |
| 2.2 | Inspect page `<head>` | `og:title`, `og:image`, `og:description`, `canonical`, `hreflang` en+ar present | |
| 2.3 | Inspect page body for JSON-LD | `RealEstateListing` and `BreadcrumbList` schemas present and valid | |
| 2.4 | Visit `/l/{tenantSlug}/sitemap.xml` | XML sitemap with `<loc>` entries for all PUBLISHED listings | |
| 2.5 | Verify rent displayed is a range, not the exact `annualRent` value | e.g. "AED 80k-90k" not "AED 85000" | |
| 2.6 | Verify pin on map is offset from actual property position (≤250m) | Pin does not sit exactly on the unit | |
| 2.7 | Visit a valid slug with wrong tenant slug prefix | 404 — cross-tenant confirmed for public endpoint | |

---

## 3. Renter — Browse & Wishlist (Web Marketplace)

| # | Step | Expected | Result |
|---|------|----------|--------|
| 3.1 | Log in as renter → navigate to `/marketplace/{tenantSlug}` | Listing cards visible with cover, rent, bedrooms, distance | |
| 3.2 | Apply bedrooms filter → results update | Only matching listings shown | |
| 3.3 | Toggle to Map view | Google Map shows pins for all visible listings | |
| 3.4 | Click a listing card | Detail page loads: gallery, quick facts, amenities, map, contact CTA | |
| 3.5 | Click "Save" / heart icon on detail page | Listing added to wishlist (heart turns filled) | |
| 3.6 | Navigate to Wishlist page `/marketplace/wishlist` | Saved listing appears with status chip | |
| 3.7 | Remove from wishlist (click again or swipe on mobile) | Listing removed from wishlist | |

---

## 4. Renter — Browse & Wishlist (Mobile App)

| # | Step | Expected | Result |
|---|------|----------|--------|
| 4.1 | Open Renter app → Browse tab | Listings load; "Near me (10 km)" chip visible if location permission granted | |
| 4.2 | Tap filter icon → adjust bedrooms/rent → Apply | Results update; active filter chips shown | |
| 4.3 | Toggle map view | Map with markers renders; tap marker shows peek card | |
| 4.4 | Tap a listing card | Detail screen opens; photo carousel, quick facts, amenities, map | |
| 4.5 | Tap Save FAB | Heart fills; listing appears in Wishlist tab | |
| 4.6 | Swipe to remove from Wishlist | Listing removed with optimistic update + rollback on failure | |

---

## 5. Notifications

| # | Step | Expected | Result |
|---|------|----------|--------|
| 5.1 | Renter saves an `UPCOMING` listing | "Notify me" FAB state visible | |
| 5.2 | Landlord publishes a previously `UPCOMING` listing | Renter receives in-app notification "listing now available" | |
| 5.3 | Renter receives email notification for the above | Email arrives at renter's address (check staging mailbox) | |
| 5.4 | Landlord views Interests for a listing | Interested renters shown with name, email, phone, date | |
| 5.5 | Landlord receives notification when renter expresses interest | In-app notification appears for landlord | |

---

## 6. Landlord — Manage & Unlist (Web)

| # | Step | Expected | Result |
|---|------|----------|--------|
| 6.1 | Open Interests drawer on a listing | Interested renters listed with contact info | |
| 6.2 | Click "Export CSV" | CSV downloads with renter name, email, phone, date | |
| 6.3 | Click "Unlist" in listing header | Status changes to `UNLISTED`; listing no longer appears in renter marketplace | |
| 6.4 | Public URL `/l/{tenantSlug}/{unitSlug}` of unlisted listing | 404 response | |
| 6.5 | Re-publish the listing | Listing reappears in marketplace | |

---

## 7. Manager App — Listings Management

| # | Step | Expected | Result |
|---|------|----------|--------|
| 7.1 | Open Manager app → More → Listings | Listings list with cover, status pill, interests badge | |
| 7.2 | Tap "+" FAB → create new listing | 5-tab form opens; fill and save | |
| 7.3 | Upload photos via Media tab (camera + gallery) | Photos upload; cover selectable | |
| 7.4 | Tap interests badge on a listing | Interests screen opens with Call/Email/WhatsApp buttons | |
| 7.5 | Tap Call button for an interested renter | Phone dialer opens with correct number | |

---

## 8. Scheduled Job — UPCOMING Status

| # | Step | Expected | Result |
|---|------|----------|--------|
| 8.1 | Create a unit with a lease ending in 14 days and an existing listing | Nightly job (`ListingUpcomingJob`) flips listing to `UPCOMING` | |
| 8.2 | Trigger job manually (or wait for 02:00 UTC run) | Listing shows `UPCOMING` status in dashboard | |

---

## 9. Rate Limiting (Public Endpoints)

| # | Step | Expected | Result |
|---|------|----------|--------|
| 9.1 | Send >60 GET requests to `/public/l/...` within 1 minute from same IP | 61st request returns `HTTP 429 Too Many Requests` | |

---

## 10. Feature Flag

| # | Step | Expected | Result |
|---|------|----------|--------|
| 10.1 | Set `FEATURE_LISTINGS_ENABLED=false` → restart → call any listing endpoint | All listing endpoints return 404 | |
| 10.2 | Re-enable flag → restart | All endpoints work again | |

---

## Sign-off

| Role | Name | Date | Status |
|------|------|------|--------|
| Dev lead | | | ⬜ Pending |
| QA | | | ⬜ Pending |
| Product | | | ⬜ Pending |
