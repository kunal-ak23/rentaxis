# Business Directory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the renter-home ad carousel with admin-managed category tiles that open a business list, then a business page with details, a website button and that business's offers.

**Architecture:** Two new tenant tables (categories, category↔business links) plus three new columns on `promo_businesses`. Admin CRUD lives under the existing `/api/v1/promotions` admin controller family. The renter reads come from new `RENTER` endpoints next to `/feed` and `/offers`. Web gets a Directory tab on `/dashboard/promotions`. The Flutter renter app swaps `HomeAdsStrip` for a tile row and adds two screens that reuse `AdCard` and the coupon sheets.

**Tech Stack:** Spring Boot 4 / JPA / Liquibase / PostgreSQL, Next.js 16 + vitest, Flutter (Riverpod, GoRouter).

**Spec:** `docs/superpowers/specs/2026-11-26-business-directory-design.md`. Read it first. Its "Calls settled" section is binding.

## Global Constraints

- Branch: `feat/business-directory` (already holds the spec commit). Plain branch, no worktrees. Commit with explicit pathspecs (`git commit -- <paths>`). The repo is public and the tree has many untracked files.
- Another session may be editing logo assets in this checkout. Touch none of `*/assets/logo*`, `web/public/miftah-*`, launcher icons.
- Liquibase changeset `195-promo-directory.yaml` (confirm 194 is still the highest). Append-only.
- Every new query is tenant-scoped and runs inside `@Transactional` (P0 class: tenant filter is off outside a transaction).
- Names: category `name_en` required ≤60, `name_ar` ≤60, `icon_url` ≤512. Business `description_en`/`description_ar` ≤500 and `website_url` ≤512, `https` only, host in the business's `allowedDomains`.
- Lists sort by `sort_order` then `created_at` ascending (categories), and by `created_at` ascending (businesses).
- Admin roles: `SUPER_ADMIN`, `TENANT_ADMIN` (same as `PromotionAdminController`). Renter endpoints: `hasRole('RENTER')`.
- Keep backend `/promotions/feed` and `/promotions/offers` working unchanged for old app versions.
- EN + AR for every new string, on web (`web/messages/{en,ar}.json`, `Promotions` namespace) and in the app (`context.isAr` `_L` helpers). Check RTL.
- Renter widget tests pump `MiftahTheme.light`.

## Review Focus

1. A renter of org A requests org B's category id or business id. Expect 404, never data. Pinned in Task 3.
2. A category whose only business gets archived or deactivated. Expect it to vanish from `/directory` and its list to be empty. Pinned in Task 3.
3. The website host is not in `allowedDomains`, or the URL is `http://`, `javascript:` or contains userinfo. Expect a 400 on save, and the app re-checks before launching. Pinned in Tasks 1 and 7.
4. A promoter sends `websiteUrl`/`descriptionEn` on their business. Expect the stored values to stay unchanged. Pinned in Task 1.
5. The directory call fails or returns `[]` on home. Expect the row to be absent and the rest of home to render normally, with no error card. Pinned in Task 6.

---

### Task 1: Schema, entities and business fields

