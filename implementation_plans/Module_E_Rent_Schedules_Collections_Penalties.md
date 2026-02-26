# Module E: Rent Schedules, Collections, Penalties

> **STATUS: 🟡 FOUNDATION ONLY**
> 
> | Item | Status |
> |------|--------|
> | `FinancialTransaction` entity (debit/credit, VAT) | ✅ Done (general-purpose, not rent-specific) |
> | `Account` entity (chart of accounts) | ✅ Done |
> | `FinancialTransactionController` (CRUD + reports) | ✅ Done |
> | Finance pages (accounts, transactions, reports) | ✅ Done (frontend scaffolding) |
> | `rent_schedules` table | ⬜ Not Started |
> | `payments` table (with PDC tracking) | ⬜ Not Started |
> | `payment_items` allocation table | ⬜ Not Started |
> | `penalty_rules` (grace period, flat/%) | ⬜ Not Started |
> | Schedule generation on lease activation | ⬜ Not Started (no leases yet) |
> | PDC lifecycle (`HELD → DEPOSITED → CLEARED/BOUNCED`) | ⬜ Not Started |
> | VAT calculation engine (5%) | ⬜ Not Started |
> | Tenant Portal "My Finances" view | ⬜ Not Started |
> | PDC Tracker (Landlord Portal) | ⬜ Not Started |
> | Pessimistic locking for concurrent payments | ⬜ Not Started |
> 
> *Last reviewed: 2026-02-26*

## 1. Overview
This module automates the financial logistics surrounding active leases. It generates rent schedules, evaluates them for late status, calculates late fees, and allocates payments. In the UAE, it specifically caters to managing physical Post-Dated Cheques (PDCs) transitioning to banked/cleared states.

## 2. Architecture & Technical Decisions
- **Predictive Scheduling Engine:** Upon Lease Activation, the backend splits the total rent into discrete schedules based on the `payment_terms` (e.g., 4 cheques).
- **PDC Vault Management:** Physical cheques require operational tracking: `HELD -> DEPOSITED -> CLEARED` or `BOUNCED`. Bounced cheques automatically trigger penalty applications and alter the schedule back to `OVERDUE`.
- **Value Added Tax (VAT):** While residential rent is largely exempt, commercial rents and administrative/penalty fees may attract 5% UAE VAT. The engine calculates sub-totals and VAT iteratively.

## 3. Data Model
### Core Tables:
- `rent_schedules`:
  - `id` (UUID), `lease_id` (UUID)
  - `due_date` (Date)
  - `amount_due`, `vat_amount`, `amount_paid` (Numeric)
  - `status` (Enum: PENDING, OVERDUE, PAID, PARTIAL)
- `payments`:
  - `id` (UUID), `lease_id` (UUID)
  - `amount_paid` (Numeric), `payment_date` (Date)
  - `method` (Enum: CASH, PDC_CHEQUE, CURRENT_CHEQUE, TRANSFER, GATEWAY)
  - `reference_no` (String, Cheque No or Txn ID)
  - `cheque_bank` (String), `cheque_date` (Date)
  - `cheque_status` (Enum: HELD, DEPOSITED, CLEARED, BOUNCED)
- `payment_items`:
  - `id`, `payment_id`, `schedule_id`, `allocated_amount`
- `penalty_rules`:
  - `tenant_id`, `grace_period_days`, `type` (FLAT/PERCENTAGE), `value`, `apply_vat` (Boolean)

## 4. API Specification
- `GET /api/v1/schedules` (Dashboard: fetch due/overdue items with pagination)
- `POST /api/v1/payments/manual` (Record manual offline collections and register PDCs)
- `PATCH /api/v1/payments/{id}/cheque-status` (Update a PDC from HELD to CLEARED/BOUNCED)
- `GET /api/v1/leases/{id}/ledger` (Retrieve full financial history for a lease)

## 5. UI Flows & Interfaces
- **Tenant Portal (My Finances):** Unified view demonstrating chronological chunks of debt, upcoming PDC dates, and historical receipts.
- **PDC Tracker (Landlord Portal):** A dedicated financial board listing upcoming cheques needing physical bank deposit, highlighting bounced cheques that require immediate tenant follow-up.

## 6. Security Constraints
- **Concurrency & Race Conditions:** Two actors paying the same schedule must invoke Pessimistic Locking (`@Lock(LockModeType.PESSIMISTIC_WRITE)`) on the `rent_schedules` table to strictly prevent double-payment bugs.

## 7. Execution Plan (MVP Phase)
- Implement Schedule generation service triggered by Lease state transitions, adding VAT calculation flags.
- Build the PDC tracking lifecycle spanning from `HELD` to `CLEARED`.
- Provide fully functional Rent Roll & Ledger screens, localized in AED formatting.
