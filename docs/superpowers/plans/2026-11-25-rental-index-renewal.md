# Rental Index on Renewal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dubai renewals show and enforce (warn + override with reason) the Decree 43/2013 rent cap from a stored DLD rental-index range, behind `RENTAL_INDEX`; units gain a DEWA premise number that works with the flag off.

**Architecture:** A pure calculator (`RentalIndexMath`) and a PDFBox reader (`DldResultParser`) feed a `rental_index_rates` store (one range per building/property and unit type). `LeaseRenewalService` consults it in `plan()` when the flag is on, adds the cap to the preview, refuses an above-cap renew without a reason, and snapshots each decision in `renewal_index_checks`. Web adds a property "Rental index" tab, an index panel in the renew dialog, an override reason in bulk renew, and a queue column.

**Tech Stack:** Java 21, Spring Boot 4, JPA, Liquibase, PostgreSQL 16, PDFBox 3.0.7; Next.js 16, TypeScript, next-intl, vitest.

**Spec:** `docs/superpowers/specs/2026-11-25-rental-index-renewal-design.md` (read §5–§8, §14, §10 before any task).

## Global Constraints

- Only the index check is behind `TenantFeature.RENTAL_INDEX` (default **off**). The DEWA premise number (unit create, set endpoint, units list, both imports, template) works with the flag off.
- Flag off ⇒ renew, preview and bulk renew responses and behaviour are byte-for-byte what they are today (new preview fields null).
- Changeset `194-rental-index.yaml` only, with rollback. Never edit an existing changeset.
- Every new table has `tenant_id` and its entity extends `BaseTenantEntity`; every new read runs inside a transaction (tenant filter is off outside one: see memory "Tenant filter off outside a transaction").
- Index applies only when `property.emirate == DUBAI` and unit type ∈ {STUDIO, BHK1, BHK2, BHK3, PENTHOUSE}.
- Bands (Decree 43 Art. 1): gap ≤ 10 → 0; ≤ 20 → 5; ≤ 30 → 10; ≤ 40 → 15; > 40 → 20. Gap = (average − base) / average × 100, 4 dp. Average = (min + max) / 2. Cap = base × (1 + pct/100) rounded HALF_UP to whole AED.
- Error codes: `renewal.indexOverrideRequired`, `rentalIndex.notDldResult`, `rentalIndex.figuresDontAddUp`, `rentalIndex.premiseMismatch`, `rentalIndex.rangeInvalid`, `unit.dewaInvalid`, `unit.dewaTaken`; each in `web/messages/en.json` and `ar.json` under `Common.errors`.
- Copy: sentence case, no "please", no "successfully". Arabic strings for every new English string.
- No real client data (DEWA numbers, building names, DLD PDFs) in code, fixtures, tests or commits. Fixtures are synthetic.
- Commits: conventional (`feat:`, `test:` …), `git commit -- <paths>` with explicit paths, ending `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. A unit whose property has no emirate: preview says `indexStatus = NO_EMIRATE`, renew is never blocked.
2. A 6-month or 24-month renewal: base and new rent are annualised by days before comparing, so a 6-month renewal at half the yearly rent is not "above cap".
3. A revoked or expired range is never used; a newer range beats an older one for the same key; building range beats property-level range.
4. Renter role or another tenant's user calling any rental-index endpoint gets 403/404; ranges of tenant A never appear for tenant B.
5. Bulk renew with mixed rows (one above cap, one within, one no index) and no reason: only the above-cap row fails with `renewal.indexOverrideRequired`, others succeed.

---

### Task 1: DEWA premise number on units (flag-independent)

**Files:**
- Create: `backend/src/main/resources/db/changelog/changesets/194-rental-index.yaml` (this task adds only the units column and index; Tasks 4–5 append their changeSets to the same file)
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml` (include 194)
- Modify: `domain/entity/Unit.java` (field `dewaPremiseNumber`, column `dewa_premise_number VARCHAR(12)`)
- Modify: `api/dto/UnitRequest.java` (add `String dewaPremiseNumber` last; keep a constructor without it if callers break)
- Modify: `core/service/UnitService.java` (`createUnit` sets it; new `setDewaPremiseNumber`)
- Modify: `core/service/UnitRules.java` (`dewaProblem`)
- Modify: `api/UnitController.java` (new endpoint)
- Modify: imports — `PortfolioImportService.validateUnitsSheet`, `PortfolioImportPersistService` (~215-245), `cutover/ContractImportPersistService` (~360-385) + `ContractImportValidator`, `PortfolioTemplateService` header arrays (lines ~98 and ~231) — column index 6 `DewaPremiseNo`
- Test: `backend/src/test/java/.../core/service/UnitDewaPremiseIT.java`, extend the existing portfolio import IT for column 6

