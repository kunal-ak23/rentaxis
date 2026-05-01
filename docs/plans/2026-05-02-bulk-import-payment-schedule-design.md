# Bulk Portfolio Import — Payment Schedule + New Lease Agreement Fields

**Date:** 2026-05-02
**Status:** Approved
**Goal:** Extend the existing bulk-import workbook (`POST /api/v1/import/portfolio`) so it can carry every field the new lease wizard captures: per-row payment method, per-cheque details, booking deposits, charges, VAT toggles, agreement date, and a per-row lease status. Old templates must keep working.

## Context

The existing bulk import (designed 2026-04-03) supports a 4-sheet workbook (Properties / Units / Renters / Leases) and creates `ACTIVE` leases with auto-generated monthly schedules. Since then the lease agreement work (April–May 2026) added:

- `PaymentMethod` enum gained `BANK_TRANSFER`, `CASH`
- Lease entity gained `adminFee`, `parkingRemoteFee`, four VAT toggles, `agreementDate`, `depositPaymentMethod`
- Booking deposit modeled as a `PaymentSchedule` row with `isBookingDeposit=true`
- Per-installment `PaymentSchedule.paymentMethod` (replacing the lease-wide value)
- Per-installment cheque/transfer/online/cash details (cheque #, date, bank — same column repurposed as a "Unique ID")
- `paymentTerms` now means "cheque count distributed across the tenure" (was previously implicitly monthly)
- The wizard's lease detail page allows editing all of the above on a DRAFT lease

The bulk import currently captures none of those. This design closes that gap while preserving backward compatibility for users with the existing 10-column Leases sheet.

## Decisions (from brainstorming)

1. **Per-cheque details:** hybrid (Q1 = C). Auto-distribute by `PaymentTerms` is the default; an optional Cheques sheet lets admins override.
2. **Booking deposit:** four columns on the Leases sheet (Q2 = A) — `BookingDeposit_Amount/Number/Date/Bank`.
3. **Lease status:** per-row `Status` column on the Leases sheet (Q3 = C), defaults to `ACTIVE`.
4. **Cheques-sheet conflict resolution:** Cheques sheet wins (Q4 = B). If any rows for a lease, those rows ARE the schedule and `lease.paymentTerms` is overridden to match. If no rows, auto-distribute per `PaymentTerms`.
5. **Rent column semantics:** both annual and monthly accepted (Q5 = C). Exactly one of `RentAmount` or `MonthlyRent` must be filled; the other is computed.

## Workbook layout

### Properties, Units, Renters sheets

Unchanged. Existing structure documented in [2026-04-03-bulk-portfolio-import-design.md](./2026-04-03-bulk-portfolio-import-design.md).

### Leases sheet (extended)

Existing columns 0–10 keep their names and positions. New columns appended:

| Col | Header | Required | Notes |
|---|---|---|---|
| 0 | PropertyName | yes | join key |
| 1 | BuildingName | no | |
| 2 | UnitNumber | yes | |
| 3 | RenterEmail | yes | join key |
| 4 | StartDate | yes | ISO date |
| 5 | EndDate | yes | ISO date, > StartDate |
| 6 | RentAmount | one-of | annual rent |
| 7 | DepositAmount | no | |
| 8 | PaymentTerms | no | default `12` |
| 9 | PaymentMethod | no | dropdown widened to `CHEQUE / BANK_TRANSFER / ONLINE / CASH` (was `CHEQUE / ONLINE`) |
| 10 | EjariNumber | no | |
| 11 | MonthlyRent | one-of | exactly one of `RentAmount` or `MonthlyRent` must be set |
| 12 | AdminFee | no | numeric ≥ 0; default 0 |
| 13 | ParkingRemoteFee | no | numeric ≥ 0; default 0 |
| 14 | RentVatApplicable | no | bool; defaults to `true` for COMMERCIAL property type, else `false` |
| 15 | AdminFeeVatApplicable | no | same defaulting |
| 16 | SecurityDepositVatApplicable | no | same defaulting |
| 17 | ParkingRemoteVatApplicable | no | same defaulting |
| 18 | DepositPaymentMethod | no | default = `PaymentMethod` |
| 19 | AgreementDate | no | ISO date; defaults to today at contract-generation time |
| 20 | Status | no | `ACTIVE` (default) or `DRAFT` |
| 21 | BookingDeposit_Amount | no | numeric > 0 if any of cols 21–24 are set |
| 22 | BookingDeposit_Number | no | cheque #/ref/notes |
| 23 | BookingDeposit_Date | no | ISO date |
| 24 | BookingDeposit_Bank | no | string |

### Cheques sheet (new, optional)

| Col | Header | Required | Notes |
|---|---|---|---|
| 0 | PropertyName | yes | join into the lease |
| 1 | UnitNumber | yes | |
| 2 | RenterEmail | yes | |
| 3 | InstallmentNo | yes | 1-based positive integer; unique per lease |
| 4 | DueDate | yes | ISO date |
| 5 | ChequeOrPaymentDate | conditional | required for non-CASH methods |
| 6 | UniqueId | conditional | cheque # / ref / online ref / cash notes; required for CHEQUE |
| 7 | Bank | conditional | required for CHEQUE / BANK_TRANSFER / ONLINE |
| 8 | Amount | yes | numeric |
| 9 | Method | no | `CHEQUE / BANK_TRANSFER / ONLINE / CASH`; default = lease's PaymentMethod |

## Validation rules (Phase 1, dry-run)

### Leases sheet — new checks

- **Rent xor:** exactly one of `RentAmount`, `MonthlyRent` non-empty.
- **VAT toggles:** parse `true / false / 1 / 0 / yes / no / blank`. Blank → property-type default.
- **PaymentMethod / DepositPaymentMethod:** must be in `{CHEQUE, BANK_TRANSFER, ONLINE, CASH}`.
- **Status:** `ACTIVE` or `DRAFT` (case-insensitive). Default `ACTIVE`.
- **AgreementDate:** parseable ISO if non-empty.
- **AdminFee, ParkingRemoteFee:** numeric ≥ 0.
- **BookingDeposit_***: all-or-nothing across the four columns. If any set, `Amount > 0` required.

### Cheques sheet — new (only when sheet has rows)

- Each `(PropertyName, UnitNumber, RenterEmail)` resolves to exactly one Leases row.
- `InstallmentNo` is a positive integer, unique per lease.
- Method-driven required fields (CHEQUE / BANK_TRANSFER / ONLINE / CASH) — same validation table the inline editor and `LeaseService.updatePaymentSchedule` use.
- For each lease with cheque rows, `Σ Amount` must equal totalRent within ±1 AED rounding tolerance.
- DueDate / ChequeOrPaymentDate parseable ISO. Cheque due dates outside `[startDate, endDate]` are warnings, not errors.

## Backward compatibility

- All new columns are appended. Indexes 0–10 unchanged.
- Parser switches from positional reads (`getCellString(row, 9)`) to **header-name lookup** so an old workbook missing the new headers parses fine; missing fields take defaults.
- Cheques sheet absence ≡ Cheques sheet present with zero rows.
- The downloadable template `GET /api/v1/import/portfolio/template` is updated to include all new columns + the Cheques sheet with example rows. Old downloaded templates still upload successfully.
- No DTO renames; new counters (`chequesFromSheet`, `bookingDepositsCreated`) are additions to `PortfolioImportResultDTO`.

## Persist flow (Phase 2, per Leases row)

1. Resolve property / unit / renter / optional building (existing logic).
2. Compute `monthlyRent` and `totalRent`:
   - If `MonthlyRent` set: `monthlyRent = MonthlyRent`; `totalRent = monthlyRent × monthsBetween(startDate, endDate)`.
   - Else: `totalRent = RentAmount`; `monthlyRent = totalRent / monthsBetween`.
   - This fixes the latent bug in the existing persist service where `monthlyRent = totalRent / paymentTerms` produced wrong values when `paymentTerms != monthsBetween`.
3. Build the `Lease` entity with all new fields populated (charges, VAT toggles, agreementDate, depositPaymentMethod, status from the `Status` column, ejari number, payment refs).
4. Save the lease.
5. If `BookingDeposit_Amount > 0`: persist a `PaymentSchedule` row with `isBookingDeposit=true`, mirroring `LeaseService.createDraftLease`.
6. Generate schedule:
   - `chequesForLease = cheques-sheet rows referencing this lease`
   - If empty: `paymentScheduleService.generateScheduleForLease(lease)` (auto-distributes per `paymentTerms`, snapped to `RentCollectionSettings.dueDayOfMonth`).
   - Else: override `lease.paymentTerms = chequesForLease.size()` and persist each cheque row directly as a `PaymentSchedule`. `purposeLabel = "RENT - <ord> INSTALLMENT"`; the first installment label gets `/ADMIN/SD/REMOTE` appended when `adminFee + depositAmount + parkingRemoteFee > 0`.
7. Apply lease-status-driven side effects:
   - `Status == ACTIVE`: unit → `OCCUPIED` (existing behavior).
   - `Status == DRAFT`: unit stays `VACANT`. No contract generated.

## Response DTO additions

```java
PortfolioImportResultDTO {
  // ... existing fields ...
  schedulesCreated: int        // existing
  chequesFromSheet: int        // new — count persisted from Cheques sheet
  bookingDepositsCreated: int  // new
}
```

## Test plan

### Unit — `PortfolioImportServiceTest`

- Rent xor: both set → error; neither → error
- Cheques sum mismatch → error with row + lease key in message
- VAT defaults inherit from property type when toggles blank
- Status defaults to ACTIVE; per-row override accepted; invalid value → error
- PaymentMethod accepts BANK_TRANSFER and CASH
- Old template (10 columns, no Cheques sheet) parses unchanged → regression guard

### Unit — `PortfolioImportPersistServiceTest`

- Persist without cheques rows → schedule matches `generateScheduleForLease` output
- Persist with cheques rows → schedule rows persisted in InstallmentNo order; `lease.paymentTerms` set to row count
- `Status = DRAFT` → unit stays VACANT
- BookingDeposit columns populated → row saved with `isBookingDeposit=true`
- `MonthlyRent` set, `RentAmount` blank → totalRent computed correctly

### Integration

- Upload new-format workbook with one lease per scenario (auto-distribute / cheques sheet / draft / booking deposit / monthly-rent input). Poll job, assert COMPLETED, then read back persisted leases + schedules + booking rows.

### Fixture workbook

`backend/src/test/resources/portfolio-import/sample-with-cheques.xlsx` — covers all five scenarios above.

## Out of scope

- Web UI changes (existing import modal handles any .xlsx).
- New endpoints (existing POST/GET endpoints unchanged).
- Async job state machine (unchanged).
- Mobile bulk import (never existed).
- Re-upload to edit existing imported leases (admins use the lease detail page metadata editor).

## Files touched

```
backend/src/main/java/com/datagami/rentaxis/
  api/dto/PortfolioImportResultDTO.java         + chequesFromSheet, bookingDepositsCreated
  core/service/PortfolioTemplateService.java    new Leases columns + Cheques sheet
  core/service/PortfolioImportService.java      header-name parser, new validators, Cheques-sheet validation
  core/service/PortfolioImportPersistService.java
                                               new lease fields, booking deposit row, per-cheque persist or auto-distribute, status-driven unit transition

backend/src/test/java/com/datagami/rentaxis/core/service/
  PortfolioImportServiceTest.java               new validation cases
  PortfolioImportPersistServiceTest.java        new persist cases

backend/src/test/resources/portfolio-import/
  sample-with-cheques.xlsx                      new fixture
```

No DB migrations. No new entities. No new endpoints.
