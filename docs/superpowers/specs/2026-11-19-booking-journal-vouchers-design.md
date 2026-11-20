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

1. Count the whole calendar months from `start`: step a month at a time while
   `start + n months - 1 day <= end`.
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

Web and mobile estimate the fee on the device today (renter portal facilities
page, `facilities_screen.dart`). They switch to a server quote,
`GET /api/v1/bookings/quote`, so the month maths lives in one place.

### 2. Income account

New nullable column `income_account_id` on `parking_spots` and
`property_amenities`. It is set in the bay and amenity forms with
`AccountPicker accountType=INCOME`.

At approval, the income account is chosen in this order:

1. The bay's or amenity's `income_account_id`, if set.
2. Parking: the `PARKING_INCOME` account of the property ("Additional Parking -
   {property}").
3. Amenity: the `AMENITY_INCOME` account of the property (new role, below).

The chosen account id is stored on the charge (`PenaltyAssessment.incomeAccountId`,
which already exists and which `approve` already honours).

The account must pass the same check as a contract JV
(`requireIncomeLeafFor`): an active income leaf, shared or of the lease's
property. The check runs when the bay or amenity is saved, and again at
approval.

New account role `AMENITY_INCOME`:

- Per-property leaf "Amenity Income - {property}" under `C-01-02`, added to
  the template in `PropertyAccountService`, with an Arabic name.
- A tenant default leaf, like the other roles.
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
  PROPERTY_MANAGER. Body: as `BookingCreateRequest`, plus `unitId` and
  `renterUserId`.
- Runs the same checks as a renter booking (dates inside the lease, overlap,
  bay not attached), then approves at once through the same approve path, so
  the same JV posts.
- Web: a "New booking" button on `/dashboard/bookings`.

### 5. Cancel and release (unchanged rules, now written down)

| Event | Fee not paid | Fee paid |
|---|---|---|
| Renter cancels before the slot | JV reversed (`reverseOnCancel`) | Refused: ask the office |
| Staff cancel | JV reversed | JV reversed, payment kept as credit, VAT credit note (`creditOnStaffCancel`) |
| Release early (parking) | No change: the fee stands | No change |
| Reject a pending request | Nothing was posted | Nothing was posted |

Partial refunds on early release are out of scope. Staff who want to refund
part of the fee use staff-cancel on the booking and approve a new booking for
the shorter dates.

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
