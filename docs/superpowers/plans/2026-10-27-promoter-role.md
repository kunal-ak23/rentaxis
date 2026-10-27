# Promoter role — implementation plan (2026-10-27)

## Why

Outside businesses (first one: a laundry/car-wash company) want to advertise to a
landlord's renters through the existing cross-promotion module (PRs #91–93). Today
only an org's admins can manage promo businesses and ads. A **PROMOTER** is a login
that can only manage ads (and optionally businesses) it has been given, and nothing
it writes reaches renters until an admin approves it.

## Rulings (Kunal, 2026-10-27)

1. New role `PROMOTER`. Promoters only create businesses and ads.
2. Admins grant each promoter access to specific businesses, plus one switch,
   **can create businesses**. Off → ads only, on the businesses granted. On → may
   also create businesses (and is granted access to each one it creates).
3. Admins choose which properties each promoter can see. The promoter sees only
   those (by name) and may target ads only at them.
4. A promoter's ad must be approved by an admin before it is live.

## Calls made by Claude (user informed; may override)

- Web portal only. Mobile apps unchanged (a promoter signing in to the manager app
  will see 403s; follow-up).
- A promoter belongs to exactly one org. A second landlord invites a separate login.
  An email that already has a Miftah login cannot be made a promoter, and a promoter
  never gets extra org memberships.
- Businesses need no approval, but **any promoter write that renters could see sends
  ads back to PENDING**: a promoter edit of an ad → that ad PENDING; a promoter edit
  of a business → every APPROVED ad of that business PENDING. Invariant: *nothing a
  promoter writes reaches renters without an admin approval after the write.*
- Priority and placement are admin-only. Promoter creates get priority 1 /
  HOME_AND_OFFERS; promoter edits keep the existing values.
- Admin-created ads are APPROVED on create. Admin edits do not change approval
  status (approval is always an explicit action).
- A promoter's ad must target ≥1 property, all within the promoter's allowed set.
  At feed time a promoter-written ad only shows in properties that are BOTH targeted
  AND currently in that promoter's allowed set — so removing a property from a
  promoter takes its ads off that property immediately, and the "zero targeting rows
  = every property" rule can never apply to a promoter ad.
- Notifications: in-app (and email through the existing NotificationService →
  EmailEventType path, same as MEETING_*) — admins on submit, the promoter on
  approve/reject. Reject requires a reason.
- Promoter sees impressions/clicks for ads of its businesses.
- Real people's emails never go in code, tests or docs — use `promoter@example.test`.

## Data (Liquibase changeset `183-promoter-role.yaml`, append to master)

- `promo_ad` add:
  - `approval_status varchar(16) NOT NULL DEFAULT 'APPROVED'` + CHECK in
    (`PENDING`,`APPROVED`,`REJECTED`) — existing rows stay live.
  - `promoter_user_id uuid NULL` FK users ON DELETE SET NULL — the promoter who last
    wrote the ad (null = admin-only ad).
  - `submitted_at timestamptz`, `reviewed_by uuid` FK users ON DELETE SET NULL,
    `reviewed_at timestamptz`, `rejection_reason varchar(500)`.
  - index `(tenant_id, approval_status)`.
- `promoter_access(user_id uuid PK FK users ON DELETE CASCADE, tenant_id uuid NOT NULL
  FK tenants, can_create_businesses boolean NOT NULL DEFAULT false, created_at,
  updated_at)`.
- `promoter_business(tenant_id, user_id FK users CASCADE, business_id FK promo_business
  CASCADE, created_at, PK(user_id, business_id))`.
- `promoter_property(tenant_id, user_id FK users CASCADE, property_id FK properties
  CASCADE, created_at, PK(user_id, property_id))`.
  **Not** `user_property_assignment`: that table drives PROPERTY_MANAGER scoping and
  recipient resolution (lease/cheque emails, "Property Manager" card) — reusing it
  would leak renter data to promoters.
- All new tables carry `tenant_id` and the Hibernate tenant filter like the other
  promo entities.

## Backend

