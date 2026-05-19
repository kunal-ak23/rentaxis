# Renter app: cheque-centric payments redesign

**Status:** Approved (brainstorm 2026-05-19)
**Surfaces:** Mobile renter app (`mobile/apps/renter`) + Web renter portal (`web/src/app/[locale]/dashboard/renter-portal`)

## Goal

The payment flow is now cheque-heavy. The renter never initiates an online payment. The renter app should become an **informational** surface focused on:

1. Which cheque is coming up for payment (the single "next" cheque).
2. Status visibility for cheques the bank already has (`DEPOSITED`) and ones that failed (`BOUNCED`).
3. Existing penalties surface (`/penalties`) stays — it already covers fines.

## Non-goals

- We do **not** delete the backend online-payment plumbing (`OnlinePaymentController`, `OnlinePaymentService`, Razorpay client, webhook handler). They stay in place to keep webhooks live and avoid blast radius in this PR. A follow-up may remove them.
- We do **not** restructure `/penalties` — the dedicated screen already works for fines.
- We do **not** touch the home screen. It continues to render its existing payment hero unchanged. Duplication between home (overview) and the new payments-screen hero (detail) is acceptable — they tell the same story at different zoom levels.
- We do **not** change tickets, meetings, profile, browse, or any non-payment surface.

## Decisions captured from brainstorm

| Question | Decision |
|---|---|
| Scope | Both surfaces (mobile + web) in lockstep. |
| Online-pay removal | Renter-facing UI only. Backend untouched in this PR. |
| Upcoming framing | Single "Next cheque" hero at the top + full list below. |
| Fines surfacing | Stay on dedicated `/penalties` screen — no inline fine on cheque rows, no new home tile, no banner. |
| Deposited detail | Status pill + "Deposited on {date}" subtitle (uses `PaymentSchedule.statusChangedAt`). |

## Architecture

Three layers touched. No new modules, no new tables, no migrations.

### Backend

- `backend/src/main/java/com/datagami/rentaxis/api/dto/RenterPaymentScheduleDTO.java` — add two fields:
  - `String statusChangedAt` — ISO instant string of `PaymentSchedule.statusChangedAt`. Nullable when the schedule has never transitioned out of PENDING.
  - `String failureReason` — name of `PaymentSchedule.failureReason` enum when present (only meaningful for BOUNCED).
- `backend/src/main/java/com/datagami/rentaxis/core/service/OnlinePaymentService.java` — in the mapper that builds `RenterPaymentScheduleDTO`, populate the two new fields. No other behavior change.

### Mobile (`mobile/apps/renter`)