**Interfaces:**
- Produces: `PUT /api/v1/units/{id}/dewa-premise-number` body `{ "dewaPremiseNumber": string|null }` → `Unit` JSON; roles SUPER_ADMIN, TENANT_ADMIN, PROPERTY_MANAGER (property-scoped like other unit writes). `Unit.getDewaPremiseNumber(): String`. `UnitRules.dewaProblem(String) -> Optional<String>`.

- [ ] **Step 1: Failing tests** in `UnitDewaPremiseIT`: `createWithDewaStoresDigitsOnly` ("681 204 553" → "681204553"); `setDewaOnExistingUnit`; `blankClearsIt` ("" → null); `rejectsNonDigits` ("12AB" → 400 code `unit.dewaInvalid`); `rejectsTooShortOrLong` (5 and 13 digits → `unit.dewaInvalid`); `duplicateInSameTenantRejected` (`unit.dewaTaken`); `sameNumberInOtherTenantAllowed`; `worksWithRentalIndexFlagOff`. Import IT: a Units row with column 6 `123456789` persists it; `12AB` yields `ImportErrorDTO("Units", row, "DewaPremiseNo", …)`.
- [ ] **Step 2:** Run `cd backend && ./gradlew test --tests '*UnitDewaPremiseIT' --offline` → FAIL (column missing).
- [ ] **Step 3:** Changeset `194-rental-index` changeSet id `194-rental-index-units`: add column; partial unique index `ux_units_tenant_dewa ON units(tenant_id, dewa_premise_number) WHERE dewa_premise_number IS NOT NULL`; rollback drops both. Normalise = strip spaces; valid = `^\d{6,12}$`; duplicates checked in service first (clear error), DB index as backstop. Wire import column 6 in all four import places and both template header arrays (sample value `""`).
- [ ] **Step 4:** Tests pass; run the existing portfolio/cut-over import test classes too.
- [ ] **Step 5:** Commit `feat(units): DEWA premise number on units, set endpoint and import column`.

### Task 2: Flag and calculator

**Files:**
- Modify: `domain/entity/enums/TenantFeature.java` (`RENTAL_INDEX(false)`), `core/service/TenantFeatureService.java` label switch ("Rental index check")
- Create: `core/service/rentalindex/RentalIndexMath.java`
- Test: `backend/src/test/java/.../core/service/rentalindex/RentalIndexMathTest.java`

**Interfaces:**
- Produces: `RentalIndexMath.average(BigDecimal min, BigDecimal max) -> BigDecimal` (2 dp); `gapPercent(BigDecimal average, BigDecimal base) -> BigDecimal` (4 dp); `allowedPercent(BigDecimal gap) -> int`; `capRent(BigDecimal base, int pct) -> BigDecimal` (whole AED, scale 0); `annualise(BigDecimal amount, LocalDate start, LocalDate end) -> BigDecimal` (unchanged when the term is exactly 12 whole months, else × 365 / days, 2 dp); `record Cap(BigDecimal average, BigDecimal gap, int percent, BigDecimal capRent)`; `Cap of(BigDecimal min, BigDecimal max, BigDecimal annualBase)`.

