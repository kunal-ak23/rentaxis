# Promoter Developer API (API keys + units feed) — design

Date: 2026-10-29 · Builds on the PROMOTER role (`docs/superpowers/plans/2026-10-27-promoter-role.md`, PRs #442–#447).

## Intent and why

A promoter (e.g. the outside car-wash company) hands renters coupons through our ads. When a renter redeems one, the
existing coupon redirect sends the promoter values from our app (coupon code, renter name/phone/email, property name,
unit number, statics). The promoter's own system must decide whether the redemption is genuine and not a duplicate.
**All of that decision logic lives on the promoter's side.** What they need from us is a trustworthy, machine-readable
list of the units they are allowed to receive coupons from, so they can check "does this redemption come from a real
unit we're promoting to?".

So we expose two read-only, server-to-server endpoints authenticated by a promoter-owned secret key, a Developer
section where the promoter manages those keys, a super-admin kill switch for leaked keys, and developer docs.

## Goals

1. **Count endpoint** — cheap call returning how many properties ("projects") and units the promoter can currently see,
   plus a change fingerprint. Promoter polls it; when it differs from what they hold, they re-sync.
2. **Units endpoint** — paged list of those units.
3. **Developer tab** in the promoter dashboard: create, list, rotate, revoke keys; read the docs.
4. **Super-admin key management**: see every promoter key across all orgs, revoke any immediately (leak response).
5. **Developer docs** reachable from the Developer tab.
6. Two new coupon-redirect param sources, `PROPERTY_ID` and `UNIT_ID`, so redemptions match the units feed exactly.

## Non-goals

- No tenant/renter/lease/rent data in the feed — **units only** (Kunal: tenants keep changing; the promoter only checks
  the coupon came from a valid unit).
- No redemption endpoint, no duplicate detection, no coupon validation on our side.
- No write endpoints for keys-holders. Keys are read-only.
- No webhooks/push of changes (polling the count endpoint is the sync mechanism).
- No mobile (manager app) UI.

## Anti-goals (failure even if it "works")

- A key returns anything outside its promoter's own org and own property grants — a multi-tenant leak is P0.
- A revoked key, or a key whose promoter is deactivated/removed, still answers.
- The plaintext secret is ever stored, logged, or shown again after creation.
- Key auth weakens or interferes with the existing JWT / legacy-header paths in `ApiSecurityFilter`.

## Decided (by Kunal)

- Feed carries units only.
- Scope = what the promoter can already see: the properties in its `promoter_property` grants, in its one org.
- **Both the promoter ("developer") and SUPER_ADMIN can revoke any of the promoter's keys.** The promoter rotates when
  they want; super admin revokes when a leak is detected.
- Key scheme: "whatever is best" — left to me; see below.

## Design

### Key scheme (my call)

A single opaque bearer secret, not a public/private keypair. These are server-to-server reads over HTTPS; signing
requests with a private key adds client complexity (clock skew, canonicalisation) with no real gain for read-only
access, and leak response is the same either way (revoke). Industry norm for this shape (Stripe, GitHub tokens).

- Format: `mfk_live_` + 32 random bytes base62 (≈190 bits). Prefix makes leaked keys greppable / secret-scanner friendly.
- Stored: SHA-256 of the full secret (high entropy, so a slow hash is unnecessary) plus a display prefix
  (first 12 chars, e.g. `mfk_live_a1B`). Lookup by hash, constant-time compare.
- Shown **once** at creation (copy box + warning). Never retrievable again.
- Sent as header `X-Api-Key: <secret>` — deliberately not `Authorization: Bearer`, which `ApiSecurityFilter` treats as
  a JWT and 401s on failure.

### Key lifecycle

- A promoter may hold **at most 2 active keys** (enough for zero-downtime rotation). Each key has a name (label).
- **Create** → returns secret once.
- **Rotate** (convenience) → creates a new key and sets the old one to expire in 24 h, so the promoter can deploy the
  new secret without downtime. Allowed even at the 2-key cap because the old one is on its way out (counts as one
  slot for the cap only after expiry — builder may simplify to "rotate requires ≤1 other active key").
- **Revoke** → immediate and permanent. Records `revoked_at`, `revoked_by` (user id), `revoked_reason` (free text,
  required for super admin, optional for promoter). Promoter sees "Revoked by RentAxis admin — <reason>" on their list.
- Key is rejected if: revoked, past `expires_at`, or the key's user no longer satisfies `role == PROMOTER &&
  status == ACTIVE && user.tenantId == key.tenant_id` (checked on every request). A promoter user belongs to exactly one
  org (`User.tenantId`, see `PromoterAdminService`), so a key is implicitly scoped to that org; create takes no org
  parameter.
- Track `last_used_at` (update at most once per minute per key to avoid write amplification) and show it.
- Org TENANT_ADMIN revocation: not asked for; not in scope. (Deactivating the promoter already kills its keys.)

### Data (changeset 187 — next free number; check the folder, 186 is the highest today)

Table `promoter_api_key`: `id uuid pk`, `tenant_id`, `user_id` (the promoter), `name`, `key_prefix`, `key_hash`
(unique), `created_at`, `created_by`, `expires_at null`, `last_used_at null`, `revoked_at null`, `revoked_by null`,
`revoked_reason null`. Append-only migration; never edit 183/184.

### Partner endpoints

Base path `/api/v1/partner/promoter` (already routed publicly by Caddy's `/api/v1/*` block).

- `GET /units/count` →
  `{ "properties": 3, "units": 412, "fingerprint": "sha256:…", "generatedAt": "…" }`
  `fingerprint` = hash over the sorted `(unitId, unitNumber, propertyId, propertyName)` set, so it changes when a unit
  is added/removed/renamed or a grant changes even if counts stay equal. (My call — better than counts alone or a
  last-modified timestamp, which is hard to get right across deletes and grant removals.)
- `GET /units?page=0&size=100` (size max 500), stable order `propertyName, unitNumber, unitId` →
  `{ "content": [ { "unitId", "unitNumber", "propertyId", "propertyName" } ], "page", "size", "totalElements",
  "totalPages", "fingerprint" }`. Including the fingerprint lets the client detect the set changed mid-pagination and
  restart. Fingerprint computed per request (one id/name projection query, O(units-in-grants) — hundreds to low
  thousands); no cache unless profiling says otherwise (my call).
- Optional filter `?propertyId=` (cheap, useful). Builder may add.
- Which units: every unit row in the granted properties, any `UnitStatus` (VACANT/OCCUPIED/MAINTENANCE) — occupancy
  changes; the promoter validates "real unit", per Kunal. Units and properties are hard-deleted (no soft-delete column
  exists), so "exists" is the only filter. No schema change needed for this.
- `propertyName` is `Property.nameEn` — the same column the redirect's `PROPERTY_NAME` sends today
  (`LeaseRepository.findActiveLeasePlacesForRenterUser`). The fingerprint hashes `nameEn` too.
- Errors: 401 `{ "error": "invalid_api_key" }` for missing/unknown/revoked/expired (one message — don't reveal which);
  429 with `Retry-After` when rate limited.

### Auth plumbing

- New `PartnerApiKeyFilter` (or a branch in `ApiSecurityFilter` — builder's call) for `/api/v1/partner/**` only:
  resolves the key, loads the promoter, sets tenant context to the key's tenant and a principal with the promoter's
  user id and a distinct authority (e.g. `PARTNER_API`). JWT/legacy headers on these paths are ignored — the key is the
  only credential. `ApiSecurityFilter` must skip this prefix so it doesn't 401 key requests.
- `PromoterFenceFilter` must not block these paths; `PromoterFenceSweepIT` must still pass (add the partner handlers to
  its expectations as key-only).
- The reverse also holds: a JWT-signed-in promoter calling `/api/v1/partner/**` without a key gets 401.
- Service reads run inside a transaction with the tenant set (known P0 class: tenant filter is off outside a tx —
  see the maintenance-tickets hotfix #301). Property scope comes from `promoter_property`, never from a request param.
- Rate limits (my calls): (a) per client IP on `/api/v1/partner/**` **before** key lookup — 30 failed-auth
  attempts/min, then 429, to blunt key-guessing and hash-lookup load; IP source as `PublicRateLimitFilter` resolves it;
  (b) per validated key after lookup — 60 req/min, burst 20. Reuse the bucket approach in `PublicRateLimitFilter`.
- Filter order: partner filter runs before `ApiSecurityFilter` and `PromoterFenceFilter`; `SecurityConfig` permits
  the prefix at the HTTP level and the partner filter alone authenticates it. `PARTNER_API` must not satisfy any
  existing role check (no `ROLE_*` authority), and the principal it sets carries the user id + tenant so nothing
  downstream re-reads `X-User-*` headers on these paths. Partner controllers check for `PARTNER_API` explicitly.

### Management endpoints (signed-in)

- Promoter (JWT, role PROMOTER), added to the promoter fence allowlist:
  `GET/POST /api/v1/promoter/api-keys`, `POST /api/v1/promoter/api-keys/{id}/rotate`,
  `POST /api/v1/promoter/api-keys/{id}/revoke`. A promoter only ever sees/acts on its own keys.
- SUPER_ADMIN: `GET /api/admin/promoter-api-keys?q=&status=` (all orgs; shows org, promoter name/email, key name,
  prefix, created, last used, status) and `POST /api/admin/promoter-api-keys/{id}/revoke` (reason required). Builder
  follows whichever admin path prefix the existing superadmin controllers use.
- Audit: key create/rotate/revoke logged (use existing audit mechanism if any; otherwise structured log line).

### Coupon redirect params

Add `PROPERTY_ID` and `UNIT_ID` to the redirect param source enum, resolved exactly like `PROPERTY_NAME`/`UNIT_NUMBER`
in `PromotionRedirectService` (renter's active lease within the ad's targeted properties). Expose them in the ad
editor's param dropdown (web) with labels. Renter app needs no change — the server builds the URL. Docs recommend
matching on `UNIT_ID`.

### Web UI

- Promoter dashboard (`web/src/app/[locale]/dashboard/promoter/page.tsx`): third tab **Developer** next to Ads /
  Businesses. Contents: keys table (name, prefix, created, last used, status, actions Rotate/Revoke), "Create key"
  dialog → one-time secret reveal with copy button and "you won't see this again" warning, and a docs panel/link.
- Superadmin: new page `superadmin/api-keys` (nav entry beside Tenants/Users) — searchable table across orgs, Revoke
  with required reason.
- en + ar strings via `useTranslations()`, RTL-safe (code blocks stay LTR). Table-first, paginated per UI standard.

### Developer docs

A docs page rendered in-app (route under the promoter dashboard, e.g. `/dashboard/promoter/developer/docs`, or inline
in the tab — builder's call), plus the same content as `docs/developer/promoter-units-api.md` in the repo. Covers:
creating/rotating/revoking keys and secure storage; base URL; auth header; both endpoints with curl examples and
response samples; the recommended sync loop (poll count every N minutes → if fingerprint differs, page through units
→ replace local set); matching a redemption using `UNIT_ID`/`PROPERTY_ID` redirect params; errors and rate limits.
No real client names.

### Tests (minimum)

- Tenant isolation: key from org A never returns org B units; promoter X never sees promoter Y's properties in the same
  org; grant removal shrinks count + changes fingerprint.
- Key states: revoked / expired / rotated-old-after-24h / promoter deactivated / role changed → 401.
- Key auth doesn't open signed-in routes and JWT doesn't open partner routes.
- Secret never persisted in plaintext; shown once.
- Promoter can't see/revoke another promoter's key; super admin can revoke any; non-super-admin can't hit admin routes.
- 2-key cap; rate limit 429.
- Fence sweep IT green.

## Left to the builder

Exact class/filter structure, DTO naming, whether rotate shares the cap, docs page placement, the `propertyId` filter,
audit mechanism, test file layout. Follow existing promoter code idioms.
