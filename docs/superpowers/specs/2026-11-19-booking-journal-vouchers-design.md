# Booking journal vouchers: parking and amenities (upfront)

Date: 2026-11-19. Status: draft for Kunal's review.

## Goal

When staff approve a parking or amenity booking, the renter is charged by a
journal voucher (`JV`) to the right income account, for the right amount, so
that the books match the bookings at all times.

Example: a renter asks in the mobile app for a common parking bay for two
months. Staff approve it. One `JV` posts that day: Dr the renter's receivable,
Cr "Additional Parking - {property}" for two months' fee, plus 5% VAT. The
renter sees the payable row in Payments.

## Decisions

- **Upfront only, for now.** The whole fee is income on the approval date,
  however many months the booking covers. Spreading the income month by month
  through Unearned is a later option (see "Later: monthly option").
- **The books always match** (see "Ledger rules"). This holds for every
  booking, cancel, release and staff-cancel.
- **No new charge entity.** Booking fees stay `PenaltyAssessment` rows with
  reason `BOOKING_FEE` (F14-50), raised by `BookingFeeService.chargeOnApproval`.
  The existing approve, reverse and credit paths do the accounting.

## What exists today

- Parking bays (`parking_spots`) and amenities (`property_amenities`) each have
  `fee_type` and `fee_amount`. Parking allows `FREE` and `PER_BOOKING`.
  Amenities also allow `PER_HOUR`.
- Approving a booking with a fee raises a `BOOKING_FEE` charge on the renter's
  live lease and approves it at once (`BookingFeeService.chargeOnApproval`).
- That charge posts a `PEN` journal to `OTHER_INCOME`, because
  `PenaltyReason.BOOKING_FEE` maps to `OTHER_INCOME`.
- The renter is sent a "penalty incurred" notification. The collection row is
  labelled "Penalty - Booking fee". Both are wrong for a service the renter
  asked for. The contract `JOURNAL_VOUCHER` path already avoids both
  (`PenaltyAssessmentService.approve`).
- `PER_BOOKING` charges one flat fee, so a two-month parking booking costs the
  same as a one-day booking.

## Changes

### 1. Fee types

| Fee type | Parking | Amenity | Quote |
|---|---|---|---|
| `FREE` | yes | yes | 0 |
| `PER_BOOKING` | yes | yes | amount |
| `PER_HOUR` | no | yes | unchanged |
| `PER_DAY` (new) | yes | yes | amount × days in range, inclusive |
| `PER_MONTH` (new) | yes | no | see below |

`PER_MONTH` quote, for a booking from `start` to `end` inclusive:

