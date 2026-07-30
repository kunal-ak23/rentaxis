# Cheque Deposit Reminder for Renters + Fix Broken Cheque/Payment Emails — Design

**Date:** 2026-07-11
**Status:** Approved (design)
**Author:** Kunal Sharma (with Claude)

## Problem

Renters who pay by post-dated cheque (PDC) get no warning before the cheque is deposited. If they haven't kept enough funds in the account, the cheque bounces, triggering a penalty and a manual replacement process. A reminder a few days ahead — "this cheque will be deposited soon, make sure funds are available" — lets the renter top up in time and avoids the bounce entirely.

While investigating how to deliver this reminder by email, we found that three existing cheque/payment email types are **silently broken today**: `PAYMENT_DUE_REMINDER`, `CHEQUE_CLEARED`, and `CHEQUE_BOUNCED` create their in-app notification row but never send the corresponding email. Root cause: `NotificationService.notify()` (the "legacy" path) wraps data in `LegacyNotificationPayload` (`userId`, `title`, `body`, `referenceType`, `referenceId`), but `RecipientResolver.userIdsFor()` requires a `renterUserId()`/`propertyManagerUserId()` accessor on the payload for `RENTER`/`PROPERTY_MANAGER` recipient roles (`RecipientResolver.java:57-65`). `LegacyNotificationPayload` has neither, so reflection lookup fails silently (`NoSuchMethodException` → `null`), recipient resolution returns empty, and only a `log.warn("No email recipients resolved...")` fires — no exception, no visible failure.

`CHEQUE_RECEIVED` and `CHEQUE_DEPOSITED` don't have this problem: `PaymentScheduleService.applyChequeReceived()` and `depositPayment()` already publish a dedicated `ChequePayload` (which has `renterUserId()`) directly via `events.publishEvent(new EmailEvent(...))`, bypassing `notify()` entirely.

Since the new reminder needs working email delivery and the fix is the same "use `ChequePayload` instead of `notify()`" pattern already proven for `CHEQUE_RECEIVED`/`CHEQUE_DEPOSITED`, both are addressed in this pass.

## Goals

- Renter receives an email N days before their cheque's `chequeDate`, for cheques in `PENDING` or `COLLECTED` status (not yet deposited/cleared/bounced/cancelled/replaced).
- N is configurable per tenant (`RentCollectionSettings.chequeDepositReminderDays`, default 3), consistent with how `paymentReminderDays` already works for payment-due reminders.
- Fix `CHEQUE_CLEARED`, `CHEQUE_BOUNCED`, and `PAYMENT_DUE_REMINDER` so their emails actually send, using the same dedicated-payload pattern.

## Non-goals