- [ ] **Step 1: Failing tests:** `bandEdges` parameterised (-5→0, 0→0, 10→0, 10.4→5, 11→5, 20→5, 20.72→10, 30→10, 30.5→15, 40→15, 40.01→20, 65→20); `dldWorkedExample` (avg 80000, base 60000 → gap 25.0000, 10%, cap 66000); `realSampleFigures` (117499–143609, base 103500 → avg 130554.00, gap 20.7225, 10%, cap 113850); `annualiseYearUnchanged` (2026-01-01..2026-12-31); `annualiseSixMonths` (50000 over 181 days → 100828.73); `rentAboveAverageIsZero`.
- [ ] **Step 2:** `./gradlew test --tests '*RentalIndexMathTest' --offline` → FAIL.
- [ ] **Step 3:** Implement as specified in Global Constraints.
- [ ] **Step 4:** PASS. **Step 5:** Commit `feat(rental-index): RENTAL_INDEX flag and Decree 43 calculator`.

### Task 3: DLD result reader

**Files:**
- Create: `core/service/rentalindex/DldResultParser.java`
- Create (test support): `backend/src/test/java/.../core/service/rentalindex/DldPdfFixtures.java` — builds synthetic PDFs with PDFBox (`PDPageContentStream`, Helvetica, one `showText` per line) in two layouts
- Test: `.../core/service/rentalindex/DldResultParserTest.java`

**Interfaces:**
- Produces: `record DldResult(int dldPercent, BigDecimal increase, BigDecimal rentAfter, String premiseNo, LocalDate contractEnd, BigDecimal currentRent, BigDecimal indexMin, BigDecimal indexMax, String category, String building, String area, String dldPropertyType, String issuedAt)`; `DldResult DldResultParser.parse(byte[] pdf)` throwing `BusinessRuleViolationException` with `rentalIndex.notDldResult` (missing title/issuer/required value) or `rentalIndex.figuresDontAddUp`.

- [ ] **Step 1: Fixtures.** `DldPdfFixtures.layoutOctober(...)` writes these lines (fictional values as parameters): "Rental Index Result" / "Rental Index Type Percentage increase Amount of increase" / "Residential - Unit {pct}% {inc} AED Annually" / "Rent amount after increase Building DEWA (Premise No.)" / "{after} AED Annually {building} {premise}" / "Property Category Contract End Date Current Annual Rent" / "{category} {dd/MM/yyyy} {rent with ,###.00} AED" / "From {min} to {max} AED per year" / "{d MMMM yyyy, h:mm a}" / "Dubai Land Department, Dubai". `layoutNovember(...)`: "Rental Index Result" / "Rental Index Type Area Property Type" / "Residential {area} Apartment" / "Category Percentage Increase Rent amount after increase" / "{category} {pct}% {after} AED Annually" / "Amount of Increase Contract End Date Current Annual Rent" / "{inc} AED Annually {dd/MM/yyyy} {rent} AED" / "DEWA (Premise No.) Building Name" / "{premise} {building}" / "Rental Index for Same Property Type*" / "From {min} to {max} AED per year" / "Dubai Land Department, Dubai".
- [ ] **Step 2: Failing tests** (fictional: premise 123456789, building "Test Tower", category "2 bed rooms+hall", rent 120000, 5%, inc 6000, after 126000, range 138000–162000, end 28/02/2027): `readsOctoberLayout`, `readsNovemberLayout` (both: every field equal; area "Test Area" only in November); `refusesNonDldPdf` (a PDF with "Invoice" text → `rentalIndex.notDldResult`); `refusesFiguresThatDontAddUp` (after = 126500 → `rentalIndex.figuresDontAddUp`); `refusesWrongIncrease` (inc 7000, after 127000 → `figuresDontAddUp`); `refusesGarbage` (`new byte[]{1,2,3}` → `notDldResult`).
- [ ] **Step 3:** Run → FAIL.
- [ ] **Step 4: Implement** with `Loader.loadPDF` + `PDFTextStripper.setSortByPosition(true)`; value-by-shape rules exactly as spec §7 and the POC: title `^Rental Index Resul?t?\s*$` (multiline) and "Dubai Land Department"; pct `(?<![\d.])(\d{1,2})%`; range `From (\d+(?:\.\d+)?) to (\d+(?:\.\d+)?) AED per year`; premise `(?<!\d)(\d{9})(?!\d)`; end `(?<![\d/])(\d{2}/\d{2}/\d{4})(?![\d/])`; exactly two `([\d,]+(?:\.\d+)?) AED Annually` (smaller = increase); rent `([\d,]+(?:\.\d{2})?) AED(?! Annually| per year)(?!\w)`; category `(?i)(studio|\d+ ?bed ?rooms?(?: ?\+ ?hall)?)`; building `^(?:\d{9} (.+)|.*AED Annually (.+) \d{9})\s*$`; area/type `^Residential (.+?) (Apartment|Villa|Hotel Apartment)\s*$`. Self-check: rent + increase = after, and round(rent × pct / 100) = increase.
- [ ] **Step 5:** PASS. **Step 6:** Commit `feat(rental-index): read DLD rental index result PDFs by value shape`.

