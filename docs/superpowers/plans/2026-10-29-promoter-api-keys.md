# Promoter Developer API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Promoters create secret API keys in a Developer tab and call two read-only partner endpoints (unit count +
paged units) scoped to their property grants; promoter and super admin can revoke keys; docs explain the integration.

**Architecture:** New `promoter_api_key` table (hash-only storage). A dedicated `PartnerApiKeyFilter` authenticates
`/api/v1/partner/**` with `X-Api-Key`, sets tenant context + a `PARTNER_API` principal, and rate-limits per IP (failed
auth) and per key. Partner controller reads units through `promoter_property` grants inside a transaction. Management
lives under the existing promoter API (already fence-allowlisted) and `/api/admin/promoter-api-keys` (SUPER_ADMIN).
Web adds a Developer tab, a superadmin page, and an in-app docs page.

**Tech Stack:** Java 21, Spring Boot 4, Spring Security, JPA/Hibernate (tenant `@Filter`), Liquibase, bucket4j,
Testcontainers ITs (`AbstractPromoterIT`); Next.js 16 + next-intl + vitest; Flutter (one label).

**Spec:** `docs/superpowers/specs/2026-10-29-promoter-api-keys-design.md` — read it before your task.

## Global Constraints

- Branch `feat/promoter-api-keys` (plain branch, no worktrees). Commit with explicit pathspecs: `git commit -- <paths>`
  (other local untracked files exist; never `git add -A` / `git add .`). Repo is public: no real client names.
- Liquibase: new changeset `187-promoter-api-key.yaml`, appended to `db.changelog-master.yaml`. Never touch 183–186.
- Key format `mfk_live_` + 32 random bytes (SecureRandom) base62-encoded. Store SHA-256 hex of the full secret
  (`key_hash`, unique) and `key_prefix` = first 12 chars. Secret returned only by create/rotate responses.
- Header `X-Api-Key`. Never `Authorization: Bearer` for keys.
- Max 2 active keys per promoter; active = `revoked_at IS NULL AND expires_at IS NULL`. A key with `expires_at` in the
  future still authenticates but doesn't count toward the cap. Rotate sets old `expires_at = now + 24h`; rotating a key
  that already has `expires_at` or is revoked → 409.
- Key authenticates only if: not revoked, (`expires_at` null or future), user `role == PROMOTER`,
  `status == ACTIVE`, `user.tenantId == key.tenantId`.
- 401 body for every key failure: `{"error":"invalid_api_key"}`. 429 body `{"error":"rate_limited"}` + `Retry-After`.
- Rate limits: failed-auth per IP 30/min on `/api/v1/partner/**`; per valid key 60/min with burst 20. Config keys
  `app.partner-api.failed-auth-per-minute`, `app.partner-api.per-key-per-minute`, `app.partner-api.per-key-burst`.
- `last_used_at` updated at most once per 60 s per key.
- Super-admin revoke requires non-blank `reason` (400 otherwise); promoter revoke reason optional.
- Units feed: every unit in the promoter's `promoter_property` grants (any status); `propertyName = Property.nameEn`;
  order `propertyName, unitNumber, unitId`; `size` default 100, max 500 (clamp), `page` ≥ 0.
- Fingerprint: `"sha256:" + hex(SHA-256(join("\n", rows)))` where each row is
  `unitId + "|" + unitNumber + "|" + propertyId + "|" + propertyName` in the feed order, null → empty string.
- i18n: every new web string in `web/messages/en.json` and `ar.json`; code/curl blocks `dir="ltr"`.

## Review Focus

1. **Grant revoked between pages** — promoter's grant removed mid-sync: next page and count must reflect it and the
   fingerprint must change (Task 3 test `grantRemovalShrinksCountAndChangesFingerprint`).
2. **Promoter deactivated / role changed / moved org** — existing keys must 401 on the very next call, with no cache
   (Task 2 test `keyDiesWithItsPromoter`).
3. **Key sent alongside a JWT or X-User-* headers** — key path must ignore them; a JWT alone on partner paths → 401;
   a key on non-partner paths grants nothing (Task 2 tests `jwtAloneIsRejectedOnPartnerPaths`,
   `keyOpensNothingOutsidePartnerPaths`).
