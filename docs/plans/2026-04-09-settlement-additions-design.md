# Settlement Additions (Repayments to Renter) Design

**Date:** 2026-04-09
**Status:** Approved

## Overview

Add "additions" (repayments to renter) alongside existing deductions in the lease termination settlement flow. Additions represent amounts the landlord owes back to the renter beyond the security deposit — prepaid rent, utility overpayments, deposit interest, landlord compensation, etc.

## Requirements

- Additions are line items alongside deductions in the same settlement
- Each addition has a category, amount, description, and optional file attachments (same as deductions: 0-10 files, images/videos/PDFs, up to 250MB)
- Settlement math: `refundAmount = depositAmount - totalDeductions + totalAdditions`
- Refund can exceed the deposit amount (additions are not capped)
- Additions follow the same DRAFT/FINALIZED lifecycle as deductions
- Full parity: backend, web, and mobile admin app

## Approach

Extend the existing `lease_settlement_deductions` table with a `type` discriminator column rather than creating a separate table. This reuses all existing attachment infrastructure, service code, and API endpoints.

## Data Model

### Modified Table: `lease_settlement_deductions` (Migration 40)

| Column | Type | Notes |
|--------|------|-------|
| `type` | `VARCHAR(20) DEFAULT 'DEDUCTION' NOT NULL` | `DEDUCTION` or `ADDITION` |
| `addition_category` | `VARCHAR(50) NULL` | Only used when type = ADDITION |

Existing rows get `type = 'DEDUCTION'` automatically via the default value.

### Modified Table: `lease_settlements` (Migration 40)

| Column | Type | Notes |
|--------|------|-------|
| `total_additions` | `DECIMAL(15,2) DEFAULT 0 NOT NULL` | Sum of all ADDITION line items |

### New Enum: `LineItemType`
`DEDUCTION`, `ADDITION`

### New Enum: `AdditionCategory`
`PREPAID_RENT`, `UTILITY_OVERPAYMENT`, `DEPOSIT_INTEREST`, `LANDLORD_COMPENSATION`, `OTHER`

### Attachments
No changes needed — existing `settlement_deduction_attachments` FK to `lease_settlement_deductions` works for both deductions and additions.

## API Changes

### Modified DTOs

- **`SaveSettlementDTO.DeductionItemDTO`**: add `type` (String, defaults to `DEDUCTION`), `additionCategory` (String, nullable — required when type = ADDITION)
- **`SettlementResponseDTO`**: add `totalAdditions` (BigDecimal); `DeductionDTO` gains `type` and `additionCategory` fields
- **`SettlementPreviewDTO`**: no changes (preview only shows auto-calculated deductions)

### Modified Service: `SettlementService`

- `saveDraft`: calculate `totalDeductions` and `totalAdditions` separately from the line items list
- `refundAmount = depositAmount - totalDeductions + totalAdditions`
- `buildSettlementResponse`: include `totalAdditions`
- Validation: if type = ADDITION, `additionCategory` must be non-null; if type = DEDUCTION, `category` (DeductionCategory) must be non-null

### No new endpoints — existing draft/finalize/attachment APIs work as-is

## Web UI Changes

### Settlement Page (`/dashboard/leases/[id]/settlement`)

Add "Additions" section between Manual Deductions and Notes:
- Same card layout as manual deductions (category dropdown, amount, description, attachments)
- Category dropdown shows: Prepaid Rent, Utility Overpayment, Deposit Interest, Landlord Compensation, Other
- Green accent styling to visually distinguish from red deductions
- "Add Addition" button

### Summary Section Update
- Security Deposit: AED X
- Total Deductions: - AED Y (red)
- Total Additions: + AED Z (green)
- Refund to Renter: AED (X - Y + Z)

## Mobile Admin App Changes

### LeaseSettlementScreen

Add "Additions" section with same UX as manual deductions:
- Add/remove addition cards
- Category dropdown with addition categories
- Amount, description fields
- Attachment support (camera, gallery, file picker)
- Green styling for addition cards
- Updated summary calculation showing additions

## Architecture Notes

- `LeaseSettlementDeduction` entity gains `type` (LineItemType) and `additionCategory` (AdditionCategory) fields
- `LineItemType` and `AdditionCategory` enums added to `enums` package
- `DeductionAttachmentService` works unchanged — attachments are per line-item regardless of type
- All operations remain scoped by `tenant_id` for multi-tenant isolation
