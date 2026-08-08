# Backend Endpoint ↔ Frontend Usage Audit

**Date:** 2026-08-08 · **Codebase:** `main` @ `7c5e65c` · **Method:** 13 domain auditors read every controller + DTO, traced every endpoint into all four frontends (web, mobile_admin, mobile_renter, mobile_security); all critical/high findings were independently re-verified against the code by adversarial verifier agents (61/61 confirmed, severity recalibrated).

## Coverage

- **47 controllers, 262 endpoints** audited; **232 used by at least one frontend**, 30 unused (inventory at the bottom).
- **129 confirmed findings**: 26 high, 73 medium, 30 low. 0 findings were refuted in verification.
- The dominant pattern: **the Flutter manager app (mobile_admin) was written against an imagined API** — wrong field names, wrong enum values, wrong HTTP methods, and two endpoints that don't exist. The web dashboard is largely correct; renter and security apps are mostly clean.

## High severity (26)

### H1. Mobile admin P&L reports read totalExpense/netIncome/incomeAccounts/expenseAccounts — expenses always show 0 and net income equals total income
**Endpoint:** `GET /api/v1/finance/reports/organisation (also /reports/property/{id}, /reports/unit/{id})` · **Affects:** mobile_admin · **Area:** accounting

ReportDTO.java exposes `totalExpenses` (line 25), `netProfit` (line 34), and Map<String,BigDecimal> `incomeBreakdown`/`directExpenseBreakdown`/`indirectExpenseBreakdown` (lines 28-30). finance_screen.dart lines 472-480 and report_detail_screen.dart lines 61-70 read `data['totalExpense']` (singular — always 0), `data['netIncome'] ?? totalIncome - totalExpense` (netIncome doesn't exist, and since totalExpense misreads as 0 the fallback equals totalIncome), and `data['incomeAccounts']` / `data['expenseAccounts']` (nonexistent — breakdown sections never render). Result: the Expenses card always shows AED 0 and Net Income always equals Total Income on the Organisation, Property and Unit report screens.

**Files:** `mobile/apps/manager/lib/screens/finance_screen.dart` · `mobile/apps/manager/lib/screens/report_detail_screen.dart` · `backend/src/main/java/com/datagami/rentaxis/api/dto/ReportDTO.java`

**Fix:** Read totalExpenses and netProfit, and render the incomeBreakdown/directExpenseBreakdown/indirectExpenseBreakdown maps (key = 'code - name', value = amount) instead of the nonexistent incomeAccounts/expenseAccounts lists.

### H2. Web invite set-password flow is blocked by the auth proxy middleware: unauthenticated invitees always get 401
**Endpoint:** `POST /api/auth/set-password` · **Affects:** web · **Area:** auth-users

SetPasswordForm.tsx:27 posts to fetch("/api/proxy/auth/set-password", {method:"POST", ...}). proxy.ts:17-24 intercepts every /api/proxy request: `const token = await getToken({ req }); if (!token) { return ... new NextResponse('Unauthorized', { status: 401 }) }`. An invited user activating their account has no NextAuth session (they have no password yet), so the POST never reaches the backend endpoint (AuthController.java:259 @PostMapping("/set-password")) and the form falls into the generic else branch "Could not set password. Please try again." (SetPasswordForm.tsx:40-42). This IS the primary invite path: PayloadVarsExtractor.java:54 builds the emailed link as `base + "/" + lang + "/auth/set-password?token=" + token`, i.e. the web page. Note the GET validate call works only because the server component (set-password/page.tsx:16-18) calls BACKEND_URL directly, bypassing the proxy — the client POST does not.

**Files:** `web/src/proxy.ts` · `web/src/app/[locale]/auth/set-password/SetPasswordForm.tsx` · `backend/src/main/java/com/datagami/rentaxis/core/email/dispatch/PayloadVarsExtractor.java`

**Fix:** Exempt /api/proxy/auth/set-password (and any other pre-auth endpoints) from the token check in proxy.ts, or have the form POST to a server action / direct route that forwards to BACKEND_URL like the validate call does.

### H3. Manager app staff create/edit sends a User-shaped body to /v1/staff; nameEn is always null so every create/update violates the NOT NULL name_en column
**Endpoint:** `POST /api/v1/staff and PUT /api/v1/staff/{id}` · **Affects:** mobile_admin · **Area:** auth-users

staff_screen.dart:333-339 creates via `service.createStaff({'name': ..., 'email': ..., 'phone': ..., 'role': role})` and staff_detail_screen.dart:229-235 updates via `service.updateStaff(widget.staffId, {'name': ..., 'email': ..., 'phone': ..., 'role': role})` with role values 'PROPERTY_MANAGER'/'TENANT_USER'. But StaffController.java:38 binds `@RequestBody Staff staff` — the Staff entity (Staff.java) has fields nameEn/nameAr/employeeId/designation/department/monthlySalary/phone/property/salaryAccount and NO name/email/role fields, and `@Column(name = "name_en", nullable = false) private String nameEn`. Jackson silently drops name/email/role, leaving nameEn null; repository.save() then hits the NOT NULL name_en column -> DataIntegrityViolationException -> 500 on every create. For update, StaffService.updateStaff copies `existing.setNameEn(updates.getNameEn())` (null) and fails the same way, and would also null out nameAr/employeeId/designation/property/salaryAccount if it saved. The screen was evidently written against /api/admin/users semantics (user accounts with roles) but wired to the HR staff endpoint.

**Files:** `mobile/apps/manager/lib/screens/staff_screen.dart` · `mobile/apps/manager/lib/screens/staff_detail_screen.dart` · `backend/src/main/java/com/datagami/rentaxis/domain/entity/Staff.java` · `backend/src/main/java/com/datagami/rentaxis/core/service/StaffService.java`

**Fix:** Point these screens at the correct API (likely /admin/users if the intent is provisioning PROPERTY_MANAGER/TENANT_USER accounts) or rewrite the forms to send Staff fields (nameEn, nameAr, designation, phone, property:{id}, ...). Do not ship both a create and an edit path that can never succeed.

### H4. Manager app staff list/detail read nonexistent fields (name/email/role/propertyIds) from the Staff DTO — every row renders 'Unknown' and search/property filter never match
**Endpoint:** `GET /api/v1/staff and GET /api/v1/staff/{id}` · **Affects:** mobile_admin · **Area:** auth-users

staff_screen.dart:150-151 filters on `(s['name'] ?? '')` and `(s['email'] ?? '')`; :157-159 filters on `(s['propertyIds'] as List<dynamic>?)`; _StaffCard (:529-533) reads staff['name'], staff['email'], staff['role'], staff['propertyIds']. The backend returns the raw Staff entity whose JSON keys are nameEn, nameAr, employeeId, designation, department, monthlySalary, joinDate, phone, emiratesId, passportNumber, property (object), salaryAccount, active — none of name/email/role/propertyIds exist. Result: every card title is 'Unknown' (staff_screen.dart:529 `staff['name'] ?? l.unknown`), no role pill, any non-empty search returns zero rows (contains() on ''), and selecting a property filter chip excludes everything (propertyIds always []). staff_detail_screen.dart:115-121 prefills its edit form from the same nonexistent keys.

**Files:** `mobile/apps/manager/lib/screens/staff_screen.dart` · `mobile/apps/manager/lib/screens/staff_detail_screen.dart` · `backend/src/main/java/com/datagami/rentaxis/domain/entity/Staff.java`

**Fix:** Render nameEn/nameAr (locale-aware), phone, designation and property.nameEn from the actual Staff DTO, and filter by `s['property']?['id']` — mirroring web/src/app/[locale]/dashboard/staff/page.tsx which consumes the same endpoint correctly.

### H5. Web registration page never calls POST /api/auth/register — it fakes a 1s delay and redirects to login as if the account was created
**Endpoint:** `POST /api/auth/register` · **Affects:** web · **Area:** auth-users

register/page.tsx:41-46: `// For Phase 3, we'll just simulate registration for now ... await new Promise(r => setTimeout(r, 1000)); router.push("/auth/login?registered=true");` — formData (fullName, email, password, companyName) is collected then discarded. The backend endpoint exists and is fully functional (AuthController.java:202-226 provisions a LandlordOrg and a TENANT_ADMIN user) and its RegisterRequest record (:47) matches the form's field names exactly. A user who "registers" lands on the login page with a success banner and then cannot log in, because no account was ever created. No frontend calls this endpoint.

**Files:** `web/src/app/[locale]/auth/register/page.tsx` · `backend/src/main/java/com/datagami/rentaxis/api/AuthController.java`

**Fix:** Wire the form to POST /api/auth/register (via a public route — note the /api/proxy middleware requires a session, so call BACKEND_URL from a server action or add a proxy exemption), or remove/disable the registration page until the flow is real.

### H6. Manager app fetches lease penalties from a nonexistent endpoint (GET /api/v1/leases/{id}/penalties)
**Endpoint:** `GET /api/v1/leases/{leaseId}/penalties (does not exist; real endpoint is GET /api/v1/penalties?leaseId=)` · **Affects:** mobile_admin · **Area:** fines-rent

penalty_service.dart:29 `final response = await _dio.get('/v1/leases/$leaseId/penalties');` is called from lease_penalties_screen.dart:76 `final penalties = await service.getPenalties(widget.leaseId);` (screen routed at manager router.dart:127 path 'penalties', linked from lease_detail_screen.dart:1127). Backend has no such mapping: PenaltyController.java:32 maps `@RequestMapping("/api/v1/penalties")` with only a root `@GetMapping` (line 43), and LeaseController.java (@RequestMapping("/api/v1/leases")) has no penalties route. The web codebase confirms this route never existed: leases/[id]/page.tsx:252-253 comment 'there is no /leases/{id}/penalties route, the previous URL silently 404'd'. Result: guaranteed 404, caught at lease_penalties_screen.dart:82-88 which permanently shows 'Failed to load penalties'. The card also reads `penalty['status'] == 'ACTIVE'` (line 296-297) — a value PenaltyDTO.status() never returns (OPEN/CLEARED/WAIVED), showing the screen was written against a phantom API.

**Files:** `mobile/packages/rentaxis_core/lib/api/services/penalty_service.dart` · `mobile/apps/manager/lib/screens/lease_penalties_screen.dart`

**Fix:** Change PenaltyService.getPenalties to GET /v1/penalties with queryParameters {leaseId: leaseId, size: ...} (as listPenalties already does) and unwrap the Spring Page `content` array; update _PenaltyCard status handling to OPEN/CLEARED/WAIVED.

### H7. Web waive-penalty uses PUT but backend endpoint is POST — guaranteed 405 with silent UI failure
**Endpoint:** `POST /api/v1/penalties/{id}/waive` · **Affects:** web · **Area:** fines-rent

Backend: PenaltyController.java:91 `@PostMapping("/{id}/waive")` (POST only). Web: leases/[id]/page.tsx:285-289 `fetch(`/api/proxy/v1/penalties/${waiveModalPenalty.id}/waive`, { method: "PUT", ... body: JSON.stringify({ reason: waiveReason.trim() }) })`. Next.js proxy rewrites preserve the HTTP method, so Spring returns 405 Method Not Allowed. handleWaivePenalty (lines 281-296) only acts on `res.ok` and has no else/error branch — the modal stays open with no message, so waiving is both broken and silent. The request body itself ({reason}) matches WaivePenaltyRequestDTO.

**Files:** `web/src/app/[locale]/dashboard/leases/[id]/page.tsx` · `backend/src/main/java/com/datagami/rentaxis/api/PenaltyController.java`

**Fix:** Change method to "POST" in handleWaivePenalty, and add an else branch surfacing the error (400/403/404/409 BusinessRuleViolation messages such as 'Penalty already cleared').

### H8. Manager app waive-penalty uses PUT but backend endpoint is POST — guaranteed 405
**Endpoint:** `POST /api/v1/penalties/{id}/waive` · **Affects:** mobile_admin · **Area:** fines-rent

penalty_service.dart:34 `final response = await _dio.put('/v1/penalties/$penaltyId/waive', data: {...});` vs backend PenaltyController.java:91 `@PostMapping("/{id}/waive")`. Dio PUT to a POST-only mapping returns 405, caught at lease_penalties_screen.dart:203-208 as generic 'Failed to waive penalty'. (Screen currently never reaches this point because the list itself 404s, but the call is independently broken.)

**Files:** `mobile/packages/rentaxis_core/lib/api/services/penalty_service.dart` · `mobile/apps/manager/lib/screens/lease_penalties_screen.dart`

**Fix:** Change _dio.put to _dio.post in PenaltyService.waivePenalty.

### H9. Manager app penalties screen calls two nonexistent lease-scoped penalty endpoints — guaranteed 404 on every load
**Endpoint:** `GET /api/v1/leases/{leaseId}/penalties and POST /api/v1/leases/{leaseId}/penalties/recalculate` · **Affects:** mobile_admin · **Area:** lease-core

penalty_service.dart:29 `await _dio.get('/v1/leases/$leaseId/penalties')` and :41 `await _dio.post('/v1/leases/$leaseId/penalties/recalculate')`. No controller maps these paths: LeaseController/LeaseAttachmentController/LeaseInteractionController are the only classes on "/api/v1/leases" and define no penalties routes; PenaltyController.java:32 is `@RequestMapping("/api/v1/penalties")` with only GET (list, takes `leaseId` query param, line 43-46), POST /{id}/payments (line 79) and POST /{id}/waive (line 91) — `PenaltyService.recalculateForLease` (PenaltyService.java:188) is never exposed via any mapping. The screen is live: manager router.dart:127 routes path 'penalties', lease_detail_screen.dart:1127 pushes '/leases/{id}/penalties', and lease_penalties_screen.dart:76 calls `service.getPenalties(widget.leaseId)` on initState → Dio throws on 404 → the screen always shows the _error state (lines 82-88); :122-124 calls recalculatePenalties → always the failure snackbar. The web already fixed the same bug and left a comment: web/src/app/[locale]/dashboard/leases/[id]/page.tsx:252-254 "there is no /leases/{id}/penalties route, the previous URL silently 404'd" and uses `/api/proxy/v1/penalties?leaseId=`.

**Files:** `mobile/packages/rentaxis_core/lib/api/services/penalty_service.dart` · `mobile/apps/manager/lib/screens/lease_penalties_screen.dart` · `backend/src/main/java/com/datagami/rentaxis/api/PenaltyController.java` · `mobile/apps/manager/lib/router.dart`

**Fix:** In penalty_service.dart change getPenalties to GET /v1/penalties?leaseId={leaseId} (unwrap the Spring Page `content` array, as listPenalties at line 17-25 already does), and either remove the Recalculate action or add a backend mapping (e.g. POST /api/v1/penalties/recalculate?leaseId=) that calls PenaltyService.recalculateForLease.

### H10. Penalty waive called with PUT but backend maps POST — waiving from the lease detail screen always fails with 405
**Endpoint:** `POST /api/v1/penalties/{id}/waive (called as PUT by both web and mobile)` · **Affects:** web, mobile_admin · **Area:** lease-core

Backend: PenaltyController.java:91 `@PostMapping("/{id}/waive")` (roles SUPER_ADMIN, TENANT_ADMIN; body WaivePenaltyRequestDTO with `reason`). Web lease detail page [id]/page.tsx:285-289 sends `fetch('/api/proxy/v1/penalties/${waiveModalPenalty.id}/waive', { method: "PUT", ... body: JSON.stringify({ reason: ... }) })` → Spring returns 405 Method Not Allowed; the handler only acts `if (res.ok)` so the waive modal silently does nothing. Mobile penalty_service.dart:34 `await _dio.put('/v1/penalties/$penaltyId/waive', ...)` is called from lease_penalties_screen.dart:193 — same guaranteed 405 (that screen is additionally broken by the 404 finding above).

**Files:** `web/src/app/[locale]/dashboard/leases/[id]/page.tsx` · `mobile/packages/rentaxis_core/lib/api/services/penalty_service.dart` · `backend/src/main/java/com/datagami/rentaxis/api/PenaltyController.java`

**Fix:** Change both call sites to POST (or add @PutMapping alongside @PostMapping on the backend). Also surface the failure in the web modal instead of silently ignoring non-ok responses.

### H11. GET lease documents/download excludes PROPERTY_MANAGER — the manager app's lease detail screen fails entirely for PM users
**Endpoint:** `GET /api/v1/leases/{id}/documents and GET /api/v1/leases/documents/{docId}/download` · **Affects:** mobile_admin, web, backend · **Area:** lease-core

LeaseController.java:201 getDocuments and :207 downloadDocument are `@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'RENTER')")` — PROPERTY_MANAGER is missing, even though GET /{id} (line 77), settlement (128-166) and LeaseAttachmentController list/download (lines 34, 40) all include PROPERTY_MANAGER. The manager app is used by PROPERTY_MANAGER users (staff_detail_screen.dart:211 creates them, profile_screen.dart:651 renders the role, guard_admin_service.dart:102 comments on PM 403s) and lease_detail_screen.dart:112-117 loads the screen with `Future.wait([getLeaseById, getPayments, getLeaseDocuments, getAttachments])` — the 403 on getLeaseDocuments rejects the whole Future.wait, so the catch at lines 129-135 sets `_error = failedToLoad` and a PM can never open ANY lease detail screen. On web, PMs get a silent no-op: dashboard/leases/page.tsx:341-345 and [id]/page.tsx:515-529 fetch documents/download inside `if (res.ok)` with no else branch.

**Files:** `backend/src/main/java/com/datagami/rentaxis/api/LeaseController.java` · `mobile/apps/manager/lib/screens/lease_detail_screen.dart` · `web/src/app/[locale]/dashboard/leases/page.tsx` · `web/src/app/[locale]/dashboard/leases/[id]/page.tsx`

**Fix:** Add 'PROPERTY_MANAGER' to the @PreAuthorize lists of getDocuments and downloadDocument (consistent with the lease read and attachment endpoints), or stop fetching documents for PM sessions in the manager app. Also make lease_detail_screen load documents outside the all-or-nothing Future.wait.

### H12. Mobile admin create-listing payload never includes required unitId — create always fails (403 for PROPERTY_MANAGER, 500 for TENANT_ADMIN)
**Endpoint:** `POST /api/listings` · **Affects:** mobile_admin · **Area:** listings

The create flow is reachable: listings_list_screen.dart:142 `context.push('/listings/new')` -> ListingEditScreen with `_listingId == null` -> listing_edit_screen.dart:351 `await service.createListing(data)`. The payload builder `_buildPayload()` (listing_edit_screen.dart:404-437) contains titleEn, furnishing, rent, amenities, etc. but `grep -n unitId listing_edit_screen.dart` returns zero matches — unitId is never collected or sent, and there is no unit picker in the screen. Backend UnitListingCreateRequest.unitId is required in practice: UnitListingController.java:87 `checkPropertyManagerAccess(req.unitId())` throws AccessDeniedException("Unit must be specified") -> 403 (GlobalExceptionHandler.java:50-52) when unitId is null for PROPERTY_MANAGER; for TENANT_ADMIN/SUPER_ADMIN the check is skipped and UnitListingService.create (UnitListingService.java:113-116) saves `listing.setUnitId(null)` into column unit_id declared `nullable: false` (37-unit-listings.yaml:22-25) -> DataIntegrityViolationException -> 500. Web sends unitId correctly (web/src/app/[locale]/dashboard/listings/[id]/page.tsx:206 `unitId: form.unitId`), confirming the mobile payload is the wrong one.

**Files:** `mobile/apps/manager/lib/screens/listings/listing_edit_screen.dart` · `mobile/apps/manager/lib/screens/listings/listings_list_screen.dart` · `backend/src/main/java/com/datagami/rentaxis/api/UnitListingController.java` · `backend/src/main/resources/db/changelog/changesets/37-unit-listings.yaml`

**Fix:** Add a unit selector to the mobile listing_edit_screen create flow (fetch units via unit_service) and include `'unitId': _unitId` in _buildPayload for creates. Backend should also validate unitId with a 400 instead of leaking a 500 for admins.

### H13. Mobile admin vendor creation sends 'name' instead of required 'nameEn' — guaranteed 400 on every create
**Endpoint:** `POST /api/v1/vendors` · **Affects:** mobile_admin · **Area:** misc

Backend: VendorController.java:34 `public ResponseEntity<Vendor> createVendor(@Valid @RequestBody Vendor vendor)` binds directly to the Vendor entity, whose only name field is Vendor.java:22-24 `@NotBlank(message = "Vendor name (English) is required") @Column(name = "name_en", nullable = false) private String nameEn;` (there is no `name` property). Frontend: vendors_screen.dart:217-227 builds the body as `await service.createVendor({ 'name': nameCtrl.text.trim(), if (...) 'email': ..., 'phone': ..., 'address': ..., 'trn': ... })`, sent via vendor_service.dart:18 `_dio.post('/v1/vendors', data: data)`. Jackson (Spring Boot default FAIL_ON_UNKNOWN_PROPERTIES=false) silently drops the unknown `name` key, leaving nameEn null, so @Valid rejects with 400 'Vendor name (English) is required' on every submission. The catch block at vendors_screen.dart:230-236 shows the generic 'create failed' snackbar, so vendor creation from the manager app can never succeed. Web sends the correct field (`nameEn`) for the same endpoint at web/src/app/[locale]/dashboard/finance/vendors/page.tsx:139, confirming the mobile payload is the wrong one.

**Files:** `mobile/apps/manager/lib/screens/vendors_screen.dart` · `mobile/packages/rentaxis_core/lib/api/services/vendor_service.dart` · `backend/src/main/java/com/datagami/rentaxis/domain/entity/Vendor.java` · `backend/src/main/java/com/datagami/rentaxis/api/VendorController.java`

**Fix:** Change the mobile payload key from 'name' to 'nameEn' in vendors_screen.dart:218 (and consider collecting nameAr too).

### H14. Mobile admin reads nonexistent vendor field 'name' — every vendor renders as 'Unknown'/'-' and name search never matches
**Endpoint:** `GET /api/v1/vendors and GET /api/v1/vendors/{id}` · **Affects:** mobile_admin · **Area:** misc

The Vendor entity returned by GET /api/v1/vendors and /{id} serializes `nameEn`/`nameAr` (Vendor.java:22-27); there is no `name` property in the JSON. Mobile reads the nonexistent key in four places: vendors_screen.dart:361 `final name = (vendor['name'] ?? l.unknown).toString();` (every list card shows the 'Unknown' fallback), vendors_screen.dart:60 `final name = (v['name'] ?? '').toString().toLowerCase();` (search-by-name always matches empty string, so name search is broken), vendor_detail_screen.dart:79 `final name = (vendor['name'] ?? l.unknown).toString();` (detail header shows 'Unknown'), and finance_reports_screen.dart:228 passes `nameKey: 'name'` to the vendor picker whose renderer at line 617 does `final name = (item[nameKey] ?? '-').toString();`, so the vendor-ledger picker lists every vendor as '-'.

**Files:** `mobile/apps/manager/lib/screens/vendors_screen.dart` · `mobile/apps/manager/lib/screens/vendor_detail_screen.dart` · `mobile/apps/manager/lib/screens/finance_reports_screen.dart` · `backend/src/main/java/com/datagami/rentaxis/domain/entity/Vendor.java`

**Fix:** Read `nameEn` (with `nameAr` fallback for AR locale) in all four locations instead of `name`.

### H15. Web gateway settings: saving without re-typing credentials overwrites the real API key with the masked placeholder and wipes the API secret
**Endpoint:** `POST /api/v1/gateway-config` · **Affects:** web, backend · **Area:** org-tenant

GET response deliberately masks/omits secrets: mapToDTO sets apiKeyMasked = first8+'****' and apiKey/apiSecret = null (TenantGatewayConfigService.java:96-110). The web page pre-fills the API Key input with that masked value — page.tsx:88 `setApiKey(config.apiKeyMasked || "")` — and apiSecret starts as "" (page.tsx:52) and is never pre-filled. handleSave (page.tsx:122-131) POSTs `{ gatewayId, apiKey, apiSecret, webhookSecret, isTestMode }` verbatim. saveConfig persists both unconditionally: `config.setApiKeyEncrypted(encryptionService.encrypt(dto.getApiKey())); config.setApiSecretEncrypted(encryptionService.encrypt(dto.getApiSecret()))` (TenantGatewayConfigService.java:50-51) — only webhookSecret has keep-if-blank handling (lines 52-54, matching the UI hint at page.tsx:316-318). So a TENANT_ADMIN who opens the page with an existing config and clicks Save (e.g. just to flip test mode) stores 'rzp_test****' as the key and an encrypted empty string as the secret, breaking Razorpay payments until both are re-entered. The post-save refetch (page.tsx:135) re-fills the masked key, so repeat saves keep corrupting.

**Files:** `web/src/app/[locale]/dashboard/settings/gateway/page.tsx` · `backend/src/main/java/com/datagami/rentaxis/core/service/TenantGatewayConfigService.java`

**Fix:** Backend: skip apiKey/apiSecret updates when blank or when apiKey equals the masked form (mirror the webhookSecret guard). Web: do not pre-fill the key field with apiKeyMasked (show it as a placeholder/hint instead) and omit blank credential fields from the POST body.

### H16. Web aging report reads buckets as a keyed object with 'items', but backend returns an array with 'details' — bucket breakdown never renders
**Endpoint:** `GET /api/v1/payments/aging-report` · **Affects:** web · **Area:** payments

Backend AgingReportDTO declares 'private List<AgingBucket> buckets' where AgingBucket has fields label/amount/count/details (AgingReportDTO.java:12-31), and PaymentScheduleService.getAgingReport builds it as an ordered list with labels {"Current", "1-30 Days", ...} (PaymentScheduleService.java:1039-1046), so the JSON is buckets: [{label, amount, count, details:[...]}, ...]. The web reports page instead types it as an object: 'buckets: { current: AgingBucket; days1to30: AgingBucket; ... }' with 'items: AgingItem[]' (reports/page.tsx:64-80) and indexes it with string keys: 'const bucket = agingData.buckets?.[cfg.key as keyof typeof agingData.buckets]' where cfg.key is "current"/"days1to30"/... (reports/page.tsx:561 and 575), then reads 'bucket.items' (line 576/601). Indexing a JSON array with "current" is always undefined, so every bucket card hits 'if (!bucket) return null' (line 562) and every detail table hits 'if (!bucket?.items ...) return null' (line 576). Only totalOutstanding (line 555) ever renders; the entire bucket summary and per-renter detail tables are guaranteed never to appear on the Finance > Reports > Aging tab.

**Files:** `web/src/app/[locale]/dashboard/finance/reports/page.tsx` · `backend/src/main/java/com/datagami/rentaxis/api/dto/AgingReportDTO.java` · `backend/src/main/java/com/datagami/rentaxis/core/service/PaymentScheduleService.java`

**Fix:** Fix the web page to consume the actual shape: map over agingData.buckets (array) by index or by matching bucket.label, and rename items -> details (and AgingItem's fields already match AgingDetail: renterName, propertyName, unitNumber, amount, daysOverdue, dueDate). Alternatively change AgingReportDTO to a keyed map, but the array fix on the frontend is non-breaking.

### H17. Manager app fetches the tenant-wide payments list as a single 500-row page and filters client-side — data silently truncated beyond 500 schedules
**Endpoint:** `GET /api/v1/payments (should also use GET /api/v1/payments/lease/{leaseId})` · **Affects:** mobile_admin · **Area:** payments

Backend GET /api/v1/payments is a Spring Page endpoint with @PageableDefault(size = 25) and server-side filters propertyId/status/renterName/overdue (PaymentScheduleController.java:45-59), and a dedicated unpaginated per-lease endpoint GET /lease/{leaseId} exists (PaymentScheduleController.java:61-65). PaymentService.getPayments defaults to a single request with 'size': 500 and unwraps only data['content'], discarding totalElements/totalPages (payment_service.dart:32-47). Three manager screens rely on that one page being the whole tenant: (1) lease_detail_screen.dart:114+122-124 calls paymentService.getPayments() and filters '.where((p) => p['leaseId'] == widget.leaseId)' instead of calling /lease/{id} — installments of the viewed lease that fall outside the first 500 rows (sorted dueDate DESC) silently disappear from the schedule tab; (2) payments_screen.dart:28-33 loads getPayments() with no filters and does all property/status/overdue filtering plus its own show-more pagination client-side over those 500 rows (payments_screen.dart:157-238), so the Paid/Overdue/All tabs under-report; (3) cheque_scan_flow_screen.dart:71-88 fetches the full list just to find one payment by id. The web equivalents pass propertyId/status/renterName/page/size server-side (finance/payments/page.tsx:170-189) and use the lease-scoped endpoint, with a comment warning about exactly this truncation (PaymentScheduleEditor.tsx:104-108). A portfolio of ~42 leases x 12 cheques exceeds 500 rows.

**Files:** `mobile/packages/rentaxis_core/lib/api/services/payment_service.dart` · `mobile/apps/manager/lib/screens/lease_detail_screen.dart` · `mobile/apps/manager/lib/screens/payments_screen.dart` · `mobile/apps/manager/lib/screens/cheque_scan/cheque_scan_flow_screen.dart`

**Fix:** In the manager app, use GET /v1/payments/lease/{leaseId} for the lease detail screen (add a wrapper to PaymentService), pass propertyId/status/overdue and real page/size to GET /v1/payments from the payments screen instead of client-side filtering, and fetch the single payment via the lease-scoped list in the scan flow. At minimum, surface totalElements so truncation is detectable.

### H18. Mobile manager 'Create Property' always fails: sends 'name' instead of required 'nameEn' and omits required 'type'
**Endpoint:** `POST /api/v1/properties` · **Affects:** mobile_admin · **Area:** property-units

Backend PropertyController.java:29-31 binds @Valid CreatePropertyDTO; CreatePropertyDTO.java:11-17 declares `@NotBlank private String nameEn` and `@NotNull private PropertyType type`. Mobile properties_screen.dart:275-279 submits `service.createProperty({'name': nameCtrl.text.trim(), 'address': ..., 'emirate': selectedEmirate})` via property_service.dart:40 (`_dio.post('/v1/properties', ...)`). Jackson ignores the unknown 'name' key, leaving nameEn=null and type=null, so validation fails and GlobalExceptionHandler.java:59-69 returns 400 on every submission; the catch at properties_screen.dart:282-286 shows only a generic 'createPropertyFailed' snackbar. Additionally the sheet's add button (properties_screen.dart:67) is not role-gated, and the endpoint's @PreAuthorize allows only SUPER_ADMIN/TENANT_ADMIN, so PROPERTY_MANAGER users would get 403 even after the payload is fixed.

**Files:** `mobile/apps/manager/lib/screens/properties_screen.dart` · `mobile/packages/rentaxis_core/lib/api/services/property_service.dart` · `backend/src/main/java/com/datagami/rentaxis/api/dto/CreatePropertyDTO.java` · `backend/src/main/java/com/datagami/rentaxis/api/PropertyController.java`

**Fix:** Send {'nameEn': ..., 'type': 'RESIDENTIAL' (or a picker value), 'emirate': ..., 'address': ...} from the mobile sheet (or accept 'name' as an alias in the DTO), and hide the create action for PROPERTY_MANAGER users to match the backend role guard.

### H19. Mobile manager 'Create Unit' always fails: payload uses nonexistent fields (propertyId/size/annualRent) and invalid UnitType values
**Endpoint:** `POST /api/v1/units` · **Affects:** mobile_admin · **Area:** property-units

Backend UnitController.java:29-33 binds @RequestBody Unit directly; Unit.java has `property` (ManyToOne, nullable=false column), `sizeSqft`, `expectedRent`, and `type` (enum STUDIO,BHK1,BHK2,BHK3,PENTHOUSE,RETAIL,OFFICE per UnitType.java). Mobile property_detail_screen.dart:446-454 sends {'unitNumber':..., 'propertyId': widget.propertyId, 'type': unitType, 'size':..., 'annualRent':...} via unit_service.dart:17-20. 'propertyId', 'size', 'annualRent' are not Unit fields and are silently dropped, so unit.property is null and the insert violates the NOT NULL property_id column (500 via GlobalExceptionHandler RuntimeException catch-all); the type dropdown (property_detail_screen.dart:352-362) offers APARTMENT (default), VILLA, SHOP, WAREHOUSE, TOWNHOUSE which are not UnitType constants, so Jackson enum deserialization fails first with those values. Every submission fails; the user only sees the generic 'createUnitFailed' snackbar (lines 457-461).

**Files:** `mobile/apps/manager/lib/screens/property_detail_screen.dart` · `mobile/packages/rentaxis_core/lib/api/services/unit_service.dart` · `backend/src/main/java/com/datagami/rentaxis/domain/entity/Unit.java` · `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/UnitType.java`

**Fix:** Send {'unitNumber':..., 'property': {'id': widget.propertyId}, 'type': <valid UnitType>, 'sizeSqft':..., 'expectedRent':...} and replace the dropdown values with the backend enum (STUDIO, BHK1, BHK2, BHK3, PENTHOUSE, RETAIL, OFFICE), mirroring the working web form at web/src/app/[locale]/dashboard/properties/[id]/page.tsx:595-611.

### H20. Public renewal-intent endpoint is unreachable for logged-out renters: web routes it through the auth-gated /api/proxy middleware
**Endpoint:** `POST /api/v1/public/renewal-intent` · **Affects:** web · **Area:** renewals

Backend deliberately makes this endpoint anonymous: SecurityConfig.java:36 permits "/api/v1/public/**" (permitAll) and ApiSecurityFilter.java:31 skips it; PublicRenewalController does a cross-tenant token lookup (findByIdAcrossTenants, line 42) precisely so no session is needed. The renewal reminder email (lease_renewal_reminder.html) links to '{portalBaseUrl}/{lang}/dashboard/renter-portal/renewal-intent?token=...&intent=...'. That page's component posts via the proxy: RenewalIntentConfirm.tsx:12 `fetch("/api/proxy/v1/public/renewal-intent", { method: "POST", ... })`. But web/src/proxy.ts:19-24 rejects EVERY /api/proxy request without a NextAuth session: `if (isApiProxy) { const token = await getToken({ req }); if (!token) { return ... new NextResponse('Unauthorized', { status: 401 }) } }` (matcher line 53 includes '/api/proxy/:path*'). Additionally the page sits under the auth-gated dashboard layout (AuthenticatedLayout.tsx:25-27 pushes unauthenticated users to /auth/login, dropping the token/intent query). So a renter clicking the email CTA while not logged into the web portal — the primary use case for a signed-token public endpoint — gets a 401 from Next middleware (surfaced as the generic error state at RenewalIntentConfirm.tsx:18) and can never confirm.

**Files:** `web/src/proxy.ts` · `web/src/components/renewals/RenewalIntentConfirm.tsx` · `backend/src/main/java/com/datagami/rentaxis/config/SecurityConfig.java` · `backend/src/main/resources/templates/email/events/lease_renewal_reminder.html`

**Fix:** Serve the confirm page outside the authenticated dashboard layout (e.g. a public /[locale]/renewal-intent route) and either exempt /api/proxy/v1/public/* from the session check in web/src/proxy.ts or add a dedicated rewrite (like the existing /api/marketplace/* and /public/l/* rewrites in next.config.ts) that forwards /api/v1/public/* to the backend without requiring a NextAuth token.

### H21. Manager app create-renter payload uses wrong field names; POST /v1/renters always fails 400
**Endpoint:** `POST /api/v1/renters` · **Affects:** mobile_admin · **Area:** renter-tickets

renters_screen.dart:275-281 sends {'name': ..., 'email': ..., 'phoneNumber': ..., 'preferredLanguage': 'ENGLISH'|'ARABIC'}. CreateRenterDTO.java declares @NotBlank String nameEn (plus nameAr, phone, Language primaryLanguage with values EN/AR). RenterController.createRenter is @Valid, so a body without nameEn is rejected by GlobalExceptionHandler.handleValidation (GlobalExceptionHandler.java:59-69) with 400 'Validation failed: nameEn: must not be blank' — on every submission. Even if it passed, 'phoneNumber'/'preferredLanguage' are unknown properties (silently dropped) so phone/language would never persist. Additionally the endpoint is @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')") (RenterController.java:36) while the FAB at renters_screen.dart:111-115 is shown to every manager-app user, so a PROPERTY_MANAGER would get 403 even with a correct payload. The response's portalPassword (RenterDTO.java:16) is also never surfaced, unlike web (renters/page.tsx:84-86).

**Files:** `mobile/apps/manager/lib/screens/renters_screen.dart` · `backend/src/main/java/com/datagami/rentaxis/api/dto/CreateRenterDTO.java`

**Fix:** Send {'nameEn': name, 'email': email, 'phone': phone, 'primaryLanguage': 'EN'|'AR'} (optionally nameAr, createPortalAccount). Hide or disable the create FAB for PROPERTY_MANAGER, and show the returned portalPassword to the admin like the web app does.

### H22. Manager app create-ticket default category MAINTENANCE (and GENERAL) not in TicketCategory enum — 400 on submit
**Endpoint:** `POST /api/v1/tickets` · **Affects:** mobile_admin · **Area:** renter-tickets

create_ticket_screen.dart:119 initialises _selectedCategory = 'MAINTENANCE' and the chip list (lines 125-135) offers 'MAINTENANCE' and 'GENERAL'. Backend enum TicketCategory is {PLUMBING, ELECTRICAL, HVAC, STRUCTURAL, PEST_CONTROL, CLEANING, APPLIANCE, SECURITY, OTHER}; MaintenanceTicketService.java:93-94 does TicketCategory.valueOf(dto.getCategory()) which throws IllegalArgumentException for MAINTENANCE/GENERAL → 400 via GlobalExceptionHandler.handleIllegalArgument. Submitting with the pre-selected default therefore always fails ('Failed to create ticket' snackbar, create_ticket_screen.dart:302-310). The renter app (renter/lib/screens/create_ticket_screen.dart:121-139) and web (tickets/page.tsx:68-78) use only valid enum values, so only the manager app is broken. STRUCTURAL and APPLIANCE are also missing from the manager list.

**Files:** `mobile/apps/manager/lib/screens/create_ticket_screen.dart` · `backend/src/main/java/com/datagami/rentaxis/core/service/MaintenanceTicketService.java`

**Fix:** Replace the manager app's category list with the backend enum values (drop MAINTENANCE/GENERAL, add STRUCTURAL/APPLIANCE), mirroring the renter app. Consider backend-side: return 400 with a clear message instead of relying on valueOf.

### H23. Manager app renters directory reads nonexistent RenterDTO fields — every row renders 'Unknown'
**Endpoint:** `GET /api/v1/renters` · **Affects:** mobile_admin · **Area:** renter-tickets

RenterDTO returns {id, nameEn, nameAr, email, phone, primaryLanguage, userId, portalPassword}. renters_screen.dart reads r['name'] for display/sort/search (lines 61, 75, 135, 430), r['unitNumber']/r['unit'] (line 431), r['propertyName']/r['property'] (line 432), r['status'] (line 438) and r['balance'] (line 439) — none of which exist in the DTO. Result: every renter row shows the l.unknown fallback (line 478), all rows group under the '#' section (line 136), name search never matches, and the trailing widget always shows a green '0 balance' (lines 533-535). Same class of bug in create_ticket_screen.dart:424 where the on-behalf-of dropdown reads r['name'] ?? r['email'] and so shows emails only.

**Files:** `mobile/apps/manager/lib/screens/renters_screen.dart` · `backend/src/main/java/com/datagami/rentaxis/api/dto/RenterDTO.java`

**Fix:** Read nameEn/nameAr (locale-aware) and phone from RenterDTO; drop unit/property/status/balance until the API actually provides them (or enrich the DTO backend-side).

### H24. Renter app reads 'closingOtp'/'otp' instead of 'closureOtp' — resolution OTP never shown, OTP close flow broken on mobile
**Endpoint:** `GET /api/v1/tickets/{id}` · **Affects:** mobile_renter · **Area:** renter-tickets

MaintenanceTicketDTO.java:27 names the field 'closureOtp' (set at MaintenanceTicketService.java:557). The renter app's RESOLVED-state OTP card passes otp: ticket['closingOtp'] ?? ticket['otp'] (ticket_detail_screen.dart:410) — both keys nonexistent — and _OtpSection returns SizedBox.shrink() when otp is null/empty (line 1149). So a renter using the mobile app never sees the 6-digit code the PM needs to close the ticket (PUT /{id}/close validates it at MaintenanceTicketService.java:283-287). The web renter view reads ticket.closureOtp correctly (web tickets/[id]/page.tsx:407-411), confirming which side is wrong.

**Files:** `mobile/apps/renter/lib/screens/ticket_detail_screen.dart` · `backend/src/main/java/com/datagami/rentaxis/api/dto/MaintenanceTicketDTO.java`

**Fix:** Change line 410 to ticket['closureOtp'] (keep the old keys as fallbacks if desired).

### H25. Web meetings page combines server-side and client-side pagination — meetings beyond the first 20 are unreachable
**Endpoint:** `GET /api/v1/meetings (and GET /api/v1/meetings/my)` · **Affects:** web · **Area:** renter-tickets

fetchMeetings requests page=currentPage-1&size=itemsPerPage from the paginated backend (meetings/page.tsx:92-96) and stores only data.content (line 102-104). The component then re-paginates client-side: paginated = filtered.slice((currentPage-1)*itemsPerPage, currentPage*itemsPerPage) (lines 125-128) — on page 2 this slices indices 20..40 of a 20-element array (empty). Worse, Pagination receives totalItems={filtered.length} (line 343) which is at most one server page (20), so Pagination.tsx:24 computes totalPages = 1 and the user can never navigate past page 1. Net effect: with more than itemsPerPage meetings, the list view and the calendar view (calendarEvents built from the same 'filtered', line 132) silently show only the first 20; client-side status/type/purpose filters also only filter within that first page. The renter-portal page handles the same endpoint correctly by reading data.totalPages (renter-portal/page.tsx:87-97).

**Files:** `web/src/app/[locale]/dashboard/meetings/page.tsx`

**Fix:** Either fetch all pages / a large size for the calendar and paginate purely client-side, or drop the client slice and drive Pagination from the backend's totalElements/totalPages.

### H26. Manager app reads 'assignedToName' but DTO field is 'assigneeName' — every ticket appears unassigned
**Endpoint:** `GET /api/v1/tickets and GET /api/v1/tickets/{id}` · **Affects:** mobile_admin · **Area:** renter-tickets

MaintenanceTicketDTO.java:34 declares 'assigneeName' (set at MaintenanceTicketService.java:584-587). Manager tickets_screen.dart:652 reads ticket['assignedToName'] (always null → list rows render the 'Unassigned' branch, line 802), and ticket_detail_screen.dart:520-524 gates the assignee card on ticket['assignedToName'] != null, so the card never renders even for assigned tickets. The renter app defends correctly with ticket['assigneeName'] ?? ticket['assignedToName'] (renter ticket_detail_screen.dart:234-236, tickets_screen.dart:424).

**Files:** `mobile/apps/manager/lib/screens/tickets_screen.dart` · `mobile/apps/manager/lib/screens/ticket_detail_screen.dart` · `backend/src/main/java/com/datagami/rentaxis/api/dto/MaintenanceTicketDTO.java`

**Fix:** Read 'assigneeName' (and 'assignedTo' for the id) in both manager screens.

## Medium severity (73)

### M1. Mobile admin transactions tab reads nonexistent debitAmount/creditAmount/transactionDate fields — amounts and dates never render
**Endpoint:** `GET /api/v1/finance/transactions` · **Affects:** mobile_admin · **Area:** accounting

Backend FinancialTransaction.java serializes `date` (line 25 `private LocalDate date;`), `debit` (line 42) and `credit` (line 45). finance_screen.dart lines 328-329 read `final debit = (tx['debitAmount'] ?? 0).toDouble(); final credit = (tx['creditAmount'] ?? 0).toDouble();` — both keys never exist, so debit=credit=0, `isDebit` is always false, neither amount Text renders (guarded by `if (debit > …

**Files:** `mobile/apps/manager/lib/screens/finance_screen.dart` · `backend/src/main/java/com/datagami/rentaxis/domain/entity/FinancialTransaction.java`

**Fix:** Change finance_screen.dart to read tx['debit'], tx['credit'] and tx['date'] (the actual JSON keys produced by the entity).

### M2. Web bank-accounts page uses 'isDefault' but Jackson serializes/deserializes the property as 'default' — default flag can never be set and is silently cleared on edit
**Endpoint:** `GET/POST/PUT /api/v1/bank-accounts` · **Affects:** web, backend · **Area:** accounting

BankAccount.java line 42 `private boolean isDefault` with Lombok @Getter/@Setter yields `isDefault()`/`setDefault()`, so Jackson names the JSON property `default` (BankAccountService.java line 48 confirms: `existing.setDefault(updates.isDefault());`). page.tsx line 313 renders the badge from `ba.isDefault` (always undefined — badge never shows) and lines 139-147 send `isDefault: formData.isDefault…

**Files:** `web/src/app/[locale]/dashboard/finance/bank-accounts/page.tsx` · `backend/src/main/java/com/datagami/rentaxis/domain/entity/BankAccount.java` · `backend/src/main/java/com/datagami/rentaxis/core/service/BankAccountService.java`

**Fix:** Either add @JsonProperty("isDefault") on the entity field (and keep web as-is), or change the web page to read/send `default`. Same for the web `active` display which relies on the correctly-named `active` property (that one is fine).

### M3. Web bank-account form has a Notes field that does not exist on the backend entity — user-entered notes are silently discarded
**Endpoint:** `POST/PUT /api/v1/bank-accounts` · **Affects:** web · **Area:** accounting

page.tsx renders a Notes textarea (lines 497-507), includes `notes` in the POST/PUT body (line 146) and declares `notes: string` on the BankAccount type (line 37). BankAccount.java (lines 18-45) has no notes field — only bankName, accountNumber, iban, branchName, currency, property, coaAccount, isDefault, isActive. Jackson silently drops the unknown property, the value is never persisted, and open…

**Files:** `web/src/app/[locale]/dashboard/finance/bank-accounts/page.tsx` · `backend/src/main/java/com/datagami/rentaxis/domain/entity/BankAccount.java`

**Fix:** Either add a `notes` column/field to BankAccount (with a Liquibase changeset) or remove the Notes field from the web form.

### M4. Mobile admin trial balance screen reads data['accounts']/data['rows'] but the DTO field is 'lines' — line items never render
**Endpoint:** `GET /api/v1/finance/reports/trial-balance` · **Affects:** mobile_admin · **Area:** accounting

TrialBalanceDTO.java line 12 declares `private List<TrialBalanceLine> lines;`. report_detail_screen.dart line 186 reads `data['accounts'] ?? data['rows'] ?? []` — both keys nonexistent, so the account list is always empty; only totalDebit/totalCredit (lines 188-189, correctly named) display. The per-account rows at lines 236-248 (which would correctly read debit/credit/accountName) are never reach…

**Files:** `mobile/apps/manager/lib/screens/report_detail_screen.dart` · `backend/src/main/java/com/datagami/rentaxis/api/dto/TrialBalanceDTO.java`

**Fix:** Read `data['lines']` in _buildTrialBalanceReport.

### M5. Mobile admin VAT return screen reads outputVat/inputVat/details — Output and Input VAT cards always show 0 and line details never render
**Endpoint:** `GET /api/v1/finance/reports/vat-return` · **Affects:** mobile_admin · **Area:** accounting

VatReturnDTO.java exposes `totalOutputVat` (line 12), `totalInputVat` (line 13), `salesLines` (line 17) and `purchaseLines` (line 18). report_detail_screen.dart lines 326-333 read `data['outputVat'] ?? data['vatOutput'] ?? 0` and `data['inputVat'] ?? data['vatInput'] ?? 0` (both always 0) and `data['details'] ?? data['rows'] ?? []` (always empty). Only `netVatPayable` matches via the fallback at l…

**Files:** `mobile/apps/manager/lib/screens/report_detail_screen.dart` · `backend/src/main/java/com/datagami/rentaxis/api/dto/VatReturnDTO.java`

**Fix:** Read totalOutputVat/totalInputVat and render salesLines + purchaseLines (fields: description, taxableAmount, vatAmount).

### M6. Mobile admin account-mappings screen reads mapping['accountName']/mapping['accountCode'] which do not exist on AccountMappingDTO — every row shows '-'
**Endpoint:** `GET /api/v1/finance/account-mappings` · **Affects:** mobile_admin · **Area:** accounting

AccountMappingDTO.java (lines 9-16) has debitAccountId/debitAccountCode/debitAccountName and creditAccountId/creditAccountCode/creditAccountName — no plain accountName/accountCode. account_mappings_screen.dart lines 212-213 read `mapping['accountName'] ?? '-'` and `mapping['accountCode'] ?? ''`, so every expanded mapping renders title '-' with no code, and the debit-vs-credit distinction is lost e…

**Files:** `mobile/apps/manager/lib/screens/account_mappings_screen.dart` · `backend/src/main/java/com/datagami/rentaxis/api/dto/AccountMappingDTO.java`

**Fix:** Render two lines per mapping: debitAccountCode/debitAccountName (Debit) and creditAccountCode/creditAccountName (Credit).

### M7. Mobile admin bank-accounts screen reads branch/propertyName/isDefault/swiftCode — branch, linked property and Default badge never display
**Endpoint:** `GET /api/v1/bank-accounts` · **Affects:** mobile_admin, backend · **Area:** accounting

bank_accounts_screen.dart reads `account['branch']` (line 41; backend field is `branchName`, line 28 of BankAccount.java), `account['propertyName']` (lines 42/216; backend serializes a nested `property` object with nameEn/nameAr, no propertyName), `account['isDefault'] == true` (line 217; JSON key is `default` per Lombok/Jackson naming), and `account['swiftCode']` (line 40; no such field exists on…

**Files:** `mobile/apps/manager/lib/screens/bank_accounts_screen.dart` · `backend/src/main/java/com/datagami/rentaxis/domain/entity/BankAccount.java`

**Fix:** Read branchName, property?['nameEn']/['nameAr'], and `default`; drop swiftCode or add the field to the backend.

### M8. Manager app exposes all finance screens to PROPERTY_MANAGER users, but every endpoint requires SUPER_ADMIN/TENANT_ADMIN — guaranteed 403s with a generic error
**Endpoint:** `All endpoints of AccountController, AccountMappingController, BankAccountController, FinancialTransactionController` · **Affects:** mobile_admin · **Area:** accounting

All four controllers carry class-level @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')") (AccountController.java:17, AccountMappingController.java:19, BankAccountController.java:13, FinancialTransactionController.java:22). The manager app is used by PROPERTY_MANAGER accounts (more_screen.dart lines 679-680 render that role's label) yet the Finance menu (more_screen.dart lines 94-116: /fin…

**Files:** `mobile/apps/manager/lib/screens/more_screen.dart` · `backend/src/main/java/com/datagami/rentaxis/api/AccountController.java` · `web/src/lib/rbac.ts`

**Fix:** Gate the Finance section of more_screen (and the /finance, /bank-accounts, /finance-reports, /settings/mappings routes) on authState.role in {SUPER_ADMIN, TENANT_ADMIN}, mirroring web's canAccessFinance.

### M9. Web account edit modal lets users change code/accountType/parentCode/group but AccountService.updateAccount silently ignores those fields
**Endpoint:** `PUT /api/v1/finance/accounts/{id}` · **Affects:** web, backend · **Area:** accounting

accounts/page.tsx handleUpdate (lines 242-252) sends code, accountType, parentCode and group in the PUT body via the shared edit form (all editable, lines 333-417). AccountService.updateAccount (lines 186-201) copies only name/nameEn/nameAr/description/accountSubType/active/displayOrder — code, accountType, parentCode and group changes are silently dropped, and the page shows success (modal closes…

**Files:** `web/src/app/[locale]/dashboard/finance/accounts/page.tsx` · `backend/src/main/java/com/datagami/rentaxis/core/service/AccountService.java`

**Fix:** Either apply code/accountType/parentCode/group in updateAccount (with validation) or make those inputs read-only in the edit modal; consider making updateAccount ignore absent fields instead of copying defaults.

### M10. Transactions list filters are silently ignored by the backend for realistic combinations (accountType with propertyId; single-sided date range)
**Endpoint:** `GET /api/v1/finance/transactions` · **Affects:** web, mobile_admin · **Area:** accounting

FinancialTransactionService.getTransactions (lines 239-263) applies filters in exclusive priority order: date filters only apply when BOTH startDate and endDate are present, and accountType is only consulted when propertyId/unitId are absent. The web filter UI (transactions/page.tsx lines 151-170, 494-519) lets the user set propertyId + accountType, or a startDate alone, and sends whatever is set;…

**Files:** `web/src/app/[locale]/dashboard/finance/transactions/page.tsx` · `backend/src/main/java/com/datagami/rentaxis/core/service/FinancialTransactionService.java`

**Fix:** Either implement combinable filtering in the backend (Specification/QueryDSL) or have the frontends enforce the supported combinations (require both dates, disable accountType when property selected).

### M11. Web chart-of-accounts CRUD shows no error feedback — realistic failures (delete with children, edit system account) return 500 and the UI does nothing
**Endpoint:** `POST/PUT/DELETE /api/v1/finance/accounts, POST /seed, POST /import` · **Affects:** web · **Area:** accounting

AccountService throws bare RuntimeException for 'System accounts cannot be modified/deleted' and 'Cannot delete account with child accounts' (AccountService.java lines 191, 208, 211), which GlobalExceptionHandler maps to 500 with a message body (lines 133-142). accounts/page.tsx handleCreate/handleUpdate/handleDelete/handleSeedDefaults/handleImport (lines 189-319) only act `if (res.ok)` and never …

**Files:** `web/src/app/[locale]/dashboard/finance/accounts/page.tsx` · `backend/src/main/java/com/datagami/rentaxis/api/GlobalExceptionHandler.java`

**Fix:** Surface the response `message` field on failure (the backend always returns {error, message, status}); ideally also convert those service RuntimeExceptions to BusinessRuleViolationException for proper 400/409 codes.

### M12. Mobile admin vendor ledger shows a Balance column that always reads 0.00 — no balance field exists in the response
**Endpoint:** `GET /api/v1/finance/ledger/vendor/{vendorId}` · **Affects:** mobile_admin · **Area:** accounting

getVendorLedger (FinancialTransactionService.java lines 558-564) returns raw FinancialTransaction entities — there is no balance or runningBalance field on the entity. report_detail_screen.dart line 420 reads `(tx['balance'] ?? tx['runningBalance'] ?? 0).toDouble()` and renders it under a 'Balance' label (lines 486-509), so every ledger row shows Balance 0.00 next to correct debit/credit values — …

**Files:** `mobile/apps/manager/lib/screens/report_detail_screen.dart` · `backend/src/main/java/com/datagami/rentaxis/core/service/FinancialTransactionService.java`

**Fix:** Compute the running balance client-side from debit/credit, or drop the Balance column, or add a running balance to the backend response.

### M13. Mobile password login cannot complete the backend's 409 multi-tenant disambiguation flow — multi-tenant users are locked out with 'Invalid email or password'
**Endpoint:** `POST /api/auth/login` · **Affects:** mobile_admin, mobile_renter · **Area:** auth-users

AuthController.java:44 documents `tenantId` as the disambiguator and :159-173 returns 409 CONFLICT with LoginAmbiguousResponse{tenants:[{tenantId,tenantName}]} when the password matches in multiple tenants, expecting the client to re-submit with tenantId. auth_service.dart:16-22 sends only `{'email': ..., 'password': ...}` — there is no tenantId parameter anywhere in the mobile stack — and auth_pr…

**Files:** `mobile/packages/rentaxis_core/lib/api/services/auth_service.dart` · `mobile/packages/rentaxis_core/lib/providers/auth_provider.dart` · `backend/src/main/java/com/datagami/rentaxis/api/AuthController.java`

**Fix:** Add an optional tenantId to AuthService.login, catch status 409 in AuthNotifier.login, surface the returned tenants list as a picker, and re-submit with the chosen tenantId — mirroring the web flow.

### M14. Ticket-detail 'Assign To...' silently broken for PROPERTY_MANAGER: page fetches /api/admin/users, which UserController restricts to SUPER_ADMIN/TENANT_ADMIN
**Endpoint:** `GET /api/admin/users` · **Affects:** web · **Area:** auth-users

tickets/[id]/page.tsx:149-158: `if (userRole === "RENTER") return;` then `fetch("/api/proxy/admin/users")` with the failure swallowed (`catch { /* ignore */ }` and no else on !res.ok). UserController.java:21-22 is `@RequestMapping("/api/admin/users") @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")`, so a PROPERTY_MANAGER (or TENANT_USER) gets 403 on every ticket-detail load. Yet page.ts…

**Files:** `web/src/app/[locale]/dashboard/tickets/[id]/page.tsx` · `backend/src/main/java/com/datagami/rentaxis/api/UserController.java`

**Fix:** Gate fetchStaff on hasRole(userRole, ['SUPER_ADMIN','TENANT_ADMIN']) and give PMs a PM-accessible assignee source (e.g. a tenant-scoped assignable-users endpoint), or widen the controller guard deliberately. Also stop hiding the 403: surface why assignment is unavailable.

### M15. Editing a PROPERTY_MANAGER and removing all their properties silently does nothing: empty propertyIds is omitted, and the backend treats null as 'skip sync'
**Endpoint:** `PUT /api/admin/users/{id}` · **Affects:** web · **Area:** auth-users

page.tsx:129-131: `if (role === 'PROPERTY_MANAGER' && selectedPropertyIds.length > 0) { bodyData.propertyIds = selectedPropertyIds; }` — an empty selection is never sent. UserController.java:101 only syncs when `request.propertyIds() != null`: removals are computed against the submitted list (:102-107), so a null list skips the whole block. Concrete scenario: admin edits a PM, removes the last ass…

**Files:** `web/src/app/[locale]/superadmin/users/page.tsx` · `backend/src/main/java/com/datagami/rentaxis/api/UserController.java`

**Fix:** Send `bodyData.propertyIds = selectedPropertyIds` unconditionally whenever role === 'PROPERTY_MANAGER' (including []), which the backend already interprets as 'remove everything not listed'.

### M16. AuthNotifier._init falls back to tenants[0]['tenantId'], a field /api/auth/me/tenants does not return (keys are id/name/slug)
**Endpoint:** `GET /api/auth/me/tenants` · **Affects:** mobile_admin, mobile_renter, mobile_security · **Area:** auth-users

auth_provider.dart:129-131 restores a session with `tenantId: savedTenantId ?? (tenants.isNotEmpty ? tenants[0]['tenantId'] : null)`. The endpoint's DTO is `record TenantInfo(String id, String name, String slug)` (AuthController.java:309), so `tenants[0]['tenantId']` is always null and the fallback can never produce a tenant. The correct key is used two functions away in resolveTenantSlug (auth_pr…

**Files:** `mobile/packages/rentaxis_core/lib/providers/auth_provider.dart` · `backend/src/main/java/com/datagami/rentaxis/api/AuthController.java`

**Fix:** Use `tenants[0]['id']` in the fallback, and consider persisting it back to storage so subsequent restores are consistent.

### M17. Superadmin users page swallows all API errors: failed create/update/delete (400 duplicate email, guard-phone conflict, 403) leaves the form open with no message
**Endpoint:** `POST /api/admin/users (also PUT /{id}, DELETE /{id})` · **Affects:** web · **Area:** auth-users

page.tsx:138-152 handleSubmitUser: `if (res.ok) { setShowForm(false); ... }` with no else branch — a 400 from IllegalArgumentException ("A user with this email already exists in this tenant.", UserService.createUser:187-192; guard-phone-taken :198-201) or a 403 from the role-hierarchy check produces no UI feedback at all; the spinner stops and the form sits there. Same pattern in confirmDelete (:1…

**Files:** `web/src/app/[locale]/superadmin/users/page.tsx` · `backend/src/main/java/com/datagami/rentaxis/api/GlobalExceptionHandler.java`

**Fix:** On !res.ok, parse the JSON body's message/error field and render it in the form (and in the delete dialog); only close on success.

### M18. Manager app 'Staff' menu entry is not role-gated: a PROPERTY_MANAGER always gets 403 from the TENANT_ADMIN-only StaffController and sees a bare 'Failed to load staff'
**Endpoint:** `GET /api/v1/staff` · **Affects:** mobile_admin · **Area:** auth-users

StaffController.java:13 gates the whole controller with `@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")`. more_screen.dart:41-46 shows the Staff row to every manager-app user with no role check (`_MenuRow(... label: l.staff, onTap: () => context.push('/staff'))`), and the manager app is also used by PROPERTY_MANAGERs (the same file's guard/gate sections exist for them). staff_screen.da…

**Files:** `mobile/apps/manager/lib/screens/more_screen.dart` · `backend/src/main/java/com/datagami/rentaxis/api/StaffController.java`

**Fix:** Hide the Staff menu row unless authState.role is TENANT_ADMIN or SUPER_ADMIN (the role is already in AuthState), matching the controller's guard.

### M19. Web renter portal leaves stale actionable rows after 400 on cancel/release
**Endpoint:** `POST /api/v1/bookings/{id}/cancel (also POST /api/v1/bookings/{id}/release)` · **Affects:** web · **Area:** facilities

web page.tsx runPendingAction lines 199-204: `} catch (err) { setPendingAction(null); setActionLoading(false); setError(err instanceof ApiError ? err.message : t("requestError")); return; }` — the error path returns without calling loadFacilities()/loadBookings() (the refresh at lines 207-211 only runs on success). Backend BookingService.java: cancel() line 236 calls requirePending(booking), which…

**Files:** `web/src/app/[locale]/dashboard/renter-portal/facilities/page.tsx` · `backend/src/main/java/com/datagami/rentaxis/core/service/BookingService.java` · `mobile/apps/renter/lib/screens/facilities/my_requests_screen.dart`

**Fix:** In runPendingAction's catch, when err is an ApiError with status 400 (and 409), re-run Promise.all([loadFacilities(), loadBookings()]) before returning, and show a localized 'this request changed state' message instead of the raw backend English string — mirroring the mobile renter app's 400 branch.

### M20. Manager app recalculate-penalties calls a nonexistent endpoint (POST /api/v1/leases/{id}/penalties/recalculate)
**Endpoint:** `POST /api/v1/leases/{leaseId}/penalties/recalculate (does not exist)` · **Affects:** mobile_admin · **Area:** fines-rent

penalty_service.dart:41 `final response = await _dio.post('/v1/leases/$leaseId/penalties/recalculate');` invoked from lease_penalties_screen.dart:122-124 (_recalculatePenalties, wired to an AppBar action at line 233). No controller in backend/src/main/java/com/datagami/rentaxis/api maps any 'recalculate' path — grep for 'recalculate' only matches the service method PenaltyService.recalculateForLea…

**Files:** `mobile/packages/rentaxis_core/lib/api/services/penalty_service.dart` · `mobile/apps/manager/lib/screens/lease_penalties_screen.dart`

**Fix:** Either add a backend endpoint (e.g. POST /api/v1/penalties/recalculate?leaseId= delegating to PenaltyService.recalculateForLease with an SA/TA/PM guard) or remove the recalculate action from the manager app.

### M21. Manager waive sends empty body when reason is blank, but backend requires @NotBlank reason
**Endpoint:** `POST /api/v1/penalties/{id}/waive` · **Affects:** mobile_admin · **Area:** fines-rent

Backend contract: WaivePenaltyRequestDTO is `record WaivePenaltyRequestDTO(@NotBlank String reason)` and the controller validates with @Valid (PenaltyController.java:95). Mobile: penalty_service.dart:34-36 sends `{ if (reason != null) 'reason': reason }` — i.e. `{}` when reason is null — and the screen passes `reason: reasonCtrl.text.isNotEmpty ? reasonCtrl.text : null` (lease_penalties_screen.dar…

**Files:** `mobile/packages/rentaxis_core/lib/api/services/penalty_service.dart` · `mobile/apps/manager/lib/screens/lease_penalties_screen.dart` · `backend/src/main/java/com/datagami/rentaxis/api/dto/WaivePenaltyRequestDTO.java`

**Fix:** Make the reason mandatory in the manager waive dialog (disable confirm until non-empty) and always send the 'reason' key; align with the web dialog behavior.

### M22. Manager rent-settings screen renders fields that do not exist in RentCollectionSettingsDTO
**Endpoint:** `GET /api/v1/rent-settings/{propertyId}` · **Affects:** mobile_admin · **Area:** fines-rent

RentCollectionSettingsDTO fields are id, propertyId, dueDayOfMonth, gracePeriodDays, penaltyType, penaltyAmount, onlinePaymentEnabled plus five fine* override fields. rent_settings_screen.dart reads `settings['penaltyRate']` (line 325) — always null, so the 'Late Payment Penalty Rate' row permanently renders '-%' via `percent(dynamic n) => '${n ?? '-'}%'` (line 436) even when penaltyType is FIXED_…

**Files:** `mobile/apps/manager/lib/screens/rent_settings_screen.dart` · `backend/src/main/java/com/datagami/rentaxis/api/dto/RentCollectionSettingsDTO.java`

**Fix:** Read penaltyAmount (labelled per penaltyType: AED/day vs %/day), and drop or re-source the autoApplyPenalty/reminderDaysBefore rows; consider also showing dueDayOfMonth and onlinePaymentEnabled.

### M23. Web lease page 'Total Outstanding Penalties' banner sums base fines and includes cleared penalties instead of using the DTO's outstanding field
**Endpoint:** `GET /api/v1/penalties` · **Affects:** web · **Area:** fines-rent

leases/[id]/page.tsx:797-804: `const activePenalties = penalties.filter(p => !p.waived); const totalOutstanding = activePenalties.reduce((sum, p) => sum + p.penaltyAmount, 0);` rendered as 'Total Outstanding Penalties: {formatCurrency(totalOutstanding)}'. PenaltyDTO provides `outstanding` (currentTotal minus recorded payments; 0 once cleared) and `clearedAt`. This banner (a) counts fully-paid CLEA…

**Files:** `web/src/app/[locale]/dashboard/leases/[id]/page.tsx`

**Fix:** Compute the banner from the DTO: filter on derived status OPEN (clearedAt == null) and sum p.outstanding (or p.currentTotal).

### M24. Web shows the Waive button to PROPERTY_MANAGER but the endpoint only allows SUPER_ADMIN/TENANT_ADMIN
**Endpoint:** `POST /api/v1/penalties/{id}/waive` · **Affects:** web · **Area:** fines-rent

Backend: PenaltyController.java:92 `@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")` on waive. Web: leases/[id]/page.tsx:153 `const isAdmin = hasRole(userRole, ["SUPER_ADMIN", "TENANT_ADMIN", "PROPERTY_MANAGER"]);` and the Waive button is rendered behind `isAdmin` (lines 909-915). A PROPERTY_MANAGER therefore sees and can submit the waive modal but the backend will reject with 403 (once…

**Files:** `web/src/app/[locale]/dashboard/leases/[id]/page.tsx` · `backend/src/main/java/com/datagami/rentaxis/api/PenaltyController.java`

**Fix:** Gate the Waive button (and modal) on hasRole(userRole, ['SUPER_ADMIN','TENANT_ADMIN']) to match the endpoint, or relax the backend guard if PMs are meant to waive.

### M25. Manager app exposes Rent Collection Settings to PROPERTY_MANAGER, but the endpoint is SUPER_ADMIN/TENANT_ADMIN-only
**Endpoint:** `GET /api/v1/rent-settings/{propertyId}` · **Affects:** mobile_admin · **Area:** fines-rent

RentCollectionSettingsController.java:20 `@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")` on GET. The manager app serves PROPERTY_MANAGER users (staff created with role PROPERTY_MANAGER, staff_screen.dart:225) and the navigation path more_screen.dart:142 (`context.push('/settings')`) → settings_hub_screen.dart:29 (`context.push('/settings/rent')`) → rent_settings_screen.dart:45 (`servi…

**Files:** `mobile/apps/manager/lib/screens/settings_hub_screen.dart` · `mobile/apps/manager/lib/screens/rent_settings_screen.dart` · `backend/src/main/java/com/datagami/rentaxis/api/RentCollectionSettingsController.java`

**Fix:** Hide the Rent Collection Settings row in SettingsHubScreen for non-TENANT_ADMIN roles (authState.role), or add PROPERTY_MANAGER to the backend guard if this read-only view is intended for PMs; at minimum map 403 to a distinct 'no permission' message.

### M26. Web rent-settings page depends on res.json() throwing to handle the documented 204 No Content response
**Endpoint:** `GET /api/v1/rent-settings/{propertyId}` · **Affects:** web · **Area:** fines-rent

Backend returns 204 with an empty body when no settings exist: RentCollectionSettingsController.java:23-25 `if (settings == null) { return ResponseEntity.noContent().build(); }`. Web fetchSettings (rent-settings/page.tsx:139-163): `if (res.ok) { const data = await res.json(); ... }` — 204 satisfies res.ok, res.json() then rejects on the empty body, and only the catch block (lines 155-160, commente…

**Files:** `web/src/app/[locale]/dashboard/settings/rent-settings/page.tsx` · `backend/src/main/java/com/datagami/rentaxis/api/RentCollectionSettingsController.java`

**Fix:** Handle 204 explicitly before parsing: `if (res.status === 204) { setSettings({...DEFAULT_SETTINGS, propertyId}); return; }`, and drop the dead 404 branch.

### M27. Registered gate vendors can never be revoked or time-bounded from any frontend
**Endpoint:** `PUT /api/v1/gatepass/visitors/{profileId}/registration` · **Affects:** mobile_admin, backend · **Area:** gate

Backend: GateWalkInController.java:215-224 `@PutMapping("/visitors/{profileId}/registration") public void registerVisitor(@PathVariable UUID profileId, @RequestBody RegistrationRequest request)` with `RegistrationRequest(UUID unitId, Instant validFrom, Instant validTo, boolean active)` (GateWalkInDtos.java:67) is the ONLY API that can set `active=false` or add validFrom/validTo bounds to an existi…

**Files:** `backend/src/main/java/com/datagami/rentaxis/api/GateWalkInController.java` · `mobile/apps/manager/lib/screens/gatepass/register_gate_vendor_screen.dart` · `mobile/packages/rentaxis_core/lib/api/services/gate_pass_service.dart`

**Fix:** Add a manager surface (mobile_admin, and/or web) that lists a unit's visitor registrations and calls PUT /v1/gatepass/visitors/{profileId}/registration with active=false or explicit validFrom/validTo — add the corresponding wrapper to rentaxis_core's GatePassApiService. If revocation is intentionally out of scope for now, document the gap; do not delete the endpoint, since it is the only revoke mechanism.

### M28. Manager app exposes TENANT_ADMIN-only lease actions (activate, extend, generate/preview contract) to PROPERTY_MANAGER with no role gating
**Endpoint:** `PUT /api/v1/leases/{id}/activate, POST /api/v1/leases/{id}/extend, POST /api/v1/leases/{id}/generate-contract, POST /api/v1/leases/{id}/generate-contract/preview` · **Affects:** mobile_admin · **Area:** lease-core

Backend restricts all four to SUPER_ADMIN/TENANT_ADMIN only: LeaseController.java:102 (activate), :169 (extend), :185 (generate-contract), :191 (preview). The manager app renders these actions unconditionally — lease_detail_screen.dart:148 `activateLease(widget.leaseId)` behind the Activate button (line 566), :437-439 extendLease from the extend dialog (menu item at 671/692), :289-290 previewContr…

**Files:** `backend/src/main/java/com/datagami/rentaxis/api/LeaseController.java` · `mobile/apps/manager/lib/screens/lease_detail_screen.dart`

**Fix:** Either add PROPERTY_MANAGER to these @PreAuthorize lists if PMs are meant to perform them (the web wizard flow suggests they are TA-only by design), or hide/disable the buttons in the manager app when the stored role is PROPERTY_MANAGER, and map 403 to a permission-specific message.

### M29. Web interactions panel has no response/error handling — spinner sticks forever on failure, silently empty on 403
**Endpoint:** `GET /api/v1/leases/{leaseId}/interactions` · **Affects:** web · **Area:** lease-core

LeaseInteractionsPanel.tsx:27-33: `const res = await fetch(...); const body = await res.json(); setItems(body.content ?? []); setLoading(false);` — no res.ok check and no try/catch, invoked as `void load()` (line 35). A network error or a non-JSON error response (e.g. proxy 502 HTML makes res.json() throw) rejects the promise before `setLoading(false)`, leaving the panel on the loading state perma…

**Files:** `web/src/components/leases/LeaseInteractionsPanel.tsx`

**Fix:** Wrap load() in try/catch/finally, check res.ok, and render an error state distinct from the empty state.

### M30. Settlement fetch conflates every failure with 'no settlement yet' and silently swaps in the blank preview flow
**Endpoint:** `GET /api/v1/leases/{id}/settlement (fallback to GET /{id}/settlement/preview)` · **Affects:** web, mobile_admin · **Area:** lease-core

Backend getSettlement (LeaseController.java:134-142) returns 404 only for NotFoundException; other failures are 5xx/403. But web settlement/page.tsx:254-307 falls through to the preview branch on ANY non-ok or thrown error, and lease_settlement_screen.dart:205-237 does the same with a bare `catch (_) { // No settlement yet — load preview }`. On a transient 500/403 while a DRAFT settlement exists, …

**Files:** `web/src/app/[locale]/dashboard/leases/[id]/settlement/page.tsx` · `mobile/apps/manager/lib/screens/lease_settlement_screen.dart` · `backend/src/main/java/com/datagami/rentaxis/api/LeaseController.java`

**Fix:** Only fall back to the preview flow on an actual 404 (web: `res.status === 404`; mobile: check `DioException.response?.statusCode == 404`); surface every other failure as an error state.

### M31. Web media reorder sends a raw JSON array but backend expects {"mediaIds": [...]} — guaranteed 400, silently swallowed
**Endpoint:** `PUT /api/listings/{id}/media/reorder` · **Affects:** web · **Area:** listings

Backend UnitListingController.java:149-157: `@PutMapping("/{id}/media/reorder") public ResponseEntity<Void> reorderMedia(@PathVariable UUID id, @RequestBody ReorderRequest body)` with `public record ReorderRequest(List<UUID> mediaIds)` — Jackson deserializes records in properties mode, so the payload must be an object `{"mediaIds": [...]}`. Web listings.ts:241-245 sends `body: JSON.stringify(media…

**Files:** `web/src/lib/api/listings.ts` · `web/src/app/[locale]/dashboard/listings/[id]/page.tsx` · `backend/src/main/java/com/datagami/rentaxis/api/UnitListingController.java`

**Fix:** Change web reorderMedia to send `body: JSON.stringify({ mediaIds })`, and surface the error in handleMoveMedia instead of the empty catch (revert local state on failure). Add a backend controller test covering the reorder payload shape.

### M32. Web marketplace bedrooms filter sends query param 'bedrooms' but backend reads 'minBedrooms' — filter is silently ignored
**Endpoint:** `GET /api/marketplace/{tenantSlug}/listings` · **Affects:** web · **Area:** listings

Backend MarketplaceController.java:72 declares `@RequestParam(required = false) Integer minBedrooms`; there is no 'bedrooms' parameter. Web listings.ts:56 sends `q.set('bedrooms', String(params.bedrooms))`, driven by the filter UI in marketplace/[tenantSlug]/page.tsx:387 `bedrooms: bedroomsParam ? Number(bedroomsParam) : undefined`. Spring silently drops the unknown parameter, so a renter selectin…

**Files:** `web/src/lib/api/listings.ts` · `web/src/app/[locale]/marketplace/[tenantSlug]/page.tsx` · `backend/src/main/java/com/datagami/rentaxis/api/MarketplaceController.java`

**Fix:** Rename the web query param to `minBedrooms` in fetchMarketplaceListings (and decide whether the UI semantic is exact-match or minimum; backend implements >= minBedrooms).

### M33. Web marketplace furnishing multi-select joins values with commas into a single-enum param — guaranteed 400 when 2+ options checked
**Endpoint:** `GET /api/marketplace/{tenantSlug}/listings` · **Affects:** web · **Area:** listings

The FilterPanel renders furnishing as checkboxes allowing multiple selections (marketplace/[tenantSlug]/page.tsx:203-205 toggleFurnishing, 260-272), and handleApplyFilters line 458 sends `furnishing: filters.furnishing.join(',')`; listings.ts:59 passes it as one query param. Backend MarketplaceController.java:75 declares `@RequestParam(required = false) Furnishing furnishing` — a single enum. Spri…

**Files:** `web/src/app/[locale]/marketplace/[tenantSlug]/page.tsx` · `web/src/lib/api/listings.ts` · `backend/src/main/java/com/datagami/rentaxis/api/MarketplaceController.java`

**Fix:** Either make the UI single-select, or change the backend param to `List<Furnishing> furnishing` (IN predicate) and keep the comma-joined value.

### M34. Web marketplace 'Nearest first' sort sends sort=distance,asc — no such entity property, backend returns 500
**Endpoint:** `GET /api/marketplace/{tenantSlug}/listings` · **Affects:** web · **Area:** listings

marketplace/[tenantSlug]/page.tsx:480-485 adds `{ value: 'distance,asc', label: t('sortNearest') }` to sortOptions whenever geolocation succeeds, and the selected sort is sent verbatim (listings.ts:61 `q.set('sort', params.sort)`; loadListings line 392). The backend applies the Pageable sort directly to the JPA Specification query: MarketplaceService.java:43-46 `return listingRepository.findAll(sp…

**Files:** `web/src/app/[locale]/marketplace/[tenantSlug]/page.tsx` · `backend/src/main/java/com/datagami/rentaxis/core/service/MarketplaceService.java`

**Fix:** Remove the 'distance,asc' sort option (or implement server-side distance ordering using nearLat/nearLng); the backend should also whitelist sortable properties to avoid 500s on arbitrary sort params.

### M35. Map/distance features read lat/lng from UnitListingSummaryDTO, which has no such fields — renter map shows zero markers, web distance chips never render
**Endpoint:** `GET /api/marketplace/{tenantSlug}/listings` · **Affects:** web, mobile_renter, backend · **Area:** listings

UnitListingSummaryDTO.java defines exactly: id, title, propertyName, bedrooms, annualRent, status, coverPhotoUrl, interestsCount, updatedAt, slug — no lat/lng/bathrooms (see also MarketplaceController.java:174-199 toSummary). The renter app map builds markers from these summary maps: browse_map.dart:52-55 `final lat = listing['lat'] as num?; final lng = listing['lng'] as num?; ... if (lat == null …

**Files:** `mobile/apps/renter/lib/screens/browse/browse_map.dart` · `web/src/app/[locale]/marketplace/[tenantSlug]/page.tsx` · `backend/src/main/java/com/datagami/rentaxis/api/dto/UnitListingSummaryDTO.java`

**Fix:** Add lat/lng (and bathrooms) to UnitListingSummaryDTO for the marketplace list response (they are already on the entity and exposed in the detail DTO), or have the map/distance features fetch detail data. Until then the renter map view is dead weight.

### M36. Dashboard listings search box does nothing: web sends 'search', backend declares 'q' — and the backend ignores both q and propertyId anyway
**Endpoint:** `GET /api/listings` · **Affects:** web, backend · **Area:** listings

Backend UnitListingController.java:59-67 declares `@RequestParam(required = false) UUID propertyId, @RequestParam(required = false) String q` but calls `service.list(tenantId, status, pageable)` — UnitListingService.java:96 `public Page<UnitListing> list(UUID tenantId, ListingStatus statusFilter, Pageable pageable)` has no q/propertyId, so both params are dead. Web additionally sends the wrong nam…

**Files:** `web/src/lib/api/listings.ts` · `web/src/app/[locale]/dashboard/listings/page.tsx` · `backend/src/main/java/com/datagami/rentaxis/api/UnitListingController.java`

**Fix:** Implement q (title search) and propertyId filtering in UnitListingService.list, and rename the web param from 'search' to 'q' — or remove the dead params and the search box until implemented.

### M37. Web marketplace pages call the auth-gated proxy for logged-out visitors — middleware 401 surfaces as a generic 'failed to load' with no login redirect
**Endpoint:** `GET /api/marketplace/{tenantSlug}/listings` · **Affects:** web · **Area:** listings

proxy.ts:19-24: every /api/proxy request without a NextAuth token gets `new NextResponse('Unauthorized', { status: 401 })`. The marketplace browse page runs loadListings unconditionally on mount (marketplace/[tenantSlug]/page.tsx:379-421) even when `session` is null, so an unauthenticated visitor (e.g. following a shared /en/marketplace/<slug> URL) always gets the catch-all `setError(t('errorLoad'…

**Files:** `web/src/app/[locale]/marketplace/[tenantSlug]/page.tsx` · `web/src/proxy.ts`

**Fix:** On the marketplace browse/detail pages, detect sessionStatus === 'unauthenticated' and redirect to login with returnTo (matching the wishlist page), or route anonymous visitors to the public /l/{tenantSlug} pages which are designed for logged-out browsing.

### M38. Renter app auto-applies a 10 km near-me filter whose backend predicate silently excludes all listings without coordinates
**Endpoint:** `GET /api/marketplace/{tenantSlug}/listings` · **Affects:** mobile_renter, backend · **Area:** listings

browse_screen.dart:109-126 `_seedLocation()` sets nearLat/nearLng/radiusKm=10 automatically whenever the device grants location, and these are always sent (lines 75-77). Backend MarketplaceService.java:109-119 then adds `cb.between(root.get("lat"), latMin, latMax)` and the same for lng — SQL BETWEEN on a NULL lat/lng is never true, so every listing whose lat/lng was left empty (both optional in Un…

**Files:** `mobile/apps/renter/lib/screens/browse/browse_screen.dart` · `backend/src/main/java/com/datagami/rentaxis/core/service/MarketplaceService.java`

**Fix:** Either include null-coordinate listings when a radius filter is active (add `cb.isNull(root.get("lat"))` OR-branch), or don't auto-seed the radius filter — require an explicit user opt-in to 'near me'.

### M39. Mobile admin fetches listings and interests as a single page of 100 with no pagination — data beyond 100 rows is silently truncated
**Endpoint:** `GET /api/listings and GET /api/listings/{id}/interests` · **Affects:** mobile_admin · **Area:** listings

Both endpoints return a Spring Page. listings_list_screen.dart:12-13: `final data = await service.getListings(size: 100); return (data['content'] as List? ?? [])...` — totalElements/totalPages are never read and there is no page control, so a tenant with more than 100 listings can never see the rest. Same pattern in listing_interests_screen.dart:12-13 with `getInterests(listingId, size: 100)`. Con…

**Files:** `mobile/apps/manager/lib/screens/listings/listings_list_screen.dart` · `mobile/apps/manager/lib/screens/listings/listing_interests_screen.dart`

**Fix:** Add paging (infinite scroll or page controls) using the Page metadata already returned, or at minimum surface a truncation indicator when content.length == size.

### M40. Web notifications 'Unread' filter sends unreadOnly param the backend does not implement — tab shows read notifications
**Endpoint:** `GET /api/v1/notifications` · **Affects:** web · **Area:** misc

Backend: NotificationController.java:21-28 declares only `@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size` — a repo-wide grep for 'unreadOnly' in backend/src/main/java returns zero hits, so the parameter is silently ignored. Frontend: notifications/page.tsx:74-77 `let url = \`/api/proxy/v1/notifications?page=${page}&size=${itemsPerPage}\`; if (filter === "UN…

**Files:** `web/src/app/[locale]/dashboard/notifications/page.tsx` · `backend/src/main/java/com/datagami/rentaxis/api/NotificationController.java`

**Fix:** Either add an `unreadOnly` request param to NotificationController/NotificationService, or filter client-side (`data.filter(n => !n.isRead)`) until the backend supports it.

### M41. Web notifications pagination sets totalItems to the returned page length — pages beyond the first are unreachable
**Endpoint:** `GET /api/v1/notifications` · **Affects:** web · **Area:** misc

Backend returns a plain `List<NotificationDTO>` capped at `size` (NotificationController.java:23-27) with no total count. Frontend: notifications/page.tsx:81-83 `if (Array.isArray(data)) { setNotifications(data); setTotalItems(data.length); }` — totalItems is therefore always <= itemsPerPage. Pagination.tsx:24 computes `const totalPages = Math.ceil(totalItems / itemsPerPage);`, which is always 1 (…

**Files:** `web/src/app/[locale]/dashboard/notifications/page.tsx` · `web/src/components/ui/Pagination.tsx` · `backend/src/main/java/com/datagami/rentaxis/api/NotificationController.java`

**Fix:** Either return Spring's Page wrapper (content + totalElements) from the backend, or infer 'has more' client-side (returned length === size => enable next page) instead of using data.length as the total.

### M42. Manager app exposes Vendors to PROPERTY_MANAGER users but all vendor endpoints require SUPER_ADMIN/TENANT_ADMIN
**Endpoint:** `GET /api/v1/vendors (and all VendorController endpoints)` · **Affects:** mobile_admin · **Area:** misc

VendorController.java:14 has class-level `@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")` — PROPERTY_MANAGER is excluded. The manager app is used by PROPERTY_MANAGER accounts (profile_screen.dart:650-651 renders labels for both 'TENANT_ADMIN' and 'PROPERTY_MANAGER'), yet the More menu shows the Vendors entry unconditionally (more_screen.dart:106-110 `_MenuRow(icon: Icons.store_outlined…

**Files:** `backend/src/main/java/com/datagami/rentaxis/api/VendorController.java` · `mobile/apps/manager/lib/screens/more_screen.dart` · `mobile/apps/manager/lib/screens/finance_reports_screen.dart`

**Fix:** Either add PROPERTY_MANAGER to VendorController's @PreAuthorize (if PMs should manage vendors), or hide the Vendors menu row and vendor-ledger picker in the manager app when authState.role == 'PROPERTY_MANAGER', matching the web rbac gating.

### M43. Portfolio import status polling never terminates on error responses — polls a dead job forever
**Endpoint:** `GET /api/v1/import/portfolio/{jobId}/status` · **Affects:** web · **Area:** misc

Backend returns 404 when the job is missing or belongs to another tenant (PortfolioImportController.java:71-76 `.filter(job -> job.getTenantId().equals(TenantContextHolder.getTenantId())) ... .orElse(ResponseEntity.notFound().build())`), and 401/403 on session expiry. Frontend: properties/page.tsx:257-271 `const interval = setInterval(async () => { ... const res = await fetch(...); if (!res.ok) re…

**Files:** `web/src/app/[locale]/dashboard/properties/page.tsx` · `backend/src/main/java/com/datagami/rentaxis/api/PortfolioImportController.java`

**Fix:** Treat 404/401/403 as terminal (clearInterval + show a failure state), and add a max-poll timeout as a safety net.

### M44. Web vendor delete silently swallows the 400 'Cannot delete vendor with existing transactions' error
**Endpoint:** `DELETE /api/v1/vendors/{id}` · **Affects:** web · **Area:** misc

Backend: VendorService.java:58-66 `deleteVendor` throws `BusinessRuleViolationException("Cannot delete vendor with existing transactions")`, mapped to HTTP 400 with a `message` field by GlobalExceptionHandler.java:41-47 (NotFoundException maps to 404 at lines 32-38). Frontend: vendors/page.tsx:187-197 `onConfirm: async () => { setConfirmDialog(null); try { const res = await fetch(\`/api/proxy/v1/v…

**Files:** `web/src/app/[locale]/dashboard/finance/vendors/page.tsx` · `backend/src/main/java/com/datagami/rentaxis/core/service/VendorService.java` · `backend/src/main/java/com/datagami/rentaxis/api/GlobalExceptionHandler.java`

**Fix:** On !res.ok, parse the body and surface `message` (e.g. via the existing banner state) so the user learns why the delete was rejected.

### M45. Web vendor create/update ignores validation failures — modal stays open with no error message
**Endpoint:** `POST /api/v1/vendors and PUT /api/v1/vendors/{id}` · **Affects:** web · **Area:** misc

Backend validates with @Valid: Vendor.java has `@NotBlank` nameEn, `@Email` email (line 33-34) and `@Size(max = 20)` phone (line 38-39), producing 400 MethodArgumentNotValidException responses. Frontend: vendors/page.tsx:162-178 `const res = await fetch(url, { method, headers..., body: JSON.stringify(body) }); if (res.ok) { setShowModal(false); ... fetchVendors(); }` — no else branch and the catch…

**Files:** `web/src/app/[locale]/dashboard/finance/vendors/page.tsx` · `backend/src/main/java/com/datagami/rentaxis/domain/entity/Vendor.java`

**Fix:** Add an error state to the modal: on !res.ok parse the validation body and display the message next to the submit button.

### M46. Manager-app gateway screen reads four fields that do not exist in TenantGatewayConfigDTO — status badge always shows Inactive, provider always '-', Key ID row never renders
**Endpoint:** `GET /api/v1/gateway-config` · **Affects:** mobile_admin · **Area:** org-tenant

Backend response DTO (TenantGatewayConfigDTO.java:8-20, populated by TenantGatewayConfigService.mapToDTO lines 89-115) serializes exactly: id, gatewayId, gatewayCode, gatewayName, apiKeyMasked, hasWebhookSecret, isActive, isTestMode (apiKey/apiSecret/webhookSecret nulled). The manager screen reads keys that are not in that set: gateway_config_screen.dart:202-204 `config['active'] == true || config…

**Files:** `mobile/apps/manager/lib/screens/gateway_config_screen.dart` · `backend/src/main/java/com/datagami/rentaxis/api/dto/TenantGatewayConfigDTO.java` · `backend/src/main/java/com/datagami/rentaxis/core/service/TenantGatewayConfigService.java`

**Fix:** In _StatusCard use config['isActive'] == true; in _ConfigCard read config['gatewayCode'] for the provider row and config['apiKeyMasked'] for the key row (and drop the client-side re-masking — the backend already masks).

### M47. Superadmin 'Provision New Organization' form collects address, TRN, phone, logo and ticket-OTP toggle, but POST /api/admin/tenants persists only the name — all other entered values are silently dropped
**Endpoint:** `POST /api/admin/tenants` · **Affects:** web, backend · **Area:** org-tenant

The create modal shows Logo upload, Address, TRN, Phone and the Ticket Closure OTP toggle in create mode too (page.tsx:188-268; only Status is edit-only, line 243), and handleSubmit POSTs the full formData `{ name, address, trn, status, logoUrl, ticketOtpRequired, phone }` to /api/proxy/admin/tenants (page.tsx:61-68 with formData defined at line 26). The backend create handler reads only `payload.…

**Files:** `web/src/app/[locale]/superadmin/tenants/page.tsx` · `backend/src/main/java/com/datagami/rentaxis/api/LandlordOrgController.java` · `backend/src/main/java/com/datagami/rentaxis/core/service/LandlordOrgService.java`

**Fix:** Make createTenant apply the same optional fields as updateTenant (or have the web client chain a PUT after create). Alternatively hide the extra fields in create mode so the UI matches the contract.

### M48. Manager app lets PROPERTY_MANAGER open Settings > Payment Gateway, but both endpoints require SUPER_ADMIN/TENANT_ADMIN — guaranteed 403 shown as a generic load failure
**Endpoint:** `GET /api/v1/gateway-config and GET /api/v1/gateway-config/gateways` · **Affects:** mobile_admin · **Area:** org-tenant

Both reads are role-guarded: `@GetMapping("/gateways") @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")` (TenantGatewayConfigController.java:21-22) and `@GetMapping @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'RENTER')")` (lines 27-28). The manager app is also used by PROPERTY_MANAGER accounts (e.g. booking_approvals_screen.dart:129 checks `authProvider.role == 'PROPERTY_MAN…

**Files:** `mobile/apps/manager/lib/screens/settings_hub_screen.dart` · `mobile/apps/manager/lib/screens/gateway_config_screen.dart` · `backend/src/main/java/com/datagami/rentaxis/api/TenantGatewayConfigController.java`

**Fix:** Gate the gateway tile (and screen) on role == TENANT_ADMIN/SUPER_ADMIN like the web sidebar does, or map DioException status 403 to a dedicated 'no permission' state instead of the retryable generic error.

### M49. Superadmin tenants page swallows all API errors on create/update — non-2xx responses close nothing, show nothing
**Endpoint:** `POST /api/admin/tenants and PUT /api/admin/tenants/{id}` · **Affects:** web · **Area:** org-tenant

handleSubmit (page.tsx:56-78) only handles success: `if (res.ok) { resetForm(); fetchTenants(); }` — the backend's documented failure statuses (400 from POST when name is blank, LandlordOrgController.java:32-34; 404 from PUT when the id no longer exists, LandlordOrgController.java:49-51; 401/403 on session expiry) leave the modal open with no error banner, no message, and the button simply stops s…

**Files:** `web/src/app/[locale]/superadmin/tenants/page.tsx`

**Fix:** On !res.ok set an error state rendered in the modal (mirroring the gateway page's error banner), at minimum distinguishing validation failure from permission/session failure.

### M50. Manager app status strip reads nonexistent summary fields depositedAmount and bouncedAmount — always displays AED 0
**Endpoint:** `GET /api/v1/payments/summary` · **Affects:** mobile_admin, backend · **Area:** payments

PaymentSummaryDTO's only amount fields are totalAmount, pendingAmount, collectedAmount, clearedAmount, overdueAmount (PaymentSummaryDTO.java:9-21); the service populates exactly those setters (PaymentScheduleService.java:450-461) — there is no depositedAmount or bouncedAmount in the response. The manager payments screen's _StatusStrip reads "((summary['depositedAmount'] ?? 0) as num).toDouble()" (…

**Files:** `mobile/apps/manager/lib/screens/payments_screen.dart` · `backend/src/main/java/com/datagami/rentaxis/api/dto/PaymentSummaryDTO.java` · `backend/src/main/java/com/datagami/rentaxis/core/service/PaymentScheduleService.java`

**Fix:** Either add depositedAmount and bouncedAmount to PaymentSummaryDTO and populate them in PaymentScheduleService.getSummary (counts are already tracked, so the amounts are cheap to accumulate), or drop the amount line from those two tiles in the mobile status strip. Adding the DTO fields is the better fix since the UI clearly intends to show them.

### M51. Finance payments page inline collect/replace modal and deposit/clear handlers swallow error responses — silent failure on 400s
**Endpoint:** `PUT /api/v1/payments/{id}/collect, /deposit, /clear; POST /api/v1/payments/{id}/replace` · **Affects:** web · **Area:** payments

These endpoints return 400 with {"message": ...} via GlobalExceptionHandler's BusinessRuleViolationException handler (GlobalExceptionHandler.java:41-47) for state-machine violations (e.g. depositing a non-COLLECTED cheque, clearing a non-DEPOSITED one). The finance payments page renders its own inline modal (page.tsx:681, not the shared CollectChequeDialog) whose submitCollect only does 'if (res.o…

**Files:** `web/src/app/[locale]/dashboard/finance/payments/page.tsx` · `web/src/app/[locale]/dashboard/leases/[id]/page.tsx`

**Fix:** Replace the finance page's inline modal with the shared CollectChequeDialog (its stated purpose per its own doc comment), and add the message||error extraction + toast to handleDeposit/handleClear on both pages, matching MarkChequeFailedDialog's error handling.

### M52. Mobile manager 'Add Contact' always fails: required 'category' never sent, nonexistent 'role' field sent instead
**Endpoint:** `POST /api/v1/properties/{propertyId}/contacts` · **Affects:** mobile_admin · **Area:** property-units

Backend PropertyContactDTO.java:12-22 requires `@NotNull ContactCategory category`, `@NotBlank String name`, `@NotBlank String phone`. Mobile property_detail_screen.dart:560-569 submits {'name':..., 'role':... (optional), 'phone':... (only if non-empty), 'email':...} via property_contact_service.dart:12-15. 'category' is never sent (null -> @NotNull fails -> guaranteed 400 from GlobalExceptionHand…

**Files:** `mobile/apps/manager/lib/screens/property_detail_screen.dart` · `mobile/packages/rentaxis_core/lib/api/services/property_contact_service.dart` · `backend/src/main/java/com/datagami/rentaxis/api/dto/PropertyContactDTO.java`

**Fix:** Add a category picker using the ContactCategory enum values (PLUMBER, ELECTRICIAN, HANDYMAN, SECURITY, HOSPITAL_CLINIC, PHARMACY, BUILDING_MAINTENANCE, CIVIL_DEFENSE, OTHER), make phone required in the form, map the free-text 'role' to category=OTHER + customLabel, and render category/customLabel instead of 'role'.

### M53. Web property import always shows 'Import failed' — backend serializes errors:[] which is truthy in JS
**Endpoint:** `POST /api/v1/properties/import` · **Affects:** web · **Area:** property-units

BulkPropertyImportResultDTO.java:15 initializes `private List<String> errors = new ArrayList<>()`, so a successful import (PropertyController.java:74-77 returns 200) still serializes `"errors": []`. Web properties/page.tsx:891 uses `importResult.errors || importResult.error || ...` to choose the red error style and line 895 `importResult.errors ? ("Import failed:" ...)` to choose the message — an …

**Files:** `web/src/app/[locale]/dashboard/properties/page.tsx` · `backend/src/main/java/com/datagami/rentaxis/api/dto/BulkPropertyImportResultDTO.java`

**Fix:** Gate on `importResult.errors?.length > 0` instead of truthiness, and render `importResult.message` (not the boolean `error` flag) for global-handler error bodies.

### M54. Web unit dropdown in CreateMeetingModal calls GET /v1/units?propertyId=... but backend ignores the param and returns ALL tenant units
**Endpoint:** `GET /api/v1/units` · **Affects:** web · **Area:** property-units

Backend UnitController.java:35-39 `@GetMapping public ResponseEntity<List<Unit>> getAllUnits()` declares no query parameters and returns every unit in the tenant. CreateMeetingModal.tsx:199 calls `fetch(`/api/proxy/v1/units?propertyId=${propertyId}`)` expecting a property-filtered list and puts the result straight into the unit picker (line 203). The propertyId query string is silently ignored, so…

**Files:** `web/src/app/[locale]/dashboard/meetings/CreateMeetingModal.tsx` · `backend/src/main/java/com/datagami/rentaxis/api/UnitController.java`

**Fix:** Change the call to `/api/proxy/v1/units/property/${propertyId}` (as done in properties/[id]/page.tsx:108 and finance/transactions/page.tsx:196).

### M55. Web 'Add Project' and 'Import Property' forms offer INDUSTRIAL, not a valid PropertyType — selection guarantees request failure, silently swallowed
**Endpoint:** `POST /api/v1/properties (and POST /api/v1/properties/import)` · **Affects:** web · **Area:** property-units

PropertyType.java defines only COMMERCIAL, RESIDENTIAL, MIXED. Web properties/page.tsx:538 (create form) and :939 (import form) both render `["RESIDENTIAL", "COMMERCIAL", "MIXED", "INDUSTRIAL"]` options. Selecting Industrial on create makes Jackson fail to deserialize CreatePropertyDTO.type (HttpMessageNotReadableException -> GlobalExceptionHandler.java:133 RuntimeException handler -> 500); handle…

**Files:** `web/src/app/[locale]/dashboard/properties/page.tsx` · `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/PropertyType.java` · `backend/src/main/java/com/datagami/rentaxis/core/service/PropertyService.java`

**Fix:** Remove INDUSTRIAL from both selects (or add it to the PropertyType enum + a Liquibase-safe migration), and surface non-OK responses in handleProjectSubmit/handleImportSubmit.

### M56. Web dashboard 'Add Property' (unit) modal offers BHK4, SHOP, WAREHOUSE — invalid UnitType values that guarantee failure, silently swallowed
**Endpoint:** `POST /api/v1/units` · **Affects:** web · **Area:** property-units

UnitType.java defines STUDIO, BHK1, BHK2, BHK3, PENTHOUSE, RETAIL, OFFICE. Web properties/page.tsx:604 renders options `["STUDIO", "BHK1", "BHK2", "BHK3", "BHK4", "PENTHOUSE", "SHOP", "OFFICE", "WAREHOUSE"]` for the POST /api/proxy/v1/units call at lines 183-196. BHK4, SHOP and WAREHOUSE cannot be deserialized into Unit.type, so those submissions always fail (RuntimeException catch-all -> 500) and…

**Files:** `web/src/app/[locale]/dashboard/properties/page.tsx` · `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/UnitType.java`

**Fix:** Reuse the valid list from properties/[id]/page.tsx:595 (including RETAIL, which this form is missing) and add an error branch to handlePropertySubmit.

### M57. Web 'Fixed Expenses' field on Add Project is silently discarded — CreatePropertyDTO has no fixedExpenses and no update endpoint exists
**Endpoint:** `POST /api/v1/properties` · **Affects:** web · **Area:** property-units

Web properties/page.tsx:561 collects a 'Fixed Expenses' number into projectFormData (line 91) and POSTs the whole object at lines 157-161. CreatePropertyDTO.java:10-22 has no fixedExpenses field and PropertyController.java:31-39 maps only nameEn/nameAr/type/emirate/address/makaniNumber, so Jackson drops the value (Spring Boot ignores unknown JSON properties by default). Property.java:40-41 does ha…

**Files:** `web/src/app/[locale]/dashboard/properties/page.tsx` · `backend/src/main/java/com/datagami/rentaxis/api/dto/CreatePropertyDTO.java` · `backend/src/main/java/com/datagami/rentaxis/domain/entity/Property.java`

**Fix:** Add fixedExpenses to CreatePropertyDTO and map it in the controller (or remove the input from the form until a property-update endpoint exists).

### M58. CreateMeetingModal PM dropdown reads fullName/firstName/lastName which do not exist on the User response — labels render as '(email)'
**Endpoint:** `GET /api/v1/properties/{id}/managers` · **Affects:** web · **Area:** property-units

Backend PropertyController.java:54-58 returns List<User>; User.java:35-36 exposes `private String name` (no fullName/firstName/lastName). CreateMeetingModal.tsx:216 maps `fullName: u.fullName ?? `${u.firstName ?? ""} ${u.lastName ?? ""}`.trim()`, which always evaluates to the empty string, and line 571 renders `{u.fullName} ({u.email})` — so every PM option shows only the parenthesized email. The …

**Files:** `web/src/app/[locale]/dashboard/meetings/CreateMeetingModal.tsx` · `backend/src/main/java/com/datagami/rentaxis/domain/entity/User.java`

**Fix:** Map `fullName: u.name ?? u.fullName ?? ...` in fetchPmUsers to match the actual User serialization.

### M59. CreateMeetingModal reads managerId/propertyManagerId from GET /v1/properties rows — fields do not exist, so host auto-derivation for property visits never works
**Endpoint:** `GET /api/v1/properties` · **Affects:** web · **Area:** property-units

PropertyStatsDTO.java:11-18 serializes {property, propertyCount, revenueAtCapacity, actualRevenue, vacancies, assignedManagers} — there is no managerId or propertyManagerId. CreateMeetingModal.tsx:190 maps `managerId: p.managerId ?? p.propertyManagerId` (always undefined), so deriveHostUserId (lines 261-263) never finds a host from the property and needsPmPicker (lines 275-277) is always true for …

**Files:** `web/src/app/[locale]/dashboard/meetings/CreateMeetingModal.tsx` · `backend/src/main/java/com/datagami/rentaxis/api/dto/PropertyStatsDTO.java`

**Fix:** Map `managerId: p.assignedManagers?.[0]?.id` (the field the DTO actually provides) so host auto-derivation works as designed.

### M60. POST /api/v1/units/bulk returns 500 for malformed CSV content (bad enum/number) and web bulk upload swallows all failures silently
**Endpoint:** `POST /api/v1/units/bulk` · **Affects:** web, backend · **Area:** property-units

UnitController.java:59-84 parses the CSV inside a try/catch where any UnitType.valueOf / UnitStatus.valueOf / BigDecimal parse failure hits `catch (Exception e) { return ResponseEntity.internalServerError().build(); }` — a bodyless 500 for what is client input error (contrast with POST /api/v1/properties/import, which returns a 400 with row-level errors). Web handleBulkUpload (properties/[id]/page…

**Files:** `backend/src/main/java/com/datagami/rentaxis/api/UnitController.java` · `web/src/app/[locale]/dashboard/properties/[id]/page.tsx`

**Fix:** Return 400 with row-level error messages from the controller (mirroring importPropertyWithUnits validation), and surface non-OK responses in handleBulkUpload.

### M61. Web create/edit forms for properties, units, buildings and contacts silently ignore non-OK responses, discarding the backend's structured error message
**Endpoint:** `POST /api/v1/properties; POST /api/v1/units; POST /api/v1/buildings; POST/PUT /api/v1/properties/{propertyId}/contacts` · **Affects:** web · **Area:** property-units

The backend returns actionable JSON on failure — e.g. PropertyService.java:62-83 translates the duplicate-name unique index into IllegalArgumentException, which GlobalExceptionHandler.java:71-78 maps to 400 {error:true, message: "A property named 'X' already exists..."}. But every consumer only checks res.ok with no else: properties/page.tsx:162-174 (handleProjectSubmit) and :197-210 (handleProper…

**Files:** `web/src/app/[locale]/dashboard/properties/page.tsx` · `web/src/app/[locale]/dashboard/properties/[id]/page.tsx` · `web/src/app/[locale]/dashboard/properties/[id]/units/page.tsx`

**Fix:** Add a shared error helper (the codebase already introduced one for the listings pages per commit 2207d66) that parses {message} from non-OK responses and shows it near the submit button.

### M62. Post-confirm redirect targets a locale-less path that matches no Next.js route — user lands on 404 after successful intent capture
**Endpoint:** `POST /api/v1/public/renewal-intent` · **Affects:** web, backend · **Area:** renewals

PublicRenewalController.java:50-53 returns `new RenewalIntentResponse(updated.getIntent().name(), updated.getLease().getId(), "/dashboard/renter-portal/renewals")`, and RenewalIntentConfirm.tsx:22 navigates there on success: `window.location.href = body.redirectTo ?? "/dashboard/renter-portal/renewals"` (the fallback has the same defect). Every web page lives under app/[locale]/ (web/src/app conta…

**Files:** `backend/src/main/java/com/datagami/rentaxis/api/PublicRenewalController.java` · `web/src/components/renewals/RenewalIntentConfirm.tsx` · `web/src/i18n/routing.ts`

**Fix:** Make the redirect locale-aware on the client: ignore or localize the backend-provided path (e.g. use next-intl's router/redirect from @/i18n/routing with the current locale) instead of assigning window.location.href to the raw backend value; alternatively stop sending a web-specific path from the backend DTO altogether.

### M63. Renter setIntent POST ignores response status — 400 'already resolved' and 404 fail silently, UI just reloads
**Endpoint:** `POST /api/v1/me/renewals/{opportunityId}/intent` · **Affects:** web · **Area:** renewals

renewals/page.tsx:29-36: `const setIntent = async (opportunityId, intent) => { await fetch(`/api/proxy/v1/me/renewals/${opportunityId}/intent`, { method: "POST", ... body: JSON.stringify({ intent }) }); await load(); }` — the response is never inspected (no res.ok check, no user feedback). The backend really does fail on realistic inputs: RenewalIntentService.captureIntentFromRenter (lines 52-54) …

**Files:** `web/src/app/[locale]/dashboard/renter-portal/renewals/page.tsx` · `backend/src/main/java/com/datagami/rentaxis/core/service/renewal/RenewalIntentService.java` · `backend/src/main/java/com/datagami/rentaxis/api/GlobalExceptionHandler.java`

**Fix:** Check res.ok in setIntent and surface a toast/message for the 400 already-resolved and 404 cases (the backend returns {error, message, status} JSON), then reload; optionally use the returned {intent, stage} to update state instead of a blind refetch.

### M64. Mobile renter app dead-ends the in-app renewal reminder: 'Tap to choose your renewal option' but the app never calls any /v1/me/renewals endpoint
**Endpoint:** `GET /api/v1/me/renewals` · **Affects:** mobile_renter · **Area:** renewals

For the non-EMAIL reminder channel, RenewalReminderService.java:132-138 sends an in-app notification to the renter: notifyInAppInNewTx(..., "LEASE_RENEWAL_REMINDER", "Your lease ends in " + slot + " days", "Lease ends ... Tap to choose your renewal option.", "LEASE", leaseId). But grep across mobile/apps/renter, mobile/apps/manager, mobile/apps/security, and mobile/packages/rentaxis_core returns z…

**Files:** `backend/src/main/java/com/datagami/rentaxis/core/service/renewal/RenewalReminderService.java` · `mobile/apps/renter/lib/screens/notifications_screen.dart`

**Fix:** Add a renewal summary/intent screen to the renter Flutter app backed by GET /api/v1/me/renewals and POST /api/v1/me/renewals/{opportunityId}/intent (a rentaxis_core service), and deep-link the LEASE_RENEWAL_REMINDER notification to it; until then, change the in-app copy so it doesn't instruct an impossible tap action.

### M65. Renter renewals page renders empty buckets on any fetch failure — errors indistinguishable from 'no renewals'
**Endpoint:** `GET /api/v1/me/renewals` · **Affects:** web · **Area:** renewals

renewals/page.tsx:23-25: `const res = await fetch("/api/proxy/v1/me/renewals"); if (!res.ok) { setData({ leases: [] }); return; }` — any 401 (expired session at the proxy, proxy.ts:23), 403 (non-RENTER role hitting the @PreAuthorize("hasAuthority('ROLE_RENTER')") guard at RenterRenewalController.java:52), 404 (user with no Renter record, controller line 56-57), or 500 is rendered as four 'emptyBuc…

**Files:** `web/src/app/[locale]/dashboard/renter-portal/renewals/page.tsx`

**Fix:** Track an error state separately from empty data (e.g. setError on !res.ok) and render a retry affordance instead of the empty-bucket copy.

### M66. Create ticket without a property triggers findById(null) server error; web even offers 'General (no specific property)'
**Endpoint:** `POST /api/v1/tickets` · **Affects:** web, mobile_admin, mobile_renter, backend · **Area:** renter-tickets

MaintenanceTicketService.java:71-72 unconditionally calls propertyRepository.findById(dto.getPropertyId()); CreateTicketDTO.propertyId has no @NotNull and the controller has no @Valid, so a null propertyId reaches Spring Data's findById(null), which throws IllegalArgumentException/InvalidDataAccessApiUsageException ('The given id must not be null') → an obscure 400 or 500, never a ticket. All thre…

**Files:** `backend/src/main/java/com/datagami/rentaxis/core/service/MaintenanceTicketService.java` · `web/src/app/[locale]/dashboard/tickets/page.tsx` · `mobile/apps/manager/lib/screens/create_ticket_screen.dart`

**Fix:** Backend: validate propertyId (@NotNull or explicit BusinessRuleViolationException 'propertyId is required'). Frontends: make property selection required (remove/repurpose the 'General' option or add a general-property concept), and show the API error message on failure.

### M67. Manager app shows 'Start Work' on OPEN (unassigned) tickets — backend always rejects with 400
**Endpoint:** `PUT /api/v1/tickets/{id}/status` · **Affects:** mobile_admin · **Area:** renter-tickets

_ActionsPanel renders the primary 'Start Work' button when status == 'OPEN' || 'ASSIGNED' (ticket_detail_screen.dart:1113-1138), wired to _updateStatus('IN_PROGRESS') (line 579). MaintenanceTicketService.java:220-222 throws BusinessRuleViolationException('Ticket must be assigned before moving to IN_PROGRESS') when assignedTo == null — which is the normal state of an OPEN ticket (assignTicket flips…

**Files:** `mobile/apps/manager/lib/screens/ticket_detail_screen.dart` · `backend/src/main/java/com/datagami/rentaxis/core/service/MaintenanceTicketService.java`

**Fix:** Only show Start Work once the ticket is ASSIGNED (or auto-assign-to-me first), and add an actions branch for REOPENED mirroring OPEN/ASSIGNED.

### M68. Backend leaks closureOtp to managers in every ticket response, defeating the renter-OTP close handshake
**Endpoint:** `GET /api/v1/tickets and GET /api/v1/tickets/{id}` · **Affects:** backend · **Area:** renter-tickets

mapToDTO unconditionally copies the secret: dto.setClosureOtp(ticket.getClosureOtp()) (MaintenanceTicketService.java:557), and GET /{id} / GET / are only @PreAuthorize("isAuthenticated()") (MaintenanceTicketController.java:33, 41). The OTP exists so the renter must confirm closure (PUT /{id}/close validates it, service lines 283-287, and notifies the reporter to 'share the OTP', line 234), but any…

**Files:** `backend/src/main/java/com/datagami/rentaxis/core/service/MaintenanceTicketService.java` · `backend/src/main/java/com/datagami/rentaxis/api/MaintenanceTicketController.java`

**Fix:** Null out closureOtp in mapToDTO unless the requester is the reporting renter (X-User-Id == reportedBy or role RENTER).

### M69. Web ticket detail reads ticket.estimatedHours; DTO field is estimatedResolutionHours — ETA never displays
**Endpoint:** `GET /api/v1/tickets/{id} (paired with PUT /{id}/estimate)` · **Affects:** web · **Area:** renter-tickets

The page's Ticket type declares estimatedHours (tickets/[id]/page.tsx:34) and renders '{ticket.estimatedHours && <DetailRow ... value={`${ticket.estimatedHours} hours`} />}' (line 509), but MaintenanceTicketDTO.java:22 names the field estimatedResolutionHours. estimatedHours is always undefined, so after a PM sets an ETA via PUT /{id}/estimate (handleSetEta, line 212-216 — which works) the ETA row…

**Files:** `web/src/app/[locale]/dashboard/tickets/[id]/page.tsx` · `backend/src/main/java/com/datagami/rentaxis/api/dto/MaintenanceTicketDTO.java`

**Fix:** Rename the field reads to estimatedResolutionHours (and reportedBy/assignedTo) to match the DTO.

### M70. Manager app 'On behalf of' sends renterId, which CreateTicketDTO does not have — selection silently discarded
**Endpoint:** `POST /api/v1/tickets` · **Affects:** mobile_admin · **Area:** renter-tickets

create_ticket_screen.dart:281 sends "if (_selectedRenterId != null) 'renterId': _selectedRenterId". CreateTicketDTO has no renterId field — the only related field is 'onBehalfOf' (String, free-text name, CreateTicketDTO.java:16), which is what web sends (tickets/page.tsx:207 'onBehalfOf: form.onBehalfOf || undefined'). Spring Boot's default fail-on-unknown-properties=false means the unknown key is…

**Files:** `mobile/apps/manager/lib/screens/create_ticket_screen.dart` · `backend/src/main/java/com/datagami/rentaxis/api/dto/CreateTicketDTO.java`

**Fix:** Send onBehalfOf with the selected renter's display name (or extend CreateTicketDTO with a renterId reference and map it server-side).

### M71. Mobile meeting lists fetch a single page (size 50) and MeetingService discards pagination metadata — silent truncation
**Endpoint:** `GET /api/v1/meetings and GET /api/v1/meetings/my` · **Affects:** mobile_admin, mobile_renter · **Area:** renter-tickets

Both endpoints return Page<MeetingDTO>. meeting_service.dart:13-34 returns only data['content'] and throws away totalPages/totalElements; manager meetings_screen.dart:132-133 calls svc.listMeetings() / svc.listMyMeetings(perspective: 'host') with the default size 50 and no paging UI, and renter meetings_screen.dart:36 does the same with perspective 'requester'. A tenant with more than 50 meetings …

**Files:** `mobile/packages/rentaxis_core/lib/api/services/meeting_service.dart` · `mobile/apps/manager/lib/screens/meetings_screen.dart` · `mobile/apps/renter/lib/screens/meetings_screen.dart`

**Fix:** Return the full page object (or expose totalPages) from MeetingService and add incremental loading, or request a sort/window that guarantees relevant items (e.g. upcoming-only).

### M72. Mobile meeting creation collapses the 409 slot-conflict response (with nextAvailableSlot) into a generic failure
**Endpoint:** `POST /api/v1/meetings` · **Affects:** mobile_admin, mobile_renter · **Area:** renter-tickets

The backend returns 409 with {error, nextAvailableSlot} on slot conflicts (GlobalExceptionHandler.java:24-30, thrown at MeetingService.java:79-83) and 400 with a message for past/out-of-window slots (MeetingService.java:60-70). Both mobile submit handlers catch every DioException identically: manager create_meeting_screen.dart:286-294 and renter create_meeting_screen.dart:212-218 show only a fixed…

**Files:** `mobile/apps/manager/lib/screens/create_meeting_screen.dart` · `mobile/apps/renter/lib/screens/create_meeting_screen.dart` · `backend/src/main/java/com/datagami/rentaxis/api/GlobalExceptionHandler.java`

**Fix:** On DioException, inspect response.statusCode/response.data: for 409 show the conflict message and offer the nextAvailableSlot; for 400 surface the backend message.

### M73. Web create-renter form (and ticket/meeting action buttons) swallow API errors — failed writes look like nothing happened
**Endpoint:** `POST /api/v1/renters (pattern also on PUT /v1/tickets/{id}/* and PUT /v1/meetings/{id}/*)` · **Affects:** web · **Area:** renter-tickets

renters/page.tsx:73-99 handleSubmit only acts 'if (res.ok)'; a 400 (@Email validation) or a failure from portal-user creation (e.g. duplicate email — RenterService.java:63-80 aborts the whole transaction) produces no user feedback at all; the catch only console.errors. Same pattern on ticket actions (tickets/[id]/page.tsx:186-198 performAction ignores non-ok, so a 400 'Invalid OTP' from PUT /close…

**Files:** `web/src/app/[locale]/dashboard/renters/page.tsx` · `web/src/app/[locale]/dashboard/tickets/[id]/page.tsx` · `web/src/app/[locale]/dashboard/meetings/[id]/page.tsx`

**Fix:** Read the error body's message on !res.ok and surface it (toast/inline), at minimum for create-renter (whose OTP/duplicate-email failures are common) and the OTP close action.

## Low severity (30)

1. **Web bank-accounts save/delete failures are silent (no error message shown)** — `POST/PUT/DELETE /api/v1/bank-accounts` (web). Read the error body ({error, message, status}) and show the message near the form/confirm dialog.
2. **Mobile change-password ignores the backend's specific 400 error body ('Current password is incorrect'), showing a generic failure for every cause** — `PUT /api/auth/me/password` (mobile_admin, mobile_renter). In the catch, inspect DioException.response (status 400, data['error']) and show the backend message, falling back to the generic string otherwise.
3. **Mobile PUT /auth/me cannot clear the phone number: the key is omitted when empty and the backend only writes non-null phoneNumber** — `PUT /api/auth/me` (mobile_admin, mobile_renter). Send `'phoneNumber': ''` (or an explicit empty string sentinel) when the user clears the field, matching the web behavior the backend already supports.
4. **web/src/lib/rbac.ts ROLE_RANK orders SECURITY_GUARD/TENANT_USER/RENTER differently from UserController.privilegeRank despite claiming to mirror it** — `POST /api/admin/users (role-assignment hierarchy)` (web). Align ROLE_RANK's bottom three with privilegeRank (TENANT_USER 3, RENTER 4, SECURITY_GUARD 5) so the two tables stay a true mirror.
5. **Admin booking drawer shows stale otherRequests after 409 on approve** — `POST /api/v1/bookings/{id}/approve` (web). In BookingDetailDrawer.act(), call load() (re-fetch the detail) after receiving a 409, matching the mobile manager sheet's invalidate-on-409 behavior.
6. **Renter request dialog does not refresh facilities after a 409 spot conflict** — `POST /api/v1/bookings` (web). Refresh myFacilities (and myBookings) whenever the request dialog closes after an error — or at minimum on a 409 — so the card's held/Available state resyncs with the server, matching the mobile renter app.
7. **Manager bulk-add comment misstates backend collision behavior** — `POST /api/v1/parking-spots/bulk` (mobile_admin). Fix the comment to say collisions fail the whole batch with a 400 naming the duplicate spot number; the created-count toast is still useful because parseSpotNumbers dedupes ranges client-side.
8. **Manager Arabic penalty-type label maps PERCENTAGE_OF_RENT but backend enum value is PERCENTAGE** — `GET /api/v1/rent-settings/{propertyId}` (mobile_admin). Rename the map key to 'PERCENTAGE' in _L.penaltyTypeLabel.
9. **Resident-approval avatar refetches walk-in photo on every widget rebuild** — `GET /api/v1/gatepass/walk-in/{id}/photo` (mobile_renter). Create the photo Future once (StatefulWidget initState, a memoized field, or a FutureProvider.autoDispose.family keyed by pass id) so each card downloads its photo exactly once per screen visit.
10. **Mobile terminateLease sends notes as a query parameter the backend never reads — silently dropped (latent, currently uncalled)** — `POST /api/v1/leases/{id}/terminate` (mobile_admin). Change terminateLease to send `data: {'notes': notes}` as the request body, or delete the method since the endpoint is otherwise unused.
11. **Web sorts lease documents by a createdAt field that does not exist in LeaseDocumentDTO — 'newest first' logic is a silent no-op** — `GET /api/v1/leases/{id}/documents` (web). Add createdAt to LeaseDocumentDTO (entity almost certainly has it) or drop the misleading sort and rely on the type filter alone.
12. **CreateMeetingModal reads nonexistent propertyManagerId from the lease list response — lease-derived meeting host never works** — `GET /api/v1/leases and GET /api/v1/leases/my-leases` (web). Either add propertyManagerId to LeaseDTO (resolvable from the lease's property manager) or remove the dead derivation and always show the picker/default host.
13. **Web wishlist page reads interestStatus/bathrooms/availableFrom from the wishlist response — none exist in UnitListingSummaryDTO, so the NOTIFIED badge and bath count never render** — `GET /api/marketplace/me/wishlist` (web). Either extend the wishlist response with interest status (the InterestService already tracks NOTIFIED) and bathrooms, or delete the phantom fields and the dead badge branch from the page.
14. **Dashboard listings 'Created' column sorts by createdAt but displays updatedAt** — `GET /api/listings` (web, backend). Add createdAt to UnitListingSummaryDTO and display it, or relabel the column and sort key to Updated.
15. **Backend sitemap emits relative <loc> URLs, served verbatim by the web sitemap route — invalid per the sitemap protocol** — `GET /public/l/{tenantSlug}/sitemap.xml` (web, backend). Pass the public base URL to the backend (config property) and emit absolute URLs, or rewrite the loc values in the Next.js route handler using the request host.
16. **Dead /api/marketplace rewrite in next.config bypasses the auth-header middleware — any future caller gets guaranteed 401/403** — `GET /api/marketplace/** (rewrite path, all MarketplaceController endpoints)` (web). Delete the /api/marketplace rewrite, or add it to the middleware matcher so auth context is injected consistently.
17. **Push-device registration endpoint is dead: registerDevice exists in the shared mobile service but is never called** — `POST /api/v1/notifications/devices/register` (backend). Either wire up FCM token registration at app startup/login in the mobile apps, or remove the endpoint and the dead service method to shrink the attack surface.
18. **Portfolio template download does not check res.ok — auth/server errors are saved as a corrupt .xlsx** — `GET /api/v1/import/portfolio/template` (web). Guard with `if (!res.ok) { show error toast; return; }` before creating the blob URL.
19. **penaltyPaymentInstructions has a read UI but no write UI anywhere — PUT /api/v1/settings/org is dead surface, so renters can only ever see instructions seeded by hand** — `PUT /api/v1/settings/org` (backend). Add the write field to an admin settings page (e.g. web /dashboard/settings) or, if intentionally API-only for now, document that on the controller like LandlordOrgController's DELETE does.
20. **Web feature-flag cache is module-global and never invalidated, and MvpSidebar's justification comment is stale now that superadmin can flip toggles** — `GET /api/v1/tenant/features` (web). Key/expire the cache (e.g. per session user id, or refetch on window focus), and revisit the GATEPASS gating decision plus its comment now that the toggle UI exists.
21. **Web Gateway type declares supportedCurrencies as string[] but backend PaymentGatewayDTO sends a plain String** — `GET /api/v1/gateway-config/gateways` (web). Change the TS type to `string` (or parse the delimited value into an array at the fetch boundary).
22. **Tenant edit sends ticketOtpRequired as a JSON boolean into a backend Map<String,String> — works only via Jackson scalar-to-String coercion** — `PUT /api/admin/tenants/{id}` (web). Bind the PUT body to a typed DTO (Map<String, Object> at minimum, ideally a TenantUpdateDTO with Boolean ticketOtpRequired) so the boolean crosses the wire as a boolean.
23. **Web gateway page's 204 handling for 'no config yet' depends on res.json() throwing, and the catch also swallows real network errors** — `GET /api/v1/gateway-config` (web). Check `res.status === 204` explicitly before parsing (matching the mobile client) and keep the catch for transport errors only, ideally surfacing them.
24. **Manager payments screen never scopes GET /summary by the selected property, unlike web — stat tiles contradict the filtered list** — `GET /api/v1/payments/summary` (mobile_admin). Convert _paymentSummaryProvider to a family keyed by the selected propertyId (mirroring the web behavior) so the tiles re-query GET /v1/payments/summary?propertyId=... when the filter changes, or visually label the tiles as portfolio-wide.
25. **Entire online-payment initiation flow (create-order / verify / cancel) is unreachable from every frontend despite full backend + webhook support** — `POST /api/v1/online-payments/create-order, /verify, /cancel/{paymentScheduleId}` (web, mobile_renter). Decide whether online payments are shipped: if deliberately deferred, update the renter help articles and consider removing the dead Dart wrappers; if it is a regression, re-wire a pay button in the renter surfaces to createOrder -> gateway SDK -> verify, with cancel on abandonment.
26. **409 ALREADY_RESOLVED from public renewal-intent is collapsed into the generic error message** — `POST /api/v1/public/renewal-intent` (web). Handle res.status === 409 (or read body.error === 'ALREADY_RESOLVED') with a dedicated 'already handled' message and hide the confirm button, mirroring the existing 410 branch.
27. **FollowUpsWidget silently shows the empty state on non-OK responses** — `GET /api/v1/renewals/follow-ups` (web). Distinguish error from empty (at minimum skip rendering the widget or show a muted 'couldn't load' line on !res.ok instead of the empty message).
28. **Web types declare unitNumber/propertyNameEn as non-nullable strings though the backend can return null** — `GET /api/v1/me/renewals` (web). Change the TypeScript types to `string | null` to match RenewalSummaryDTO.LeaseRenewalView and add a fallback label when both are null.
29. **Manager app history rows read item['description']; DTO field is 'notes' — descriptive history text never shown** — `GET /api/v1/tickets/{id}/history` (mobile_admin). Read item['notes'] ?? item['action'] and append item['performedByName'] like the web activity list.
30. **Ticket attachments endpoints return the raw JPA entity; mobile reads nonexistent 'name'/'fileName' keys** — `GET /api/v1/tickets/{id}/attachments (and POST)` (backend, mobile_admin). Introduce a TicketAttachmentDTO {id, fileUrl, fileType, fileSize, uploadedAt} (or @JsonIgnore the ticket field), and have the manager app derive the display name from fileUrl like web does.

## Unused endpoint inventory

Endpoints no frontend calls (dead surface, ops-only, or superseded):

**AuthController (/api/auth)**
- POST /api/auth/register (backend exists; web register page fakes success and never calls it — see finding)
- POST /api/auth/firebase (duplicate of /api/v1/auth/firebase; no frontend calls it — security app uses the /api/v1 variant)

**UserController (/api/admin/users)**
- POST /api/admin/users/{userId}/properties/{propertyId} (web syncs assignments via PUT /{id} propertyIds instead)
- DELETE /api/admin/users/{userId}/properties/{propertyId}

**StaffController (/api/v1/staff)**
- GET /api/v1/staff/by-property/{propertyId} (core StaffService.getStaffByProperty wrapper exists but is called by no app or web page)

**LandlordOrgController (/api/admin/tenants, class-level @PreAuthorize SUPER_ADMIN)**
- DELETE /api/admin/tenants/{id}?confirmName= (intentional: Javadoc at LandlordOrgController.java:77-88 states it is for E2E/ops cleanup and 'there is no UI surface for this and there should not be one')

**OrgSettingsController (/api/v1/settings/org)**
- PUT /api/v1/settings/org (write side of penaltyPaymentInstructions — no caller in any frontend; see findings)

**BuildingController (/api/v1/buildings)**
- GET /api/v1/buildings
- GET /api/v1/buildings/{id}
- DELETE /api/v1/buildings/{id}

**LeaseController (/api/v1/leases)**
- POST /api/v1/leases/{id}/terminate — only callers are unreachable legacy web code (web/src/app/[locale]/dashboard/leases/page.tsx:297, dead after early return at line 287) and an uncalled mobile service method (lease_service.dart:37); live termination goes through POST /{id}/settlement/finalize
- GET /api/v1/leases/{id}/events — mobile core LeaseService.getLeaseEvents (lease_service.dart:62) exists but has no callers in any app; web never calls it
- POST /api/v1/leases/{id}/renewal/mark-renewed — documented in web/README.md:51 but called by no frontend

**LeaseInteractionController (/api/v1/leases/{leaseId}/interactions)**
- PATCH /api/v1/leases/{leaseId}/interactions/{id} — no callers in any frontend
- DELETE /api/v1/leases/{leaseId}/interactions/{id} — no callers in any frontend

**OpsRenewalController (/api/v1/admin/renewals)**
- POST /api/v1/admin/renewals/run-now (SUPER_ADMIN only; no call site in web, mobile_admin, mobile_renter, or mobile_security — ops-only surface, presumably invoked via curl)

**PaymentScheduleController (/api/v1/payments)**
- PUT /api/v1/payments/{id}/bounce — documented legacy endpoint (javadoc says kept for backward compatibility, superseded by POST /{id}/mark-failed). No web call site; rentaxis_core PaymentService.bouncePayment (payment_service.dart:79-85) exists but no screen calls it.

**OnlinePaymentController (/api/v1/online-payments)**
- POST /api/v1/online-payments/create-order — rentaxis_core wrapper createOrder (payment_service.dart:13-18) has no caller in any app; no web call site
- POST /api/v1/online-payments/verify — wrapper verifyPayment (payment_service.dart:20-25) has no caller; no web call site
- POST /api/v1/online-payments/cancel/{paymentScheduleId} — wrapper cancelPayment (payment_service.dart:27-29) has no caller; no web call site

**WebhookController (/api/webhooks)**
- POST /api/webhooks/razorpay — by design called by the Razorpay gateway servers, not by any frontend; correctly absent from all four frontends

**AccountController (/api/v1/finance/accounts)**
- GET /api/v1/finance/accounts/type/{type}
- GET /api/v1/finance/accounts/code/{code}

**AccountMappingController (/api/v1/finance/account-mappings)**
- POST /api/v1/finance/account-mappings (single save; web uses PUT /bulk, mobile only reads)

**BankAccountController (/api/v1/bank-accounts)**
- GET /api/v1/bank-accounts/{id} (rentaxis_core BankAccountService.getBankAccountById exists but has no callers)
- GET /api/v1/bank-accounts/by-property/{propertyId} (rentaxis_core BankAccountService.getBankAccountsByProperty exists but has no callers)

**GateWalkInController (/api/v1/gatepass) — backend/src/main/java/com/datagami/rentaxis/api/GateWalkInController.java**
- PUT /api/v1/gatepass/visitors/{profileId}/registration (registerVisitor, TENANT_ADMIN/PROPERTY_MANAGER) — no call site in web, mobile_admin, mobile_renter, or mobile_security; rentaxis_core GatePassApiService has no wrapper for it

**RenterController (/api/v1/renters)**
- PUT /api/v1/renters/{id} (updateRenter) — RenterService.updateRenter exists in rentaxis_core (renter_service.dart:23-27) but no screen in any app calls it, and web never issues PUT to /v1/renters

**MeetingController (/api/v1/meetings)**
- GET /api/v1/meetings/calendar — grep for 'meetings/calendar' across web/src, all three mobile apps and rentaxis_core returns nothing; the web meetings calendar view instead reuses GET /v1/meetings|/my client-side

**NotificationController**
- POST /api/v1/notifications/devices/register (no caller in any frontend; NotificationApiService.registerDevice exists in mobile/packages/rentaxis_core/lib/api/services/notification_service.dart:29 but is never invoked, and no FCM/firebase-messaging integration exists in any app, so push device tokens are never registered)