- No SMS or push delivery — email only.
- No renter-facing UI change (no new page/toggle) — this is a backend notification addition.
- No fix for the pre-existing cross-tenant reminder-day union quirk in `checkPaymentDueReminders`/`checkChequeDepositReminders` (all tenants' distinct reminder-day values are unioned and applied globally rather than strictly per-tenant) — out of scope for this round.
- No change to who can close/deposit cheques or any other cheque lifecycle behavior.
- No "already sent" dedup table — relies on the existing once-daily-cron, exact-day-match pattern already used by `checkPaymentDueReminders`/`checkExpiringLeases` for idempotency.

## Decisions (from brainstorming)

| Question | Decision |
|---|---|
| Who receives the reminder | The renter (cheque writer) — not staff/property manager. |
| Trigger date | `PaymentSchedule.chequeDate` (the date written on the cheque), not the schedule's `dueDate`. |
| Channel | Email only. |
| Eligible statuses | `PENDING` and `COLLECTED` (cheque not yet deposited). |
| Reminder window | Configurable per tenant via `RentCollectionSettings.chequeDepositReminderDays`, default 3. |
| Existing broken emails (`PAYMENT_DUE_REMINDER`, `CHEQUE_CLEARED`, `CHEQUE_BOUNCED`) | Fix in this same pass, since the correct pattern (`ChequePayload` + direct `EmailEvent` publish) is needed here anyway. |

## Design

### Settings

- Add `chequeDepositReminderDays` (`Integer`, nullable, treated as 3 when null) to `RentCollectionSettings`.
- New Liquibase changeset `64-cheque-deposit-reminder-days.yaml` adding the column (no default constraint needed — application code falls back to 3).

### Email event type

- New `EmailEventType.CHEQUE_DEPOSIT_REMINDER` in `EmailEventType.java`, category `TRANSACTIONAL`, attachment `NONE`, recipient roles `{RENTER}`.
- New template `templates/email/events/cheque_deposit_reminder.html`, styled like `cheque_received.html`, using `ChequePayload`'s fields (cheque number, bank, cheque date, amount, installment) plus copy explaining the cheque will be deposited soon and funds should be available.

### Fix existing broken emails

All three replace their `notificationService.notify(...)` call with a direct `events.publishEvent(new EmailEvent(...))` using `ChequePayload`, matching `applyChequeReceived`/`depositPayment`:

- **`PaymentScheduleService.clearPayment()`** (`PaymentScheduleService.java:801-813`): swap `notify("PAYMENT_CLEARED", ...)` for `events.publishEvent(EmailEventType.CHEQUE_CLEARED, ChequePayload, ...)` + `notificationService.notifyInAppInNewTx(...)` for the in-app row (`failureReason` null, `depositDateIso` from `statusChangedAt`).
- **`PaymentScheduleService.markFailed()`** (`PaymentScheduleService.java:886-899`): swap `notify("PAYMENT_BOUNCED", ...)` for `events.publishEvent(EmailEventType.CHEQUE_BOUNCED, ChequePayload, ...)` (with `failureReason` populated) + `notifyInAppInNewTx(...)`. `sendPenaltyIncurred` call is unaffected (separate, correctly-routed penalty notification).
- **`NotificationScheduler.checkPaymentDueReminders()`** (`NotificationScheduler.java:83-91`): inject `ApplicationEventPublisher events` into `NotificationScheduler`; publish `EmailEventType.PAYMENT_DUE_REMINDER` with a `ChequePayload` (cheque-specific fields — `chequeNumber`, `bankName`, `depositDateIso`, `failureReason` — null, since this reminder fires for any pending installment regardless of payment method) alongside the existing in-app `notify()` call, which still writes the in-app row via its own path — replace with `notifyInApp(...)` to avoid a duplicate email attempt through the (now-still-broken-for-this-type-if-left-as-is) legacy mapping.

### New scheduler method

`NotificationScheduler.checkChequeDepositReminders()`, called from `sendDailyNotifications()` alongside the existing three checks:

- Same shape as `checkPaymentDueReminders()`: collect the distinct `chequeDepositReminderDays` values across all tenants' `RentCollectionSettings` (default `{3}` if none configured), and for each offset, query `PaymentSchedule` where `status IN (PENDING, COLLECTED)` and `chequeDate.equals(today.plusDays(offset))`.
- For each match: publish `EmailEventType.CHEQUE_DEPOSIT_REMINDER` with `ChequePayload` (`renterUserId` from `lease.getRenter().getUserId()`, cheque fields populated, `failureReason`/`depositDateIso` null) plus an in-app `notifyInApp(...)` call, wrapped in try/catch per-item like the other checks (a failure on one schedule shouldn't stop the rest).
- Skips schedules with a null `chequeDate` or null renter user id, matching existing null-guards in sibling methods.

### Testing

- Unit test: `RecipientResolver` resolves a non-empty recipient list for `ChequePayload`-backed `CHEQUE_CLEARED`, `CHEQUE_BOUNCED`, `PAYMENT_DUE_REMINDER`, and `CHEQUE_DEPOSIT_REMINDER` events (regression coverage for the bug found here).
- Unit test: `checkChequeDepositReminders` date-window matching (`chequeDate - today == configured days`, boundary cases at 0/negative/null `chequeDate`) and settings fallback to default 3 when `chequeDepositReminderDays` is null.
- Unit/integration test: eligible statuses (`PENDING`, `COLLECTED`) fire; ineligible ones (`DEPOSITED`, `CLEARED`, `BOUNCED`, `CANCELLED`, `REPLACED`, `ONLINE_PENDING`) don't.
- Extend or add to existing `PaymentScheduleService` tests covering `clearPayment`/`markFailed` to assert an `EmailEvent` with the correct `EmailEventType` and `ChequePayload` fields is published (not just that a legacy `notify()` call happened).

## Open questions / risks

- None outstanding — scope and constraints were confirmed during brainstorming.