### Task 4: Range store and its API (flag-gated)

**Files:**
- Modify: `194-rental-index.yaml` (changeSet `194-rental-index-rates`: table `rental_index_rates` per spec §5; FKs to `landlord_org`, `properties`, `buildings` (nullable), `units` (`source_unit_id`, nullable, `ON DELETE SET NULL`); index `ix_rir_lookup (tenant_id, property_id, building_id, unit_type, checked_on DESC)`; rollback)
- Create: `domain/entity/RentalIndexRate.java`, `domain/repository/RentalIndexRateRepository.java`, enums `RentalIndexSource {DLD_PDF, MANUAL, DLD_API}`, `DldPropertyType {APARTMENT, VILLA, HOTEL_APARTMENT}`
- Create: `core/service/rentalindex/RentalIndexRateService.java`, `api/RentalIndexController.java`, DTOs `api/dto/rentalindex/{RentalIndexRateDTO, ManualRateRequest, ParsedResultDTO}.java`
- Modify: `core/service/BlobStorageService.java` — `UploadResult uploadRentalIndexDoc(UUID tenantId, byte[] pdf)` (path `rental-index/{uuid}.pdf`, same container scheme as `uploadBranding`)
- Test: `.../api/RentalIndexControllerIT.java`, `.../core/service/rentalindex/RentalIndexRateServiceIT.java`