### Role plumbing
- `UserRole.PROMOTER`. `UserController.privilegeRank` PROMOTER → 7 (lowest).
- `UserController` create/update **refuse** role PROMOTER and any role change to or
  from PROMOTER (400: "Promoters are invited from Promotions → Promoters"). Users list,
  deactivate/reactivate/delete/resend-invite keep working for promoters.
- `UserService`: refuse `addTenantMembership` for a PROMOTER. Check every
  role switch (`issuesInviteToken`, `getsTenantMembership`, recipient resolution,
  login gates, AuthTokenService, TokenRevocationService) and add PROMOTER where a
  password-login staff-like user belongs (invite token: yes; tenant membership: home
  org only).

### Promoter fence (defence in depth — the most important piece)
Many endpoints are `isAuthenticated()` or role-switch inside services (tickets,
meetings, …), so auditing them one by one is not safe. Add a fence (servlet filter
or HandlerInterceptor, after authentication) that answers **403** to a PROMOTER on
every path except an explicit allowlist:
- `/api/v1/promoter/**`
- the self-service profile path(s) (`ApiSecurityFilter.SELF_SERVICE_PROFILE_PATH`,
  `/api/auth/me*`), password change
- `/api/v1/notifications/**`
- GET `/api/v1/tenant/features`, GET `/api/v1/tenant/info` (web shell), GET org
  branding read (if the web shell calls it)
Plus a **sweep test**: enumerate every handler from `RequestMappingHandlerMapping`,
call each as a PROMOTER (bearer or legacy headers, as the existing auth ITs do) and
assert 403 for everything outside the allowlist. Mutation-check it.

### Admin API (`/api/v1/promotions`, SUPER_ADMIN/TENANT_ADMIN — existing controller)
- Ad DTO gains `approvalStatus`, `rejectionReason`, `submittedAt`, `reviewedAt`,
  `submittedByName` (promoter name, null for admin ads).
- `GET /ads?approvalStatus=PENDING|APPROVED|REJECTED` filter (optional).
- `GET /ads/pending-count` → `{count}`.
- `POST /ads/{id}/approve` → APPROVED, reviewed_by/at set, rejection_reason cleared.
- `POST /ads/{id}/reject` `{reason}` (required, trimmed, ≤500) → REJECTED.
- Approve/reject a non-PENDING ad → 400. Cross-tenant → 404.
- Promoters:
  - `GET /promoters` → `[{userId, name, email, phoneNumber, status, canCreateBusinesses,
    businessIds, propertyIds}]` for the tenant (sorted createdAt asc).
  - `POST /promoters` `{name, email, phoneNumber?, canCreateBusinesses, businessIds,
    propertyIds}` → creates PROMOTER user in the caller's org through
    `UserService.createUser` (normal set-password invite email), then access rows.
    Email already has a login → 400.
  - `PUT /promoters/{userId}` `{canCreateBusinesses, businessIds, propertyIds}` →
    replaces access. Target must be a PROMOTER in the caller's org (else 404).
  - All business/property ids validated against the tenant (404 otherwise), as
    `replaceTargeting` does.

### Promoter API (`/api/v1/promoter`, `hasRole('PROMOTER')` — new controller)
Every lookup scoped to the caller's home tenant AND their access rows; a business/ad
outside their access → 404 (never 403, no probing).
- `GET /me` → `{canCreateBusinesses, organisationName}`.
- `GET /properties` → `[{id, nameEn, nameAr}]` of allowed properties only.
- `GET /businesses` → businesses granted (PromoBusinessDTO shape).
- `POST /businesses` (requires canCreateBusinesses) → create + grant.
- `PUT /businesses/{id}` (requires canCreateBusinesses and access) → update; every
  APPROVED ad of the business → PENDING (submitted_at now, promoter_user_id = caller).
- `GET /ads?businessId=` (paged, createdAt asc) → ads of granted businesses with
  approval fields; `propertyIds` filtered to the caller's allowed set.
