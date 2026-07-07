# Backdated Clearance/Failure Date for Cheque Status Transitions — Design

**Date:** 2026-07-08
**Status:** Approved (design)
**Author:** Kunal Sharma (with Claude)
**Branch:** `fix/cheque-clearance-effective-date`

## Problem

A cheque can physically clear (or bounce) a day or two before a staff member gets around to updating RentAxis. Today, clicking "Clear" or "Mark Failed" always stamps the schedule's `statusChangedAt` and the two ledger `FinancialTransaction` rows with **today's date** — there is no way to record the actual clearance/failure date. This makes the ledger report transactions out of chronological order relative to when the cash movement actually happened, and produces incorrect "collected this month" / monthly-collection numbers around month boundaries.

The backend already has the plumbing: `UpdatePaymentStatusDTO.effectiveDate` and `MarkFailedRequestDTO.effectiveDate` are wired through `PaymentScheduleService.clearPayment` / `markFailed` (and also `collectPayment` / `depositPayment`) via the shared `effectiveInstant()` / `effectiveDateOrToday()` helpers. The gap is entirely on the client side: every "Clear" button (web finance/payments, web lease detail, mobile manager) fires the request with an empty body, and `MarkChequeFailedDialog` / mobile's mark-failed dialog never send `effectiveDate` either.

## Goals

- Let staff pick the actual clearance date when clicking "Clear", and the actual failure date when marking a cheque failed, on **all three surfaces**: web Finance → Payments, web Lease detail, and the mobile manager app.
- Reject future-dated `effectiveDate` values consistently, server-side, for every transition that accepts one (collect/deposit/clear/mark-failed) — not just clear/mark-failed — since the field is already shared plumbing.
- Ledger transaction dates and `statusChangedAt` reflect the chosen date, exactly as the existing `effectiveDate` plumbing already does for historical/backfilled entries.

## Non-goals

- No change to the `collectPayment` / `depositPayment` UI flows (they don't currently collect a date and this doesn't ask for one) beyond the shared future-date guard.
- No stricter floor (e.g. "can't clear before the deposit date") — out of scope per this round; only "no future dates."
- No backend schema changes — `effectiveDate` fields already exist on the DTOs.

## Decisions (from brainstorming)

| Question | Decision |
|---|---|
| Which surfaces | Web Finance → Payments, web Lease detail, and mobile manager app — all three. |
| Mark Failed too? | Yes — same backdating gap, same fix, applied symmetrically. |
| Date constraint | No future dates. No additional floor (e.g. not-before-deposited-date). |
| Where to enforce "no future" | Server-side, in the shared `effectiveInstant()`/`effectiveDateOrToday()` helpers — covers collect/deposit/clear/mark-failed in one place, not just the two surfaces asked about. |

## Design

### Backend

- `PaymentScheduleService.effectiveInstant(LocalDate)` and `effectiveDateOrToday(LocalDate)`: when `effectiveDate` is non-null and after `LocalDate.now(UAE_ZONE)`, throw `BusinessRuleViolationException("Effective date cannot be in the future")`. Since both helpers are already the single choke point for every caller that accepts `effectiveDate` (`collectPayment`, `depositPayment`, `clearPayment`, `markFailed`), this one change enforces the invariant everywhere consistently.
- No DTO or migration changes — `effectiveDate` already exists on `UpdatePaymentStatusDTO` and `MarkFailedRequestDTO`.

### Web (`web/src/components/payments/`)

- **New `ClearChequeDialog.tsx`**, structurally mirroring the existing `MarkChequeFailedDialog.tsx` / `CollectChequeDialog.tsx` pattern: shows installment/amount context, a single date `<input type="date">` (default = today, `max` = today), Cancel/Confirm. On confirm: `PUT /api/proxy/v1/payments/{id}/clear` with `{ effectiveDate }`.
- `finance/payments/page.tsx`: replace the generic `confirmDialog`-based `handleClear` with this new dialog.
- `leases/[id]/page.tsx`: replace the `window.confirm`-based `handleClear` with the same dialog.
- `MarkChequeFailedDialog.tsx`: add the same date field (default today, `max` today) alongside the existing failure-reason dropdown and notes field; include `effectiveDate` in the `POST /mark-failed` body.
- Surface the backend's `BusinessRuleViolationException` message (already handled generically by both dialogs' existing error-display path) if a future date somehow reaches the server.

### Mobile (`mobile/apps/manager`)

- **New `clear_cheque_dialog.dart`**, mirroring `mark_cheque_failed_dialog.dart`: a `showDatePicker` (initialDate = today, lastDate = today) plus Cancel/Confirm.
- `payments_screen.dart`: `_PaymentActionSheet`'s `onClear` currently calls `clearPayment(paymentId)` directly with no dialog — change it to open `clear_cheque_dialog.dart` first, then call `clearPayment(paymentId, effectiveDate: pickedDate)`.
- `payment_service.dart`: add an optional `effectiveDate` (`DateTime?`) param to `clearPayment()` and `markPaymentFailed()`, serialized as `yyyy-MM-dd` in the request body when present.
- `mark_cheque_failed_dialog.dart`: add the same date picker field, wired through to `markPaymentFailed`'s new `effectiveDate` param.

### Testing

- Backend: unit test(s) asserting `clearPayment` / `markFailed` throw `BusinessRuleViolationException` when `effectiveDate` is in the future (extend `PaymentScheduleServiceMarkFailedTest` and `PaymentScheduleServiceVatTest`, or add a small dedicated test class).
- Backend: extend `ChequeFailurePenaltyIT` (or add a case) asserting a past `effectiveDate` on `markFailed`/`clearPayment` lands on both `statusChangedAt` and the ledger transaction `date` — not just today.
- Frontend: no existing test coverage for these dialogs to extend; manual verification via the dev server (per this repo's "test UI changes in a browser before reporting done" convention) is the bar here, consistent with `CollectChequeDialog`/`MarkChequeFailedDialog` today.

## Open questions / risks

- None outstanding — scope and constraints were confirmed during brainstorming.
