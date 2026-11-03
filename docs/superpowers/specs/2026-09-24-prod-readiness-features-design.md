# Production-readiness features: design

> **Product-owner decisions (2026-09-24) — binding; they override any conflicting text below.**
> - Rent-free recognition is **straight-line (IFRS 16)**: total contract rent ÷ whole-term days, recognised evenly
>   *including* the free months. The cheque grid still excludes free months; the contract shows the concession.
> - **Auto tax invoice per instalment**: each instalment's VAT tax-point posting also produces a numbered tax
>   invoice (TRN, VAT breakdown, PDF) — in scope for PR 2.
> - Retained Earnings: **one account, lines tagged by property**.
> - Escalation: **whole AED, no RERA cap**; operator may type an exact amount.
> - Defaults: a PDC handed over at signing is **not** payment (cheque date governs the VAT tax point); no
>   cross-entity transfers; a transfer keeps the old rent unless edited; no VAT on residential admin/renewal fees.


**Status: DRAFT 2026-09-24.** For review. No code has been written.
**Product decisions:** Kunal, 2026-09-24 (push to 95%, cheques only, no Razorpay). Each
feature below takes the recommended option recorded in the decision memo. Where this spec
chooses something the memo leaves open, the choice is marked **Decision** and repeated in
*Open questions*.

Addresses findings from the two-year simulation (`tutorials/miftah-2y/gaps.md`):

| # | Pri | Feature | Section |
|---|---|---|---|
| 56 | P1 | Output VAT recognised per instalment, not all at TCO | §1 |
| 52 | P1 | Unit transfer | §2 |
| 53 | P2 | Fiscal year-end close into Retained Earnings | §3 |
| 24 / 50 / 23 | P2 | Renewal escalation, rent-free periods, one-off fees, renewal charges | §4 |
| 55 / 59 / 65 | P2–P3 | VAT return view, collection tile by due date, lease grace inherits | §5 |

Ledger rules from the accounting-v2 spec (`2026-09-17-accounting-v2-design.md`) hold
throughout:

- `PostingService` is the only journal writer.
- `reverse` is the only mutation.
- Callers name `AccountRole`s and `AccountResolver` maps them per property.
- Per-day recognition is `rent ÷ term days`, and the last period absorbs rounding.
- The golden gate (`./gradlew test -PincludeTags=golden`) stays green on every PR.

Liquibase changesets start at **101**. Changesets 97–99 are on the current branch and 100
is reserved.

---

## Shared additions

| Kind | Addition | Used by |
|---|---|---|
| `AccountRole` | `OUTPUT_VAT_DEFERRED`: tenant-scoped liability "Output VAT – not yet due", seeded leaf `B-01-03-002` under `B-01-03 Output VAT` | §1 |
| `AccountRole` | `RETAINED_EARNINGS`: tenant-scoped equity, seeded leaf `F-03 Retained Earnings` under `F Equity` | §3 |
| `JournalDocType` | `VTP`: VAT tax point (deferred → output VAT) | §1 |
| `JournalDocType` | `YEC`: year-end closing entry | §3 |
| `LeaseStatus` | `TRANSFERRED`: ended because the renter moved to another unit under the same landlord | §2 |
| `ChequeStatus` | `TRANSFERRED`: terminal; the instrument now belongs to the successor lease's row | §2 |
| `ChargeType.recurrence` | `RECURRING` / `ONE_OFF` | §4 |

**Verified:**

- There is **no** retained-earnings role or account today. The equity group holds only
  `F-01 Capital` and `F-02 Opening Balance Difference` (`AccountService.seedDefaultAccounts`).
- `JournalDocType`, `LeaseStatus` and `ChequeStatus` are stored as VARCHAR with no CHECK
  constraint. Only vouchers and import batches have one (changesets 87, 88). New enum values
  therefore need no DDL.
- Both new roles go into `AccountRole.isPropertyScoped() == false` and
  `PropertyAccountService.defaultIfMissing`.
- Existing tenants get the new leaves and default mappings through the changeset data
  migrations below. Each migration inserts only where the parent group exists, which is
  the same per-row tolerance `template()` has.

---

## 1. VAT per instalment (#56)

### Goal

The first VAT-bearing TCO (TCO-26/3 in the simulation) credited the whole year's 6,000 to
`OUTPUT_VAT` on 20/04/2026. That put four quarters' tax into the Q2 return. Output VAT must
instead reach `OUTPUT_VAT` on each instalment's **tax point**. Until then it sits in a
deferred liability.

### Tax point rule (decision and justification)

UAE VAT Decree-Law No. 8 of 2017, Art. 26 (date of supply for periodic payments): the
date of supply is the **earliest of**:

1. issuing a tax invoice;
2. the payment due date stated on the invoice;
3. receiving payment.

The tax adviser should confirm this wording. We treat each instalment as its own supply
period. **The contract (TCO) is not a tax invoice**, so it creates no tax point for future
instalments. Therefore:

> **tax point(row) = min(row.cheque_date, row.cleared_at)**

- For a PDC, `cheque_date` is the due date. A PDC handed over at signing is **not** payment
  on the day it is handed over, because it cannot be presented before its date. Its tax
  point is its own date unless it clears earlier, which a PDC cannot do. This is an
  accountant ruling (*Open questions*, Q1).
- A CASH, TRANSFER or ONLINE row received before its due date reaches its tax point on the
  receipt date.
- A bounced cheque does not move the tax point: the due date passed, so the VAT is due.
  Bad-debt relief (Art. 64) is out of scope.

We decided against "cheque clearing only" because a cheque that clears late (or bounces)
would push VAT past its due-date tax point. That under-declares, which is the unsafe
direction.

### Data model (changeset `101-vat-per-instalment.yaml`)

| Change | Notes |
|---|---|
| `cheques.vat_amount NUMERIC(14,2) NOT NULL DEFAULT 0` | The VAT this instalment collects. Shown as a grid column. Editable on DRAFT rows. |
| `cheques.vat_taxable_amount NUMERIC(14,2) NOT NULL DEFAULT 0` | The net value that VAT is charged on. Feeds VAT201 box 1 without a ÷ 0.05 back-calculation. |
| `vat_tax_points` (new) | `id, tenant_id, lease_id, cheque_id NULL, property_id, emirate, kind (INSTALMENT \| TERMINATION_ADJUSTMENT), tax_point_date, taxable_amount, vat_amount, status (PLANNED \| POSTED \| CANCELLED), journal_id NULL, created_at`. Indexes `(tenant_id, status, tax_point_date)` and `(lease_id)`. |
| `leases.vat_timing VARCHAR(12) NOT NULL DEFAULT 'INSTALMENT'` | Data migration sets `CONTRACT` on every lease with `posted_at IS NOT NULL`. Drafts stay `INSTALMENT`. |
| Account + mapping data migration | `B-01-03-002 Output VAT – not yet due` (LIABILITY, leaf) and the `OUTPUT_VAT_DEFERRED` default mapping, inserted per tenant where `B-01-03` exists |

`vat_tax_points` mirrors `recognition_entries`: a precomputed schedule that a job posts.
It is a table rather than a column on the cheque row for three reasons:

- A termination adjustment has no cheque.
- The VAT return reads it directly.
- Cancelling or re-pointing a schedule row leaves the cheque row untouched.

### Allocating VAT to rows

`Σ row.vat_amount = Σ LeaseVat.vatOf(line)` exactly. This is checked at post next to the
existing `Σ amount = contract value` check.

