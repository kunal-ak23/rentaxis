# Module E: Rent Schedules, Collections, Penalties

## 1. Overview
This module automates the financial logistics surrounding active leases. It generates chronological rent schedules, evaluates them for late status, calculates late fees automatically based on Landlord settings, and allocates payments correctly even in partial payment scenarios.

## 2. Architecture & Technical Decisions
- **Predictive Scheduling Engine:** Upon Lease Activation (`start_date` to `end_date`), the backend triggers an async scheduler that extrapolates the entire contract period into discrete due dates based on frequency (Monthly, Quarterly, Semiannually, Annually).
- **Payment Allocation Algorithm:** Incoming manual or automated payments allocate amounts to the oldest `PENDING` or `OVERDUE` schedules, splitting and carrying over partial balances mathematically accurately.
- **Penalty Runner:** A daily `@Scheduled` chore sweeps over `OVERDUE` schedules. If the age of the debt exceeds the tenant-defined grace period, it references `penalty_rules` to dynamically attach fine items (Percentage/day or Flat rate).

## 3. Data Model
### Core Tables:
- `rent_schedules`:
  - `id` (UUID), `lease_id` (UUID)
  - `due_date` (Date)
  - `amount_due` (Numeric), `amount_paid` (Numeric)
  - `status` (Enum: PENDING, OVERDUE, PAID, PARTIAL)
- `payments`:
  - `id` (UUID), `lease_id` (UUID)
  - `amount_paid` (Numeric), `payment_date` (Date)
  - `method` (Enum: CASH, CHEQUE, TRANSFER, GATEWAY)
  - `reference_no` (String, Cheque No or Txn ID)
- `payment_items`:
  - `id`, `payment_id`, `schedule_id`, `allocated_amount` (Links payments to precise schedules)
- `penalty_rules`:
  - `tenant_id`, `grace_period_days`, `type` (FLAT/PERCENTAGE), `value`

## 4. API Specification
- `GET /api/v1/schedules` (Dashboard: fetch due/overdue items with pagination)
- `POST /api/v1/payments/manual` (Record manual offline collections)
- `GET /api/v1/leases/{id}/ledger` (Retrieve full financial history for a lease)

## 5. UI Flows & Interfaces
- **Tenant Portal (My Finances):** Unified view demonstrating chronological chunks of debt, current due amounts, and historical receipts. "Pay Now" capability initiates Module I.
- **Landlord Portal (Ledger View):** Detailed accounting layout displaying every generated charge, attached penalties, and itemized splits of recorded payments. Modal to log manual Cheque/Cash receipts generating PDF receipts.

## 6. Security Constraints
- **Concurrency & Race Conditions:** Two actors (or gateway hooks) paying the same schedule must invoke Pessimistic Locking (`@Lock(LockModeType.PESSIMISTIC_WRITE)`) or Optimistic Versioning on the `rent_schedules` table to strictly prevent double-payment bugs.

## 7. Execution Plan (MVP Phase)
- Implement Schedule generation service triggered by Lease state transitions.
- Build offline `payments` capture endpoints and the `payment_items` allocation engine.
- Provide fully functional Rent Roll & Ledger screens.