- `POST /ads` → PENDING, promoter_user_id = caller, submitted_at now; propertyIds
  required non-empty and ⊆ allowed (400 otherwise); priority 1, HOME_AND_OFFERS.
- `PUT /ads/{id}` → apply edits except priority/placement (kept), → PENDING.
- `DELETE /ads/{id}` → same rule as admin delete (only when no events).
- `GET /ads/{id}/stats` (or included in list) → impressions/clicks via
  `PromotionStatsService`, access-checked.
- `POST /images`, `GET /images/{file}` → `PromotionImageService`, caller's tenant.
- Every new service method `@Transactional` (readOnly for reads) — tenant filter only
  applies inside a transaction (see memory: tenant filter outside tx).

### Feed (`PromoAdRepository.findEligible` and every renter-facing read in
`PromotionFeedService` — offers, click/impression tracking by ad id)
- `AND a.approvalStatus = APPROVED`.
- `AND (a.promoterUserId IS NULL OR EXISTS (SELECT 1 FROM PromoAdProperty p,
  PromoterProperty pp WHERE p.adId = a.id AND pp.userId = a.promoterUserId
  AND pp.propertyId = p.propertyId AND p.propertyId IN :propertyIds))`.
- Click/impression endpoints must not accept a non-APPROVED ad id.

### Notifications
- Events published from the service, handled AFTER_COMMIT with
  `NotificationService.notifyInAppInNewTx` per recipient (BookingNotificationService
  pattern) — or `notify` + new EmailEventType entries + templates if the
  MEETING_* email path is straightforward: `PROMO_AD_SUBMITTED` → every active
  TENANT_ADMIN of the org; `PROMO_AD_APPROVED` / `PROMO_AD_REJECTED` (with reason) →
  the promoter. EN + AR text.

### Tests (Testcontainers ITs + unit)
- Fence sweep IT (above).
- Cross-tenant IT: promoter of org A cannot read/write org B's businesses/ads/
  properties (404); admin of B cannot approve A's ads.
- Access IT: promoter without business grant → 404; without canCreateBusinesses →
  403 on create business; target property outside allowed → 400; zero properties →
  400; priority/placement in request ignored.
- Approval IT: promoter create → PENDING → not in feed; approve → in feed; promoter
  edit → PENDING → out of feed; business edit → its approved ads PENDING; reject
  needs reason; admin create → APPROVED.
- Feed IT: promoter ad targets P1,P2; remove P2 from promoter → renter at P2 no
  longer sees it, P1 still does.
- UserController refuses PROMOTER create/role change; existing-login email refused.
- Golden gate untouched: `./gradlew test -PincludeTags=golden` stays green.

## Web (Next.js)

- `rbac.ts`: `PROMOTER` in `UserRole`, `ROLE_RANK` (7), excluded from
  `PROVISIONABLE_ROLES` (like RENTER — promoters are invited from Promotions);
  `getRoleLabel`; new permission `canUsePromoterPortal: ['PROMOTER']`.
- `auth.ts` / types accept the role.
- `routeGuard.homeRedirect`: PROMOTER → `/dashboard/promoter`.
- `routeRegistry`: `/dashboard/promoter` allowed for PROMOTER only.
- `navModel`: a PROMOTER sees only "My ads" (+ Notifications/Profile/Help as other
  roles do); no other links. Existing routes already deny the role via permissions —
  verify with the existing route-registry/nav tests and add PROMOTER cases.
- **Promoter portal** `/dashboard/promoter`: tabs **Ads** and **Businesses**.
  - Ads: list with status pill (Pending approval / Live / Rejected — show reason /
    Scheduled / Ended / Paused), impressions/clicks, New ad, Edit, Delete. Reuse
    `AdEditor` with a `mode="promoter"` prop: hides priority and placement, property
    picker lists only `/promoter/properties` and requires ≥1, shows "Saving sends
    this ad for approval" notice. Uses a promoter API client
    (`lib/api/promoter.ts`) hitting `/api/proxy/v1/promoter/...`.
  - Businesses: list of granted businesses; New/Edit only when
    `canCreateBusinesses` (reuse `BusinessEditor`), with a notice that editing a
    business sends its live ads back for approval.