4. **Same unit number in two properties** — both rows present, distinct `unitId`, fingerprint stable across calls
   (Task 3 test `duplicateUnitNumbersAcrossPropertiesAreDistinctRows`).
5. **Promoter with zero grants** — count `{properties:0, units:0}`, empty page, fingerprint of empty set, 200 not 404
   (Task 3 test `noGrantsIsAnEmptyFeed`).

---

### Task 1: Key storage and lifecycle service

**Files:**
- Create: `backend/src/main/resources/db/changelog/changesets/187-promoter-api-key.yaml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml` (append include)
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/PromoterApiKey.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/repository/PromoterApiKeyRepository.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/PromoterApiKeyService.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/security/ApiKeySecrets.java`
- Test: `backend/src/test/java/com/datagami/rentaxis/core/security/ApiKeySecretsTest.java`
- Test: `backend/src/test/java/com/datagami/rentaxis/api/PromoterApiKeyServiceIT.java` (extends `AbstractPromoterIT`)

**Interfaces:**
- Produces:
  - Table `promoter_api_key(id uuid pk, tenant_id uuid not null, user_id uuid not null fk users, name varchar(80) not
    null, key_prefix varchar(16) not null, key_hash varchar(64) not null unique, created_at timestamptz not null,
    created_by uuid not null, expires_at timestamptz, last_used_at timestamptz, revoked_at timestamptz, revoked_by uuid,
    revoked_reason varchar(500))`, index on `(user_id)`.
  - `PromoterApiKey extends BaseTenantEntity` with getters/setters for those columns.
  - `ApiKeySecrets`: `static String generate()`, `static String hash(String secret)` (SHA-256 lowercase hex),
    `static String prefix(String secret)` (first 12 chars), `static boolean looksLikeKey(String s)` (starts
    `mfk_live_`, length 40–60, base62 tail).
  - `PromoterApiKeyService`:
    - `record IssuedKey(PromoterApiKey key, String secret)`
    - `List<PromoterApiKey> listForPromoter(UUID userId)` — newest first
    - `IssuedKey create(UUID promoterUserId, String name)` — name trimmed, 1–80 chars else 400; cap exceeded → 409
      `api_key.limit`
    - `IssuedKey rotate(UUID promoterUserId, UUID keyId)` — 404 if not that promoter's key; 409 `api_key.not_active`
    - `PromoterApiKey revokeByPromoter(UUID promoterUserId, UUID keyId, String reason)` — idempotent on already revoked
    - `PromoterApiKey revokeByAdmin(UUID adminUserId, UUID keyId, String reason)` — cross-tenant; blank reason → 400
    - `Optional<AuthenticatedKey> authenticate(String secret)` with
      `record AuthenticatedKey(UUID keyId, UUID tenantId, UUID promoterUserId)`; applies every rule in Global
      Constraints; throttled `last_used_at` touch.
    - `Page<AdminKeyRow> listAll(String q, String status, Pageable p)` — cross-tenant; `status` ∈
      `active|expiring|revoked|expired|null`; `q` matches org name, promoter name/email, key name, prefix
      (case-insensitive). `record AdminKeyRow(UUID id, UUID tenantId, String orgName, UUID promoterUserId,
      String promoterName, String promoterEmail, String name, String keyPrefix, Instant createdAt,
      Instant lastUsedAt, Instant expiresAt, Instant revokedAt, String revokedReason, String status)`.
  - Status derivation (shared, static `PromoterApiKey.statusAt(Instant now)`): revoked → `revoked`; expires_at ≤ now
    → `expired`; expires_at > now → `expiring`; else `active`.
  - Errors use the project's existing exception → status mapping in `GlobalExceptionHandler` (find the 409/400/404
    exception types the promoter services already throw and reuse them).

- [ ] **Step 1: Write `ApiKeySecretsTest`**: `generate()` starts `mfk_live_`, two calls differ, `looksLikeKey` true;
  `hash("abc")` = `"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"`; `prefix` length 12;
  `looksLikeKey("Bearer x")` false.
- [ ] **Step 2: Run** `cd backend && ./gradlew test --tests '*ApiKeySecretsTest'` → FAIL (class missing).
- [ ] **Step 3: Implement `ApiKeySecrets`.** → test PASS.
- [ ] **Step 4: Write `PromoterApiKeyServiceIT`** with tests:
  `createReturnsSecretOnceAndStoresOnlyHash` (DB row `key_hash` = `hash(secret)`, no column contains the secret),
  `thirdActiveKeyIs409`, `rotateIssuesNewAndExpiresOldIn24h` (old `expires_at` within 24h±1min, old still
  authenticates), `rotateAtCapIsAllowed`, `rotatingExpiringKeyIs409`, `revokedKeyDoesNotAuthenticate`,
  `expiredKeyDoesNotAuthenticate` (set `expires_at` past via jdbc), `promoterCannotTouchAnotherPromotersKey` (404),
  `adminRevokeNeedsReason`, `adminRevokeRecordsWhoAndWhy`, `listAllSpansOrgsAndFilters` (two orgs, `q` + `status`),
  `lastUsedTouchedAtMostOncePerMinute`.
- [ ] **Step 5: Run** `./gradlew test --tests '*PromoterApiKeyServiceIT'` → FAIL.
- [ ] **Step 6: Implement changeset, entity, repository, service.** Cross-tenant reads (`authenticate`, `listAll`,
  `revokeByAdmin`) must work with no tenant context set; tenant-scoped ones run in `@Transactional` with the caller's
  tenant (see memory: tenant filter is off outside a transaction — reads must be inside one).
- [ ] **Step 7: Run** both tests → PASS. Run `./gradlew test --tests '*Promoter*'` → PASS.
- [ ] **Step 8: Commit** `feat(promoter): API key storage and lifecycle` with pathspecs.

### Task 2: Partner key authentication filter + rate limits

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/security/PartnerApiKeyFilter.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/config/SecurityConfig.java` (permitAll `/api/v1/partner/**`;
  register filter before `ApiSecurityFilter`)
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/security/ApiSecurityFilter.java` (skip
  `/api/v1/partner/`)
- Modify: `backend/src/test/java/com/datagami/rentaxis/api/PromoterFenceSweepIT.java` (partner paths: expect 401
  without a key, not 403)
- Modify: `backend/src/main/resources/application*.yml` (the three `app.partner-api.*` keys with defaults)
- Create (temporary probe for this task's tests, kept): `backend/src/main/java/com/datagami/rentaxis/api/PartnerPingController.java`
  — `GET /api/v1/partner/promoter/ping` → `{"promoterUserId", "tenantId"}`. Kept as a documented "check your key"
  endpoint.
- Test: `backend/src/test/java/com/datagami/rentaxis/api/PartnerApiKeyAuthIT.java` (extends `AbstractPromoterIT`)

**Interfaces:**
- Consumes: `PromoterApiKeyService.authenticate(String)`, `ApiKeySecrets.looksLikeKey`.
- Produces:
  - `PartnerApiKeyFilter.PARTNER_AUTHORITY = "PARTNER_API"` (no `ROLE_` prefix, so no `hasRole` matches it).
  - `PartnerPrincipal(UUID keyId, UUID tenantId, UUID promoterUserId)` record set as the Authentication principal;
    controllers read it via `@AuthenticationPrincipal PartnerPrincipal p`.
  - On success: `TenantContextHolder` set to key tenant for the request, cleared in `finally`.
  - Filter only acts on paths starting `/api/v1/partner/`; for others it passes through untouched. On partner paths
    it never calls the next auth filters' identity logic (JWT/X-User-* ignored); `ApiSecurityFilter` skips the prefix.
  - Partner controllers additionally guard with `@PreAuthorize("hasAuthority('PARTNER_API')")`.
  - Failed-auth IP bucket reuses `PublicRateLimitFilter`'s client-IP resolution (extract a shared static helper if it
    is private) and bucket4j; per-key bucket keyed by `keyId`.

- [ ] **Step 1: Write `PartnerApiKeyAuthIT`**: `validKeyReachesPing` (200, body ids match),
  `missingKeyIs401`, `unknownKeyIs401SameBody`, `revokedKeyIs401`, `keyDiesWithItsPromoter` (flip user status to
  INACTIVE via jdbc → 401; restore; set role to TENANT_USER → 401; change tenant_id → 401),
  `jwtAloneIsRejectedOnPartnerPaths` (promoter bearer token, no key → 401),
  `keyOpensNothingOutsidePartnerPaths` (key on `GET /api/v1/promoter/me` and `/api/v1/properties` → 401/403, never
  200), `spoofedHeadersAlongsideKeyAreIgnored` (valid key of org A + `X-Tenant-Id`/`X-User-Id` of org B → ping returns
  org A), `failedAttemptsAreRateLimitedPerIp` (set `app.partner-api.failed-auth-per-minute=3` via
  `@TestPropertySource` in a nested class or separate IT; 4th bad call → 429 with `Retry-After`),
  `perKeyRateLimit` (per-key 5/min, burst 5 → 6th → 429).
- [ ] **Step 2: Run** `./gradlew test --tests '*PartnerApiKeyAuthIT'` → FAIL.
- [ ] **Step 3: Implement filter, ping controller, config, `ApiSecurityFilter` skip, SecurityConfig wiring.**
- [ ] **Step 4: Update `PromoterFenceSweepIT`** so routes under `/api/v1/partner/` are asserted 401 for a promoter's
  JWT/headers (no key) instead of 403, and still counted in `checked`.
- [ ] **Step 5: Run** `./gradlew test --tests '*PartnerApiKeyAuthIT' --tests '*PromoterFenceSweepIT' --tests
  '*CallerIdentity*'` → PASS.
- [ ] **Step 6: Commit** `feat(partner-api): X-Api-Key authentication with rate limits`.

### Task 3: Partner units endpoints

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/api/PartnerPromoterController.java`
  (`@RequestMapping("/api/v1/partner/promoter")`; move `ping` here and delete `PartnerPingController`)
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/PartnerUnitFeedService.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/PartnerUnitDtos.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/repository/UnitRepository.java` (projection query)
- Test: `backend/src/test/java/com/datagami/rentaxis/api/PartnerUnitFeedIT.java`

**Interfaces:**
- Consumes: `PartnerPrincipal`.
- Produces:
  - `GET /units/count` → `UnitCount(int properties, long units, String fingerprint, Instant generatedAt)`.
    `properties` = number of granted properties (including ones with zero units).
  - `GET /units?page&size&propertyId` → `UnitPage(List<PartnerUnit> content, int page, int size, long totalElements,
    int totalPages, String fingerprint)`; `PartnerUnit(UUID unitId, String unitNumber, UUID propertyId,
    String propertyName)`. `propertyId` not in grants → 200 empty page (no existence leak). Fingerprint is always over
    the full grant set (ignores `propertyId` filter).
  - `UnitRepository.findPartnerFeedRows(UUID tenantId, UUID promoterUserId)` → rows `(unitId, unitNumber, propertyId,
    propertyNameEn)` joined through `promoter_property`, filtered by tenant AND user, ordered per Global Constraints.
    Paging done in memory over this list (sizes are hundreds–low thousands; keeps fingerprint and page consistent).
  - Service method runs `@Transactional(readOnly = true)` with tenant from the principal.

- [ ] **Step 1: Write `PartnerUnitFeedIT`**: `countMatchesGrants` (2 granted props with 3+2 units, 1 ungranted with 4
  → `{properties:2, units:5}`), `pagesInStableOrder` (size=2 → 3 pages, concatenation equals full ordered list),
  `sizeIsClampedTo500`, `otherOrgsUnitsNeverAppear` (org B promoter key sees none of org A, and vice versa),
  `otherPromoterInSameOrgSeesOnlyOwnGrants`, `grantRemovalShrinksCountAndChangesFingerprint`,
  `renamingPropertyChangesFingerprint`, `duplicateUnitNumbersAcrossPropertiesAreDistinctRows`,
  `noGrantsIsAnEmptyFeed` (fingerprint = `"sha256:" + sha256("")` =
  `"sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"`),
  `foreignPropertyIdFilterIsEmpty`, `fingerprintSameOnCountAndUnits`, `responseHasNoTenantOrLeaseFields` (JSON keys of
  a unit are exactly the four).
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** repository query, service (fingerprint algorithm from Global Constraints), DTOs,
  controller.
- [ ] **Step 4: Run** `./gradlew test --tests '*PartnerUnitFeedIT' --tests '*PartnerApiKeyAuthIT' --tests
  '*PromoterFenceSweepIT'` → PASS.
- [ ] **Step 5: Commit** `feat(partner-api): unit count and paged units feed`.

### Task 4: Key management endpoints (promoter + super admin)

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/PromoterController.java` (add api-keys routes)
- Create: `backend/src/main/java/com/datagami/rentaxis/api/PromoterApiKeyAdminController.java`
  (`@RequestMapping("/api/admin/promoter-api-keys")`, `@PreAuthorize("hasRole('SUPER_ADMIN')")`)
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/PromoterApiKeyDtos.java`
- Test: `backend/src/test/java/com/datagami/rentaxis/api/PromoterApiKeyManagementIT.java`

**Interfaces:**
- Consumes: `PromoterApiKeyService` (Task 1). Caller id from the existing promoter controller's identity pattern.
- Produces (JSON):
  - `ApiKeyDTO(UUID id, String name, String keyPrefix, Instant createdAt, Instant lastUsedAt, Instant expiresAt,
    Instant revokedAt, String revokedReason, boolean revokedByAdmin, String status)`
  - `IssuedKeyDTO(ApiKeyDTO key, String secret)`
  - `GET /api/v1/promoter/api-keys` → `ApiKeyDTO[]`
  - `POST /api/v1/promoter/api-keys` body `{name}` → 201 `IssuedKeyDTO`
  - `POST /api/v1/promoter/api-keys/{id}/rotate` → 201 `IssuedKeyDTO`
  - `POST /api/v1/promoter/api-keys/{id}/revoke` body `{reason?}` → 200 `ApiKeyDTO`
  - `GET /api/admin/promoter-api-keys?q&status&page&size` → Spring page of `AdminKeyRow`
  - `POST /api/admin/promoter-api-keys/{id}/revoke` body `{reason}` → 200 `AdminKeyRow`
  - `revokedByAdmin` = `revoked_by != promoter user id`.
  - Responses set `Cache-Control: no-store` on create/rotate.
  - Log (INFO, structured) on create/rotate/revoke: keyId, prefix, tenantId, actor id — never the secret.

- [ ] **Step 1: Write `PromoterApiKeyManagementIT`**: `promoterCreatesListsRotatesRevokes` (bearer + legacy headers),
  `listNeverContainsSecret`, `createResponseIsNoStore`, `nonPromoterCannotUsePromoterRoutes` (TENANT_ADMIN → 403),
  `superAdminListsAcrossOrgsAndRevokes`, `superAdminRevokeShowsAsRevokedByAdminToPromoter`,
  `tenantAdminCannotHitAdminRoutes` (403), `promoterCannotHitAdminRoutes` (403 from fence).
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew test --tests '*PromoterApiKey*' --tests '*PromoterFenceSweepIT' --tests
  '*PromoterApiIT'` → PASS.
