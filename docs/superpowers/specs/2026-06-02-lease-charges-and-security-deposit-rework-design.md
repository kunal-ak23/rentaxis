# Flexible Lease Charges + Security Deposit Rework — Design

**Date:** 2026-06-02
**Status:** Approved (design)
**Author:** Kunal Sharma (with Claude)
**Branch:** `feat/lease-charges-rework`

## Problem

The lease schema hardcodes two fee columns — `admin_fee` and `parking_remote_fee` — each with its own VAT flag. This doesn't scale: there's no way to record a custom charge (e.g. monthly maintenance, chiller, fit-out), to mark a charge recurring vs one-time, or to control VAT per charge. Today these amounts are also **cosmetic** — they are never added to any `payment_schedule.amount`; they only appear in the contract PDF (Section 3) and as the `/ADMIN/SD/REMOTE` label suffix on installment #1. Separately, the **security deposit** is not its own schedule line — it's folded into that same cosmetic label.

## Goals

- Replace the fixed fee columns with a **flexible `lease_charges`** model: custom name, amount, VAT-applicable flag, and a frequency (one-time vs per-installment).
- Make charges **real collected money** in the payment schedule (additive 5% VAT when applicable).
- Make the **security deposit its own schedule row**.
- Keep it simple and single-path.

## Non-goals

- No backward compatibility. The customer has not started using the app, so the legacy fee columns are **dropped**, not deprecated. No data migration/backfill.
- Mobile (`lease_detail_screen`) is a **fast-follow** PR, not part of this one.
- Rent's existing VAT handling is unchanged (rent VAT stays inclusive; charges VAT is additive — documented difference).

## Decisions (from brainstorming)

| Question | Decision |
|---|---|
| Data model | New `lease_charges` table; rent and security deposit remain their own concepts. |
| Charges in schedule | **Hybrid**: ONE_TIME → its own schedule row; PER_INSTALLMENT → folded into each rent installment. |
| VAT | **Additive 5%**, included in the collected amount (schedule + contract consistent). |
| Existing leases | **No backward compat — drop legacy columns.** |
| Security deposit | Its own schedule row (`is_security_deposit`), amount = `deposit_amount`, own editable cheque date. |
| Mobile | Web + backend now; mobile fast-follow. |
| Legacy lease editing | N/A (no legacy data to support). |

## Design

### Data model

**New table `lease_charges`** (Liquibase changeset `61-lease-charges.yaml`):
- `id` UUID PK
- `tenant_id` UUID NOT NULL (tenant isolation, consistent with other tables / `TenantAspect`)
- `lease_id` UUID NOT NULL FK → `leases(id)` (cascade delete)
- `name` VARCHAR(120) NOT NULL
- `amount` DECIMAL(12,2) NOT NULL DEFAULT 0
- `vat_applicable` BOOLEAN NOT NULL DEFAULT false
- `frequency` VARCHAR(20) NOT NULL  (`ONE_TIME` | `PER_INSTALLMENT`)
- `created_at` TIMESTAMP
- Index on `lease_id`, index on `tenant_id`.

**`payment_schedules`** — add (same changeset):
- `is_security_deposit` BOOLEAN NOT NULL DEFAULT false
- `is_charge` BOOLEAN NOT NULL DEFAULT false

**`leases`** — drop (same changeset): `admin_fee`, `admin_fee_vat_applicable`, `parking_remote_fee`, `parking_remote_vat_applicable`, `security_deposit_vat_applicable` (the security deposit is refundable → never VAT).
**Keep**: `deposit_amount`, `rent_vat_applicable`.

**Entities/repos**: `LeaseCharge` entity + `ChargeFrequency` enum (`ONE_TIME`, `PER_INSTALLMENT`) + `LeaseChargeRepository`. Add `isSecurityDeposit` / `isCharge` to `PaymentSchedule`. Remove the four dropped fields from `Lease`.

### DTOs

- `LeaseChargeDTO { String name; BigDecimal amount; boolean vatApplicable; ChargeFrequency frequency; }`
- `CreateLeaseDTO`: remove `adminFee`, `parkingRemoteFee`, `adminFeeVatApplicable`, `parkingRemoteVatApplicable`, `securityDepositVatApplicable`; add `List<LeaseChargeDTO> charges`. Keep `depositAmount`, `rentVatApplicable`.
- `LeaseDTO`: same — expose `List<LeaseChargeDTO> charges`.
- `PaymentScheduleDTO` / `mapToDTO`: add `isSecurityDeposit`, `isCharge`.

### Schedule generation (`LeaseService.createLease` + `PaymentScheduleService.generateScheduleForLease`)

VAT helper: `collected(amount, vat) = vat ? amount × 1.05 : amount` (round to 2dp; `VAT_RATE` reused).

1. **Persist charges**: on create, write `dto.charges` to `lease_charges`.
2. **PER_INSTALLMENT charges** → folded into **each** rent installment:
   `installment[i].amount = rentShare[i] + Σ collected(c.amount, c.vat)` over per-installment charges.
   Rent distribution (`ChequeRoundingCalculator.distribute(totalRent, n, depositAmount)`) is unchanged; recurring charges are added on top of each installment (clean-denomination rounding no longer holds — accepted). The installment `purpose_label` lists folded charge names (e.g. `RENT - 2ND INSTALLMENT (+ Maintenance)`).