- **Admin Promotions page**:
  - Ads tab: approval status pill + filter (All / Pending approval / Rejected),
    "Submitted by {name}" line, Approve and Reject (reason dialog) actions on
    pending ads; pending count badge on the tab.
  - New **Promoters** tab: list (name, email, status, businesses count, properties
    count, can-create badge); "Invite promoter" dialog (name, email, phone optional,
    can-create switch, business multi-select, property multi-select); Edit access
    dialog (same minus identity). Deactivate etc. stays on Users/Staff.
- i18n EN + AR for everything (`Promotions`, new `Promoter` namespace, `Roles.PROMOTER`
  = "Promoter" / "مروّج"). RTL-safe classes (`ms-`/`me-`, `start`/`end`).
- Vitest tests for: promoter portal renders only granted data, editor promoter mode
  hides priority/placement and requires a property, admin approve/reject flow,
  Promoters tab invite payload, homeRedirect + route registry for PROMOTER.

## Order

1. Backend (one branch, commits per slice) and web in parallel against the API above.
2. Full backend suite + golden gate + web `npm run lint`, `npx tsc --noEmit`,
   `npx vitest run`.
3. Review loop (security-focused reviewer on the fence, tenant scoping, feed query;
   general reviewer on web) until no Critical/Important.
4. PR → merge → deploy (push to main runs deploy.yml) → verify on prod.

## Addendum A — coupon redirect with custom parameters (Kunal, 2026-10-27)

Ruling: for a COUPON ad, **Copy code** copies the code AND then opens a redirect link
(e.g. QuickWash's app/universal link) with **custom query parameters** whose names the
admin/promoter chooses and whose values come from our app. The renter confirms what is
shared before the link opens (only when renter data is in it).

### Ad fields (admin + promoter requests and `PromoAdDTO`)
- `redirectUrl: string | null` — COUPON ads only (cleared for every other ctaType, like
  `ctaUrl`/`couponCode` are). https, validated against the business's allowed domains
  with the same `PromotionUrlValidator` rule as a WEBSITE `ctaUrl`. ≤ 2048 chars.
- `redirectParams: Array<{ name: string, source: Source, value?: string }>` — ≤ 10
  entries; `name` matches `^[A-Za-z0-9_.\-]{1,40}$` and is unique within the ad;
  `value` required (trimmed, ≤ 200) only when `source = "STATIC"`, ignored/cleared
  otherwise. Params without a `redirectUrl` → 400. Empty list / null = none.
- `Source` = `COUPON_CODE | RENTER_NAME | RENTER_PHONE | RENTER_EMAIL | PROPERTY_NAME |
  UNIT_NUMBER | STATIC`.
- Storage: `promo_ad.redirect_url varchar(2048)`, `promo_ad.redirect_params jsonb`
  (in changeset 183 — it is not merged anywhere yet).
- Part of the ad, so a promoter edit of either field → PENDING like any edit, and the
  admin sees them when approving.

### Renter feed (`PromoAdCardDTO`, `/feed` and `/offers`)
- `redirectUrl: string | null` — **resolved server-side for the calling renter**:
  `redirectUrl` + each param appended as `name=value` (URL-encoded, existing query kept).
  A param whose value is unavailable (no email, no lease) is omitted.
  `PROPERTY_NAME` / `UNIT_NUMBER` come from the renter's active lease at a property the
  ad targets (ad targets all → their earliest-starting active lease); English property
  name; unit number as shown on the lease.
- `sharedFields: string[]` — the renter-data sources actually present in the resolved
  URL (subset of RENTER_NAME, RENTER_PHONE, RENTER_EMAIL, PROPERTY_NAME, UNIT_NUMBER),
  for the consent sheet. COUPON_CODE and STATIC are not renter data.
- Both null/empty for non-COUPON ads and coupon ads without a redirect.

### Renter app (Flutter, `mobile/apps/renter/lib/widgets/coupon_sheet.dart`,
`mobile/packages/rentaxis_core/lib/models/promo_ad.dart`)
- Model gains `redirectUrl` and `sharedFields` (tolerant of their absence — old
  backends).