- **The generator** (`ChequeGenerationService.buildRows`) knows how each row is built, so it
  writes `vat_amount` and `vat_taxable_amount` directly:
  - A rent row carries `rent VAT ÷ n`, rounded the same way as the rent (the first row
    absorbs the remainder, as `ChequeRoundingCalculator` already does for amounts).
  - A folded extra adds its own `LeaseVat.vatOf(line)`.
  - Deposits add zero.
- **A manually added or edited row** gets a pro-rata default when the grid is saved:
  `vat = round(V_remaining × row.amount ÷ Σ amount of unallocated rows)`, and the last row
  by `cheque_date` absorbs the remainder. The accountant can overwrite it.

  Pro-rata can pull VAT slightly earlier, for example onto a row 1 that also carries a
  deposit. That errs on the safe side.
- `LeaseVat` stays the only place the 5% is applied. The allocation adds a helper
  `LeaseVat.allocate(total, weights)` that uses largest-remainder rounding with the last
  row absorbing the remainder.

### Postings (lease with `vat_timing = INSTALMENT`)

| Event | Doc | Date | Lines |
|---|---|---|---|
| Post / extend / addendum | `TCO` | contract date | per VAT line: `Dr RENT_RECEIVABLE vat` / `Cr OUTPUT_VAT_DEFERRED vat` (replaces today's `Cr OUTPUT_VAT` in `LeasePostingService.planLines`) |
| Tax point reached | `VTP` (one per row) | `tax_point_date` | `Dr OUTPUT_VAT_DEFERRED row.vat_amount` / `Cr OUTPUT_VAT row.vat_amount`. Dimensions: lease, unit, renter, property, **cheque**. Narration `VAT on <row narration>`. |
| Row received early (`CRT` with `cleared_at < cheque_date`) | `VTP` | `cleared_at` | Same lines. Posted inside `ChequeService.clear`'s transaction, and the PLANNED tax point flips to POSTED. |

Worked example: a commercial lease, 120,000 + VAT, TCO dated 20/04/2026, four cheques of
31,500 dated 01/05/2026, 01/08/2026, 01/11/2026 and 01/02/2027. Each row carries
`vat_amount` 1,500 and `vat_taxable_amount` 30,000.

- `TCO-26/3`: `Dr RR 120,000 / Cr ADVANCE_RENT 120,000`; `Dr RR 6,000 / Cr OUTPUT_VAT_DEFERRED 6,000`.
- `VTP-26/1` (01/05/2026): `Dr OUTPUT_VAT_DEFERRED 1,500 / Cr OUTPUT_VAT 1,500`. The Q2 return shows 1,500, not 6,000.
- `VTP-26/2`, `VTP-26/3` and `VTP-26/4` follow on 01/08, 01/11 and 01/02. Entry numbers
  follow the fiscal year of the date, so the February one is numbered in FY 2027.

**Who posts it:** `VatTaxPointJob`, which runs nightly per tenant and follows the
`RevenueRecognitionJob` pattern:

- It posts every PLANNED tax point with `tax_point_date ≤ today` and
  `> books_locked_through`, ordered by date.
- Each post runs in `REQUIRES_NEW`, so one failure does not stop the rest.

A manual **Run tax points to date** (with preview) sits on the VAT return screen.
`TenantFiscalSettingsService.lockThrough(d)` refuses while any PLANNED tax point is dated
`≤ d` ("Post the VAT tax points through d first"). A lock can therefore never strand a tax
point in a closed month.

### Lifecycle and reversal handling

| Event | Handling |
|---|---|
| **Replacement of a bounced row** | The bounced row's tax point is already POSTED (its due date passed). Replacement rows get `vat_amount = 0` and no tax point. The VAT was declared once and the money is the same money. |
| **Cheque-grid edit of a REGISTERED row** (reverse PDR, new PDR) | The PLANNED tax point moves to the new row with the new date. If the tax point is already POSTED, a change to the row's amount or `vat_amount` is refused ("VAT on this instalment is already declared"). A date change is allowed and leaves the posted `VTP` alone. |
| **Cancel a REGISTERED row** | Its PLANNED VAT must be moved onto other pending rows in the same request. Otherwise the cancel is refused, because the deferred balance would be stranded. |
| **`amendLines`** (TCR + fresh TCO) | Refused when any tax point on the lease is POSTED, with the message "VAT already declared on instalment N; use an addendum". This matches the addendum spec's rule that anything settled is never touched and is corrected by a delta. When nothing is posted, the PLANNED tax points are CANCELLED, the TCO reversal carries the deferred credit away, and the re-post rebuilds the schedule. |
| **Extension / addendum** | The new rows carry VAT allocated from the new lines only. Their `TCO` credits `OUTPUT_VAT_DEFERRED`. |
| **Termination at T** | See below. |
| **Transfer** | Same as termination on the old lease (§2). The successor builds its own schedule. |

**Termination at T** (`LeaseTerminationService.postUnearnedReversal`, INSTALMENT leases):

1. Post every PLANNED tax point dated `≤ T` (the same-transaction variant, as recognition
   does with `postJoining`). The existing `requireOpenPeriod` check covers their dates.
2. Let `P` = Σ `vat_amount` of the tax points still PLANNED, which are all dated `> T`,
   whether their rows are being returned or kept. Mark them all CANCELLED.
3. Let `U` = `plan.unearnedVat()`, which is unchanged (VAT on unearned rent).
4. Add these to the `TCR`, alongside the existing rent pairs:
   - Pair A: `Dr OUTPUT_VAT_DEFERRED min(P, U)` / `Cr RENT_RECEIVABLE min(P, U)`
   - If `P > U`: Pair B `Dr OUTPUT_VAT_DEFERRED (P − U)` / `Cr OUTPUT_VAT (P − U)`. This
     declares, at T, VAT on earned days that no instalment has declared yet. A
     `TERMINATION_ADJUSTMENT` tax point row is written as POSTED with this journal.
   - If `U > P`: Pair B `Dr OUTPUT_VAT (U − P)` / `Cr RENT_RECEIVABLE (U − P)`. This is a
     credit note for VAT already declared on instalments that covered days now unearned.
     It is also written as a `TERMINATION_ADJUSTMENT` with a negative value.

   The result is that the deferred balance for the lease is 0, the receivable falls by
   exactly `U` (as today), and the lease's total output VAT equals the VAT on earned rent
   plus fees.
5. Kept rows dated `> T` then carry no tax point of their own. Their VAT was settled by
   step 4.

Worked example: 120,000 + VAT, term 01/05/2026–30/04/2027 (365 days), T = 31/10/2026
(184 days earned). Day rate is 328.767123.

- Earned 60,493.15, unearned 59,506.85, `U` = 2,975.34.
- May and August are POSTED (3,000). November and February are pending, so `P` = 3,000.
- `TCR`: `Dr ADVANCE_RENT 59,506.85 / Cr RR 59,506.85`;
  `Dr OUTPUT_VAT_DEFERRED 2,975.34 / Cr RR 2,975.34`;
  `Dr OUTPUT_VAT_DEFERRED 24.66 / Cr OUTPUT_VAT 24.66`.
- Check: declared output VAT = 3,000 + 24.66 = 3,024.66 = 5% × 60,493.15.

### Existing leases (decision: leave as is)

Leases posted before this ships have `vat_timing = CONTRACT`:

- Their VAT is already in `OUTPUT_VAT`, and may already be in a filed return.
- Moving it into the deferred account would create a negative adjustment in the next return.
- Termination keeps today's `Dr OUTPUT_VAT unearnedVat` branch for them.
- No migration journal is posted.

The planned production data wipe (memory `project_prod_data_wipe`) means that in practice
no CONTRACT lease should survive into production use. The branch stays because it is cheap
and makes the change safe to deploy before the wipe.

### Cut-over import (must not be missed)

- `ContractImportPostService` posts the INSTALMENT model and then posts the tax points with
  `tax_point_date < D` inside the batch, dated on their own dates. Import journals are
  exempt from the lock. This is the same catch-up it already does for recognition through
  `D − 1`.
- **`OUTPUT_VAT_DEFERRED` joins the opening-balance derived-role exclusion list**
  (`OpeningBalanceService`). Without it, the R16 delta rule posts `−ours` against an
  account PACT never names, which would wipe the deferred VAT.

### Services and endpoints

- `VatTaxPointService` (new): builds the schedule on post, extend and addendum from the
  rows; implements post / cancel / re-point; provides the termination helper
  `settleForTermination(lease, T)`. Called by `LeasePostingService`, `ChequeService`,
  `LeaseTerminationService` and `LeaseVariationService`. It posts only through
  `PostingService`.
- `VatTaxPointJob` (new, nightly).
- `GET /api/v1/leases/{id}/vat-schedule`: tax points with status and journal number.
- `POST /api/v1/finance/vat/tax-points/run?to=` (preview with `dryRun=true`). Restricted
  to TENANT_ADMIN and ACCOUNTANT.

### Web UI

- Cheque grid: a **VAT** column (editable on DRAFT rows) and a footer check showing
  "Σ VAT 6,000.00 = contract VAT 6,000.00".
- Lease detail: a **VAT schedule** tab next to *Recognition schedule*, shown only when the
  lease carries VAT.
- The termination preview shows the VAT settlement pair (declared at T or credited back).
- AR and EN strings. Columns are logical start/end so they work in RTL.

### Edge cases

- **Row dated in a locked period at post:** already refused by `periodLockErrors`. The tax
  point uses the same date, so the same refusal covers it.
- **Tax point on a locked date, where the job fell behind the lock:** impossible, because
  `lockThrough` refuses while such a point is PLANNED.
- **A lease with no VAT** writes no tax points and never touches `OUTPUT_VAT_DEFERRED`.
- **Partial months:** do not apply. Tax points are instalment events, not calendar slices.
- **Rounding:** handled per row by `allocate`, with the last row absorbing the remainder.
  The termination pair is computed exactly from `P` and `U`.

### Tests

- Unit tests: `LeaseVat.allocate` (sums, remainder, zero rows); `min(cheque_date, cleared_at)`.
- ITs:
  - the worked example above (Q2 shows 1,500);
  - early cash receipt moves the tax point;
  - bounced then replaced posts VAT once;
  - `amendLines` refused after a `VTP`;
  - termination for `P > U`, `P < U` and `P = U`, each asserting deferred = 0 and the
    output VAT total;
  - `lockThrough` refused with a pending point;
  - a legacy CONTRACT lease terminates on the old path;
  - import catch-up plus OB delta leaves the deferred balance intact.
- **Golden gate:** verified that both golden ledgers are residential with no VAT line
  (`tenant-one-ledger.csv` and `tenant-two-ledger.csv` contain no VAT account). Both ITs
  assert the renter ledger touches **exactly** the export's accounts
  (`containsExactlyInAnyOrderElementsOf`), so a stray `OUTPUT_VAT_DEFERRED` or `VTP` line
  would fail the gate. That is the guard we want. The gate is expected to stay green
  without any fixture change.

---

## 2. Unit transfer (#52)

### Goal

Omar moves from A-102 to A-201 mid-lease. Today that means a terminate, a 10,000 exit fee,
a settle, a refund, a fresh 10,500 deposit and a new admin fee.

A **Transfer** instead does the following:

- ends the old lease pro-rata on the move date with **no exit fee**;
- starts the new lease the next day on the new unit;
- carries the deposit, any prepaid or owed balance, and the unused PDCs across;
- keeps one linked history.

### Shape: draft, then post (like renewal)

A renewal is drafted and then posted, because the new terms get negotiated after the draft
exists. A transfer is the same:

1. **Transfer** on lease A creates a DRAFT successor B with `transferred_from_lease_id = A`,
   the move date and a cheque disposition plan. It writes nothing to the ledger.
2. **Post** on B runs the whole transfer in one transaction: it terminates A, posts B, and
   posts the carries.

The operator can edit B's lines and cheque grid in between. Deleting draft B abandons the
transfer.

**Reuse:**

- The termination core becomes a package-visible `LeaseTerminationService.endForTransfer(A, T, dispositions)`.
  This is the same cheque split, `truncateForTermination` and `postUnearnedReversal`, with
  the new CARRY bucket added.
- `DepositCarryForward` is generalised from "renewal predecessor" to "predecessor", with
  cross-property account resolution.
- `LeaseRenewalService.copiedLines` is reused for recurring lines (one-off fees are skipped
  per §4c).
- `LeasePostingService.post` gains one branch for transfer successors.

### Dates

- `T` = **move date = the last day in the old unit**. A is earned through T.
- B starts on T + 1.
- A must satisfy `A.start ≤ T < A.end`. If `T = A.end`, the move is a renewal onto another
  unit, so it is refused with a hint to use Renew.
- `T > books_locked_through`.
- B's end date defaults to `A.end` (the rest of the term), and the operator can change it.

### Data model (changeset `102-unit-transfer.yaml`)

| Change | Notes |
|---|---|
| `leases.transferred_from_lease_id UUID NULL FK leases` | B → A. Chain: B.`chain_id` = A.`chain_id`. |
| `leases.transfer_move_date DATE NULL` | T, recorded on B |
| `lease_transfer_cheques` (new) | `tenant_id, successor_lease_id, cheque_id, disposition (CARRY \| KEEP \| RETURN)`. This is the saved plan on the draft, unique `(successor_lease_id, cheque_id)`. |
| `cheques.transferred_from_id`, `cheques.transferred_to_id UUID NULL` | Links the old row to the new row for the same physical instrument |
| Partial unique index | At most one non-deleted successor per lease across renewal and transfer, via a function index over `coalesce(renewed_from_lease_id, transferred_from_lease_id)` |

### Cheque disposition (decision: carry, not re-register)

The instrument is the same paper, payable to the same landlord. Asking the renter to write
new cheques for the new unit adds friction without making anything safer. So each
uncleared row on A goes into exactly one bucket, with the same completeness rule as
`chosenReturns` (every uncleared row must appear in exactly one list):

| Disposition | Default for | Effect |
|---|---|---|
| **CARRY** | uncleared, dated `> T` | Old row → `TRANSFERRED` and its PDR reversed on A. A new row on B copies `cheque_number`, `cheque_date`, `payee_bank`, `amount`, image and OCR fields, with `transferred_from_id` set. `debit_account` becomes B's property BANK when the property changes. The new row's PDR is posted on B. |
| **KEEP** | uncleared, dated `≤ T` | Stays on A for collection, as in termination |
| **RETURN** | none by default | Handed back, as in termination (PDR reversed, RETURNED) |

BOUNCED rows on A are never moved. The amount is owed on A, and A stays TERMINATED-like
until it is settled (see below).

**If B's property belongs to a different landlord entity** (see Q4), cheques payable to A's
owner cannot fund B. CARRY is then disabled and the default becomes RETURN.

### Postings, in order, one transaction (lock A then B, validate everything first)

Worked example:

- **A:** A-102, 60,000 for 01/01–31/12/2026 (365 days), deposit 3,000, four rent cheques
  of 15,000 dated 1 Jan, 1 Apr, 1 Jul and 1 Oct. January and April have cleared.
- **Move:** T = 15/05/2026, so 135 days are earned (day rate 164.383562).
- **B:** A-201 (same or another property), 16/05–31/12/2026 (230 days). The operator enters
  B's rent as 41,589.04 (66,000 per year × 230 ÷ 365).

| # | Doc | Date | Lines (dimensions) |
|---|---|---|---|
| 1 | `PDR` reversal ×2 (CARRY rows Jul, Oct) | T | `Dr RENT_RECEIVABLE(A) 15,000 / Cr PDC_RECEIVABLE(A) 15,000` each |
| 2 | `CIL` truncation / re-post as needed | per §8.5 | `truncateForTermination(A, T)`, unchanged |
| 3 | `TCR` on A | T | `Dr ADVANCE_RENT(A) 37,808.22 / Cr RR(A) 37,808.22` (unearned = 60,000 − 22,191.78), plus the §1 VAT pairs when A carries VAT. **No `EARLY_TERMINATION_FEE`, no settlement deduction.** |
| 4 | `JV` receivable carry | T + 1 | Let C = RR(A) excluding BOUNCED rows' amounts = 30,000 − 37,808.22 = **−7,808.22** (prepaid). C < 0, so: `Dr RR(A) 7,808.22 / Cr RR(B) 7,808.22`, narration "Balance carried to <B contract no> on transfer". A C > 0 (owed) is posted the other way round. |
| 5 | `TCO` on B | B.contract_date (default T + 1) | `Dr RR(B) 41,589.04 / Cr ADVANCE_RENT(B's property) 41,589.04`, plus recurring fee lines. VAT pairs go to `OUTPUT_VAT_DEFERRED`. |
| 6 | `PDR` per B row | row posting date | Carried Jul and Oct: `Dr PDC_RECEIVABLE(B) 15,000 / Cr RR(B)`. New row for the gap of 3,780.82: `Dr PDC(B) / Cr RR(B)`. |
| 7 | `JV` deposit carry | B.contract_date | `Dr SECURITY_DEPOSIT(A's leaf, A dims) 3,000 / Cr SECURITY_DEPOSIT(resolved for B's property, B dims) 3,000`. The amount comes from `LeaseDepositLedger.heldByAccount(A)`, as in renewal. |

**Result:**

- **A:** RR(A) = 0, apart from any BOUNCED amounts. Advance rent is 0 after recognition to
  T. Deposit is 0.
- **B:** RR(B) = 41,589.04 − 7,808.22 − 30,000 − 3,780.82 = 0. This is the usual
  "posted lease nets to zero" invariant, with C folded in.

**Posting validation for a transfer successor:**

- `Σ B rows = B contract value incl. VAT + C`, which here is 33,780.82.
- The generator's "gap" is the same figure minus the carried rows.
- B's VAT schedule allocates B's VAT over all of B's rows, carried rows included.
- When C < 0 (prepaid), the prepaid share counts as payment already received. B's earliest
  rows get their tax points on T + 1, up to the VAT content of |C|. This follows the
  "earlier of payment" part of §1.

**Deposit across properties:**

- `DepositCarryForward` today credits the *same* leaf on both sides.
- For a transfer, the credit side resolves the deposit's role (the role of A's
  `ChargeType` for that deposit line: `SECURITY_DEPOSIT` or `PARKING_DEPOSIT`) against B's
  property.
