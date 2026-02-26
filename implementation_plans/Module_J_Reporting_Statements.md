# Module J: Reporting & Statements

> **STATUS: 🟡 FOUNDATION ONLY**
> 
> | Item | Status |
> |------|--------|
> | Property-level financial report API | ✅ Done (`GET /reports/property/{id}`) |
> | Unit-level financial report API | ✅ Done (`GET /reports/unit/{id}`) |
> | Org-level financial report API | ✅ Done (`GET /reports/organisation`) |
> | `ReportDTO` | ✅ Done |
> | Finance Reports page (frontend) | ✅ Done (basic scaffolding at `/dashboard/finance/reports`) |
> | `vw_rent_roll` view | ⬜ Not Started |
> | `vw_overdue_aging` view | ⬜ Not Started |
> | `vw_vat_summary` view | ⬜ Not Started |
> | `vw_profit_loss` view | ⬜ Not Started |
> | CSV/XLSX export (Apache POI, bilingual) | ⬜ Not Started |
> | Tax/FTA Compliance Dashboard | ⬜ Not Started |
> | Landlord Dashboard metric cards (occupancy %, etc.) | ⬜ Not Started |
> | Statement Viewer (printable, RTL-aware) | ⬜ Not Started |
> 
> *Last reviewed: 2026-02-26*

## 1. Overview
Provide actionable financial transparency. Summarize vast amounts of operational data into accessible visual dashboards, rent rolls, overdue aging analysis, and printable landlord statements. Ensure these match UAE tax authority (FTA) expectations for VAT reporting.

## 2. Architecture & Technical Decisions
- **Read-Optimized Queries:** Leverage discrete native PostgreSQL views (e.g., `vw_rent_roll`, `vw_landlord_statements`) specifically scoped by `tenant_id` for maximum speed.
- **Bilingual Export Framework:** Integrates Apache POI to parse standard DTOs directly into styled `.xlsx` blobs. Headers and boilerplate text will resolve based on the user's localized session preference prior to Excel generation.
- **Tax (FTA) Compliant View:** Specific dataset exposing Output VAT (collected on fees) against Input VAT (paid to vendors via expenses).

## 3. Data Model
### Virtual Data Sets (Views):
- `vw_rent_roll`: Aggregates active Leases, mapped to Properties, current period amount, expiry distance, and occupant details.
- `vw_overdue_aging`: Bins overdue schedules into relative buckets `<30 days`, `30-60 days`, `>90 days`.
- `vw_vat_summary`: Aggregates Output VAT lines (from `payments`) and Input VAT lines (from `expenses`) grouped by calendar quarter (typical FTA reporting frequency).
- `vw_profit_loss`: Merges `payments` (cash in) minus `expenses` (cash out) partitioned by `property_id` grouped by Month.

## 4. API Specification
- `GET /api/v1/reports/rent-roll` (JSON payload)
- `GET /api/v1/reports/rent-roll/export?format=csv&locale=ar` (Binary stream response, localized)
- `GET /api/v1/reports/aging`
- `GET /api/v1/reports/vat` (Dedicated Tax Summary)
- `GET /api/v1/reports/statements?propertyId=X&month=Y` (Calculates PNL statement variables)

## 5. UI Flows & Interfaces
- **Landlord Dashboard (Landing Page):** High-level metric cards (Total Expected vs Collected this month in AED, Occupancy %, Open Maintenance Tickets).
- **Tax & Compliance Dashboard:** View grouping the Input/Output VAT sums to prepare the landlord's accountant for filing.
- **Statement Viewer:** A stylized printable template outlining "Income - Expenses = Net". It leverages RTL layout structurally when requested in Arabic.

## 6. Security Constraints
- Extreme restriction on report parameters ensuring users bounded to specific `property_ids` can never query the unified `tenant_org_id` aggregated views. The database view layers will enforce RBAC injection parameters directly if possible.

## 7. Execution Plan (MVP Phase)
- Implement baseline operational metric endpoints (Due vs Collected, Occupancy).
- Implement VAT aggregation reporting hooks.
- Format basic CSV dump utility injecting localized headers.
- Build Next.js visual dashboard layout supporting bidirectional data display.
