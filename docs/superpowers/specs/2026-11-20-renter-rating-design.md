# Renter Rating (premium) — Design

Date: 2026-11-20
Status: Approved 2026-11-20
Branch: `feat/tenant-rating` (worktree `C:\rentaxis-wt\tenant-rating`)

## 1. Intent

Landlords want to know which renters are reliable. The main use is **rewarding good renters at renewal**: staff see a renter's grade on the renewal screen and can offer a discount, knowing payments will not get stuck. The same grade also warns about bad payers.

This is a **premium feature**, sold per landlord org.

The rating must be explainable. Staff never get a bare number. Every point traces back to a dated event they can see.

Naming: in this codebase "tenant" means the landlord org (`tenant_id`). The feature is called **Renter Rating** in code (`RENTER_RATING`, `renter_ratings`) to avoid that clash. Product copy may say "Tenant rating".

## 2. Goals, non-goals, anti-goals

Goals:
- Each renter gets a score from 0 to 100 and a grade, computed from their history in this org.
- Staff see the grade on the renewal queue, the lease page and the renter profile.
- Clicking the grade opens a details panel with the score, the dated events that built it (points + or −), and a monthly trend.
- A "Renter ratings" page lists all renters by grade, with filters and CSV export.
- An org admin can add a manual adjustment with a required reason.
- The whole feature sits behind the premium switch `TenantFeature.RENTER_RATING`, off by default.

Non-goals (this release):
- Alerts when a grade drops, renewal-queue automation, suggested discounts or deposit terms. These are a possible later upgrade.
- Mobile apps.
- Per-org custom weights. Weights live in one policy class so this can come later.

Anti-goals (a failure even if it works):
- A renter can see their own rating, through any API, page or app.
- A score staff cannot explain from the events shown.
- One org's data leaks into another org's rating (P0).
- The ratings list is slow for an org with 8,000 renters.

## 3. Decisions made with the user

- Package: badge + details + portfolio list (option 2).
- Look-back: the last 24 months. Events 0–12 months old count fully. Events 12–24 months old count at half weight. Older events do not count.
- Overrides: no direct edits. `TENANT_ADMIN` only can add a manual adjustment (+/− points) with a required reason. It appears in the timeline with who and when.
- Approach: one scoring engine; the badge and details are computed live; a nightly job stores results for the list page (approach B below).
- Staff only. Never the renter.
- Late renewal: only holds longer than 14 days count, whoever caused them.
- Meeting no-shows linked to the renter's lease count against the renter.
- Scale: must stay cheap at 8,000 renters per org (see §6.3).

Calls made by the designer (check these):
- Bounce = `bouncedAt`/`importedBouncedOn` set; `RETURNED` (unused cheque handed back) is not a bounce; `TECHNICAL_RETURN` is excluded.
- `USE_AND_OCCUPATION` is treated as a charge, not misconduct.
- `WRITTEN_OFF` misconduct penalties still count.
- Any manual adjustment moves a renter out of NEW.
- Only started leases count (not `DRAFT` / `PENDING_SIGNATURE`).
- `ACCOUNTANT` can view; `TENANT_USER` and `PROMOTER` cannot.
- Base 70, on-time grace 7 days, and the credit values and caps in §5.3.

## 4. Approaches considered

- **A. Compute live everywhere.** Always fresh and no storage, but the list page would score every renter on each load, and sorting or filtering by grade becomes slow. There is no trend.
- **B. One engine, live for one renter, stored nightly for the list (chosen).** The details panel is always exact. The list is fast, sortable and exportable. A monthly snapshot gives the trend. The cost: the list can be up to one day behind. The badge and details are never behind.
- **C. Update on each event as it happens.** Instant everywhere, but it needs hooks in many existing workflows (cheques, penalties, renewals). It misses imported legacy data and drifts if a hook is missed.

B was picked because it is fast where speed matters (the list) and exact where trust matters (the details), and it does not change existing payment or renewal code.

## 5. Scoring

All numbers live in one class, `RenterRatingPolicy`.

### 5.1 Scope