- For the same property the result is identical to today.
- For a different property, the liability moves between the two properties' deposit leaves.

**Status and units:**

- A → `TRANSFERRED`, with a lease event "Transferred to <B> on T".
- `unit(A).current_lease_id` is cleared and the unit becomes VACANT unless another lease
  holds it.
- B → ACTIVE and `unit(B).current_lease_id = B`.
- Flush A's status before B's, for the same reason `markPredecessorRenewed` flushes: the
  `ux_leases_one_active_per_unit` race. It cannot collide here, because the units differ,
  but ordering stays deterministic.
- The renewal opportunity on A is closed as `TRANSFERRED`.

### A's remaining settlement

After the transfer, A has a deposit of 0 and a receivable of 0, except for BOUNCED rows and
KEEP rows. If both of those are empty, A needs no settlement and shows "Settled by
transfer".

Otherwise the ordinary settlement screen opens on A and lists only those items. The
deposit line reads "carried to <B>" and is not refundable there.

`SettlementService` must read "deposits held" from the ledger, which it already does through
`LeaseDepositLedger`. A therefore shows 0 held, and the settlement cannot refund the carried
deposit twice.

### One audit trail

- B.`chain_id` = A.`chain_id`, so the chain column and the tracking number are unchanged.
- `LeaseDepositLedger`'s chain walk follows `coalesce(renewed_from_lease_id, transferred_from_lease_id)`.
- The lease page's **History** strip lists the chain in order, labelling each link
  *Renewed* or *Transferred*.
