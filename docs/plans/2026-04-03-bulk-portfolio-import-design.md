# Bulk Portfolio Import — Design Document

**Date:** 2026-04-03
**Status:** Approved
**Goal:** Enable onboarding a new landlord's entire portfolio (properties, units, renters, leases) via a single Excel workbook upload with auto-generated payment schedules.

## Context

Current import supports CSV for a single property's units only. New landlords with 20+ properties and 500+ units need a streamlined onboarding flow that imports everything at once.

## Excel Template Structure

Single `.xlsx` workbook with 4 sheets. Downloadable via `GET /api/v1/import/portfolio/template` with headers, dropdown validations, and example rows.

### Sheet 1: Properties

| Column | Required | Example | Validation |
|--------|----------|---------|------------|
| PropertyName | Yes | Marina Heights | Unique within file |
| PropertyNameAr | No | مارينا | |
| Emirate | Yes | DUBAI | Dropdown: DUBAI, ABU_DHABI, SHARJAH, AJMAN, RAS_AL_KHAIMAH, FUJAIRAH, UMM_AL_QUWAIN |
| Address | No | Dubai Marina | |
| Type | Yes | RESIDENTIAL | Dropdown: RESIDENTIAL, COMMERCIAL, MIXED |
| MakaniNumber | No | 12345-67890 | |

### Sheet 2: Units

| Column | Required | Example | Validation |
|--------|----------|---------|------------|
| PropertyName | Yes | Marina Heights | Must match Properties sheet |
| BuildingName | No | Tower A | Groups units; auto-creates Building entities |
| UnitNumber | Yes | 101 | Unique within property |
| UnitType | No | BHK1 | Dropdown: STUDIO, BHK1, BHK2, BHK3, PENTHOUSE, RETAIL, OFFICE |
| SizeSqft | No | 850 | Numeric |
| ExpectedRent | No | 60000 | Numeric |

### Sheet 3: Renters

| Column | Required | Example | Validation |
|--------|----------|---------|------------|
| Name | Yes | Ahmed Ali | |
| NameAr | No | أحمد علي | |
| Email | Yes | ahmed@email.com | Unique within file, used as reference key |
| Phone | No | +971501234567 | |

### Sheet 4: Leases

| Column | Required | Example | Validation |
|--------|----------|---------|------------|
| PropertyName | Yes | Marina Heights | Must match Properties sheet |
| UnitNumber | Yes | 101 | Must match Units sheet (within same property) |
| RenterEmail | Yes | ahmed@email.com | Must match Renters sheet |
| StartDate | Yes | 2026-01-01 | ISO date format |
| EndDate | Yes | 2026-12-31 | ISO date format, must be after StartDate |
| RentAmount | Yes | 60000 | Numeric, annual rent |
| DepositAmount | No | 5000 | Numeric |
| PaymentTerms | No | 12 | Number of installments (default: 12) |
| PaymentMethod | No | CHEQUE | Dropdown: CHEQUE, BANK_TRANSFER, CASH, ONLINE |
| EjariNumber | No | 123456 | |

### Cross-Sheet References

- `PropertyName` links Units → Properties and Leases → Properties
- `UnitNumber` + `PropertyName` links Leases → Units
- `RenterEmail` links Leases → Renters

## Backend Architecture

### Endpoints

```
POST   /api/v1/import/portfolio              Upload .xlsx, returns jobId
GET    /api/v1/import/portfolio/{jobId}/status Poll job status + results
GET    /api/v1/import/portfolio/template      Download .xlsx template
```

Auth: SUPER_ADMIN or TENANT_ADMIN

### Two-Phase Processing

**Phase 1: Validate (Dry Run)**
- Parse all 4 sheets using Apache POI 5.3.0
- Validate field types, required fields, enum values, date formats
- Validate cross-sheet references (PropertyName, UnitNumber, RenterEmail)
- Check duplicates within file (property names, unit numbers per property, renter emails)
- Check conflicts with existing DB data
- Collect all errors — no DB writes

**Phase 2: Persist (if validation clean)**
- Single `@Transactional` — all-or-nothing
- Insert order: Properties → Buildings → Units → Renters → Leases → PaymentSchedules
- Auto-generate payment schedules from lease data
- Set lease status to ACTIVE, unit status to OCCUPIED for leased units

### Async Job Pattern

1. Upload returns `jobId` immediately
2. Processing runs in `@Async` thread
3. Status polling via GET endpoint
4. Job states: `VALIDATING → VALIDATION_FAILED | PERSISTING → COMPLETED | FAILED`

### Payment Schedule Auto-Generation

For each lease:
- `installmentCount = paymentTerms` (default 12)
- `installmentAmount = rentAmount / installmentCount`
- Due dates: monthly intervals starting from `startDate`
- Status: PENDING
- Payment method: from lease's paymentMethod field

### Response DTO

```java
PortfolioImportResultDTO {
  jobId: UUID
  status: String  // VALIDATING, VALIDATION_FAILED, PERSISTING, COMPLETED, FAILED
  propertiesCreated: int
  buildingsCreated: int
  unitsCreated: int
  rentersCreated: int
  leasesCreated: int
  paymentSchedulesCreated: int
  errors: List<ImportError>
}

ImportError {
  sheet: String   // "Properties", "Units", etc.
  row: int        // 1-based row number
  field: String   // Column name
  message: String // Human-readable error
}
```

## Database Migration

### New Table: `import_jobs` (changeset 38)

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| tenant_id | UUID | FK, multi-tenant |
| status | VARCHAR(30) | Job state |
| file_name | VARCHAR(255) | Original filename |
| properties_created | INT DEFAULT 0 | |
| buildings_created | INT DEFAULT 0 | |
| units_created | INT DEFAULT 0 | |
| renters_created | INT DEFAULT 0 | |
| leases_created | INT DEFAULT 0 | |
| schedules_created | INT DEFAULT 0 | |
| errors | JSONB | Error array |
| created_by | UUID | Who initiated |
| created_at | TIMESTAMP | |
| completed_at | TIMESTAMP | Nullable |

No changes to existing tables.

## Web Frontend UI

Full-screen modal on Properties page with "Import Portfolio" button:

1. **Upload step** — drag-drop .xlsx + "Download Template" link
2. **Validation step** — progress indicator, errors grouped by sheet tab with row numbers
3. **Confirm step** — summary counts preview, "Confirm Import" button
4. **Result step** — final counts or error details, close button

Error display: grouped by sheet with row number and field-level messages.

## Mobile

No mobile UI for portfolio import — web-only admin/onboarding operation.

## Out of Scope

- Excel support for incremental/ongoing imports
- Import history list page
- Partial success (skip bad rows)
- Rollback/undo of completed imports