A renter's rating uses every **started** lease in this org where `lease.renter` is that renter. Started means any status except `DRAFT` and `PENDING_SIGNATURE`. The same rule applies to "earliest lease" (NEW rule) and to which renters the nightly job pages through. It also uses and every cheque, penalty and write-off linked to that renter. When a write-off has no `renterId`, it falls back to the lease's renter. Data from other orgs is never used.

### 5.2 Score

Start at a **base of 70**. Add credits, subtract deductions, each multiplied by its age weight. Clamp the result to 0–100 and round to a whole number.

Age weight, by event date relative to today:
- 0–12 months: 1.0
- over 12 months up to 24 months: 0.5
- over 24 months: 0 (the event is not listed)

### 5.3 Credits

| Event | Points | Cap | Event date |
|---|---|---|---|
| Cheque cleared on time: `CLEARED`, cleared no later than `chequeDate` + 7 days | +1 each | +20 total | `clearedAt` |
| Completed renewal: a lease with `renewedFromLeaseId` pointing at this renter's earlier lease | +5 each | +10 total | new lease start date |
| Early renewal intent: `RenewalOpportunity.intent = RENEW` captured at least 30 days before the lease `endDate` | +3 each | +6 total | `intentCapturedAt` |

Caps apply after age weighting.

### 5.4 Deductions

| Event | Points | Event date |
|---|---|---|
| Cheque bounced: `bouncedAt` or `importedBouncedOn` is set, whatever the current status (a replaced bounce ends as `REPLACED` and still counts). Counted once per cheque. Excluded when `failureReason = TECHNICAL_RETURN` | −15 | `bouncedAt`, else `importedBouncedOn` |
| Cheque cleared late (more than 7 days after `chequeDate`). Not counted when an `APPROVED`/`WRITTEN_OFF` `LATE_PAYMENT` penalty is linked to the same cheque (`chequeId`): one deduction per late cheque, and the penalty's −8 is kept | −3 | `clearedAt` |
| Late-payment penalty (`LATE_PAYMENT`, status `APPROVED` or `WRITTEN_OFF`) | −8 | `incidentDate`, else the approval date, else the proposal date (app zone) |
| Other misconduct penalty (`DAMAGE`, and `CHEQUE_RETURN` without a linked cheque; status `APPROVED` or `WRITTEN_OFF`) | −10 | `incidentDate`, else the approval date, else the proposal date (app zone) |
| Bad-debt write-off (status `WRITTEN_OFF`) | −30 | `writeOffDate` |
| Long hold: the lease stayed `ON_HOLD` more than 14 days, whoever caused it (user decision). Duration runs from the `LeaseEvent` into `ON_HOLD` to the next state-change event out of it, or to today if still on hold | −10, once per lease | `ON_HOLD` date + 15 days |
| Renewal ended with no response (`RenewalOpportunity.outcome = EXPIRED_NO_RESPONSE`). Not counted when the lease has a notice date, is `RENEWED`, or a started lease names it in `renewed_from_lease_id`: the renewal sweep writes this outcome for any non-`ACTIVE` lease without a captured intent, which includes a renter who gave notice or renewed after a short hold | −5 | `closedAt` |
| Meeting no-show: `Meeting.status = NO_SHOW` and the meeting is linked to one of this renter's leases. Assumed to be the renter's no-show (user decision). Meetings with no lease are ignored | −5 | meeting `slotStart` |

Not counted:
- Penalties that are `PROPOSED`, `WAIVED` or `REVERSED`.
- Charge-type penalty reasons: `SERVICE_RECHARGE`, `ADMIN_FEE`, `MAINTENANCE_RECHARGE`, `BOOKING_FEE`, `JOURNAL_VOUCHER`, `OTHER`, and `USE_AND_OCCUPATION`. Use-and-occupation is rent for holdover days, and the hold itself is already scored.
- Write-offs that are `PROPOSED`, `REJECTED` or `REVERSED`.
- `RETURNED` cheques. That status means the landlord handed an unused cheque back, for example at termination. It is not a bounce.