- A's journals and B's journals are cross-referenced by the two JV narrations and by
  `cheques.transferred_*`.

### Services and endpoints

- `LeaseTransferService` (new):
  - `draft(A, request)`: lock A, validate, build B through `LeaseService.createTransferDraft`,
    save the disposition plan;
  - `preview(A, T, targetUnit)`: read-only;
  - `complete(B)`: called from `LeasePostingService.post` when `transferred_from_lease_id`
    is set.
- `POST /api/v1/leases/{id}/transfer` with body `{ moveDate, targetUnitId, endDate?, contractDate?, lines?, chequeDispositions[{chequeId, disposition}], carryDepositForward = true }`.
  Returns draft B.
- `GET /api/v1/leases/{id}/transfer/preview?moveDate=&targetUnitId=`. Returns earned
  through T, unearned, C, deposit held, the default buckets, a suggested rent for B
  (A's day rate × B's days, as a hint only), and the VAT pairs.
- Posting uses the existing `POST /leases/{id}/post` and `/post/dry-run`. The dry run lists
  all seven groups of journals above.
- Roles:
  - drafting: `requireManageable`, as for Renew;
  - posting: TENANT_ADMIN or ACCOUNTANT, as for Post and Terminate.

### Web UI

1. Lease detail → **Transfer to another unit**, shown only on ACTIVE or NOTICE_GIVEN leases
   with no successor draft.
2. **Step 1:** move date (labelled "Last night in the current unit"), target unit (picker
   of VACANT units across properties the user can manage), end date, and the rent for the
   new term (pre-filled hint), plus recurring fee lines as toggles.
3. **Step 2:** cheque table with a Carry / Keep / Return segmented control per row, and a
   summary card:
   - earned on the old unit;
   - received;
   - balance carried (prepaid or owed);
   - deposit carried;
   - carried cheques;
   - **gap to collect**, with an "Add row" action.
4. **Create draft** opens B. B shows a banner "Transfer from A-102 · move date 15/05/2026"
   and A shows "Transfer pending → draft B".
5. On B, **Review & post** shows the dry-run journals. Post completes the transfer.

Arabic RTL: the stepper and the Carry/Keep/Return control mirror. Amounts stay LTR inside
`<bdi>`.

### Edge cases

- **Cleared between draft and post** (a CARRY row clears on A): at post the plan is
  re-validated. A cleared row drops out of CARRY and its money flows into C. The Σ check
  then fails with the exact gap, and the operator adjusts B's grid.
- **Termination after transfer:** B terminates normally. `LeaseDepositLedger` walks the
  chain and finds the deposit on B. VAT and recognition are B's own.