3. **ONE_TIME charges** → each its own `payment_schedule` row: `is_charge=true`, `installment_number=0`, `amount = collected(c.amount, c.vat)`, `purpose_label = c.name`, `due_date = lease.startDate`, `status=PENDING`, blank cheque fields (scan-able), payment method from lease.
4. **Security deposit** → its own row: `is_security_deposit=true`, `installment_number=0`, `amount = deposit_amount`, `purpose_label="SECURITY DEPOSIT"`, `due_date = lease.startDate`, blank/editable cheque fields, `status=PENDING`. Created when `deposit_amount > 0`.
5. **Booking deposit** → unchanged (`is_booking_deposit`).
6. **Installment #1 label** — `/ADMIN/SD/REMOTE` suffix removed entirely.
7. `generateScheduleForLease` preservation filter (`!isBookingDeposit`) extended to also skip `isSecurityDeposit` and `isCharge` rows so they aren't clobbered/duplicated on regeneration.

Edge cases: `deposit_amount = 0` → no SD row. Empty `charges` → no charge rows, installments = rent only. No-rent lease (`totalRent ≤ 0`) → rent generation still returns early, but SD + one-time charge rows are still created (they're added in `LeaseService`, independent of rent generation).

### VAT in payment schedule

- Charges: **additive** — collected amount includes 5% when `vat_applicable`.
- Rent: unchanged (existing `rentVatApplicable` inclusive reporting at `PaymentScheduleService:557-563`).
- Security deposit: **never VAT** (refundable); collected amount = `deposit_amount` as-is. The `security_deposit_vat_applicable` flag is dropped.
- This intentional asymmetry (rent inclusive, charges additive) is documented in code comments.

### Contract generation (`ContractGenerationService`)

- Section 3 (charges table) renders from `lease_charges` (rent + security deposit shown as their own lines; each charge a row with amount, VAT%, VAT amount, total). No legacy column reads (columns dropped).
- Section 4 (payment details) reads `payment_schedule` rows — now includes one-time charge rows + the SD row + per-installment folded amounts. Verify ordering/labels render sensibly.
- `VAT_RATE = 0.05` reused.

### Portfolio import (`PortfolioImportPersistService`)

- The import sheet's `admin_fee` / `parking_remote_fee` columns map into `lease_charges` ONE_TIME rows at import (carrying VAT intent), so imported leases use the new model. No Excel template change in this pass. The `/ADMIN/SD/REMOTE` label generation there is removed.

### Frontend (web)

- **`LeaseWizard`** (Charges & VAT step): replace the fixed Admin Fee / Parking Remote inputs + their VAT toggles with a dynamic **"Other charges"** repeater — each row: `name` (text), `amount` (number), `VAT` (toggle), `frequency` (select: One-time / Per installment), with add/remove. Keep Rent (+ rent VAT) and Security Deposit (no VAT toggle — refundable). Submit `charges: [...]`. Update the step's summary line.
- **`LeaseMetadataEditor`**: same charges repeater for DRAFT leases; remove the legacy fee fields. Reads/writes `charges`.
- **`PaymentScheduleEditor`**: add `isSecurityDeposit` / `isCharge` to `ScheduleRow`; sort all non-rent rows (booking, SD, charges) last; show row markers — `B` (booking), `S` (security deposit), `C` (charge); fallback labels; per-installment charges visible via the installment's `purposeLabel`. Amount + cheque fields editable; per-row Scan Cheque works as today.
- Types: add `LeaseChargeDTO` shape; update lease create/edit payloads.

### Testing

**Backend**
- Lease creation persists `lease_charges`; per-installment charge folds into every installment with additive VAT; one-time charge → own row with VAT; SD row created with `deposit_amount`; `deposit_amount=0` → no SD row; empty charges → rent-only installments; no-rent lease still gets SD/one-time rows.
- Contract generation renders charges from `lease_charges`.
- Portfolio import maps fee columns → ONE_TIME charges.

**Frontend**
- Wizard charges repeater: add/remove rows, submit shape includes `charges`.
- `PaymentScheduleEditor` renders charge ("C") + SD ("S") rows, sorted last.
- Cheque-amount mismatch warning (separate spec) compares against the now charge-inclusive installment amount.

**Manual**
- Create a lease with: rent, security deposit, a PER_INSTALLMENT "Maintenance" (VAT on), and a ONE_TIME "Admin Fee" (VAT on). Expect: each rent installment = rent + maintenance×1.05; a standalone "Admin Fee" row = fee×1.05; a "SECURITY DEPOSIT" row = deposit; contract Section 3/4 consistent.

### Risks / notes

- Dropping columns is irreversible, but acceptable (no production data).
- Clean-denomination cheque rounding no longer holds once recurring charges fold in.
- Mobile lease detail will not show custom charges until the fast-follow PR.
- This design depends on the schedule-editor endpoint fix (`fix/payment-schedule-editor-lease-endpoint`) to actually display the new SD/charge rows; ensure that fix is merged.

## Relationship to other in-flight work

- **Cheque amount extraction + wizard bulk upload** (`feat/cheque-amount-extraction`): independent; its mismatch-warning compares cheque amount to installment amount, which now includes folded per-installment charges.
- **Schedule-editor endpoint fix** (`fix/payment-schedule-editor-lease-endpoint`): prerequisite for these rows to be visible.