**Files:**
- Create: `backend/src/main/resources/db/changelog/changesets/195-promo-directory.yaml` (+ include in master changelog)
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/PromoDirectoryCategory.java`, `.../entity/PromoDirectoryLink.java`
- Create: `.../domain/repository/PromoDirectoryCategoryRepository.java`, `.../repository/PromoDirectoryLinkRepository.java`
- Modify: `.../entity/PromoBusiness.java`, `.../api/dto/PromoBusinessRequest.java`, `.../api/dto/PromoBusinessDTO.java`, `.../api/dto/PromoAdDtos.java` (`business(...)` mapper), `.../core/service/PromotionService.java` (`applyBusiness`), `.../core/service/PromoterService.java` (`updateBusiness`, `createBusiness`)
- Test: `backend/src/test/java/com/datagami/rentaxis/api/PromotionServiceTest.java`, `.../api/PromoterReviewFixesIT.java` (or a new `PromoterDirectoryFieldsIT.java` extending `AbstractPromoterIT`)

**Interfaces:**
- Produces: tables `promo_directory_categories(id, tenant_id, name_en, name_ar, icon_url, sort_order int default 0, active bool default true, archived_at, created_at, updated_at)` and `promo_directory_category_businesses(id, tenant_id, category_id FK cascade, business_id FK cascade, unique(category_id, business_id))`. Columns `promo_businesses.description_en`, `description_ar` varchar(500) and `website_url` varchar(512).
- `PromoDirectoryCategory extends BaseTenantEntity`. `PromoDirectoryLink extends BaseTenantEntity` with `UUID categoryId, UUID businessId`.
- `PromoBusinessRequest` gains `@Size(max=500) String descriptionEn, descriptionAr, @Size(max=512) String websiteUrl`. `PromoBusinessDTO` gains the same three.

- [ ] **Step 1: Failing tests.** `PromotionServiceTest`: `createBusiness_rejectsWebsiteOutsideAllowedDomains` (domains `["quickwash.ae"]`, website `https://evil.example` → `BusinessRuleViolationException`); `rejectsHttpWebsite` (`http://quickwash.ae`); `acceptsWebsiteOnAllowedHost` (`https://www.quickwash.ae/book` with domains `["quickwash.ae"]` → saved, if `isAllowed` treats subdomains so; otherwise use `https://quickwash.ae/book`); `blankWebsiteStoresNull`. Promoter IT: `promoterUpdate_keepsDescriptionAndWebsite` (admin sets both, promoter PUTs different values → GET shows the admin's values); `promoterCreate_ignoresDescriptionAndWebsite` (stored null).
- [ ] **Step 2: Run, expect FAIL.** `cd backend && ./gradlew test --tests '*PromotionServiceTest' --tests '*PromoterDirectoryFieldsIT'`
- [ ] **Step 3: Implement.** Changeset, entities, repos, and new fields mapped through `applyBusiness`. Validate the website with `PromotionUrlValidator.isAllowed(url, allowedDomainsRaw)` against the domains in the *same* request. `PromoterService.updateBusiness` copies the stored description/website into the request before delegating. `createBusiness` nulls them.
- [ ] **Step 4: Run, expect PASS** (same command, plus `--tests '*PromotionArchiveIT' --tests '*PromotionAdminControllerTest'` for regressions).
- [ ] **Step 5: Commit** `feat(promotions): business description and website, directory tables`.

### Task 2: Admin category CRUD API

**Files:**
- Create: `.../core/service/PromoDirectoryService.java`, `.../api/PromoDirectoryAdminController.java`, `.../api/dto/PromoDirectoryDtos.java`
- Test: `backend/src/test/java/com/datagami/rentaxis/api/PromoDirectoryAdminIT.java`

**Interfaces:**
- Consumes: Task 1 entities and repos.
- Produces REST under `/api/v1/promotions/directory-categories`, `@PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")`:
  - `GET ?archived=false&page&size` → `Page<CategoryAdminDTO>`
  - `POST` / `PUT /{id}` body `CategoryRequest(@NotBlank @Size(max=60) nameEn, @Size(max=60) nameAr, @Size(max=512) iconUrl, Integer sortOrder, Boolean active, List<UUID> businessIds)` → `CategoryAdminDTO`
  - `DELETE /{id}` → archives (sets `archived_at`, `active=false`). `POST /{id}/restore`.
  - `CategoryAdminDTO(UUID id, String nameEn, String nameAr, String iconUrl, int sortOrder, boolean active, List<UUID> businessIds, int businessCount, Instant createdAt, Instant updatedAt)`
- `PUT` replaces the link set. Each `businessId` must belong to the caller's tenant, else 400 `UNKNOWN_BUSINESS`.