- **Delete** `lib/screens/pay_rent_screen.dart`.
- `lib/router.dart` — remove the `/payments/pay` sub-route and the `pay_rent_screen` import.
- `lib/screens/payments_screen.dart`:
  - Drop the `InkWell` "push to pay" handler from `_ChequeCard`. Tapping a card is now a no-op (or scrolls into view from the hero).
  - Insert a new private `_NextChequeHero` widget between `_Header` and `_ProgressCard`. Behavior:
    - Picks the most-imminent unpaid cheque (priority: `OVERDUE` first by due date, then `PENDING` by due date, then by `installmentNumber`).
    - Renders amount, due date, cheque number, property/unit label (`propertyName` + `unitIdentifier` from the DTO), status pill.
    - **Tap is a no-op.** No navigation, no scroll, no interaction. The hero is purely informational.
    - Empty state: "All caught up" copy (mirrors today's `home_screen.dart _HeroPayment` empty state).
  - `_ChequeCard` gets a new subtitle line when status is one of `COLLECTED`, `DEPOSITED`, `BOUNCED`:
    - `COLLECTED` → "Collected on {date}"
    - `DEPOSITED` → "Deposited on {date}"
    - `BOUNCED` → "Bounced on {date} · {failureReason}"
- `lib/screens/home_screen.dart` — **not touched.** The existing `_HeroPayment` continues to render on home. (We accept duplication with the new payments-screen hero; home is the overview, payments is the detail.)

### Web (`web/src/app/[locale]/dashboard/renter-portal`)

- `payments/page.tsx`:
  - Remove any "Pay now" CTA / online-payment buttons. (Verify against current page contents.)
  - Add a `<NextChequeHero />` card at the top with the same selection logic as mobile.
  - Each cheque row gets the deposited-on / collected-on / bounced-on + failure-reason subtitle.
- Optional refactor: extract `<NextChequeHero />` and `<ChequeRow />` into `web/src/components/renter-portal/` if the current page exceeds ~300 LOC after edits.
- `web/messages/en.json` and `web/messages/ar.json` — add keys under `RenterPortal.payments`:
  - `nextCheque` ("Next cheque" / "الشيك القادم")
  - `noUpcoming` ("All caught up" / "كل شيء على ما يرام")
  - `depositedOn` ("Deposited on {date}" / "تم الإيداع في {date}")
  - `collectedOn` ("Collected on {date}" / "تم الاستلام في {date}")
  - `bouncedOn` ("Bounced on {date}" / "ارتدّ الشيك في {date}")
  - `failureReason` (used for the "· {reason}" tail)
  - `viewPenalties` ("View penalties" / "عرض الغرامات") — if not already present, used for the linking copy.

## Data flow

```
PaymentSchedule (db, existing)
    ↓ existing query
OnlinePaymentService.getMyPayments(userId)
    ↓ List<RenterPaymentScheduleDTO> (now includes statusChangedAt + failureReason)
GET /api/v1/online-payments/my-payments    ← endpoint name kept for compatibility
    ↓
Mobile PaymentService.getMyPayments() / Web fetch
    ↓
Renter surfaces:
  - select first OVERDUE → hero; else first PENDING → hero; else "All caught up"
  - render full list below; each row may show a status-specific subtitle
```

Strictly read-only on the renter side.

## Selection rules

**Hero pick** (both surfaces):

1. Filter to rows where `status ∈ {PENDING, OVERDUE}` and `installmentNumber` is set.
2. If any `OVERDUE` exist, pick the one with the earliest `dueDate`.
3. Else pick the `PENDING` with the earliest `dueDate`, breaking ties by `installmentNumber`.
4. If list is empty, show the empty hero ("All caught up").

**List visibility** (both surfaces):

- Skip rows where `status ∈ {CANCELLED, REPLACED}` (matches existing behavior).
- Sort ascending by `installmentNumber`.

## Subtitle rules

| status | subtitle |
|---|---|
| `PENDING` | none (the due-date line is enough) |
| `OVERDUE` | "Overdue since {dueDate}" |
| `COLLECTED` | "Collected on {statusChangedAt}" |
| `DEPOSITED` | "Deposited on {statusChangedAt}" |
| `CLEARED` | none (status pill is enough) |
| `BOUNCED` | "Bounced on {statusChangedAt} · {failureReason}" |

Dates render as `d MMM yyyy` (mobile) / locale-appropriate equivalent (web). When `statusChangedAt` is missing, omit the date and just show the status word ("Deposited", "Bounced") so we degrade gracefully against older backends.

## Error handling

- **No active lease** → existing empty state on both surfaces, hero hidden.
- **All cheques cleared** → hero shows "All caught up" empty state.
- **API error** → existing `ErrorState` widget / web error component, unchanged.
- **`statusChangedAt` missing on DTO** (old backend, new client) → render subtitle without the date portion.
- **Tap interactions** → both row taps and hero tap are no-ops. The screen is purely informational. No navigation off the payments screen.
- **`/payments/pay` deep links** → after route removal, GoRouter falls back to `/`; web URL responds with 404 from Next.js. Acceptable since we never sent these to users.

## Testing

### Backend

- Extend or add `OnlinePaymentServiceTest`:
  - DTO carries `statusChangedAt` for `DEPOSITED`, `CLEARED`, `BOUNCED`.
  - DTO carries `failureReason` only for `BOUNCED`.
  - Both null when status is `PENDING`.

### Mobile

- Widget test on `payments_screen.dart`:
  - Hero renders for the next `PENDING` with correct amount + due date.
  - DEPOSITED row shows "Deposited on …" subtitle.
  - BOUNCED row shows "Bounced on … · …" subtitle.
  - `_ChequeCard` no longer navigates on tap.
- Smoke: `/payments/pay` is not declared as a route.

### Web

- vitest for `renter-portal/payments/page.tsx`:
  - Hero rendered with the next pending.
  - Deposited subtitle on right rows.
  - No "Pay now" element in the DOM.

## Rollout

1. Ship backend DTO change behind no flag — additive fields, always safe.
2. Ship mobile + web changes in the same PR (they only consume the new fields, never require them).
3. No data migration. No env vars. No downtime.

## Out of scope (follow-ups)

- Remove `OnlinePaymentController`, `OnlinePaymentService` write paths, Razorpay client, webhook handler.
- Decide whether `/payments` should be renamed to `/cheques` on both surfaces (naming alignment with the new mental model). Not part of this PR.
- Auto-link an open penalty to its source cheque on the row (rejected during brainstorm — fines stay on the dedicated screen).