**Interfaces:**
- Consumes: Task 3 `DldResultParser.parse`, Task 2 `RentalIndexMath`.
- Produces: `Optional<RentalIndexRate> RentalIndexRateService.inForce(Unit unit, LocalDate on)` — lookup order spec §14.6 (building range, then property-level range with `building_id IS NULL`; newest `checked_on`, then `created_at`; not revoked; `expires_on >= on`); `boolean RentalIndexRateService.applies(Unit unit)` (flag on, emirate DUBAI, residential type); `enum IndexStatus {OK, NO_INDEX, NOT_DUBAI, NO_EMIRATE, NOT_RESIDENTIAL}` with `IndexStatus statusFor(Unit unit, LocalDate on)`.
- Endpoints (all `requireFeature()` → 404 when off; read: SUPER_ADMIN, TENANT_ADMIN, PROPERTY_MANAGER, ACCOUNTANT; write: SUPER_ADMIN, TENANT_ADMIN, PROPERTY_MANAGER; property scope enforced):
  - `GET /api/v1/rental-index/rates?propertyId=` → `List<RentalIndexRateDTO>` (includes revoked/expired with flags)
  - `POST /api/v1/rental-index/parse` multipart `file`, optional `unitId`, `leaseId` → `ParsedResultDTO` (fields + checks `premiseMatches`, `endMatches`, `rentMatches` as `Boolean` or null) — stores nothing
  - `POST /api/v1/rental-index/rates/from-pdf` multipart `file`, `propertyId`, optional `buildingId`, `unitType`, optional `unitId` → `RentalIndexRateDTO` (stores PDF; `rentalIndex.premiseMismatch` when `unitId` given and premise differs)
  - `POST /api/v1/rental-index/rates/manual` `ManualRateRequest(propertyId, buildingId?, unitType, dldPropertyType, category?, indexMin, indexMax, checkedOn)` → DTO (`rentalIndex.rangeInvalid` when min ≤ 0 or max < min)
  - `POST /api/v1/rental-index/rates/{id}/revoke` → DTO
  - `GET /api/v1/rental-index/rates/{id}/document` → PDF bytes via `BlobStorageService.download`
  - `GET /api/v1/rental-index/caps?leaseIds=a,b` → `List<{leaseId, status, allowedPercent, capRent, baseRent}>` (for the queue; uses Task 5's cap computation via `LeaseRenewalService.indexCapFor(leaseId)`)
- `expires_on = checked_on + 12 months`; `checked_on` for a PDF = issue date when read, else today.

- [ ] **Step 1: Failing tests.** Service IT: `lookupPrefersBuildingOverProperty`, `newestCheckedOnWins`, `revokedIgnored`, `expiredIgnored`, `otherUnitTypeIgnored`, `notDubaiStatus`, `noEmirateStatus`, `retailNotResidential`, `flagOffAppliesFalse`. Controller IT: `flagOffReturns404` (every endpoint), `renterForbidden`, `otherTenantCannotSeeRates`, `uploadFromPdfStoresRateAndDocument` (synthetic PDF from `DldPdfFixtures`), `premiseMismatchRefusedWhenUnitGiven`, `manualRangeInvalidRefused`, `revokeKeepsRowFlagged`, `parseReturnsChecks`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. Reads `@Transactional(readOnly = true)`. **Step 4:** PASS. **Step 5:** Commit `feat(rental-index): range store, DLD upload and API behind RENTAL_INDEX`.

### Task 5: Cap in renewal preview and renew

**Files:**
- Modify: `194-rental-index.yaml` (changeSet `194-renewal-index-checks`: table per spec §5 with `lease_id`, `successor_lease_id`, `rate_id` FKs; rollback)
- Create: `domain/entity/RenewalIndexCheck.java`, repository
- Modify: `api/dto/lease/RenewLeaseRequest.java` (add `String indexOverrideReason` last; keep the 5-arg and existing constructors compiling)
- Modify: `api/dto/lease/RenewalPreviewDTO.java` (add nullable: `indexStatus`, `indexMin`, `indexMax`, `indexAverage`, `indexGapPercent`, `indexAllowedPercent`, `indexCapRent`, `indexAboveCap` (Boolean), `indexRateId`, `indexSource`, `indexCheckedOn`, `indexUnitResult` (Boolean: rate's `source_unit_id` is this unit), `noticeDeadline`, `noticePassed` (Boolean))
- Modify: `core/service/lease/LeaseRenewalService.java` (`RenewalPlan` gains `IndexAssessment index`; `plan()` computes it; `renew()` enforces + writes check; new `public IndexAssessment indexCapFor(UUID leaseId)` for the queue using the current rent and a 12-month successor term)
- Modify: `api/LeaseController.java` renewal-preview: optional query param `indexOverrideReason` not needed (preview never blocks)
- Test: `.../core/service/lease/LeaseRenewalIndexIT.java`

**Interfaces:**
- Consumes: Task 4 `inForce`, `statusFor`, `applies`; Task 2 `RentalIndexMath.of`, `annualise`.
- Produces: `record IndexAssessment(IndexStatus status, RentalIndexRate rate, RentalIndexMath.Cap cap, BigDecimal annualBase, BigDecimal annualNew, boolean aboveCap, LocalDate noticeDeadline, boolean noticePassed)`; when the unit's own DLD result is the rate in force and has `dld_percent`, the cap uses `dld_percent` and `capRent(annualBase, dld_percent)`.
- Rules: flag off or `!applies` ⇒ `index = null` except status for NOT_DUBAI/NO_EMIRATE/NOT_RESIDENTIAL when flag on; renew unchanged. `annualBase = annualise(plan.baseRent, predecessor.start, predecessor.end)`; `annualNew = annualise(plan.newRent, r.startDate, r.endDate)`; `aboveCap = annualNew > cap.capRent`. `noticeDeadline = predecessor.endDate − 90 days`; `noticePassed = today > noticeDeadline`. Renew with `aboveCap` and blank `indexOverrideReason` → `BusinessRuleViolationException("…", "renewal.indexOverrideRequired", Map.of("cap", capRent))`; reason > 500 chars → same code family `renewal.indexOverrideTooLong`. When the index applied (status OK), write `RenewalIndexCheck` (snapshot figures, `above_cap`, reason, `decided_by`, `successor_lease_id`) in the same transaction.

- [ ] **Step 1: Failing tests:** `flagOffPreviewAndRenewUnchanged` (index fields null, no check rows); `previewShowsCap` (range 138000–162000, base 120000 → 5%, cap 126000); `renewAboveCapWithoutReasonRefused`; `renewAboveCapWithReasonStoresCheck`; `renewWithinCapStoresCheckWithoutReason`; `noRangeRenewsAsToday` (status NO_INDEX, no check row); `sixMonthRenewalAnnualised` (predecessor 1-year base 120000, successor 6 months at 63000 → annualNew ≈ 126,690 → above cap; at 62000 → within); `unitOwnDldPercentWins` (rate from unit PDF with dld_percent 10 over a range whose midpoint math says 5); `noticeDeadlineAndPassed`; `checkIsSnapshotAfterRevoke` (revoke the rate after renew: stored check unchanged).
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** PASS, plus run existing `*Renew*` test classes (`./gradlew test --tests '*Renew*' --offline`) — all green. **Step 5:** Commit `feat(renewal): rental index cap in preview, override reason enforced on renew`.

### Task 6: Bulk renew and queue caps

**Files:**
- Modify: `api/dto/lease/LeaseBatch.java` (`RenewRequest` gains `String indexOverrideReason` last; `RenewRow` gains `Integer indexAllowedPercent`, `BigDecimal indexCapRent`, `Boolean indexAboveCap`, `String indexStatus`)
- Modify: `core/service/lease/LeaseBatchService.java` (`termsFor` passes the reason into `RenewLeaseRequest`; row fills index fields from the plan)
- Modify: `api/RentalIndexController.java` `/caps` (Task 4 stub → real)
- Test: `.../core/service/lease/LeaseBatchRenewIndexIT.java`, extend `RentalIndexControllerIT` with `capsForQueue`

**Interfaces:** Consumes Task 5 `IndexAssessment`, `indexCapFor`.

- [ ] **Step 1: Failing tests:** `mixedRowsWithoutReason` (Review Focus 5); `sharedReasonStoredOnEveryAboveCapRow`; `flagOffBulkUnchanged`; `capsForQueue` returns OK/NO_INDEX/NOT_DUBAI per lease.
- [ ] **Step 2–4:** FAIL → implement → PASS (+ existing `LeaseBatch*` tests). **Step 5:** Commit `feat(renewal): rental index in bulk renew and queue caps`.

### Task 7: Web — DEWA premise number (flag-independent)

**Files:**
- Modify: `web/src/app/[locale]/dashboard/properties/[id]/units/page.tsx` (type gains `dewaPremiseNumber?: string|null`; create form input `data-testid="unit-dewa"`; list column "DEWA premise no." with inline edit button `unit-dewa-edit-{id}` → small inline input + save calling the PUT)
- Create: `web/src/lib/units/dewa.ts` (`normaliseDewa(s: string): string`, `dewaProblem(s: string): "invalid" | null` mirroring `^\d{6,12}$`)
- Modify: `web/messages/en.json`, `ar.json` (labels + `Common.errors.unit.dewaInvalid`, `.dewaTaken`); import help text that lists columns, if present (grep `SizeSqft` in `web/src`)
- Test: `web/src/lib/units/__tests__/dewa.test.ts`, page test if the units page has one (`__tests__` beside it)

- [ ] **Step 1:** Failing vitest: `normaliseDewa("681 204 553") === "681204553"`; `dewaProblem("12AB") === "invalid"`; `dewaProblem("") === null`; field renders with flag off (mock `useTenantFeatures` returning false).
- [ ] **Step 2–4:** FAIL → implement → `cd web && npx vitest run src/lib/units` PASS. **Step 5:** Commit `feat(web): DEWA premise number on units`.

### Task 8: Web — property "Rental index" tab

**Files:**
- Create: `web/src/lib/api/rentalIndex.ts` (types mirroring Task 4 DTOs; `rentalIndexApi.{list, parse, fromPdf, manual, revoke, documentUrl, caps}`)
- Create: `web/src/components/rentalIndex/useRentalIndexEnabled.ts` (`isEnabled("RENTAL_INDEX") && hasPermission(role, "canViewRentalIndex")`)
- Create: `web/src/app/[locale]/dashboard/properties/[id]/_components/RentalIndexTab.tsx`, `.../_components/RentalIndexAddDialog.tsx`, `web/src/components/rentalIndex/CheckOnDld.tsx`, `web/src/components/rentalIndex/fillFromMiftah.ts`
- Modify: `properties/[id]/page.tsx` (tab `"rentalIndex"` shown when enabled and `property.emirate === "DUBAI"`); `web/src/lib/rbac.ts` (`canViewRentalIndex`, `canManageRentalIndex` = SUPER_ADMIN, TENANT_ADMIN, PROPERTY_MANAGER (+ ACCOUNTANT view)); `messages/en.json`/`ar.json` namespace `RentalIndex`
- Test: `web/src/app/[locale]/dashboard/properties/[id]/_components/__tests__/RentalIndexTab.test.tsx`, `web/src/components/rentalIndex/__tests__/fillFromMiftah.test.ts`

**Interfaces:**
- `fillFromMiftah.ts`: `bookmarkletHref(): string` (`"javascript:" + encodeURIComponent(FILL_SOURCE)`, where FILL_SOURCE is the POC script: chosen-list picking on `#searchWithDDL` "DEWA (Premise No.)" and `#selectPropertyType`, then `#dewaNumber`, `#annualRent`, `#ContractEndDate`); `clipboardPayload(d: {end: string; typeLabel: "Apartment"|"Villa"|"Hotel Apartment"; number: string; rent: number}): string` → `"MIFTAH-DLD:" + JSON`.
- `CheckOnDld` props `{ end: string; typeLabel; dewa: string|null; rent: number }`: copy buttons per value, "Copy for DLD" (clipboardPayload), "Open DLD rental index" (`https://dubailand.gov.ae/en/eservices/rental-index-overview/rental-index/#/`, new tab, `rel="noopener"`), and a collapsible "Set up Fill from Miftah" with the draggable bookmark and 3-line instructions. Missing DEWA → note "Add the unit's DEWA premise number first" with a link to the units tab.
- Tab: table (type, building, range, average, source, checked on, expires on, added by, PDF link, status badge Active/Expired/Revoked) and actions "Add from DLD result" (upload → shows parsed fields with ✓/✗ checks → confirm building + unit type → save), "Enter by hand", "Revoke" (confirm). Category→unit type proposal: "Studio"→STUDIO, "N bed rooms+hall"→BHK N (N≤3), else no proposal.

- [ ] **Step 1:** Failing tests: tab hidden when flag off; hidden for non-Dubai property; lists rates with status badges; upload flow posts to `fromPdf` with chosen `unitType`; `clipboardPayload` exact string; `bookmarkletHref()` starts with `javascript:` and decodes to source containing `#dewaNumber`.
- [ ] **Step 2–4:** FAIL → implement → PASS. **Step 5:** Commit `feat(web): rental index tab, DLD upload and Check on DLD`.

### Task 9: Web — renew dialog, bulk renew and queue

**Files:**
- Modify: `web/src/lib/api/leasing.ts` (`RenewLeaseInput.indexOverrideReason?`, `RenewalPreview` index fields, `BulkRenewInput.indexOverrideReason?`, `BulkRenewRow` index fields)
- Create: `web/src/components/leases/RentalIndexPanel.tsx`
- Modify: `RenewLeaseDialog.tsx` (panel above the rent step when `preview.indexStatus`; when `indexAboveCap` show red notice `data-testid="renew-index-above"` instead of `renew-rent-notice` and a required textarea `renew-index-reason` (1–500); Renew disabled reason shown inline; send `indexOverrideReason`; show `noticeDeadline` and a warning when `noticePassed`; "Attach this unit's DLD result" → `RentalIndexAddDialog` prefilled with the unit, then re-fetch preview)
- Modify: `BulkRenewDialog.tsx` (after the per-row preview/result shows above-cap rows, a shared reason textarea `bulk-index-reason`; rows failing with `renewal.indexOverrideRequired` listed via `serverText`)
- Modify: `RenewalQueue.tsx` ("Max increase" column when `useRentalIndexEnabled()`, batch-loaded from `rentalIndexApi.caps` like the rating column: "+5% (126,000)", "No index", "Not Dubai", "Set the emirate")
- Modify: `messages/en.json`, `ar.json` (`RentalIndex` strings; `Common.errors.renewal.indexOverrideRequired` "This rent is above the rental index cap of AED {cap}. Give a reason to renew at this rent.")
- Test: `web/src/components/leases/__tests__/RenewLeaseDialog.test.tsx` (extend), `RentalIndexPanel.test.tsx`, `renewals/__tests__/RenewalQueue` (extend if present)

- [ ] **Step 1:** Failing tests: panel absent when `indexStatus` null (flag off, existing tests unchanged); panel shows "5%" and "126,000"; above cap → reason required, request carries reason; within cap → no reason field; notice passed → warning; queue column hidden when flag off, values when on.
- [ ] **Step 2–4:** FAIL → implement → `npx vitest run src/components/leases` PASS. **Step 5:** Commit `feat(web): rental index panel in renew and bulk renew, queue column`.

### Task 10: Whole-branch verification and ship

- [ ] **Step 1:** `cd backend && ./gradlew test --offline` (full, ~38 min, run in background; wait with `pgrep -f "[G]radleWrapperMain|[G]radleWorkerMain"`) — 0 failures; `./gradlew test -PincludeTags=golden --offline` green.
- [ ] **Step 2:** `cd web && npx tsc --noEmit && npm run lint && npx vitest run` — clean.
- [ ] **Step 3:** Local recording stack (`tutorials/recording-stack.sh build/start`, Oasis Crest): flag off → units page shows DEWA field and saves; renew dialog unchanged. Flag on (super-admin toggle) → add a manual range on Tower for BHK2/BHK1, renew a lease above cap: reason required; within: no reason; queue column shows caps. Upload a synthetic DLD PDF from `DldPdfFixtures` written to the scratchpad.
- [ ] **Step 4:** Push, open PR, run the review loop (fresh reviewer on the whole diff), fix, re-run suites.
- [ ] **Step 5:** Merge and deploy per standing OK (2026-09-23); confirm the deploy run succeeds; smoke prod: `/api/v1/rental-index/rates` → 404 (flag off), unit DEWA set endpoint works on the demo tenant, renewal preview unchanged.