- **Transfer after transfer:** allowed. The chain continues.
- **Transfer of a lease with a DRAFT renewal:** refused ("delete draft renewal <id> first").
- **Renewal of a TRANSFERRED lease:** refused. Renew B instead.
- **Rent-free period on A covering T** (§4b): recognition to T counts charged days only, so
  earned can be 0.
- **A with VAT:** the §1 termination pairs apply on the `TCR`. B's own schedule is fresh.
- **Locked periods:** T, T + 1, B.contract_date and every B row date must be open. All are
  checked before the first write. The recognition reversal date is checked through the
  existing `requireOpenPeriod`.
- **Same unit:** refused.
- **The target unit has a DRAFT lease for somebody else:** allowed at draft. Post is
  refused by the one-active-per-unit index with a clear message.

### Tests

- The worked example as an IT:
  - every journal's lines are asserted to the fil;
  - RR(A) = RR(B) = 0;
  - the deposit has moved;
  - A is TRANSFERRED and the units have flipped.
- Variants: cross-property (different deposit and PDC leaves); C > 0; a BOUNCED row left on
  A (A's settlement lists only it); a CARRY row cleared before post; RETURN everything (B is
  funded by new rows only); termination of B refunds the carried deposit once; transfer
  refused when T = A.end; the concurrent-post lock.
- Golden gate: untouched code paths. Run it anyway, since `DepositCarryForward` and
  `LeaseTerminationService` change.

---

## 3. Year-end close (#53)

### Goal

Today the trial balance as of 31/12/2025 and as of 01/01/2026 are identical. Income carries
across the year boundary and there is no Retained Earnings account.

**Close fiscal year** will:

1. post one `YEC` journal that moves every income and expense balance into Retained
   Earnings;
2. lock the year.

**Re-open** reverses that journal and unlocks the year.

### Decisions

- **One `YEC` per tenant per fiscal year, not one per property.** Retained earnings belong
  to the legal entity. Every YEC line carries the **property dimension** of the balance it
  closes, and the Retained Earnings lines are split per property. A per-property P&L and
  per-property equity roll-forward therefore stay available without a journal per building.
- **Retained Earnings is a new tenant-level role, `RETAINED_EARNINGS`**, with seeded leaf
  `F-03 Retained Earnings`. It is not `OPENING_BALANCE_DIFFERENCE`, which is a cut-over
  suspense account.
- **Dated the last day of the fiscal year**, as the standard post-closing convention. The
  numbering is `YEC-<fy2>/1`.
- **YEC lines carry no lease, renter or unit dimension.** Renter ledgers and the golden
  replays therefore never see them.

### Data model (changeset `103-fiscal-year-close.yaml`)

| Change | Notes |
|---|---|
| `fiscal_year_closes` (new) | `id, tenant_id, fiscal_year INT, period_start, period_end, status (CLOSED \| REOPENED), journal_id, lock_before DATE, closed_by, closed_at, reopened_by, reopened_at, reopen_reason`. Partial unique `(tenant_id, fiscal_year) WHERE status = 'CLOSED'`. |
| Account + mapping data migration | `F-03 Retained Earnings` (EQUITY, leaf, `is_system`) and the `RETAINED_EARNINGS` default mapping per tenant where `F` exists |

### Close

Preconditions, all listed together in the preview:

1. `period_end < today`.
2. The previous fiscal year is closed. The exception is when the previous year has no lines
   or ends before `books_start_date − 1`. Years close in order.
3. No PLANNED `recognition_entries` with `period_end ≤ period_end(FY)`, meaning recognition
   has run to year end.
4. No PLANNED `vat_tax_points` dated `≤ period_end(FY)` (§1).
5. No DRAFT vouchers dated inside the year. This is a warning that can be overridden, not a
   blocker.
6. No live import batch in DRAFT dated inside the year.

The posting, in one transaction:

- For every leaf with `account_type ∈ {INCOME, EXPENSE}`, group Σ(debit − credit) over lines
  with `entry_date ≤ period_end` by `(account_id, property_id)`. The sum is cumulative. It
  equals this year's activity because earlier years were closed (precondition 2), or because
  it includes OB-imported P&L balances in the first year (see edge cases).
- For each non-zero group, post the opposite side for the same amount on that account and
  property.
- Per property, one `RETAINED_EARNINGS` line for the net: Cr for a profit, Dr for a loss.
- Zero groups are skipped. A year with no P&L activity posts no journal, but still records
  the close and the lock.

Example, FY 2026, property P1 (dates and entry numbers illustrative):

`YEC-26/1`, 31/12/2026, narration "Year-end close FY 2026":

- `Dr Rental Income P1 331,979.81`
- `Dr Admin Fee – P1 1,800.00`
- `Cr Bank Charges 200.00` (property P1 dimension)
- `Cr RETAINED_EARNINGS 333,579.81` (P1)

Afterwards:

- `TenantFiscalSettingsService.lockThrough(max(current lock, period_end))`.
- Record `fiscal_year_closes` with `lock_before` = the lock value before the close.

### Posting into a locked period

The accountant has often locked December already. `YEC` therefore joins `OB` as a
**doc-type-keyed lock exemption** in `PostingService.post` and `reverse`, with the same
reachability argument as the existing OB comment:

- `YearEndCloseService` is the only producer of `YEC`.
- No request body carries a doc type.
- `JournalService.reverse` (the HTTP JV path) refuses `source_type = YEAR_END`. That is a new
  `JournalSourceType` value.

The exemption keys on the original entry's doc type, exactly as OB's does.

**Closing the import-batch hole:**

- `PostingService.post` gains a check: when `importBatchId != null` and `entryDate` is
  on or before the latest CLOSED `period_end`, refuse with "the fiscal year is closed;
  re-open it first".
- OB reposts go through the same guard.
- Without this, an import or OB posted after a close would leave the income it posts
  unclosed.

### Re-open

`POST /finance/fiscal-years/{fy}/reopen {reason}`, TENANT_ADMIN only.

- Only the **latest** CLOSED year can be re-opened. A later closed year must be re-opened
  first.
- `PostingService.reverse(yecId, period_end, reason)` writes a mirror dated the same
  `period_end`, exempt by doc type. This follows the OB rule of pinning the mirror to the
  original's date, so the TB as of year end is restored exactly.
- Set `books_locked_through = min(current, period_start − 1)` through a new
  `TenantFiscalSettingsService.reopenTo(date, reason)`. This is the only backwards move the
  lock allows. It is audited and refused unless it is called from `YearEndCloseService`.
  Re-opening a year unlocks everything after it. The UI says so, and the accountant re-locks
  afterwards.
- The status becomes REOPENED.
- A later **Close** posts a fresh `YEC`. The first one stays REVERSED, which leaves an
  audit trail.

### Reports either side of the boundary

| View | Behaviour |
|---|---|
| Trial balance, as of FY end | Post-closing by default: income and expense are 0 and RE holds the year's profit. The toggle **Before closing entries** (`excludeClosing=true`) excludes `doc_type = YEC` dated on the as-of date and shows the pre-closing TB the accountant reviews. |
| Trial balance, as of the first day of the next FY | Income and expense show only the new year's activity. RE carries the prior profit. This fixes #53's symptom. |
| **Profit & Loss** (new) `GET /finance/profit-and-loss?from&to&propertyId` | Σ over INCOME and EXPENSE lines in `[from, to]`, **always excluding `YEC`** so a year's P&L is never zero. It is grouped by the account tree. This screen is the close preview's body. |
| General ledger / account ledger | Shows the YEC line, which is the point of it |
| Renter ledger | Unchanged, because YEC lines have no renter dimension |

### Services, endpoints, UI

