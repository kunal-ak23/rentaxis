# Cheque-Failure Penalties — Design

**Date:** 2026-05-03
**Status:** Approved
**Goal:** Reinforce cheque-first rent collection. When a cheque fails (bounce / signature mismatch / account closed), trigger a fixed fine configured at company level (with optional per-property override), accrue a per-day surcharge on the unpaid fine after a grace period, surface the penalty to the renter, and let the admin record offline payment receipts that flow into financial accounting.

## Context

The system already supports cheque-first collection in many places: `PaymentMethod.CHEQUE` is widely used, `PaymentScheduleService` has a mark-bounced action with a `replacedBy` follow-on, the `BOUNCED` payment status exists, and the `PAYMENT_BOUNCED` notification template even mentions "to avoid penalties." What's missing is the penalty side of the loop:

- Only one bounce status today; no distinction between bounce / signature mismatch / account closed.
- `PaymentPenalty` exists, but only for per-day overdue accrual — there's no fixed-fee event-based fine.
- Penalty configuration lives at property level (`RentCollectionSettings`) only — no company-level config.
- No renter UI to see and clear penalties.
- No payment-of-fine flow (bank transfer / cheque / cash) recorded against the penalty.

This design closes those gaps.

## Decisions (from brainstorming)

1. **Coexistence (Q1 = B + extension):** Fixed event-based fines replace per-day overdue accrual on schedules in `BOUNCED` state. Per-day overdue still applies to non-cheque schedules that miss their due date without a recorded failure event. The unpaid *fine itself* accrues per-day after a configured grace period.
2. **Configuration scope (Q2 = B):** Company-level default config + optional per-property override. Mirrors the existing `OrgSettings` ↔ `RentCollectionSettings` precedence pattern.
3. **Marking flow (Q3 = A + lease-page surface):** Extend the existing mark-bounced action with a required failure-reason dropdown. Exposed on both the lease detail page (per-row quick action) and the finance/payments page.
4. **Payment-of-fine model (Q4 = B):** New `PenaltyPayment` entity as a journal of receipts (one fine → many `PenaltyPayment` rows), supporting partial payments.
5. **Renter clearance UX (Q5 = A):** Read-only on renter side for MVP. Admin records all receipts. Self-report (`Q5 = B`) is a future iteration.
6. **Fines settings access:** Both `SUPER_ADMIN` and `TENANT_ADMIN` can view + edit the org-level config and per-property overrides.
7. **Notifications:** `PENALTY_INCURRED`, `PENALTY_CLEARED`, and `PENALTY_WAIVED` all fire to the renter.

## Architecture

### Trigger model
A cheque schedule transitions to `BOUNCED` (existing status) **with a new mandatory `failureReason`**: `BOUNCE` / `SIGNATURE_MISMATCH` / `ACCOUNT_CLOSED`. That transition fires:

1. A `PaymentPenalty` row of `penaltyType = CHEQUE_FAILURE`, `penaltyAmount = configured-fine-for-reason`, `daysOverdue = 0`, against the `paymentScheduleId`.
2. The existing `PAYMENT_BOUNCED` notification (already wired) — copy extended to include reason + fine amount.
3. The existing replacement-cheque follow-on (unchanged) — admin issues a replacement via `replacedBy`. The penalty is independent of replacement.

