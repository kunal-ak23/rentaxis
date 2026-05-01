# Lease Agreement PDF — Client Format Design

**Date:** 2026-04-30
**Status:** Approved (brainstorming complete, ready for implementation plan)
**Source format:** `GH2-603_001.pdf` shared by client (Tarek Mohammed Alashram, Galah Residence 2 sample lease)

## Goal

Generate a 6-page bilingual (English + Arabic) lease agreement PDF that matches the client-provided format, populated entirely from RentAxis lease data. Once printed and signed in wet ink, the scanned signed copy is uploaded as a `LeaseAttachment` (existing flow, no change).

## Scope

This work **upgrades** the existing contract generation pipeline rather than rebuilding it. The existing `ContractGenerationService`, `contract-template.html`, `LeaseDocument` storage, and `LeaseAttachment` (signed-copy upload) flow are all preserved. The HTML template is rewritten and a small set of new fields is added to `Lease`, `PaymentSchedule`, and `LandlordOrg`.

## Out of scope

- VAT report changes (existing `FinancialTransaction.vatApplicable/vatRate` and `VatReturnDTO` already cover this end of the pipeline).
- Per-LandlordOrg editable T&Cs — the 53 terms are hardcoded in the template for now; a future change can promote them to a CRUD-backed table when a second landlord asks for different clauses.
- Digital signing / e-signature integration — out of scope; landlord prints, signs/stamps physically, uploads scan.
- Fines, late penalties, replacement cheques in the agreement document — those live on the rent ledger / receipt, not on the original signed agreement.

## Format reference (from PDF)

**Page 1:**
- Header band: landlord name, P.O. Box address, telephone (centered, every page).
- Title: **LEASE AGREEMENT**.
- Top row: `Contract No. : 1751` (left), `Agreement Date : 17 Apr 2026` (right).
- **Section 1. Tenant Information** — Landlord, Building Name, Tenant, Email, Contact No.
- **Section 2. Lease Period Information** — Lease Start Date, Lease End Date, Flat No.
- **Section 3. Property Information** — table: S No / Particulars / Amount / VAT % / VAT Amount / Amount With VAT. Rows: Rent, Admin Fee, Security Deposit, Parking Remote. TOTAL row.
- **Section 4. Payment Details** — table: S No / Chq.No / Che Date / In Favour Of / PayeeBank / Amount.
- "Amount In Words" (left) + grand total (right).
- Three signature blocks at bottom: Tenant Signature (left), Stamp (center), Landlord Signature (right).

**Pages 2–6:** ADDITIONAL TERMS AND CONDITIONS — 53 numbered bilingual terms (English left column, Arabic right column). Same three signature blocks at bottom of every page. Page header repeats landlord name + page number.

## Data model changes

### `Lease` (new fields)

| Field | Type | Default | Purpose |
|---|---|---|---|
| `contractNumber` | `Long` | null until contract generated | Sequential per `LandlordOrg`. Assigned on first `generate-contract` call. Unique constraint `(tenant_id, landlord_org_id, contract_number)`. |
| `agreementDate` | `LocalDate` | null (form-editable; falls back to `LocalDate.now()` at generation) | Header right side of page 1. |
| `adminFee` | `BigDecimal(12,2)` | `0.00` | Section 3 row. Hidden in PDF if `0`. |
| `parkingRemoteFee` | `BigDecimal(12,2)` | `0.00` | Section 3 row. Hidden if `0`. |
| `rentVatApplicable` | `boolean` | `false` (default `true` if `Property.type == COMMERCIAL` at lease creation) | If true → 5% VAT on rent shown in Section 3. |
| `adminFeeVatApplicable` | `boolean` | `false` | Same. |
| `securityDepositVatApplicable` | `boolean` | `false` | Same. |
| `parkingRemoteVatApplicable` | `boolean` | `false` | Same. |

VAT rate is a constant `VatConstants.UAE_STANDARD_RATE = 5.00` in code. No need to store rate per lease.