- `YearEndCloseService`: `list()`, `preview(fy)`, `close(fy, overrideWarnings)`,
  `reopen(fy, reason)`.
- `GET /finance/fiscal-years` returns each year with status, net result, and the closed or
  reopened by/at.
- `GET /finance/fiscal-years/{fy}/close-preview`
- `POST /finance/fiscal-years/{fy}/close`, restricted to TENANT_ADMIN and ACCOUNTANT
- `POST /finance/fiscal-years/{fy}/reopen`, restricted to TENANT_ADMIN
- `LedgerController` trial balance gains `excludeClosing`.
- Web: Settings → **Fiscal & period lock** gets a **Fiscal years** table with status chips
  and Close / Re-open actions.
  - The close dialog shows the blockers, the P&L summary, the RE line per property, and the
    new lock date.
  - Re-open asks for a reason and warns "this unlocks every period after 31/12/2025".
  - The TB page gets the toggle. There is a new P&L page under Finance → Reports.
  - AR and EN strings.

### Edge cases

- **First fiscal year containing the cut-over:** the OB journal (dated D − 1) may carry
  PACT's P&L balances. That year's YEC closes them together with the post-cut-over activity.
  If D − 1 is in an earlier fiscal year than the first one with activity, that earlier year
  can be closed on its own (it contains only the OB).
- **Fiscal year not starting in January:** `period_start` and `period_end` derive from
  `fiscal_year_start_month`, and the label comes from `fiscalYearOf`.
- **Lock already past year end:** the exemption handles it, and the lock is not moved
  backwards on close.
- **A reversal of an income entry after close:** it can only be dated in an open period,
  i.e. the new year. That is correct accounting and needs no special case.
- **Rounding:** none. The lines are exact sums of 2-dp amounts.

### Tests

- IT: two years of activity, then close FY 1:
  - TB(FY end, post-closing) has income = 0 and RE = profit;
  - TB(FY end, excludeClosing) matches the pre-close state;
  - TB(next day) has RE = profit;
  - P&L(FY) is unchanged by the close.
- Other ITs:
  - re-open restores the TB exactly and the lock, and a re-close posts a fresh YEC;
  - out-of-order close refused;
  - a PLANNED recognition entry blocks the close;
  - an import post dated in a closed year refused;
  - the JV API cannot reverse a YEC;
  - a year with a loss.
- **Golden gate:** the fixtures never close a year, so they are unaffected. Add
  `GoldenLedgerYearCloseIT` (tagged golden): replay tenant one, close FY 2025, and assert
  the renter ledger is unchanged line for line. This proves YEC stays out of renter
  dimensions.

---

## 4. Renewal and rent terms (#24, #50, #23, renewal charges)

### 4a. Escalation on renew (#24)

**Request.** `RenewLeaseRequest` gains:

- `rentChange { mode: NONE | PERCENT | AMOUNT, percent, newRentAmount }`
- `ejariNumber` (optional)
- `additionalLines` (see 4d)

**Base.** The predecessor's contract RENT line's **headline** gross. This is `gross_amount`,
before any rent-free concession (4b) and before discount. Extension and addendum rent lines
are already excluded by `copiedLines`.

**PERCENT mode:**

- `new gross = round(base × (1 + p/100))` to whole AED, because PACT contracts are
  whole-dirham.
- This applies only when the new term has the same number of whole months as the old one.
- Otherwise PERCENT is refused with "term length changed; enter the new rent amount". We
  deliberately do not pro-rate silently by days, because a 365/366-day year would move the
  rent by a day's worth.

**AMOUNT mode:** sets the new RENT line gross directly.

**Other rules:**

- Discount is reset to 0 and the rent-free amount to 0. Concessions do not renew.
- `LeaseVat` computes VAT on the new net.
- The dialog shows old rent → new rent and the percentage change.
- The dialog shows an **informational** notice when the percentage exceeds a
  tenant-configurable cap (`rent_collection_settings.renewal_increase_warn_percent`,
  default unset). There is no RERA index integration. That is *Out of scope*.
- `ejariNumber` is written to the draft. When it is blank, the existing follow-up-task
  mechanism from the addendum work records a follow-up to register.

**Audit.** Changeset `107-renewal-terms.yaml` adds `leases.renewal_previous_rent NUMERIC(14,2) NULL`
and `leases.renewal_change_percent NUMERIC(7,3) NULL` on the successor. They are shown on
the lease header and on the contract PDF ("Rent revised from 85,000 to 91,800, +8.00%").

### 4b. Rent-free periods (#50)

**Model (changeset `105-rent-free-periods.yaml`):**

- `lease_rent_free_periods (id, tenant_id, lease_id, from_date, to_date, note)`. Rules: not
  overlapping, inside the lease term, and each RENT line window keeps at least one charged
  day.
- `lease_lines.rent_free_amount NUMERIC(14,2) NOT NULL DEFAULT 0`, the concession value.
- Replace `ck_lease_lines_net` with
  `net_amount = gross_amount − discount_amount − rent_free_amount AND net_amount >= 0`. This
  is a drop and re-add in the new changeset. Changeset 83 is not edited.

**Pricing.**

- The operator enters the headline rent `G` for the whole term and the free window.
- `rent_free_amount = round(G × freeDays ÷ termDays, 2)`, which the operator can overwrite
  (for example "exactly one month = 4,000").
- `net = G − discount − rent_free_amount` is what the renter pays, what the TCO raises, and
  what VAT is computed on.

**Recognition (exact per day, free days excluded).** `RecognitionService.build`:

- For each RENT line window `[from, to]`, split the window into charged sub-windows
  `W1..Wk` (the window minus free periods).
- `chargedDays = Σ days(Wi)` and `dayRate = net ÷ chargedDays` (6 dp).
- One `RentSegment` per sub-window, with `amount_i = round(dayRate × days_i, 2)`. The
  **last** sub-window gets `net − Σ previous`.
- Each segment is sliced with a new overload `ProrationEngine.slice(amount, from, to, rate)`
  that uses the given line rate instead of re-deriving `amount ÷ days`. Each month is
  therefore `dayRate × days`, and only the final slice of the line absorbs rounding.
- A month that is wholly free produces no entry, so it recognises exactly zero.
- With no discount, `dayRate = (G − G·f/T) ÷ (T − f) = G ÷ T`: the headline day rate,
  exact before rounding.
- The existing `slice(amount, from, to)` is unchanged, which keeps the golden gate
  byte-identical.
- There is no unique index on `rent_segments(lease_line_id)`, so several segments per line
  are allowed. Verified: changeset 85 only indexes `lease_id`.
- `truncateForTermination` already works per segment. Unearned is Σ over segments, so free
  days contribute 0 to both earned and unearned.

**Cheque grid.** The generator splits the rent gross (net + VAT) as today, with these
changes:

- `firstDueDate` defaults to the first charged day.
- For monthly terms, `n` defaults to the number of charged months.
- A generated due date that falls inside a free window moves to the day after the window.

**Contract PDF** (`ContractGenerationService`):

- A "Rent-free period" row with dates, days and the concession value.
- The rent row shows headline, concession and payable.
- Arabic template parity.

**UI:**

- Draft editor and wizard: an **Add rent-free period** control under the rent line, with
  from/to date pickers, a computed concession, and an override field.
- The recognition tab greys out free months.
- Amend Lines can edit free periods under the same all-REGISTERED gate. The TCO is
  reversed and reposted, and recognition is rebuilt as in §8.5.

**Edge cases:**

