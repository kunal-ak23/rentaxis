# Lease on hold (holdover) and resume — design

**Date:** 2026-11-06 · **Status:** built · **Branch:** `feat/lease-on-hold`

## Problem

When a contract reaches its end date and the renewal has not happened (often the client could not be
reached), the nightly `LeaseExpirationJob` set the lease to `EXPIRED` and released the unit to `VACANT`.
The renter was usually still living there. So:

- the unit could be let to someone else while it was occupied;
- a renewal agreed months later had no clean way to charge the gap;
- a back-dated renewal posted its recognition into months whose books were already closed.

## Rules (settled with Kunal and the client, 2 Nov and 6 Nov 2026)

1. At term end, a running lease (`ACTIVE`, not marked not-renewing) becomes **ON_HOLD**, not EXPIRED. The
   unit stays OCCUPIED and cannot be let to anyone else. A lease under notice, or marked not-renewing, still
   expires and releases the unit as before.
2. Nothing is invoiced, accrued or recognised while the lease is on hold. There are no cheques for the hold
   period.
3. **Resume = a normal renewal, back-dated** to the day after the old end date. The rent defaults to the old
   rent and can be changed to the new rent. A renewal of an ON_HOLD lease must start exactly on `endDate + 1`;
   any other start is refused, so the hold period is always covered by the new term.
4. **Any back-dated contract** (start date before the first of the contract date's month) gets a
   **"Recognise income from"** month when it is posted. It defaults to the contract date's month. The
   recognition of earlier months is folded into **one catch-up entry (CIL)** dated on the posting date
   (the contract date, or the end of the chosen month if that is earlier). Nothing is dated in a closed month.
   The month must be between the start month (or the first open month after the lock) and the contract
   month.
5. **Tenant left during the hold:** the user enters the move-out date. The days from `endDate + 1` to the
   move-out date are charged as **use and occupation** at the old day rate, as one charge booked today. Then
   the lease becomes EXPIRED and the unit is released.

## Model

- `LeaseStatus.ON_HOLD` (varchar enum, no DB constraint change).
- `leases.income_from_date` (changeset `188-lease-income-from.yaml`): the month chosen in rule 4, null when
  every month is recognised in its own month.
- `PenaltyReason.USE_AND_OCCUPATION` → credit `RENTAL_INCOME`.

## Backend

- `LeaseService.markExpired`: ON_HOLD for a running lease (event recorded, unit kept). `goesOnHold` holds the
  rule. `markHoldoverVacated` expires an ON_HOLD lease and releases the unit.
- Occupancy: ON_HOLD counts as holding the unit with an open end (`holdingLeases`, `holdsUntil` → null,
  and the `LeaseRepository` occupancy queries).
- ON_HOLD is added to the live/chargeable/renewable status sets (booking fee, ticket charges, penalties,
  cheque collection, renewal, renewal marks, contract stamps).
- `BackdatedIncome`: computes the options (`GET /leases/{id}/income-from`) and validates the month.
- `LeasePostingService.post(id, version, incomeFrom)`: posts, stores the month, then
  `RecognitionService.foldBeforeIncomeFrom` cancels the PLANNED rows before the month and posts one CIL per
  account pair.
- `HoldoverService`: `GET /leases/{id}/holdover/vacate-preview?movedOutOn=` prices the days month by month;
  `POST /leases/{id}/holdover/vacate` re-prices, refuses if the figure changed (`holdover.changed`), raises
  and approves the USE_AND_OCCUPATION charge, then expires the lease.

## Web

- Lease page: ON_HOLD status chip (warning); primary actions **Renew** and **Tenant left**
  (`HoldoverVacateDialog`).
- `PostLeaseDialog`: the "Back-dated contract" section with the month picker and the folded amount.
- Renewal queue and season buckets treat ON_HOLD like an expired-but-renewable contract.

## Mobile

- ON_HOLD colour and label in the manager app and the shared status helper; "Renewal pending" in the renter app.

## Tests

- `LeaseExpirationJobIT`: running leases go ON_HOLD and keep the unit; notice-given leases still expire.
- `HoldoverIT` (golden): with books locked through 31/10/2026 and today 13/11/2026, two back-dated renewals
  posted with income from 11/2026 leave **no journal dated before 01/11/2026**; the catch-up CILs are on
  today; November recognises on 30/11/2026. Also: wrong renewal start refused, closed or late income month
  refused, vacate on 15/11/2025 charges 46 days (7,561.64) and frees the unit.
- Web: `PostLeaseDialogIncomeFrom`, `HoldoverVacateDialog`, `leaseActions` ON_HOLD cases.

## Out of scope (raised on the client call)

Maker-checker contract posting, RERA-index renewal notice, building filter on the ledgers.