- Copy code: copy to clipboard as today; then if `redirectUrl` is set → if
  `sharedFields` non-empty show a confirm sheet "Open {business}? {business} will
  receive your: Name, Phone, …" (EN/AR) with Cancel / Continue; Continue (or no shared
  fields) → re-check the URL is https with no userinfo, then launch it with
  `LaunchMode.externalApplication` so a universal link opens the business's app.
  Cancel leaves the code copied and does nothing else. Record the click as the existing
  CTA click does.
- Ships with the next renter app release (not the server deploy). Old app builds ignore
  the new fields and keep plain copy behaviour.

### Web editor
- Coupon section of `AdEditor` (both admin and promoter modes): "After copying, open
  this link (optional)" URL input + parameter rows (name input, source select, value
  input shown only for STATIC; add/remove; max 10) + a live example URL using sample
  values. Admin ad list/approval view shows the redirect URL and parameter names.

## Addendum B — delete that always works (archive when history exists) (Kunal, 2026-10-27)

Problem: delete is refused today — a business with ads, and an ad with view/click
events (`fk_pae_ad` RESTRICT), cannot be deleted. Ruling (Claude's call between the two
options Kunal offered): **Delete always succeeds for the user.** Nothing referenced →
hard delete. Otherwise → **archive** (soft delete), so impression/click history and
stats survive. Archived items are hidden from every list and the renter app; an
"Archived" filter shows them, with Restore (admin only).

### Data (changeset 183 — still unmerged)
- `promo_business.archived_at timestamptz NULL`, `promo_ad.archived_at timestamptz NULL`.
- Make the business-name unique index ignore archived rows: drop `uq_promo_business_name`
  and recreate it with `WHERE archived_at IS NULL` (same columns/expression as today —
  read the original changeset), so a new business can reuse an archived one's name.
  Keep the constant `UQ_PROMO_BUSINESS_NAME` translation working.

### Behaviour
- `DELETE /promotions/ads/{id}` and `DELETE /promoter/ads/{id}` (access-checked): no
  events → hard delete; else `archived_at = now`, `active = false`. Response **200**
  `{ "outcome": "DELETED" | "ARCHIVED" }`.
- `DELETE /promotions/businesses/{id}` and new `DELETE /promoter/businesses/{id}`
  (promoter needs canCreateBusinesses + access): no ads at all → hard delete; else archive
  the business (`archived_at`, `active = false`) and archive every non-archived ad of it
  (ads with no events may be hard-deleted instead — your call, say which). Same response.
- Lists (`GET /promotions/ads`, `/promotions/businesses`, `/promoter/ads`,
  `/promoter/businesses`, `pending-count`, promoters' business lists): archived rows
  excluded by default. Admin lists accept `?archived=true` → archived only.
- `POST /promotions/ads/{id}/restore`, `POST /promotions/businesses/{id}/restore` (admin
  only): `archived_at = null`; stays `active = false` (admin re-activates by editing).
  Restoring an ad whose business is archived → 400 "Restore the business first".
  Restoring a business whose name is now taken by a live business → 400 (the unique
  index message).
- Approve/reject/edit of an archived ad → 400 ("This ad is archived — restore it first").
  Promoter edit/read of an archived ad → 404.
- Feed/offers/events: `archived_at IS NULL` on ad and business (belt and braces on top of
  `active`).
- Stats for archived ads remain queryable (admin `?archived=true` list shows them).

### Web
- Ads and Businesses tabs (admin) and the promoter portal: Delete with confirm text
  "Delete this ad? If renters have already seen it, it is archived instead so its view
  history is kept." (business: "…its ads are archived too."); toast "Deleted" /
  "Archived" from `outcome`.
- Admin tabs: "Show archived" toggle → archived list with Restore; archived rows show an
  Archived pill and no Edit/Approve.
- EN + AR strings, Vitest tests.