- A free period at the start is the common case.
- A free period mid-term (for example Ramadan) splits the line into two segments.
- A free period at the end followed by termination: the unearned figure is correct because
  free days carry 0.
- Extension: a free window must lie inside some RENT line window. An extension line may have
  its own window.
- Renewal does not copy free periods.
- Transfer at T inside a free window: earned is 0 for the free days.

### 4c. One-off vs recurring charges (#23)

- Changeset `104-charge-type-recurrence.yaml` adds
  `charge_types.recurrence VARCHAR(12) NOT NULL DEFAULT 'RECURRING'`.
- Data migration: `ONE_OFF` where `code IN ('ADMIN_FEE')` or `role = 'ADMIN_FEE'`. Every
  other existing type stays `RECURRING`:
  - RENT, COOLING, PARKING_FEE and MAINTENANCE recur;
  - deposit types are governed by their own carry/copy rule, not by recurrence.
- Seed and insert per tenant: `RENEWAL_FEE` "Renewal Fee / رسوم التجديد", `FEE → ADMIN_FEE`,
  `ONE_OFF`, `vat_applicable_default = false` (see Q8), display order 35.
- `ChargeTypeService.DEFAULTS` gets the same entry.
- `copiedLines` skips `recurrence = ONE_OFF` lines, alongside its existing addendum and
  extension skips. It returns the skipped lines in the renewal response
  (`skippedOneOffLines`), and the dialog shows them: "Not copied: Admin Fee 1,500
  (one-off)". Skipping is shown to the user, so nothing is dropped silently.
- The charge-types settings page gets a Recurring / One-off column.

### 4d. Renewal charges

- `RenewLeaseRequest.additionalLines: List<LeaseLineInput>` is appended after the copied or
  escalated lines.
- The dialog has an **Add charge** row picker, defaulting to Renewal Fee, with amount and
  VAT.
- These lines are ordinary lease lines on the DRAFT. They fold into row 1 of the grid by the
  existing fold rule, post in the successor's TCO, and fall under §1's VAT treatment.
- DEPOSIT behaviour is allowed here: this is a new contract, unlike `extend`.
- A fee typed ONE_OFF is naturally not copied at the *next* renewal.

### Postings for section 4

- Escalation, renewal fee and one-off skip: no new posting logic. The successor's TCO, PDR,
  recognition and VAT follow the existing rules.
- Rent-free: the TCO credits `ADVANCE_RENT` with `net` (after the concession). Nothing is
  posted to `DISCOUNT_ALLOWED`, consistent with v2 §6.2 ("discount is informational"). The
  concession is contract data, not a ledger event.

### Endpoints and UI for section 4

- The existing `POST /leases/{id}/renew` gets the new fields.
- `GET /leases/{id}/renewal-preview?mode=&percent=&amount=&startDate=&endDate=` returns the
  base, the new rent, the copied and skipped lines, and the cap warning.
- `PUT /leases/{id}/rent-free-periods` works on DRAFT leases, or through amend.
- The Renew dialog in order:
  1. dates;
  2. **Rent change** (None / % / New amount) with a live preview;
  3. carry deposit;
  4. copied lines with the skipped one-offs listed;
  5. additional charges;
  6. Ejari.

### Tests for section 4

- Unit tests: sub-window split, `slice` with a given rate (Σ exact, free months absent),
  and the concession formula.
- ITs:
  - 12-month lease with the first month free: January recognises 0, and February to
    December follow `G ÷ 365 × days` with December absorbing rounding;
  - a mid-term free month;
  - termination inside the free month;
  - renewal +8% of 85,000 gives 91,800;
  - PERCENT refused on a changed term length;
  - Admin Fee not copied and reported as skipped;
  - renewal fee posted in the successor TCO;
  - the migration marks ADMIN_FEE ONE_OFF.
- Golden gate: recognition's default path is unchanged, so it should stay green. Run it.

---

## 5. Smaller defaults

### 5a. VAT return view (#55)

**Endpoint:** `GET /api/v1/finance/vat-return?from&to` (TENANT_ADMIN, ACCOUNTANT), plus a
CSV export. There is no schema change. It reads the ledger and `vat_tax_points`.

Shape, following the FTA VAT201 box numbers (simplified to the boxes this product
produces):

| Box | Content | Source |
|---|---|---|
| 1a–1g | Standard-rated supplies per emirate: amount, VAT | `vat_tax_points` POSTED with `tax_point_date ∈ [from, to]`, grouped by `emirate` (copied from `property.emirate` at schedule time). Amount = Σ `taxable_amount`, VAT = Σ `vat_amount`. TERMINATION_ADJUSTMENT rows net in, and negative rows are listed as adjustments. |
| — | Legacy CONTRACT-timing VAT | `OUTPUT_VAT` credits from `TCO` on CONTRACT leases in range, shown in a separate row "at contract date (legacy)". Should be empty after the data wipe. |
| 9 | Standard-rated expenses: amount, recoverable VAT | Posted `PISR` voucher lines with `vat_amount > 0` in range (Σ amount, Σ vat_amount), less reversals |
| 12 | Total value of due tax | Σ box 1 VAT (+ legacy) |
| 13 | Total recoverable tax | box 9 VAT |
| 14 | Net payable / (refundable) | 12 − 13 |
| Check | Ledger reconciliation | Σ period movement on `OUTPUT_VAT` should equal box 12, and on `INPUT_VAT` box 13. A mismatch is shown with a drill-down to the journals, for example a manual JV to a VAT account. |

**Out of scope:** boxes 2–8 (tourist refunds, reverse charge, zero-rated, exempt, imports),
10 and 11. We do not file with the FTA. Residential (exempt) rent is not reported.

**UI:** Finance → Reports → **VAT return**. It has a quarter picker (defaulting to the
last complete quarter), the box table, the reconciliation badge, **Run tax points to date**
(§1), and CSV export. RTL-safe.

**Tests:** an IT with commercial leases in Dubai and Sharjah, one early cash receipt, one
termination credit note and one PISR with input VAT. It asserts every box and a clean
reconciliation. A manual JV to `OUTPUT_VAT` flags the reconciliation.

### 5b. Collection tile by due date (#59)

**Diagnosis.** The web tile divides `receivedThisMonth` (cleared by `cleared_at`) by
`thisMonth.expected` (by `cheque_date`). The two bases differ, and one catch-up banking run
produced "139,550 of 3,667".

**Fix:**

- **Backend:** `DashboardSummaryDTO` gains four fields, all scoped the same way and computed
  from the register:
  - `dueThisMonth`: live rows with `cheque_date` in the month, which is `aggregateMonthly`'s
    "expected";
  - `collectedAgainstDueThisMonth`: those rows that are CLEARED;
  - `collectedArrears`: cleared this month with `cheque_date` before the month;
  - `collectedAdvance`: cleared this month with `cheque_date` after the month.

  The identity is
  `receivedThisMonth = collectedAgainstDueThisMonth + collectedArrears + collectedAdvance`,
  where each term counts only rows that cleared this month. It is asserted in a test.
- **Web** (`dashboard/page.tsx`):
  - The headline is **Collected against this month's dues**:
    `collectedAgainstDueThisMonth of dueThisMonth`, with a percentage.
  - The sub-line reads "+ AED X arrears, + AED Y advance collected this month" when those
    are non-zero.
  - The sparkline uses the monthly `collected` series, which is already by due month.
  - The fallback path for old backends is removed.
- **Tests:** a unit test for the identity. A web test: 15 catch-up clearances of old rows
  and one September PDC give a headline of `0 of 3,667` plus "arrears 139,550".

### 5c. Lease inherits property grace (#65)

