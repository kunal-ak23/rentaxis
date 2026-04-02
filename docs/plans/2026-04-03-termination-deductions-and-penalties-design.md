# Termination Deductions & Penalty System — Design Document

**Date:** 2026-04-03
**Status:** Approved

---

## Feature A: Termination Deductions

### Overview

Replace the simple terminate confirmation with a Settlement Form that calculates deposit refund after deductions.

### Flow

1. Admin clicks "Terminate Lease" → Settlement Form opens
2. Auto-populated deductions:
   - **Unpaid Rent** — sum of OVERDUE + PENDING payment schedules
   - **Accumulated Penalties** — sum of persisted penalty records for this lease
3. Admin adds manual deductions (predefined + free-text):
   - Categories: PROPERTY_DAMAGE, EARLY_TERMINATION_FEE, CLEANING, UTILITY_ARREARS, KEY_REPLACEMENT, OTHER
   - Each has: category, description (free-text), amount
4. Settlement summary shows: deposit - deductions = refund
5. On confirm:
   - Creates financial transactions (debit deposit liability, credit income/expense accounts)
   - Terminates lease (existing flow: status → TERMINATED, unit → VACANT, cancel pending payments)
   - Records LeaseEvent with settlement reference
   - Stores all deductions for audit

### Database

**`lease_settlements` table:**
- `id` UUID PK
- `lease_id` UUID FK → leases(id), UNIQUE
- `tenant_id` UUID
- `deposit_amount` DECIMAL — security deposit at time of settlement
- `total_deductions` DECIMAL — sum of all deductions
- `refund_amount` DECIMAL — deposit minus deductions
- `notes` TEXT — admin notes
- `settled_by` UUID FK → users(id)
- `settled_at` TIMESTAMP
- `created_at`, `updated_at` TIMESTAMP

**`lease_settlement_deductions` table:**
- `id` UUID PK
- `settlement_id` UUID FK → lease_settlements(id)
- `tenant_id` UUID
- `category` VARCHAR — enum: UNPAID_RENT, PENALTIES, PROPERTY_DAMAGE, EARLY_TERMINATION_FEE, CLEANING, UTILITY_ARREARS, KEY_REPLACEMENT, OTHER
- `description` VARCHAR — free-text description
- `amount` DECIMAL
- `auto_calculated` BOOLEAN — true for auto-populated items
- `created_at` TIMESTAMP

### API

- `GET /api/v1/leases/{id}/settlement/preview` — calculates auto-deductions, returns preview (deposit, unpaid rent total, penalty total)
- `POST /api/v1/leases/{id}/terminate` — ENHANCED: accepts settlement body with deductions array instead of just notes param
- `GET /api/v1/leases/{id}/settlement` — returns settlement details after termination

### Frontend

Settlement modal on lease detail page (replaces simple confirm dialog):
- Top: Security Deposit amount
- Auto-calculated deductions (read-only, editable amount)
- Manual deductions section with "+ Add Deduction" button
- Each deduction: category dropdown, description input, amount input, remove button
- Summary bar: Deposit - Deductions = Refund
- Terminate + Settle button (destructive)
- Accessible from both lease list and lease detail page

---

## Feature B: Penalty System

### Overview

Automated daily penalty calculation for overdue payments with persistence, visibility, and admin waiver capability.

### Daily Cron Job

Spring `@Scheduled` task running at 2:00 AM daily:

1. Query all ACTIVE leases
2. For each lease, find payment schedules where:
   - status = PENDING
   - dueDate + gracePeriod < today
3. Mark those payments as OVERDUE (status change)
4. Calculate penalty using existing `PenaltyCalculationService` + `RentCollectionSettings`
5. Create or update `payment_penalties` record
6. Create notification for tenant on first overdue detection

### Database

**`payment_penalties` table:**
- `id` UUID PK
- `payment_schedule_id` UUID FK → payment_schedules(id), UNIQUE
- `lease_id` UUID FK → leases(id)
- `tenant_id` UUID
- `penalty_amount` DECIMAL — current accumulated penalty
- `days_overdue` INT — days past due (excluding grace period)
- `penalty_type` VARCHAR — FIXED_PER_DAY or PERCENTAGE (snapshot from settings)
- `penalty_rate` DECIMAL — rate used (snapshot from settings)
- `grace_period_days` INT — grace period applied (snapshot)
- `waived` BOOLEAN DEFAULT false
- `waived_by` UUID FK → users(id)
- `waived_reason` VARCHAR
- `waived_at` TIMESTAMP
- `last_calculated_at` TIMESTAMP
- `created_at`, `updated_at` TIMESTAMP

### API

- `GET /api/v1/leases/{leaseId}/penalties` — all penalties for a lease
- `PUT /api/v1/penalties/{id}/waive` — waive a penalty (TENANT_ADMIN only)
- `POST /api/v1/penalties/calculate` — manual trigger to recalculate all penalties (admin)

### Auto-OVERDUE Marking

The cron job also transitions PENDING payments to OVERDUE when:
- `dueDate < today` (past the due date)
- This replaces the current manual/implicit overdue tracking

### Frontend

**Payment Schedule Table Enhancements:**
- New "Penalty" column showing accumulated penalty amount (red text)
- OVERDUE status badge (red) on overdue payments
- Click penalty amount → popover showing: days overdue, rate applied, waive button
- Waive button opens confirmation with reason input (TENANT_ADMIN only)

**Lease Detail Enhancements:**
- Penalty summary card: total penalties across all payments for the lease
- Warning banner if lease has overdue payments with penalties

### Integration with Termination

When terminating a lease, the settlement preview auto-includes the total accumulated penalties as a deduction line item.

---

## Migration Plan

Single migration file: `35-penalties-and-settlements.yaml`
- Creates `payment_penalties` table
- Creates `lease_settlements` table
- Creates `lease_settlement_deductions` table
- Adds indexes on foreign keys

## Liquibase Changeset: 35