### Daily accrual
Existing `PenaltyService` (today's daily cron) gets a sibling pass that walks open `PaymentPenalty` rows where `penaltyType = CHEQUE_FAILURE` and `clearedAt IS NULL`. For each such penalty, it computes days past `(createdAt + fineGraceDays)` and updates `daysOverdue`. The `currentTotal = penaltyAmount + max(0, daysOverdue) * finePerDayRate` is **not stored** — derived in DTOs at API time so it stays consistent with the payment journal.

Per-day overdue on the *rent* schedule (the existing FIXED_PER_DAY / PERCENTAGE behavior on `RentCollectionSettings`) **does NOT fire** for schedules in `BOUNCED` state. It still applies to non-cheque schedules that miss their due date.

### Configuration resolution
`FineConfigResolver.resolve(propertyId): FineConfig` is the single server-side helper for both penalty creation and accrual:

```
PerProperty rcs = rentCollectionSettings.findByPropertyId(propertyId)
OrgConfig org   = landlordOrgFineSettings.findByLandlordOrgId(currentTenantId)
                  // upsert with defaults if missing

return new FineConfig(
  bounceAmt            = coalesce(rcs.fineBounceAmount,             org.fineBounceAmount),
  signatureMismatchAmt = coalesce(rcs.fineSignatureMismatchAmount,  org.fineSignatureMismatchAmount),
  accountClosedAmt     = coalesce(rcs.fineAccountClosedAmount,      org.fineAccountClosedAmount),
  graceDays            = coalesce(rcs.fineGraceDays,                org.fineGraceDays),
  perDayRate           = coalesce(rcs.finePerDayRate,               org.finePerDayRate)
)
```

If the org row is missing for a legacy tenant, the resolver creates one with system defaults (500 / 500 / 1000 / 7 / 25) on first call.

### Clearance
`PenaltyPayment` is a journal of admin-recorded receipts. On each receipt:
- `FinancialTransaction` of new nature `PENALTY_INCOME` is posted.
- When `Σ PenaltyPayment.amount >= currentTotal`, the penalty's `cleared_at` is set, `PENALTY_CLEARED` notification fires.
- Existing `waived` / `waivedBy` / `waivedReason` columns remain the waiver path (acts like CLEARED but distinguishable for reports). Waivers fire `PENALTY_WAIVED`.

## Data model

### New enum

```
ChequeFailureReason: BOUNCE | SIGNATURE_MISMATCH | ACCOUNT_CLOSED
```

### New `TransactionNature` value
`PENALTY_INCOME` — distinct from existing `CHEQUE_BOUNCED` (which reverses revenue); this is the fine receipt as standalone income.

### Modified columns

**`payment_schedules`** — `cheque_failure_reason VARCHAR(30) NULL`. Set when `status=BOUNCED`; null otherwise. Backed by `PaymentSchedule.failureReason: ChequeFailureReason`.

**`rent_collection_settings`** — five nullable columns for per-property override:
- `fine_bounce_amount NUMERIC(12,2) NULL`
- `fine_signature_mismatch_amount NUMERIC(12,2) NULL`
- `fine_account_closed_amount NUMERIC(12,2) NULL`
- `fine_grace_days INT NULL`
- `fine_per_day_rate NUMERIC(12,2) NULL`

Null = inherit from org-level config.

**`payment_penalties`** — three new columns:
- `fine_grace_days INT NULL` — snapshot of the rate at penalty creation (so retroactive config changes don't rewrite history).
- `fine_per_day_rate NUMERIC(12,2) NULL` — same snapshot rationale.
- `cleared_at TIMESTAMP NULL` — set when fully paid or waived.

Existing `penalty_type` becomes a real enum string with a new value: `CHEQUE_FAILURE` joins `OVERDUE_FIXED_PER_DAY` / `OVERDUE_PERCENTAGE`.

### New entities

**`landlord_org_fine_settings`**
```
id UUID PK
tenant_id UUID NOT NULL                    -- BaseTenantEntity convention
landlord_org_id UUID UNIQUE NOT NULL FK -> landlord_org(id)
fine_bounce_amount NUMERIC(12,2) NOT NULL DEFAULT 500
fine_signature_mismatch_amount NUMERIC(12,2) NOT NULL DEFAULT 500
fine_account_closed_amount NUMERIC(12,2) NOT NULL DEFAULT 1000
fine_grace_days INT NOT NULL DEFAULT 7
fine_per_day_rate NUMERIC(12,2) NOT NULL DEFAULT 25
created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
```

Standalone (not folded into `org_settings`) — keeps fine policy separated from currency / locale / timezone.

**`penalty_payments`**
```
id UUID PK
tenant_id UUID NOT NULL
payment_penalty_id UUID NOT NULL FK -> payment_penalties(id) ON DELETE CASCADE
amount NUMERIC(12,2) NOT NULL CHECK (amount > 0)
payment_method VARCHAR(20) NOT NULL          -- BANK_TRANSFER / CHEQUE / CASH
payment_reference VARCHAR(255) NULL          -- UTR / cheque# / cash receipt#
received_at DATE NOT NULL
received_by UUID NOT NULL                    -- admin user id
notes TEXT NULL
financial_transaction_id UUID NULL FK -> financial_transactions(id)
created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
```

Index on `(payment_penalty_id, received_at)` for the journal read.

### Migrations
Two append-only Liquibase changesets:
- `47-cheque-failure-penalties.yaml` — adds the column to `payment_schedules`, the 5 columns to `rent_collection_settings`, the 3 columns to `payment_penalties`, creates `landlord_org_fine_settings` and `penalty_payments`.
- `48-default-fine-settings.yaml` — backfills one `landlord_org_fine_settings` row per existing `landlord_org` with system defaults (500/500/1000/7/25). Idempotent insert.

### Compute model (not stored)
```
currentTotal = penaltyAmount + max(0, daysOverdue) * finePerDayRate
outstanding  = currentTotal - Σ penalty_payments.amount
```

## API

### Fines configuration
`@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")`
- `GET /api/v1/settings/fines` → org config (creates default on first GET if absent).
- `PUT /api/v1/settings/fines` → upsert org config. Validation: amounts ≥ 0, `graceDays ≥ 0`.
- Per-property overrides reuse the existing `GET/PUT /api/v1/properties/{id}/rent-settings` — DTO gets the 5 new optional fields; nulls mean "inherit org default."

### Mark cheque failed
`POST /api/v1/payments/{paymentScheduleId}/mark-failed`
Auth: `hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')`.
```
{ failureReason: "BOUNCE" | "SIGNATURE_MISMATCH" | "ACCOUNT_CLOSED",
  notes: string? }
```

`PATCH /api/v1/payments/{id}/failure-reason` — TENANT_ADMIN / SUPER_ADMIN only. Corrects a wrong reason; the resulting fine delta is applied as a `PenaltyPayment` adjustment with a clearly-labeled `notes` line.

### Penalty journal
- `GET /api/v1/penalties?leaseId=…&status=open|cleared|all` — paginated; embeds `outstanding`, `currentTotal`, `daysOverdue`, full payment history per row.
- `POST /api/v1/penalties/{penaltyId}/payments` — admin records a receipt. See "Clearance flow" below.
- `POST /api/v1/penalties/{id}/waive` — TENANT_ADMIN / SUPER_ADMIN only. Reuses existing `waived` columns; sets `cleared_at`. Fires `PENALTY_WAIVED` notification.

### DTOs

```
FineConfigDTO {
  bounceAmount: number, signatureMismatchAmount: number, accountClosedAmount: number,
  graceDays: number, perDayRate: number,
  source: "ORG" | "PROPERTY"
}

PenaltyDTO {
  id, paymentScheduleId, leaseId, penaltyType, failureReason,
  penaltyAmount, daysOverdue, finePerDayRate, fineGraceDays,
  currentTotal, outstanding, status: "OPEN" | "CLEARED" | "WAIVED",
  payments: PenaltyPaymentDTO[],
  createdAt, clearedAt
}

PenaltyPaymentDTO { id, amount, paymentMethod, paymentReference, receivedAt, receivedBy, notes }
```

## Failure-marking flow (single transaction)

1. Validate the schedule is in a markable state — `PENDING / OVERDUE / COLLECTED / DEPOSITED`. Reject `CLEARED / CANCELLED / already-BOUNCED`.
2. `schedule.status = BOUNCED`; `schedule.failureReason = <reason>`; `schedule.statusChangedAt = now()`.
3. `FineConfig cfg = fineConfigResolver.resolve(schedule.property.id)`.
4. `BigDecimal fineAmount = cfg.amountFor(reason)`.
5. Create `PaymentPenalty`:
   - `paymentScheduleId = schedule.id`
   - `leaseId = schedule.lease.id`
   - `penaltyType = "CHEQUE_FAILURE"`
   - `penaltyAmount = fineAmount`
   - `daysOverdue = 0`
   - `fineGraceDays = cfg.graceDays`     ← snapshot
   - `finePerDayRate = cfg.perDayRate`   ← snapshot
   - `lastCalculatedAt = now()`
6. Post a `FinancialTransaction` of nature `CHEQUE_BOUNCED` for the bounced cheque amount (existing nature, existing AccountMapping).
7. Send `PAYMENT_BOUNCED` notification (existing template, copy extended to include reason + fine amount). Send `PENALTY_INCURRED` notification.
8. Write `LeaseEvent` of type `PAYMENT_FAILED_<REASON>` with the actor's user id.
9. Return updated schedule + new penalty so the UI doesn't need a follow-up GET.

## Daily accrual flow

`PenaltyService.processChequeFailureAccruals()` runs alongside the existing `processLeaseOverduePayments`. For each open `CHEQUE_FAILURE` penalty:

```
graceUntil = createdAt.toLocalDate() + fineGraceDays
if today <= graceUntil:
    daysOverdue = 0
else:
    daysOverdue = DAYS.between(graceUntil, today)
penalty.daysOverdue = daysOverdue
penalty.lastCalculatedAt = now()
```

Per-penalty transaction; idempotent within a day.

## Clearance flow

`POST /api/v1/penalties/{penaltyId}/payments` (single transaction):

1. Reject if penalty is already cleared (`cleared_at IS NOT NULL`).
2. Reject if `amount > outstanding`.
3. Insert `PenaltyPayment` with `received_by = currentUser`.
4. Post `FinancialTransaction` of nature `PENALTY_INCOME`. Store its id on `PenaltyPayment.financial_transaction_id`.
5. If `Σ payments == currentTotal`, set `payment_penalty.cleared_at = now()`. Fire `PENALTY_CLEARED` notification.
6. Write `LeaseEvent` `PENALTY_PAYMENT_RECORDED` with amount + method.

## UI

### Web
- **New page** `dashboard/settings/fines` (TENANT_ADMIN, SUPER_ADMIN). Form with the 5 fields; helper banner explains the org → property fallback.
- **Extend** `dashboard/settings/rent-settings/[propertyId]` with a collapsible "Cheque-failure fine overrides" section. Each field shows the org-level value as a placeholder; admin can override or clear back.
- **Lease detail page** — per-row "Mark failed" action opens `<MarkChequeFailedDialog/>` (reason dropdown + notes). New "Penalties" section listing the lease's penalties.
- **Finance / payments page** — same per-row "Mark failed" action. Compact penalty pill on rows that have an open penalty.
- **Renter portal `/portal/penalties`** — list of open penalties (reason badge, base fine, accrued, total outstanding, days overdue, related cheque) + "How to pay" panel (bank account, office address, hours; sourced from a new `OrgSettings.penalty_payment_instructions` text column). Cleared-penalties tab. Read-only.

### Mobile
- **Manager app** — Flutter equivalent of `<MarkChequeFailedDialog/>` on the lease detail screen's payment list.
- **Renter app** — new `penalties_screen.dart` mirroring web. New "Penalties" tile on home; open-penalty badge on `payments_screen.dart`. Notifications deep-link to penalty detail.

## Notifications

Three new types via `NotificationService`:

| Type | Trigger | Body summary |
|---|---|---|
| `PENALTY_INCURRED` | After mark-failed creates a penalty | reason, amount, related cheque, "how to pay" instructions |
| `PENALTY_CLEARED` | After full payment closes the penalty | amount paid, payment method, balance now zero |
| `PENALTY_WAIVED` | After admin waives the penalty | waiver reason, balance now zero |

Reminder cadence (open penalties past `graceUntil`) reuses the existing `RentCollectionSettings.payment_reminder_days` parsing (e.g., `7,3,1`). No separate config in MVP.

## Cheque-first reinforcement (the framing the client opened with)

Beyond the fine workflow, low-risk surface tweaks:
- **Lease wizard** — `paymentMethod` default flips from "ONLINE if `RentCollectionSettings.onlinePaymentEnabled=true` else CHEQUE" to **always CHEQUE** unless the property explicitly opts into ONLINE in its rent-collection settings. The toggle moves from "is online enabled" (passive) to "use online by default" (explicit per-property).
- **Renter portal copy** on payment-due notifications references cheque collection / replacement first, online as a secondary path.

These are small content / default flips, bundled in the same release.

## Permissions matrix

| Action | SUPER_ADMIN | TENANT_ADMIN | PROPERTY_MANAGER | RENTER |
|---|---|---|---|---|
| Configure fines (org + per-property) | ✅ | ✅ | ❌ | ❌ |
| Mark cheque failed | ✅ | ✅ | ✅ | ❌ |
| Correct failure reason (`PATCH .../failure-reason`) | ✅ | ✅ | ❌ | ❌ |
| Record penalty payment | ✅ | ✅ | ✅ | ❌ |
| Waive penalty | ✅ | ✅ | ❌ | ❌ |
| List penalties (own scope) | ✅ all | ✅ tenant | ✅ assigned properties | ✅ own only |

## Test plan

### Unit / service
- `PenaltyServiceTest` — adds tests for `processChequeFailureAccruals` (day 0 / pre-grace / first day past / day 30 with stable `Clock`).
- `FineConfigResolverTest` — org-only / per-property override / partial override (some fields null) / missing org row autocreate.
- `PenaltyPaymentServiceTest` — partial pay, full pay → CLEARED, over-pay rejection, waiver path, reason-correction adjustment.
- `PaymentScheduleServiceMarkFailedTest` — extends existing markBounced tests with per-reason fine assertions, replacement-cheque interaction, notification fire.

### Repository slice (`@DataJpaTest`)
- `LandlordOrgFineSettingsRepositoryTest` — unique-per-tenant constraint, tenant-filter activation.
- `PenaltyPaymentRepositoryTest` — index sanity on `(payment_penalty_id, received_at)`, FK cascade on penalty delete.

### Integration (`@SpringBootTest` + Testcontainers, infra added in the bulk-import branch)
- `ChequeFailurePenaltyIT` — full path: bulk-import workbook → mark a cheque failed via the controller → verify penalty + notification + LeaseEvent + replacement-cheque flow.
- `PenaltyClearanceIT` — partial-pay + final-pay journal flow → verify FinancialTransaction posting + penalty status transitions + `PENALTY_CLEARED` notification fired.

### Frontend
- Web component tests for `<MarkChequeFailedDialog/>` and the new fines settings page.
- Mobile widget tests for the renter `penalties_screen.dart` against a stubbed API service.

## Bulk-import touch points

- `failureReason` is **NOT** importable. Bulk import is an onboarding tool; historical fine import is a separate "settlement migration" feature out of scope.
- Cheques sheet semantics unchanged — every persisted cheque enters the system as `PENDING`.
- One small example-row doc note in `PortfolioTemplateService`: "the system is cheque-first; expect to set `Method=CHEQUE` for typical leases."
- `PaymentMethod` default for both wizard and import remains CHEQUE — no change.
- Regression test: bulk-importing a Cheques workbook does NOT create any pre-existing `PaymentPenalty` rows.

## Rollout

- Default org-level fine config (500/500/1000/7/25) is backfilled by `48-default-fine-settings.yaml` for every existing `landlord_org`. Per-property override columns default to NULL.
- Feature is **on by default** for all tenants — no per-tenant feature flag.
- Behavioral change for existing data: the daily `PenaltyService` will now skip schedules in `BOUNCED` state (instead of accruing per-day overdue on them). Legacy `PaymentPenalty` rows of type `OVERDUE_*` keep their current `daysOverdue` value but stop incrementing; documented in changeset and release notes.
- No backfill of cheque-failure fines for historical bounces — `failureReason` data not available retroactively. Documented as "fines apply forward from release date."

## Out of scope

- Self-report payment by renter (Q5 = B path).
- Per-failure-type grace / per-day rates (one shared pair).
- Online payment of the fine via Razorpay — admin-recorded only in MVP. Razorpay integration for fines is a follow-on.
- Historical fine import.
- Multi-currency. AED only.

## Files touched

```
backend/src/main/java/com/datagami/rentaxis/
  domain/entity/enums/ChequeFailureReason.java                NEW
  domain/entity/enums/TransactionNature.java                  + PENALTY_INCOME
  domain/entity/PaymentSchedule.java                          + failureReason
  domain/entity/PaymentPenalty.java                           + fineGraceDays, finePerDayRate, clearedAt
  domain/entity/RentCollectionSettings.java                   + 5 nullable override fields
  domain/entity/LandlordOrgFineSettings.java                  NEW
  domain/entity/PenaltyPayment.java                           NEW
  domain/entity/OrgSettings.java                              + penalty_payment_instructions
  domain/repository/LandlordOrgFineSettingsRepository.java    NEW
  domain/repository/PenaltyPaymentRepository.java             NEW
  core/service/FineConfigResolver.java                        NEW
  core/service/PenaltyService.java                            + processChequeFailureAccruals
  core/service/PaymentScheduleService.java                    markBounced → markFailed(reason)
  core/service/PenaltyPaymentService.java                     NEW (clearance flow)
  core/service/NotificationService.java                       + PENALTY_INCURRED / CLEARED / WAIVED
  core/service/AccountMappingService.java                     + PENALTY_INCOME mapping
  api/PenaltyController.java                                  NEW (list / record-payment / waive)
  api/PaymentScheduleController.java                          POST /mark-failed (replaces /mark-bounced)
  api/FineSettingsController.java                             NEW
  api/dto/FineConfigDTO.java                                  NEW
  api/dto/PenaltyDTO.java                                     NEW
  api/dto/PenaltyPaymentDTO.java                              NEW

backend/src/main/resources/db/changelog/changesets/
  47-cheque-failure-penalties.yaml                            NEW
  48-default-fine-settings.yaml                               NEW

backend/src/test/java/com/datagami/rentaxis/
  core/service/FineConfigResolverTest.java                    NEW
  core/service/PenaltyServiceChequeFailureTest.java           NEW
  core/service/PenaltyPaymentServiceTest.java                 NEW
  core/service/PaymentScheduleServiceMarkFailedTest.java      NEW
  core/service/ChequeFailurePenaltyIT.java                    NEW
  core/service/PenaltyClearanceIT.java                        NEW

web/src/app/[locale]/dashboard/settings/fines/page.tsx        NEW
web/src/app/[locale]/dashboard/settings/rent-settings/...     + override fields section
web/src/app/[locale]/dashboard/leases/[id]/page.tsx           + penalties section + dialog
web/src/app/[locale]/dashboard/finance/payments/page.tsx      + dialog wiring
web/src/app/[locale]/portal/penalties/page.tsx                NEW

mobile/apps/manager/lib/screens/lease_detail/...              + mark-failed dialog
mobile/apps/renter/lib/screens/penalties_screen.dart          NEW
mobile/apps/renter/lib/screens/payments_screen.dart           + open-penalties badge
mobile/apps/renter/lib/screens/home_screen.dart               + penalties tile
mobile/packages/rentaxis_core/lib/services/...                + penalty API client
```