"Cheque" in the rows above means the renter's own instalments. Charge collection rows that the system adds to the register are not scored and do not count toward NEW: rows with `penalty_assessment_id` set (an approved fine, including one restored after a write-off reversal), a settlement's balance-due row (`lease_settlements.collection_cheque_id`), and a write-off reversal's restored row (no link column; its narration starts "Bad debt write-off reversed"). `TRANSFERRED` cheques do not count toward NEW either; their copy on the new lease does. A transferred copy of a charge row (its `transferred_from_id` names a row that is one of the above) is a charge row too, although the copy does not carry `penalty_assessment_id`.

`CHEQUE_RETURN` penalties linked to a cheque of this organisation through `chequeId` (`penalty_assessments.cheque_id`) are not counted, because the bounce already counts. That is where the system-proposed cheque-return fine (`PenaltyRuleEngine.onBounce`) and a fine raised by hand against a cheque record the cheque; `sourceType`/`sourceId` are never a cheque.

### 5.5 Manual adjustments

`TENANT_ADMIN` can add an adjustment of −30 to +30 points with a required reason (max 500 characters). It is an event dated on the day it was added, and it follows the same age weighting. An admin can remove an adjustment. The removal is recorded (who and when), and removed adjustments stop counting.

### 5.6 Grades

| Grade | Score |
|---|---|
| A — Excellent | 85–100 |
| B — Good | 70–84 |
| C — Fair | 50–69 |
| D — Poor | 0–49 |
| NEW — Not enough history | see below |

A renter is **NEW** when their earliest lease in this org started less than 6 months ago, **or** they have fewer than 3 cheques dated in the window (only real instalments count: `CLEARED`, `BOUNCED`, `DEPOSITED`, `REGISTERED`, `ONLINE_PENDING`; not `DRAFT`, `CANCELLED`, `REPLACED`, `RETURNED` or `TRANSFERRED`), **and** they have no deductions and no manual adjustments. A renter with any deduction always gets a real grade. A manual adjustment (positive or negative) also gives a real grade, because an admin has vouched for the renter's history. Negative adjustments count in `deductions_count`. A NEW renter's score is still computed and shown greyed out.

Worked example: a renter with 12 on-time cheques and an early renewal intent scores 70 + 12 + 3 = 85, grade A. One bounce 8 months ago brings it to 70, grade B.

## 6. Architecture

### 6.1 Engine

`RenterRatingEngine` is a pure function: `(RenterHistory, LocalDate today, RenterRatingPolicy) → RenterRatingResult`.
- `RenterHistory` holds the plain rows for one renter (cheques, penalties, write-offs, leases, lease events, renewal opportunities, meetings, adjustments).
- `RenterRatingResult` holds the score, the grade, and the list of `RatingEvent` (type, date, base points, weight, applied points, label, source type and id).
- No database access. Unit-tested heavily.

`RenterHistoryLoader` loads histories in bulk: given a set of renter ids in one org, about 7 queries return the rows for all of them. One renter is a set of size 1.

### 6.2 Storage (Liquibase changeset 190)

- `renter_ratings`: `id`, `tenant_id`, `renter_id` (unique per tenant), `score`, `grade`, `deductions_count`, `events_json`, `fingerprint`, `computed_at`.
- `renter_rating_snapshots`: `id`, `tenant_id`, `renter_id`, `month` (first day of month), `score`, `grade`. Unique per (tenant, renter, month).
- `renter_rating_adjustments`: `id`, `tenant_id`, `renter_id`, `points`, `reason`, `created_by`, `created_at`, `removed_by`, `removed_at`.

All three extend the tenant filter (`BaseTenantEntity`).

### 6.3 Nightly job