### `PaymentSchedule` (new fields)

| Field | Type | Default | Purpose |
|---|---|---|---|
| `purposeLabel` | `varchar(120)` | auto-populated | "RENT - 2ND INSTALLMENT" / "RENT - 1ST INSTALLMENT/ADMIN/SD/REMOTE" / "BOOKING RECEIVED" / landlord-edited custom. |
| `isBookingDeposit` | `boolean` | `false` | Marks the pre-contract booking row. |

### `LandlordOrg` (new fields)

| Field | Type | Default | Purpose |
|---|---|---|---|
| `phone` | `varchar(40)` | null | Header per page: "Tel : +971 4 272 7070". |
| `stampImageUrl` | `varchar(500)` | null | Center of every page's signature row (round seal). If null, render an empty bordered placeholder. |

### Liquibase changeset

`45-lease-agreement-fields.yaml` — append-only (per project rule). Adds the columns above plus the unique index `uq_lease_landlord_contract_number ON (tenant_id, landlord_org_id, contract_number)`. All new columns are nullable or have safe defaults so existing rows remain valid.

### Explicitly NOT added

- No new tables. Terms live in the template; charges live as Lease columns (not a `LeaseCharge` line-item table).
- No VAT rate field on Lease (5% UAE standard is constant).
- No "InFavourOf bank-account" lookup — existing `PaymentSchedule.bankName` already holds the value.

## Template & rendering

### Files

- `backend/src/main/resources/templates/contract-template.html` — **rewritten** to match the client format.
- `backend/src/main/resources/templates/contract-terms-en.html` — **new partial**, 53 numbered T&Cs in English (verbatim from PDF pages 2–6).
- `backend/src/main/resources/templates/contract-terms-ar.html` — **new partial**, 53 corresponding Arabic terms.