- [ ] **Step 1: Failing IT.** `create_list_update_archive_restore` round trip; `businessIdsFromAnotherTenant_rejected400`; `otherTenantsCategory_404onPutDeleteRestore`; `renterAndPropertyManager_forbidden403`; `iconUrl_mustBeOwnUpload` (mirror the existing business `logoUrl` rule in `PromotionAdminController`).
- [ ] **Step 2: Run, expect FAIL.** `./gradlew test --tests '*PromoDirectoryAdminIT'`
- [ ] **Step 3: Implement** the service (`@Transactional`, tenant from `requireTenant()` like the sibling controllers) and the controller.
- [ ] **Step 4: Run, expect PASS**, plus `--tests '*PromoterFenceSweepIT'` (the new paths sit under `/api/v1/promotions` and must stay closed to promoters with no fence edit).
- [ ] **Step 5: Commit** `feat(promotions): admin API for directory categories`.

### Task 3: Renter directory API

**Files:**
- Modify: `.../api/PromotionFeedController.java`, `.../core/service/PromotionFeedService.java` (`offers` gains `UUID businessId`), `.../core/service/PromoDirectoryService.java`
- Test: `backend/src/test/java/com/datagami/rentaxis/api/PromoDirectoryRenterIT.java`, `PromotionFeedServiceTest.java`

**Interfaces:**
- Produces (renter, same controller):
  - `GET /api/v1/promotions/directory` → `List<DirectoryCategoryDTO(UUID id, String nameEn, String nameAr, String iconUrl, int businessCount)>`. Only active, non-archived categories with ≥1 active, non-archived business.
  - `GET /api/v1/promotions/directory/{categoryId}` → `DirectoryCategoryDetailDTO(DirectoryCategoryDTO category, List<DirectoryBusinessDTO> businesses)`. 404 if the category is missing, of another tenant, archived or inactive.
  - `GET /api/v1/promotions/businesses/{id}` → `DirectoryBusinessDTO(UUID id, String nameEn, String nameAr, String logoUrl, String descriptionEn, String descriptionAr, String websiteUrl, String phoneE164, String whatsappE164)`. 404 if the business is missing, of another tenant, archived, inactive, or **in no active category**.
  - `GET /api/v1/promotions/offers?businessId=` filters the existing eligible set (all placements) by business. The `category` param stays.
- Icon and logo URLs go through the same `images::forDto` resolver the feed uses.

- [ ] **Step 1: Failing IT.** `directory_hidesEmptyAndArchivedCategories`; `archivingOnlyBusiness_hidesCategory`; `otherTenantCategoryAndBusiness_404`; `businessInNoActiveCategory_404`; `businessListedRegardlessOfAds` (a business with zero ads appears); `offersByBusiness_includesOffersOnlyPlacement_excludesOtherBusiness`; `managerRole_forbidden403`.
- [ ] **Step 2: Run, expect FAIL.** `./gradlew test --tests '*PromoDirectoryRenterIT' --tests '*PromotionFeedServiceTest'`
- [ ] **Step 3: Implement.** Read-only `@Transactional` service methods. One query per endpoint, with no N+1 over businesses.
- [ ] **Step 4: Run, expect PASS**, plus `--tests '*PromotionFeedController*' --tests '*PromoterFeedIT'`.
- [ ] **Step 5: Commit** `feat(promotions): renter directory endpoints`.

### Task 4: Web admin — business fields and Directory tab

**Files:**
- Modify: `web/src/types/promotion.ts`, `web/src/lib/api/promotions.ts`, `web/src/app/[locale]/dashboard/promotions/page.tsx` (add the `"directory"` tab), `.../_components/BusinessEditor.tsx`, `web/messages/en.json`, `web/messages/ar.json`
- Create: `.../_components/DirectoryTab.tsx`, `.../_components/CategoryEditor.tsx`
- Test: `.../promotions/__tests__/DirectoryTab.test.tsx`, `.../__tests__/CategoryEditor.test.tsx`, extend `BusinessEditor.test.tsx`
- Also: `web/src/app/[locale]/dashboard/promoter/_components/PromoterBusinessesTab.tsx`. Make sure it does not render the new fields (it may share `BusinessEditor`; gate them with a prop such as `showDirectoryFields`).