1. Count the whole calendar months from `start`: the largest `n` with
   `start + n months - 1 day <= end`. It is worked out from the calendar-month
   difference, not counted a month at a time (PR #468 review I1), so a far-off
   end date costs nothing.
2. The days left over are charged at `amount / days in the month they start
   in`, per day.
3. Round the total to 2 places, half up.

Examples at 300 a month: 1 Dec to 31 Jan is 600. 15 Dec to 14 Feb is 600.
1 Dec to 10 Jan is 300 + 10 × 300 / 31 = 396.77.

Changes:

- `BookingFeeService.TYPES`, `validate` and `quote`.
- The `ck_spot_fee` and `ck_amenity_fee` checks (from `143-booking-fees.yaml`),
  dropped and re-added in a new changeset.
- Web: fee type select in `ParkingTab.tsx` and `AmenitiesTab.tsx`.
- Web and mobile: fee labels ("per day", "per month"), including
  `facilities_screen.dart` and the renter portal facilities page.

The quote is still taken when the renter asks and stored on the request.
Approval charges the stored quote, as today. If approval fills in dates the
request did not have (an undated parking request), the quote is taken again.

A parking booking on a `PER_DAY` or `PER_MONTH` bay requires an end date
(final review, 2026-11-20). `create` and `createOnBehalf` refuse one without
with `booking.endDateRequired`, so no undated periodic request exists for
approval to fill in and re-quote. The renter portal, the mobile app and the staff
"New booking" dialog make the end date required for those bays and show no quote
until both dates are set. `FREE` and `PER_BOOKING` bays may still be requested
without an end date. An amenity is booked for one day, so a `PER_DAY` amenity
needs no end date.

Web and mobile estimate the fee on the device today (renter portal facilities
page, `facilities_screen.dart`). They switch to a server quote,
`GET /api/v1/bookings/quote`, so the month maths lives in one place.

The quote endpoint bounds its dates before pricing (PR #468 review I1): an end
date needs a start date and is not before it, and the range covers at most
`MAX_PARKING_YEARS` (2) years, refused with `booking.rangeTooLong` as on a
request. A date before today is not refused for a quote.

### 2. Income account

New nullable column `income_account_id` on `parking_spots` and
`property_amenities`. It is set in the bay and amenity forms with
`AccountPicker accountType=INCOME`.

At approval, the income account is chosen in this order:

1. The bay's or amenity's `income_account_id`, if set.
2. Parking: the `PARKING_INCOME` account of the property ("Additional Parking -
   {property}").
3. Amenity: the `AMENITY_INCOME` account of the property (new role, below).
4. Either: `OTHER_INCOME` of the property, while it has no parking or amenity
   leaf mapped (PR #468 review m5). This is where every booking fee posted
   before this change, so approval never fails for a missing parking or
   amenity leaf while `OTHER_INCOME` is mapped.

The chosen account id is stored on the charge (`PenaltyAssessment.incomeAccountId`,
which already exists and which `approve` already honours).

The account must pass the same check as a contract JV
(`requireIncomeLeafFor`): an active income leaf, shared or of the lease's
property. The check runs when the bay or amenity is saved, and again at
approval.

New account role `AMENITY_INCOME`:

- Per-property leaf "Amenity Income - {property}" under `C-01-02`, added to
  the template in `PropertyAccountService`, with an Arabic name.
- No tenant-level default leaf is seeded (as for `PARKING_INCOME` and
  `OTHER_INCOME`). Until a property has its own leaf, amenity fees fall back
  to `OTHER_INCOME` (below).
- Existing tenants: `seedDefaultTemplateAndDefaults` adds template rows only
  when a tenant has none, so a changeset adds the `AMENITY_INCOME` template row
  for tenants that already have a template (the pattern of
  `138-interproperty-clearing.yaml`). New properties then get the leaf.
  Existing properties get it when staff press "Generate missing accounts"
  (`PropertyAccountService.generateMissing`).
- Until a property has the leaf, amenity fees fall back to `OTHER_INCOME`, as
  today. Approval never fails for a missing amenity leaf.
- A report line next to Additional Parking in `ReportLines`.

Banquet, pool and tennis income can be split by giving each amenity its own
leaf under `C-01-02` and picking it on the amenity.

### 3. Post as a journal voucher, not a penalty

Add `PenaltyReason.requestedByRenter()`, true for `JOURNAL_VOUCHER` and
`BOOKING_FEE`. In `PenaltyAssessmentService.approve`, use it in place of the
three `== JOURNAL_VOUCHER` checks:

- Doc type `JV`, not `PEN`.
- Collection row label: the charge's narration, for example "Booking fee -
  Parking P-12, 1 Dec 2026 to 31 Jan 2027". Not "Penalty - Booking fee".
- No "penalty incurred" notification. The renter already hears about the
  approval from `BookingNotificationService`. Add the amount and "pay in
  Payments" to that notification.

Charges posted before this change keep their `PEN` journals. Nothing is
re-posted.

The narration names the bay or amenity and the full date range. Today it
shows only the start date.

### 4. Staff booking on behalf of a renter

For walk-in banquet or court bookings:

- `POST /api/v1/bookings/on-behalf`. Roles: SUPER_ADMIN, TENANT_ADMIN,
  PROPERTY_MANAGER. Body (`BookingOnBehalfRequest`):
  `{ "booking": BookingCreateRequest, "renterUserId": UUID }`. The unit is
  `booking.unitId` (required); there is no top-level `unitId`.
- Runs the same checks as a renter booking (dates inside the lease, overlap,
  bay not attached), then approves at once through the same approve path, so
  the same JV posts.
- Web: a "New booking" button on `/dashboard/bookings`.
- When the renter already has a pending request for the same slot, `create`
  hands it back and that request is approved. Its fee, `fee_type` and
  `fee_unit_amount` are taken again from the bay or amenity first, so it is
  charged at the price staff were quoted (PR #468 review m6).

### 5. Cancel and release (unchanged rules, now written down)

| Event | Fee not paid | Fee paid |
|---|---|---|
| Renter cancels before the slot | JV reversed (`reverseOnCancel`) | Refused: ask the office |
| Staff cancel (amenity, or parking before its start) | JV reversed | JV reversed, payment kept as credit, VAT credit note (`creditOnStaffCancel`) |
| Release early (parking, `PER_DAY` / `PER_MONTH`) | Unused days credited (see 6) | Unused days credited as renter credit (see 6) |
| Fee payment bounced, replaced, returned or transferred | Release refused (`booking.feeRowNotSettled`) | Release refused |
| Release early (parking, `PER_BOOKING`) | No change: the fee stands | No change |
| Reject a pending request | Nothing was posted | Nothing was posted |

Decided 2026-11-20 (Kunal): parking refunds are pro-rata on early release, and
staff may cancel an approved parking booking before its start date.

`PER_DAY` and `PER_MONTH` parking bookings always have an end date (§1), so the
fee charged is the fee quoted and an early release has a range to credit.

### 6. Pro-rata credit on early parking release

Today approved parking can only be released, and the fee always stands. This
changes:

- **Staff cancel before start.** `cancelByStaff` accepts an approved parking
  booking whose start date is after today. It goes through
  `creditOnStaffCancel`, exactly as for amenities. On or after the start
  date, staff release instead.
- **Fee snapshot.** At quote time the booking stores the fee type and unit
  amount it was quoted on (new columns `fee_type`, `fee_unit_amount` on
  `booking_requests`), so a later change to the bay's price does not change
  the credit. Bookings made before this change have no snapshot, and early
  release credits nothing for them.
- **Used part.** On release, the used part is `quote(fee_type,
  fee_unit_amount, start, releaseDate)`, where `releaseDate` is the day of
  release (inclusive) in Dubai time. The credit is `fee_amount − used`,
  never below 0 and never above `fee_amount`. A release on or after the end
  date credits 0. The month maths is the same `quote` as at approval, so a
  two-month booking at 300 a month released after the first whole month
  credits 300.
- **Posting.** One credit journal, dated today, linked to the charge (source
  `PENALTY`, charge id):
  - Dr the same income account the charge credited, for the credit;
  - Dr `OUTPUT_VAT` for the VAT share, if the charge carried VAT
    (`LeaseVat.vatOfNet(credit)`), with a VAT credit note for that amount;
  - Cr the lease receivable for credit + VAT.
- **Collection row.**
  - Not paid: the row's amount drops by credit + VAT, so the renter owes
    only the used part.
  - Paid (cleared): the row stays as it is, and credit + VAT is left as a
    credit on the renter's account, as `creditCollected` does today.
  - Payment in flight (deposited, or paying online): the release is
    refused with `booking.feeInFlight`, as for staff cancel. The release
    can be repeated once the payment clears.
  - Allowed row statuses: no collection row, `REGISTERED` (not paid) or
    `CLEARED` (paid). Any other status (bounced, replaced, returned,
    transferred, draft) refuses the release with `booking.feeRowNotSettled`:
    the row or its replacement would still ask for the full fee after the
    receivable was credited (ledger rule 3). Settle or replace the payment
    first. A row cancelled elsewhere stands like a write-off: no credit.
  - Written off: the release goes ahead with no credit, as the fee stands.
- **Settled contract.** When a credit would post but the lease is `CLOSED`
  or its settlement is finalised, the release is refused with
  `booking.leaseSettled` (PR #468 review m2/m3): the settlement counted the
  full fee. `ChequeService.reduceUnpaidBySystem` refuses a finalised
  settlement too (`cheque.settlementFinalized`). A release with nothing to
  credit goes ahead.
- **Who.** Both the renter's release and the staff release credit
  automatically. No cash leaves: the credit sits on the renter's account
  until the existing refund or settlement flows use it.
- The charge records `credited_amount` (net). A second release of the same
  booking is impossible, because a booking is released once.

## Ledger rules (the books always match)

1. Every booking fee journal has source `PENALTY` with the charge id, and the
   charge has source `BOOKING` with the booking id. Every journal can be
   traced back to its booking.
2. A journal is never dated in a locked or closed month. Approval, reversal
   and credit are dated on the day they happen, inside the manual-date window
   (`PostingDatePath.PENALTY_CHARGE`).
3. The receivable from a booking fee always equals the fee plus VAT, which is
   also the amount of its collection row.
4. VAT is charged on the full fee at approval (tax point = approval date).
5. A cancelled or staff-cancelled booking leaves no balance in the receivable.
   Its income nets to zero, or its payment sits as renter credit.
6. Approved, not-cancelled bookings with a fee = approved `BOOKING_FEE`
   charges = their journals. A reconciliation query in the golden test checks
   all three counts and totals.
7. For a released booking, income net of its credit journal = the used part
   (`fee_amount − credited_amount`), and receivable plus renter credit nets to
   the used part plus its VAT.

## Tests

Golden (accounting acceptance gate):

- Two-month parking booking at `PER_MONTH` 300: one `JV` on the approval date,
  Dr receivable 630, Cr Additional Parking 600, Cr Output VAT 30.
- The same booking cancelled before its start: the reversal nets every account
  to zero.
- Paid, then staff-cancelled: receivable 0, renter credit 630, VAT credit note.
- Amenity with its own income leaf: credit lands on that leaf.
- Amenity with none: credit lands on "Amenity Income - {property}".
- A booking approved after a period lock: no journal dated inside the locked
  period.
- Reconciliation (rule 6) over the whole seeded tenant.
- Two-month parking at `PER_MONTH` 300, unpaid, released the day after the
  first whole month ends: credit 300 − 1 day (300 − 300/days in that month),
  VAT share reversed, collection row reduced by the same total.
- The same, paid: row unchanged, renter credit = credit + VAT, VAT credit note.
- Released on its last day: credit 0, no journal.
- Staff cancel of approved parking before its start: full reversal.
- Staff cancel of approved parking on its start date: refused.
- Release with payment in flight: refused with `booking.feeInFlight`.
- Rule 7 over the reconciliation tenant.

Unit: `PER_MONTH` and `PER_DAY` quotes, including the examples above, leap
years and a range shorter than a month.

Web and mobile: fee labels, fee type selects, income account picker, staff
booking form.

## Later: monthly option

Not built now. The design leaves room for it:

- An organisation setting `booking_income_mode`, `UPFRONT` (default) or
  `MONTHLY`. Applies only to bookings that cross a month end.
- `MONTHLY` would raise a `PARKING_FEE` lease addendum line for the booking's
  dates through `LeaseVariationService.addCharge`. The existing recognition
  engine then credits Unearned at approval and moves it to income month by
  month, with locked-month catch-up.
- Before it can ship it needs a way to cut an addendum short on early release
  or cancel: drop the recognition rows after the release date and reverse the
  unearned remainder, dated today. That piece does not exist yet.

Keep the choice of posting path in one place in `BookingFeeService`, so the
monthly path is one more branch.

## Out of scope

- Security deposits for banquet bookings.
- Amenity capacity, opening hours and slot templates.
- A generic "any free bay" parking request. The renter still picks a bay.
- Showing allocated parking in the mobile renter app (web only today).
- A parking list filtered by building.