The main template inlines both partials at render time via `String.replace("{{TERMS_EN}}", ...)` (the existing `ContractGenerationService` already uses `replace`-based substitution; OpenHtmlToPdf doesn't run Thymeleaf).

### Library

Keep **OpenHtmlToPdf 1.1.37** (already in `build.gradle`). It supports custom fonts (Noto Sans / Noto Sans Arabic — already registered), `<img>` from URL/path (for stamp), `@page` rules, `page-break-before/after`, and RTL via `dir="rtl"`.

### Rendered structure

**Page 1** — header band, title, contract metadata, Sections 1/2/3/4, amount-in-words + total, three signature blocks.

**Pages 2–6** — header band, title, two-column terms list (en left, ar right with `dir="rtl"`), three signature blocks at bottom. Page breaks placed at items 15, 26, 36, 49, 53 (matching the sample).

### Number-to-words

New utility: `backend/.../core/util/AmountInWordsUtil.java`.

```java
public static String toEnglishWords(BigDecimal amount, String currencyCode);
// e.g., toEnglishWords(60300.00, "AED") -> "AED Sixty Thousand Three Hundred Only"
```

Standard recursive thousands-block algorithm. No external dependency. Unit-tested for boundaries (0, 1, 19, 20, 99, 100, 999, 1000, 1751, 60300, 1000000, 999999999) and negative input (rejected with `IllegalArgumentException`). Fils/decimals are rounded to whole AED for the words line — matching the sample.

### Edge cases handled

- Admin fee / parking remote = 0 → row hidden in Section 3.
- No payment schedule entries → Section 4 renders an empty table with a "—" placeholder.
- Stamp image not uploaded → empty bordered box (so the printed PDF can be physically stamped).
- Renter has no Arabic name → falls back to English only in the "Tenant" line.
- Long landlord/property names → table cells use `word-wrap: break-word`.
- Property with no `address` → header omits the P.O. Box line entirely.

## API & service changes

### Existing endpoints (unchanged contract, expanded behavior)

**`POST /api/v1/leases/{id}/generate-contract`**

1. Load Lease + Unit + Property + Renter + LandlordOrg + paymentSchedule.
2. Assign `contractNumber` if null: `MAX(contract_number) WHERE landlord_org_id = ?` + 1, persist. Race-safe via DB unique constraint + `@Retryable` on `DataIntegrityViolationException`.
3. Set `agreementDate` to `LocalDate.now()` if null.
4. Build template variables (Section 3 rows skipping zero amounts; "Amount in Words"; Section 4 rows ordered by `chequeDate ASC` with `isBookingDeposit` row placed last).
5. Render with OpenHtmlToPdf → PDF bytes.
6. Upload via existing `LeaseAttachmentService` storage helper (Azure Blob primary, local fallback).
7. Save/replace `LeaseDocument` row of `type=CONTRACT` (regeneration overwrites the previous file).
8. Transition lease to `PENDING_SIGNATURE` if not already there.
9. Return DTO with `documentUrl`, `contractNumber`, `documentId`.

**`GET /api/v1/leases/documents/{docId}/download`** — unchanged. Filename becomes `lease-{contractNumber}.pdf`.

**`GET/POST/PUT /api/v1/leases/{id}/attachments`** — unchanged. Used after physical signing to upload the scanned signed copy.

### New endpoints

**`POST /api/v1/leases/{id}/generate-contract/preview`** — same logic but returns PDF inline, does NOT persist `LeaseDocument`, does NOT change lease status, does NOT reserve a `contractNumber` (uses placeholder `"DRAFT"`). Lets the landlord preview before committing.

**`POST /api/v1/landlord-org/stamp`** — new multipart upload endpoint mirroring the existing logo upload, sets `LandlordOrg.stampImageUrl`.

**`PATCH /api/v1/landlord-org/profile`** — accept new fields `phone` (and `stampImageUrl` if cleared via JSON).

### Service changes

**`ContractGenerationService`** — extended:
- `assignContractNumberIfNull(Lease)` (transactional, retryable).
- `buildSection3Rows(Lease)` — filter zero-amount; compute VAT per-component.
- `buildSection4Rows(Lease)` — ordered, includes booking row.
- `formatHeaderLines(LandlordOrg)`.
- Existing template-substitution loop kept; just more variables + the two terms partials.

**`LeaseService`** — extended:
- `createLease(...)` accepts new fields via `CreateLeaseDTO`.
- `defaultVatFlagsForProperty(Property)` — `true` for all four if `property.type == COMMERCIAL`.
- When generating `PaymentSchedule` rows, set `purposeLabel` based on installment number; if installment 1 bundles admin/SD/parking → suffix `"/ADMIN/SD/REMOTE"`.
- New `addBookingDeposit(leaseId, amount, chequeNumber, chequeDate, bankName)` — inserts a `PaymentSchedule` row with `isBookingDeposit=true`, `purposeLabel="BOOKING RECEIVED"`.

**`FinancialTransactionService`** — minor:
- When recording lease-related rent / admin / SD / parking transactions, read the matching `*VatApplicable` flag from the lease and stamp `vatApplicable` + `vatRate=5.00`. Existing VAT return logic untouched.

### DTO changes

- `CreateLeaseDTO` / `UpdateLeaseDTO` — add `adminFee`, `parkingRemoteFee`, `agreementDate`, four `*VatApplicable` flags, optional `bookingDeposit { amount, chequeNumber, chequeDate, bankName }`.
- `LeaseResponseDTO` — add `contractNumber`, `agreementDate`, four amounts, four VAT flags.
- `PaymentScheduleResponseDTO` — add `purposeLabel`, `isBookingDeposit`.
- `LandlordOrgDTO` — add `phone`, `stampImageUrl`.

## Frontend (Next.js)

**Lease create / edit form** (`web/src/app/[locale]/dashboard/leases/...`):
- "Charges" panel: Rent (existing), Admin Fee, Security Deposit (existing), Parking Remote — each with a "VAT 5%" toggle.
- Toggles default OFF; flip ON automatically when `Property.type === COMMERCIAL`. User can override.
- "Agreement Date" date picker (optional, placeholder "defaults to today").
- Optional "Booking Deposit" collapsible: amount, cheque#, date, bank.
- Inline-editable "In Favour Of" label per payment row in the existing schedule editor.

**Lease detail page**:
- "Generate Contract" → opens preview modal (uses new `/preview` endpoint), with a confirm button that calls the real `generate-contract` endpoint.
- After generation, contract number shown prominently (e.g., "Contract No. 1751").
- Existing Attachments panel gets a small inline note: "Upload the signed copy here once both parties have signed." No structural change.

**LandlordOrg profile page**:
- New input: Phone.
- New file upload: Stamp / Seal image. Preview thumbnail.

## Mobile (Flutter)

**Manager app** (`mobile/apps/manager/lib/screens/lease_detail_screen.dart` + lease create flow):
- Same form additions as web (Admin Fee, Parking Remote, VAT toggles, Agreement Date, Booking Deposit).
- "Generate / Download Contract" button on lease detail (likely already exists — wire to new endpoint, refresh contract number).
- LandlordOrg settings screen — add phone + stamp upload.

**Renter app** — no changes. Renter sees the generated PDF via existing lease-document download flow.

## i18n

New keys in both `en.json` and `ar.json`: `lease.adminFee`, `lease.parkingRemoteFee`, `lease.vatApplicable`, `lease.bookingDeposit`, `lease.contractNumber`, `lease.agreementDate`, `landlordOrg.phone`, `landlordOrg.stamp`. The 53 terms are baked into the template, not user-facing UI strings.

## Testing

**Backend unit tests:**
- `AmountInWordsUtilTest` — boundaries (0, 1, 19, 20, 99, 100, 999, 1000, 1751, 60300, 1000000, 999999999); negative input rejected.
- `ContractGenerationServiceTest` — golden-string test: build a known lease fixture, render to PDF bytes, assert the **HTML pre-PDF** contains expected substrings (contract number, all amounts, "Exempt" vs "5%", three signature labels per page, all 53 numbered terms). Avoid byte-comparing PDFs (timestamps/fonts make them unstable).
- `ContractGenerationServiceTest` — VAT branches: residential (all "Exempt"), commercial (all "5%"), mixed (rent 5%, admin Exempt).
- `ContractGenerationServiceTest` — zero admin/parking row hidden in Section 3.
- `ContractGenerationServiceTest` — booking-deposit row appears with the correct label.
- `LeaseServiceTest` — sequential `contractNumber` per LandlordOrg; concurrent generation produces distinct numbers (DB unique constraint enforces).

**Backend integration test:**
- One happy-path test: `POST /generate-contract` → `GET /documents/{id}/download` → assert `Content-Type: application/pdf`, non-zero length.

**Frontend / mobile:** No new automated tests (consistent with project conventions). Manual test plan:
- Create residential lease → generate → verify all sections.
- Create commercial lease → verify VAT 5% rendered everywhere.
- Add booking deposit → verify row in Section 4.
- Set admin/parking to 0 → verify rows hidden in Section 3.
- Verify Arabic terms render right-to-left.
- Upload stamp → verify it appears on every page.

## Rollout / risk

- No feature flag needed. New template is backward-compatible: existing leases regenerate cleanly because `contractNumber`/`agreementDate` get default values at generation time and zero-default fees don't render rows.
- No data migration of existing leases.
- Old contracts already generated under the previous template are simply replaced when the landlord regenerates.

## Open questions / future work

- The 53 terms are hardcoded for now. If a second landlord onboards and wants different clauses, promote terms to a CRUD-backed `LandlordTerms` table (tracked as future work).
- E-signature integration (DocuSign, etc.) is out of scope; revisit if the client requests it.
- Whether the "booking deposit" row should sort to the top or bottom of Section 4 — sample puts it at the bottom; default to that, can be changed cosmetically without a schema impact.
