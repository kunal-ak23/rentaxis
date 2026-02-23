# Module J: Reporting & Statements

## 1. Overview
Provide actionable financial transparency. Summarize vast amounts of operational data into accessible visual dashboards, rent rolls, overdue aging analysis, and printable landlord statements.

## 2. Architecture & Technical Decisions
- **Read-Optimized Queries:** Instead of mapping complex Java ORM iterations and DTO parsing, we will leverage JPQL constructors or discrete native PostgreSQL views (e.g., `vw_rent_roll`, `vw_landlord_statements`) specifically scoped by `tenant_id` for maximum speed.
- **Export Framework:** Integrates Apache POI to parse standard DTOs directly into styled `.xlsx` blobs, and OpenCSV for lightweight dumps.

## 3. Data Model
### Virtual Data Sets (Views):
- `vw_rent_roll`: Aggregates active Leases, mapped to Properties, current period amount, expiry distance, and occupant details.
- `vw_overdue_aging`: Bins overdue schedules into relative buckets `<30 days`, `30-60 days`, `>90 days`.
- `vw_profit_loss`: Merges `payments` (cash in) minus `expenses` (cash out) partitioned by `property_id` grouped by Month.

## 4. API Specification
- `GET /api/v1/reports/rent-roll` (JSON payload)
- `GET /api/v1/reports/rent-roll/export?format=csv` (Binary stream response)
- `GET /api/v1/reports/aging`
- `GET /api/v1/reports/statements?propertyId=X&month=Y` (Calculates PNL statement variables)

## 5. UI Flows & Interfaces
- **Landlord Dashboard (Landing Page):** High-level metric cards (Total Expected vs Collected this month, Occupancy %, Open Maintenance Tickets).
- **Reports Module:** Filterable tables equipped with universal generic "Download to Excel" actions in the top right corners.
- **Statement Viewer:** A stylized printable template outlining "Income - Expenses = Net" explicitly formatted for record-keeping and partner briefings.

## 6. Security Constraints
- Extreme restriction on report parameters ensuring users bounded to specific `property_ids` can never query the unified `tenant_org_id` aggregated views. The database view layers will enforce RBAC injection parameters directly if possible, or heavily filtered JVM service layers.

## 7. Execution Plan (MVP Phase)
- Implement baseline operational metric endpoints (Due vs Collected, Occupancy).
- Format basic CSV dump utility.
- Build Next.js visual dashboard layout using lightweight charting libraries (Recharts / Chart.js).
