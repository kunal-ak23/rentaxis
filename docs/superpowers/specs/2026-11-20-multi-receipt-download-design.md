# Multi-receipt download — design

Date: 2026-11-20
Branch: `feat/multi-receipt-download`

## Intent

A renter asks "give me all my receipts for this year". Today staff (and the
renter) open each receipt one by one. Staff and renters, on web and on mobile,
should be able to tick several receipts of one contract and download them as
one PDF, with a one-tap way to tick a whole year.

## Goals

- Every place that lists a contract's cleared payments lets the user tick
  rows and download them as one PDF:
  - staff web: lease page cheque grid (exists since #463; add the year shortcut)
  - renter web: renter portal Payments page
  - renter mobile: Payments screen
  - staff mobile: lease detail screen's cheque schedule
- A "select year" shortcut ticks every cleared row of that contract whose
  money landed in the chosen calendar year. Individual rows can then be
  unticked.
- Output is the existing combined receipt: `POST /api/v1/cheques/receipts/combined`,
  one PDF, a receipt per page, in the order the money landed.

## Non-goals

- Cross-contract downloads (several leases, a whole property, a month across
  the portfolio). One download = one contract. A year that spans a renewal
  takes two downloads, one per contract.
- ZIP output, emailing the bundle, server-side "receipts for a period" endpoint.
- Changing the receipt page layout or numbering.
- Rebuilding the staff mobile Payments tab (see Constraints).

## Anti-goals

- A renter must never get a receipt for someone else's row. The per-row checks
  in `RentReceiptService.receiptable` (lease readable, renter's own row after an
  assignment, not pre-acquisition, CLEARED) stay the only gate.
- No receipt e-mails fire from a combined download (as today).

## Constraints / current state

- `RentReceiptService.combined(ids)`: dedups, rejects empty, rejects more than
  `MAX_COMBINED = 100`, rejects rows from more than one lease, sorts by
  cleared date then seq. Roles: SA, TA, ACCOUNTANT, PROPERTY_MANAGER, RENTER.
  A year of monthly rent plus charges is well under 100, so the limit stays.
- "Year" uses the date the money landed: `clearedAt`, falling back to
  `chequeDate` — the same `receivedOn` the PDF prints. The client mirrors this
  with `clearedAt ?? chequeDate` (renter rows: `clearedAt ?? dueDate`).
- Renter web Payments page (`renter-portal/payments/page.tsx`) lists the
  renter's rows across all their leases (`RenterCheque` carries `leaseId`).
  Selection must be per contract: when the renter has more than one lease, the
  download button acts on one lease at a time.
- Renter mobile Payments screen already has a lease picker (`_leaseId`) when
  the renter has more than one lease; selection works on the picked lease.
- Staff mobile: the lease detail screen loads its schedule from
  `/v1/payments/lease/{id}` and the Payments tab from `/v1/payments/*`. No
  `/api/v1/payments` controller exists in the backend any more, so these calls
  fail (the lease detail screen's `Future.wait` fails as a whole). This work
  moves the lease detail schedule to `GET /v1/leases/{id}/cheques` (the same
  rows the web grid shows), which is needed to tick cleared rows at all. The
  other dead v1 calls (Payments tab, mark-failed dialog, single
  `downloadReceipt`) are a separate follow-up.

## Approaches considered

- **A. Tick boxes + "select year" shortcut, existing endpoint (chosen).**
  Matches "select several and download together"; reuses the merged PDF; only
  client work plus the staff mobile data-source fix.
- **B. "Receipts for a period" button + new `GET /leases/{id}/receipts?from&to`.**
  Leanest on mobile, but no picking of individual receipts.
- **C. Cross-contract ZIP from the collections register.** Dropped: the user
  wants one contract at a time.

User picked A.

## Design

### Backend

No new endpoint. One DTO field: `RenterChequeDTO.settledBeforeAcquisition`
(see below). Add tests that pin the behaviour the clients now rely on:
- a RENTER can download a combined receipt of their own cleared rows;
- a RENTER is refused (not-found) when any id belongs to another renter's
  lease or to the other party after an assignment;
- mixed leases and > 100 ids are refused (existing tests may already cover;
  add what is missing).

**Builder's call (review): rows banked by a previous owner.** `combined` fails
the whole PDF if any row has `settledBeforeAcquisition` (`requireOurs`). Staff
web already reads that flag from `ChequeDTO`; `RenterChequeDTO` lacks it, so
the renter's single receipt link is already offered on rows the server
refuses. Add `settledBeforeAcquisition` to `RenterChequeDTO` (and the web
`RenterCheque` type); renter web and mobile neither tick nor link a receipt
for those rows. Year select skips them.

**Builder's call (review): finished leases.** Renters may download receipts
for their own cleared rows whatever the lease status (expired, terminated,
cancelled). That is the server rule, and the "all my receipts for last year"
request often comes after the lease ended. The staff web grid keeps its
existing rule.

### Shared year rule

A row is in year Y when its received date (`clearedAt`, else cheque/due date)
falls in Y. The year picker offers the years present among the contract's
cleared rows, newest first, defaulting to the current year when present.

### Staff web — lease page

`ChequeGrid` combined-receipt bar gains a year select ("Select year: 2026 ▾"
+ "Select" button, or a select that ticks on change). Choosing a year replaces
the current ticks with that year's receiptable rows. Existing per-row
checkboxes and the download button stay.

### Renter web — Payments page

History section (CLEARED rows) gets a checkbox per row and a bar:

```
[Contract: TA-101 ▾]  [Year: 2026 ▾] [Select year]   [Download receipts (12)]
```

The contract select appears only when the renter has more than one lease with
cleared rows; ticking a row of another lease switches nothing — rows of
other leases are not tickable while a contract is chosen. Download calls
`chequeApi.combinedReceipt(ids)` and saves the blob; ticks clear on success;
errors show the server message.

### Renter mobile — Payments screen

On the picked lease (or the only lease), CLEARED rows get a checkbox. A
bottom/top action row offers year chips or a dropdown ("Select 2026") and
"Download receipts (n)". The bytes go through the existing share sheet
(`shareRenterDocumentProvider`), file name from the server's
`Content-Disposition`, falling back to `receipts.pdf`. With several leases and
"all leases" selected, the selection controls are hidden and a hint says to
pick a contract.

### Staff mobile — lease detail

**User decision:** built behind the existing `MOBILE_FINANCE` flag (default
off), which already hides every `/leases*` route in the manager app. No flag
change in this work; the screen is ready when finance is switched on.

Schedule loads from `GET /v1/leases/{id}/cheques` (map `status`, `amount`,
`chequeDate`, `chequeNumber`, `clearedAt`, `seqNo` into the existing
`_ChequeTimelineRow`). CLEARED rows get a checkbox; the same year shortcut
and "Download receipts (n)" button; bytes shared via the platform share sheet
like the manager app's existing document downloads. Roles that can open the
lease detail already match the endpoint's roles.

### Limits and errors

- Max 100 per download (server). Clients disable the button above 100 and say
  why.
- Server refusals (mixed leases, not cleared, not found) surface as the
  server's message.

## Left to the builder

- Exact widget placement and copy (en + ar) on each surface.
- Whether the year control is a select + button or a select that ticks on change.
- How the staff mobile timeline row maps any v1-only fields it used.
- Test split between unit and widget tests on mobile.