**Interfaces:**
- Consumes: the Task 2 REST shapes and Task 1 business fields.
- `promotions.ts` adds `listCategories(archived, page, size)`, `createCategory(req)`, `updateCategory(id, req)`, `archiveCategory(id)`, `restoreCategory(id)`, typed as `DirectoryCategory` and `DirectoryCategoryRequest` in `promotion.ts`.

- [ ] **Step 1: Failing tests.** DirectoryTab: renders a paginated table (icon, EN name, business count, active), an empty state, and an archived toggle. CategoryEditor: requires the EN name; the business multi-select sends `businessIds`; the icon goes through `PromoImageField`; the server's 400 `UNKNOWN_BUSINESS` shows inline. BusinessEditor: sends `descriptionEn/Ar` and `websiteUrl`; the hint says the site must be on an allowed domain; with `showDirectoryFields={false}` the fields are absent.
- [ ] **Step 2: Run, expect FAIL.** `cd web && npx vitest run src/app/\[locale\]/dashboard/promotions`
- [ ] **Step 3: Implement.** Table-first with pagination, matching `BusinessesTab`. Add the Arabic strings.
- [ ] **Step 4: Run, expect PASS**, plus `npx vitest run src/app/\[locale\]/dashboard/promoter` and `npx tsc --noEmit` and `npm run lint`. Also run the existing translation-catalogue test (`npx vitest run src/lib/__tests__`) so EN/AR keys match.
- [ ] **Step 5: Commit** `feat(web): directory categories tab and business website/description`.

### Task 5: Flutter core — models and API

**Files:**
- Create: `mobile/packages/rentaxis_core/lib/models/promo_directory.dart`
- Modify: `.../lib/api/services/promotion_service.dart`, `.../lib/rentaxis_core.dart` (export)
- Test: `mobile/packages/rentaxis_core/test/promo_directory_test.dart`

**Interfaces:**
- `DirectoryCategory { id, nameEn, nameAr, iconUrl, businessCount; String name(bool isAr); static List<DirectoryCategory> listFromJson(List) }`
- `DirectoryBusiness { id, nameEn, nameAr, logoUrl, descriptionEn, descriptionAr, websiteUrl, phoneE164, whatsappE164; name(isAr); description(isAr) }`
- `PromotionApiService.directory() → Future<List<DirectoryCategory>>`, `directoryCategory(String id) → Future<({DirectoryCategory category, List<DirectoryBusiness> businesses})>`, `business(String id) → Future<DirectoryBusiness>`, and `offers({String? category, String? businessId})`.
- These are typed models, the same documented exception to the raw-Map rule as `PromoAd`: bilingual fallback plus `resolveMediaUrl`. Reuse `PromoAd`'s `_pick` rule (blank counts as absent) by moving it to a shared `pickLang` helper in that file or a util.

- [ ] **Step 1: Failing tests.** Parses full rows; AR falls back to EN when blank and the other way round; relative icon and logo URLs resolve; junk rows drop out of `listFromJson`.
- [ ] **Step 2: Run, expect FAIL.** `cd mobile/packages/rentaxis_core && flutter test test/promo_directory_test.dart`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS** (whole package: `flutter test`).
- [ ] **Step 5: Commit** `feat(mobile-core): directory models and API`.

### Task 6: Renter home — tile row replaces the ad strip

**Files:**
- Create: `mobile/apps/renter/lib/widgets/home_directory_row.dart`
- Modify: `mobile/apps/renter/lib/screens/home_screen.dart` (swap `HomeAdsStrip()` at line ~74; extract the `_QuickAction` tile look into a shared widget if the row needs it), `mobile/apps/renter/lib/providers/promotion_provider.dart` (add `directoryProvider`; remove `homePromoFeedProvider`)
- Delete: `lib/widgets/home_ads_strip.dart`, `lib/widgets/ads_carousel.dart`, and their tests (`test/home_ads_strip_test.dart`, any carousel test)
- Test: `mobile/apps/renter/test/home_directory_row_test.dart`