`RenterRatingJob`, 02:00 daily, using the `LeaseRenewalScheduler` pattern. Flag: `app.renter-rating.job.enabled` (default `true`; it only acts for orgs with the feature on).
- For each org with `RENTER_RATING` on: page through renter ids 500 at a time, bulk-load histories, score in memory.
- Write a `renter_ratings` row only when its `fingerprint` (hash of score, grade and events) changed.
- One short transaction per batch.
- Each run also writes the current month's snapshot for every renter that has none yet (normally on the 1st; a month whose 1st was missed gets it on the next run). An existing snapshot is kept.
- A full recompute is required each night because ratings change with time alone: events cross the 12- and 24-month lines, and renters leave NEW. Change tracking would need `updated_at` on tables that lack it (`cheques`, `penalty_assessments`, `bad_debt_write_offs`, `lease_events`). That is not worth it.
- Expected load for 8,000 renters: about 16 batches and roughly 110 queries in total, well under a minute.
- Ops entry point: `runNowForTenant(orgId)`, exposed to `SUPER_ADMIN`.
- When `RENTER_RATING` is switched on for an org, run that org once in the background, so the list is not empty until the next night.

Adding or removing an adjustment recomputes and stores that renter at once.

### 6.4 API

All endpoints return 404 when `RENTER_RATING` is off for the caller's org. Viewing roles: `SUPER_ADMIN`, `TENANT_ADMIN`, `PROPERTY_MANAGER`, `ACCOUNTANT`. All other roles (`RENTER`, `SECURITY_GUARD`, `TENANT_USER`, `PROMOTER`) are denied.

- `GET /api/v1/renters/{id}/rating` → computed live: score, grade, events, and trend (the last 12 snapshots plus today).
- `GET /api/v1/renter-ratings?grade=&propertyId=&q=&sort=&page=&size=` → from stored rows: renter name, grade, score, deductions count, current lease/unit, computed_at.
- `GET /api/v1/renter-ratings/export` → CSV of the same filter.
- `POST /api/v1/renters/{id}/rating/adjustments` (`TENANT_ADMIN`) with `{points, reason}`.
- `DELETE /api/v1/renters/{id}/rating/adjustments/{adjustmentId}` (`TENANT_ADMIN`).
- `POST /api/v1/admin/renter-ratings/recompute?tenantId=` (`SUPER_ADMIN`).

Lease and renter lists are not changed. The web fetches the rating separately, so existing endpoints stay untouched.

### 6.5 Web

All hidden unless `useTenantFeatures().RENTER_RATING` is true and the user is staff.
- `RatingBadge`: grade letter, score and label, coloured by grade (NEW is grey). Clicking it opens the details panel.
- `RatingDetailsPanel` (side sheet):
  - score and grade, with the base of 70 explained;
  - a monthly trend line;
  - the event timeline, newest first, each with date, label, points, and "half weight" when older than 12 months;
  - links to the source cheque, penalty or lease;
  - for `TENANT_ADMIN`: "Add adjustment" (points and reason) and "Remove" on adjustments.
- Placement:
  - the renewals queue (`dashboard/leases/renewals`) gets a Rating column;
  - the lease page (`dashboard/leases/[id]`) shows the badge next to the renter;
  - the renter profile (`dashboard/renters/[id]`) shows the badge in the header.
- New page `dashboard/renter-ratings`: table with grade filter chips, property filter, search, sort by score, and CSV export. Nav entry under Renters, flagged `RENTER_RATING` in `navModel.ts` and `routeRegistry.ts`.
- English and Arabic strings, RTL-safe.

## 7. Testing

- Engine unit tests:
  - every event type;
  - age weights at 12 and 24 months exactly;
  - caps, and clamping at 0 and 100;
  - NEW rules;
  - no double count for a `CHEQUE_RETURN` penalty linked to a cheque;
  - waived and charge penalties ignored;
  - adjustments, and removed adjustments.
- Integration tests (Testcontainers):
  - the loader returns the right rows and never another org's;
  - the job writes only changed rows and the monthly snapshot;
  - endpoint auth: 404 when the feature is off, 403 for `RENTER`, adjustment is admin-only.
- Web (vitest):
  - the badge renders each grade;
  - the details panel lists events and shows the adjust form only for admins;
  - the list page filters;
  - nothing renders when the flag is off.

## 8. Left to the builder

- Exact colours, icons and copy, within the existing design system.
- The CSV column order.
- How the background run on enable is triggered (event listener or a direct call from the feature toggle service).