**Diagnosis.** The backend already inherits: `LeaseService.gracePeriodFor` uses
`rent_collection_settings.grace_period_days` when the request's grace is null. The bug is on
the web side: `LeaseWizard` initialises `gracePeriodDays: 0` and always sends it, and
`LeaseMetadataEditor` does the same. The explicit 0 wins.

**Fix:**

- **Changeset `106-lease-grace-override.yaml`:** `leases.grace_period_overridden BOOLEAN NOT NULL DEFAULT TRUE`.
  Existing rows stay `TRUE`, because their snapshot is what the renter agreed to. New rows
  are written by `gracePeriodFor`: `FALSE` when inherited, `TRUE` when the request named a
  value.
- **Web:**
  - The wizard and editor field is empty by default, with the placeholder "Property
    default: 5 days". Sending null means inherit.
  - A "Use property default" link clears an override.
  - The lease header shows "5 days (property default)" or "7 days (set on this lease)".
- **Renewal / transfer:**
  - If the predecessor was not overridden, the successor re-reads the property's *current*
    value.
  - Otherwise the override is copied (today `successorHeader` always copies).
  - The snapshot rule still holds: a lease's grace never changes after it is created.
- **Labels (#65's second half):**
  - Rent settings *Grace period (days)* becomes "Default grace for new leases (days
    before a cheque counts as overdue)".
  - The cheque-failure *Grace days* becomes "Days before a bounced-cheque fine is proposed".
  - The lease field becomes "Grace before overdue (days)".
  - EN and AR.
- **Tests:** an IT: a draft with null grace inherits 5 and is marked not overridden; an
  explicit 0 is kept and marked overridden; a renewal of a non-overridden lease picks up a
  changed property value. A web test checks the wizard sends null by default.

---

## Changesets

| # | File | Feature |
|---|---|---|
| 101 | `101-vat-per-instalment.yaml` | cheques VAT columns, `vat_tax_points`, `leases.vat_timing` + backfill, `B-01-03-002` + `OUTPUT_VAT_DEFERRED` mapping |
| 102 | `102-unit-transfer.yaml` | `transferred_from_lease_id`, `transfer_move_date`, `lease_transfer_cheques`, cheque transfer links, successor uniqueness index |
| 103 | `103-fiscal-year-close.yaml` | `fiscal_year_closes`, `F-03` + `RETAINED_EARNINGS` mapping |
| 104 | `104-charge-type-recurrence.yaml` | `recurrence` + ADMIN_FEE backfill + `RENEWAL_FEE` insert |
| 105 | `105-rent-free-periods.yaml` | `lease_rent_free_periods`, `lease_lines.rent_free_amount`, replaced `ck_lease_lines_net` |
| 106 | `106-lease-grace-override.yaml` | `grace_period_overridden` |
| 107 | `107-renewal-terms.yaml` | `renewal_previous_rent`, `renewal_change_percent`, `renewal_increase_warn_percent` setting |

The numbers are assigned in build order below. If PRs land out of order, renumber at merge
time; never edit a merged changeset. Tenant-purge exemptions (changeset 89's pattern) must
list the new tenant-scoped tables: `vat_tax_points`, `lease_transfer_cheques`,
`fiscal_year_closes` and `lease_rent_free_periods`. Every new query goes through the
Hibernate tenant filter inside a transaction (memory `project_tenant_filter_outside_tx`).

## Build order and PR split

Each PR runs the review loop, the full backend suite, the golden gate and the web tests
before merge and deploy.

| PR | Contents | Depends on | Why this order |
|---|---|---|---|
| 1 | §5b collection tile + §5c grace (106) | none | Small and independent. Removes two visible dashboard and overdue bugs immediately. |
| 2 | §1 VAT per instalment (101): schema, allocation, `VatTaxPointService`, job, TCO/termination/amend/cheque hooks, OB exclusion, grid VAT column, VAT schedule tab | none | P1. Every later ledger feature (transfer, renewal fee VAT) should be built on the new VAT model rather than retrofitted onto it. |
| 3 | §5a VAT return view | PR 2 | Reads `vat_tax_points` |
| 4 | §4c recurrence + §4a escalation + §4d renewal charges (104, 107) | none (VAT-aware via PR 2) | One Renew-dialog rework |
| 5 | §4b rent-free periods (105) | PR 4 (Renew must not copy free periods) | Touches recognition, the generator and the PDF. Kept isolated so the golden gate diff has one cause. |
| 6 | §3 year-end close (103), P&L report, TB toggle, lock exemption, import guard | PR 2 (close preconditions check tax points) | Independent of the lease features |
| 7 | §2 unit transfer (102) | PRs 2, 4, 5 | Composes termination (VAT pairs), one-off skip, recognition and deposit carry, so it lands last |

PR 2 and PR 7 are each big enough for a two-part split, backend then web. The web part lands
behind no flag, because the backend endpoints are additive.

Mobile: finance screens stay hidden behind `TenantFeature.MOBILE_FINANCE`. The manager app's
lease list must render the new `TRANSFERRED` status. It uses raw maps, so this needs a label
and a colour fallback check only.

After each PR, following the house practice, record a scenario walkthrough (memory
`feedback_phase_walkthrough_videos`) that re-runs the matching Miftah-2y story beat:
Ahmed +8% with the admin fee skipped, the rent-free first month, Omar's transfer, the Q2
VAT return, and the 2025 close.

## Out of scope

- A per-instalment **tax invoice PDF** and its numbering. This is legally required within
  14 days of each tax point, but it is a document feature. Q2 decides whether it joins PR 2.
- Bad-debt VAT relief.
- VAT201 boxes 2–8, 10 and 11.
- RERA rental-index lookup.
- Negotiated downward rent corrections (see the addendum spec's amendment).
- Multi-unit contracts.
- A transfer that changes the renter.

## Open questions for the product owner / client accountant

1. **PDC as payment (§1):** is a post-dated cheque handed over at signing "receipt of
   payment" for the tax point? We assume **no**: the tax point is the cheque date. If the
   answer is yes, every PDC instalment's VAT falls due at signing, which is today's
   behaviour, and §1 matters only for instalments paid by transfer.
2. **Tax invoice per instalment:** should RentAxis issue the instalment tax invoice (PDF +
   series) on each tax point, or does the landlord invoice outside the system? This decides
   whether PR 2 includes the document.
3. **Rent-free recognition:** the decision says to exclude free months, so they recognise
   zero. IFRS 16 for lessors would spread the concession straight-line across the whole
   term. The accountant should confirm that exclusion is what they want in the books, not
   just on the grid.
4. **Transfer across landlord entities:** does any tenant manage buildings for different
   owners (the v2 D6 landlord item)? If yes, PDCs cannot be carried across owners (we fall
   back to RETURN), and the deposit JV crosses entities, which would need an inter-company
   treatment. We assume one entity per tenant.
5. **Transfer rent:** the new unit's rent is entered by the operator, with A's day rate as a
   hint. Should there be a policy default (keep the old rent for the rest of the term, or
   the new unit's listed rent)?
6. **Retained earnings granularity:** one tenant-level RE account with a property dimension.
   Is a per-property RE leaf wanted (for owner statements), or is the dimension enough?
7. **Escalation rounding and cap:** is whole-AED rounding correct for all clients? Should the
   cap warning be driven by a configured percentage, or left off until RERA-index work?
8. **VAT on the renewal and admin fee:** seeded with VAT off, like Admin Fee today. For a
   residential lease, is the fee ancillary to the exempt supply or standard-rated? This
   needs the tax adviser's ruling before the default is flipped.