**Interfaces:**
- Consumes: `PromotionApiService.directory()`.
- `directoryProvider = FutureProvider.autoDispose<List<DirectoryCategory>>`.
- The tile pushes `/directory/:categoryId`.

- [ ] **Step 1: Failing tests.** Two categories → two tiles with names (EN, and AR when the locale is AR). `[]` → `SizedBox.shrink` and no gap left. An error → nothing rendered and no `ErrorState`. Six categories → two rows of tiles, four per row. A tap pushes `/directory/<id>`. The icon image falls back to an icon on load error.
- [ ] **Step 2: Run, expect FAIL.** `cd mobile/apps/renter && flutter test test/home_directory_row_test.dart`
- [ ] **Step 3: Implement.** The row owns its leading gap, as the strip did (see the comment at `home_screen.dart:70-73`). Pull-to-refresh on home invalidates `directoryProvider`.
- [ ] **Step 4: Run, expect PASS.** Run the whole renter suite (`flutter test`) and `flutter analyze`.
- [ ] **Step 5: Commit** `feat(renter): category tiles replace the home ad carousel`.

### Task 7: Renter category list and business page

**Files:**
- Create: `mobile/apps/renter/lib/screens/directory/category_screen.dart`, `.../directory/business_screen.dart`
- Modify: `mobile/apps/renter/lib/router.dart` (add `/directory/:categoryId` and `/directory/business/:id`; remove `/offers` at ~line 270), `lib/providers/promotion_provider.dart` (`directoryCategoryProvider` family, `directoryBusinessProvider` family; `promoOffersProvider` becomes a family over `({String? category, String? businessId})` or a new `businessOffersProvider`)
- Delete: `lib/screens/offers_screen.dart` and its test, if nothing else imports it
- Test: `mobile/apps/renter/test/directory_category_screen_test.dart`, `test/directory_business_screen_test.dart`; update `test/support/fake_promotion_service.dart`

**Interfaces:**
- Consumes: Task 5 API and the existing `AdCard`, `PromoActions`, coupon sheet and redirect sheet, plus `promo_url.dart` checks for the website launch.

- [ ] **Step 1: Failing tests.** Category screen: lists businesses (logo, name, one-line description) and taps through to `/directory/business/<id>`; a 404 shows the not-found state; an error offers retry. Business screen: name, description, a **Visit quickwash.ae** button (label = URL host) that opens externally only after the `promo_url` scheme/host check, and is absent when there's no website; Call and WhatsApp only when set; "Current offers" lists `AdCard`s from `offers(businessId:)` and is hidden when empty; coupon copy then redirect works as on the old Offers screen; ad clicks still record through `promoEventQueueProvider`. Run each in AR too: RTL and back-button mirroring.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS.** Run the whole renter suite and `flutter analyze` (no dangling imports of the deleted files).
- [ ] **Step 5: Commit** `feat(renter): directory category and business screens`.

### Task 8: End-to-end check

- [ ] **Step 1:** Backend promotion suites: `./gradlew test --tests '*Promo*'`. Then the golden gate: `./gradlew test -PincludeTags=golden`. The full suite runs ~38 min, so run it in the background (see the Gradle-wait memory).
- [ ] **Step 2:** Run locally with the stack up. As tenant admin, create "Sample Group" (2 sample businesses) and "Car wash" (QuickWash, website `https://quickwash.ae`, domain `quickwash.ae`, one coupon ad). Optionally add the same to `scripts/seed_demo_tenant.py` behind its idempotent manifest.
- [ ] **Step 3:** In the renter app on the iOS simulator, in EN and AR: home shows two tiles and no carousel; tile → list → business page → Visit site opens; the coupon copy and redirect work. Take a screenshot of each screen in both languages.
- [ ] **Step 4:** Commit any seed change, push the branch, and open the PR (link the spec and plan, attach the screenshots).