- [ ] **Step 5: Commit** `feat(promoter): API key management endpoints for promoters and super admins`.

### Task 5: PROPERTY_ID / UNIT_ID coupon redirect params

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/PromoRedirectSource.java` (add
  `PROPERTY_ID`, `UNIT_ID`; both `isRenterData() == true`)
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/PromotionRedirectService.java` (`Place` gains
  `unitId`; resolve new sources)
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/repository/LeaseRepository.java`
  (`findActiveLeasePlacesForRenterUser` also selects `l.unit.id`; update all callers)
- Modify: `web/src/types/promotion.ts`, `web/src/app/[locale]/dashboard/promotions/_components/RedirectLinkFields.tsx`
  (`RENTER_DATA_SOURCES`, example values `PROPERTY_ID: "8f2c…-example"`-style UUID, `UNIT_ID` likewise), en/ar
  labels ("Property ID"/"Unit ID")
- Modify: `mobile/apps/renter/lib/widgets/coupon_redirect_sheet.dart` (`PROPERTY_ID` → same label as `PROPERTY_NAME`;
  `UNIT_ID` → same as `UNIT_NUMBER`) + `mobile/apps/renter/test/coupon_redirect_test.dart`
- Test: extend the existing redirect tests (find with `grep -rl PromotionRedirect backend/src/test`) and
  `web/src/app/[locale]/dashboard/promotions/__tests__/AdEditor.redirect.test.tsx`

**Interfaces:**
- Produces: redirect URL param values `PROPERTY_ID` = property UUID string, `UNIT_ID` = unit UUID string, resolved from
  the same `Place` as `PROPERTY_NAME`/`UNIT_NUMBER`; `sharedFields` includes them.

- [ ] **Step 1: Write failing tests**: backend — an ad with params `pid=PROPERTY_ID, uid=UNIT_ID` produces a URL whose
  `pid`/`uid` equal the renter's lease property/unit ids and `sharedFields` contains both; web — editor offers both
  sources and flags them as renter data; mobile — `field('UNIT_ID')` = `'Unit number'` / Arabic `'رقم الوحدة'`.
- [ ] **Step 2: Run** backend test, `cd web && npx vitest run src/app/\[locale\]/dashboard/promotions`, `cd
  mobile/apps/renter && flutter test test/coupon_redirect_test.dart` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** same three + `cd web && npx vitest run src/lib/__tests__/promoter-i18n.test.ts` → PASS.
- [ ] **Step 5: Commit** `feat(promotions): PROPERTY_ID and UNIT_ID coupon redirect params`.

### Task 6: Web — promoter Developer tab

**Files:**
- Modify: `web/src/app/[locale]/dashboard/promoter/page.tsx` (`type Tab = "ads" | "businesses" | "developer"`)
- Create: `web/src/app/[locale]/dashboard/promoter/_components/PromoterDeveloperTab.tsx`
- Modify: `web/src/lib/api/promoter.ts` (`fetchPromoterApiKeys()`, `createPromoterApiKey(name)`,
  `rotatePromoterApiKey(id)`, `revokePromoterApiKey(id, reason?)`), `web/src/types/promotion.ts` (`ApiKeyDTO`,
  `IssuedKeyDTO` matching Task 4)
- Modify: `web/messages/en.json`, `web/messages/ar.json` (keys under `Promoter`)
- Test: `web/src/app/[locale]/dashboard/promoter/__tests__/PromoterDeveloperTab.test.tsx`

**Interfaces:**
- Consumes: Task 4 promoter routes via `/api/proxy/v1/promoter/api-keys`.
- Produces: tab UI — table (name, prefix `mfk_live_a1B…`, created, last used or "Never", status badge, actions Rotate
  / Revoke on active keys); "Create key" disabled with hint at 2 active; create/rotate open a one-time reveal dialog
  (monospace, Copy button, "This is the only time you'll see this key" warning, must click "I've saved it" to close);
  revoke confirm dialog with optional reason; revoked-by-admin rows show "Revoked by RentAxis admin — {reason}";
  link "Read the developer docs" → `/dashboard/promoter/developer` (Task 8). Follow `PromoterBusinessesTab.tsx`
  idioms (Toast, ApiError).

- [ ] **Step 1: Write tests** (vitest + testing-library, fetch mocked): renders keys; create shows secret once and it
  is gone after closing; Create disabled at 2 active keys; rotate calls endpoint and shows new secret; revoke sends
  reason; admin-revoked row shows reason; Developer tab button exists on page.
- [ ] **Step 2: Run** `cd web && npx vitest run src/app/\[locale\]/dashboard/promoter` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** same + `npx vitest run src/lib/__tests__/promoter-i18n.test.ts` + `npx tsc --noEmit` → PASS.
- [ ] **Step 5: Commit** `feat(web): promoter Developer tab for API keys`.

### Task 7: Web — superadmin API keys page

**Files:**
- Create: `web/src/app/[locale]/superadmin/api-keys/page.tsx`
- Create: `web/src/components/superadmin/PromoterApiKeysManager.tsx`
- Modify: `web/src/lib/nav/navModel.ts` (add `pi("apiKeys", "/superadmin/api-keys", N("apiKeys"),
  "sidebar-api-keys")` after users) and any nav key catalogs that `navModel` requires (`help.ts`, `actionCatalog.ts`
  if they enumerate superadmin routes — follow how `/superadmin/users` is registered)
- Modify: `web/messages/en.json`, `ar.json`
- Test: `web/src/components/superadmin/__tests__/PromoterApiKeysManager.test.tsx`

**Interfaces:**
- Consumes: `/api/proxy/admin/promoter-api-keys` (Task 4).
- Produces: paginated table (org, promoter name + email, key name, prefix, created, last used, status), search box
  (debounced `q`), status filter, Revoke action with required reason dialog (submit disabled until non-blank).

- [ ] **Step 1: Write tests**: lists rows; search sends `q`; revoke requires reason; revoke success updates row status.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** tests + `npx tsc --noEmit` + existing nav tests
  (`npx vitest run src/lib`) → PASS.
- [ ] **Step 5: Commit** `feat(web): superadmin page to review and revoke promoter API keys`.

### Task 8: Developer docs

**Files:**
- Create: `docs/developer/promoter-units-api.md`
- Create: `web/src/app/[locale]/dashboard/promoter/developer/page.tsx` (+ `_content` component if useful)
- Modify: `web/messages/en.json`, `ar.json` (prose under `PromoterDocs`; code samples are not translated)
- Test: `web/src/app/[locale]/dashboard/promoter/__tests__/DeveloperDocs.test.tsx`

**Interfaces:**
- Consumes: final endpoint shapes from Tasks 2–5 (read the controllers/DTOs, not this plan, for exact JSON).
- Produces: docs covering, in order: overview (what the API is for; units only, no tenant data); getting a key
  (Developer tab, shown once, store in a secret manager, max 2, rotation with 24 h overlap, revocation incl. by
  RentAxis); base URL `https://<your RentAxis host>/api/v1/partner/promoter` (page renders the real origin via
  `window.location.origin`); auth header; `GET /ping`, `GET /units/count`, `GET /units` with curl + JSON samples
  (fake UUIDs); recommended sync loop (poll count every 15 min → fingerprint differs → page all units with size=500 →
  if any page's fingerprint differs from the first, restart → replace local set atomically); validating a coupon
  redemption using `UNIT_ID`/`PROPERTY_ID` redirect params (configure them on the ad; match `UNIT_ID` against the
  synced set; duplicate/fraud logic is the promoter's); errors (401 `invalid_api_key`, 429 `rate_limited` +
  `Retry-After`, limits 60/min burst 20) ; security notes (server-side only, never in mobile apps or browsers, rotate
  on staff change). The in-app page is reachable only to PROMOTER (it sits under the fenced promoter dashboard) and
  is linked from the Developer tab.

- [ ] **Step 1: Write test**: page renders headings for each section and the curl samples contain `X-Api-Key` and the
  three paths; en/ar `PromoterDocs` key sets equal.
- [ ] **Step 2: Run** → FAIL. **Step 3: Write docs + page.** **Step 4: Run** test + `npx tsc --noEmit` → PASS.
- [ ] **Step 5: Commit** `docs: promoter partner API developer guide`.

### Final verification (controller, after all tasks)

- `cd backend && ./gradlew test` (full suite incl. `PromoterFenceSweepIT`), and `./gradlew test -PincludeTags=golden`.
- `cd web && npx vitest run && npx tsc --noEmit && npm run lint`.
- `cd mobile/apps/renter && flutter test`.
- Manual local smoke: bootRun + web dev, sign in as a seeded promoter, create key, curl `/units/count` and `/units`,
  revoke as super admin, curl again → 401.
